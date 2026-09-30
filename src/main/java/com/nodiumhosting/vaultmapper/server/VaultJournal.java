package com.nodiumhosting.vaultmapper.server;

import com.google.protobuf.ByteString;
import com.google.protobuf.UnknownFieldSet;
import com.nodiumhosting.vaultmapper.VaultMapper;
import com.nodiumhosting.vaultmapper.proto.VaultCell;
import com.nodiumhosting.vaultmapper.proto.Vault;
import com.nodiumhosting.vaultmapper.server.VaultSaveFiles.CorruptDataException;

import javax.annotation.Nullable;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.zip.CRC32;

// the vault journal (write-ahead log): record format and per-vault journal file I/O.
// knows nothing about indexes, config, or the server's worlds - callers hand it paths.
//
// file layout: int32 signature + int32 version + (in v2/v3) 128-bit journal id and
//              int32 header crc32, then records of int32 length + int32 crc32 +
//              serialized VaultCell (last write wins per cell). v1 has an 8-byte header.
// v3 also supports atomic update batches with durable receipt metadata in a reserved
// protobuf field; ordinary v1/v2 cell payloads remain readable without conversion.
final class VaultJournal {
    static final int FILE_SIGNATURE = ('V' << 24) | ('M' << 16) | ('W' << 8) | 'A';
    static final int VERSION = 3;
    private static final int UPDATE_FIELD = 65000;
    static final int LEGACY_HEADER_BYTES = 8;
    static final int HEADER_BYTES = 28;

    // the header a fresh journal file starts with
    static byte[] header() {
        return header(UUID.randomUUID());
    }

    static byte[] header(UUID id) {
        ByteBuffer header = ByteBuffer.allocate(HEADER_BYTES);
        header.putInt(FILE_SIGNATURE);
        header.putInt(VERSION);
        header.putLong(id.getMostSignificantBits());
        header.putLong(id.getLeastSignificantBits());
        header.putInt(crc32(header.array(), 0, HEADER_BYTES - 4));
        return header.array();
    }

    // v1 journals remain readable; a new journal or one upgraded at rotation gets
    // an id inside its own header, so it cannot outlive a separate metadata file.
    static int headerLength(byte[] bytes) {
        if (bytes.length < LEGACY_HEADER_BYTES) {
            return -1;
        }
        ByteBuffer header = ByteBuffer.wrap(bytes);
        if (header.getInt() != FILE_SIGNATURE) {
            return -1;
        }
        int version = header.getInt();
        if (version == 1) {
            return LEGACY_HEADER_BYTES;
        }
        if ((version != 2 && version != VERSION) || bytes.length < HEADER_BYTES) {
            return -1;
        }
        return header.getInt(HEADER_BYTES - 4) == crc32(bytes, 0, HEADER_BYTES - 4) ? HEADER_BYTES : -1;
    }

    @Nullable
    static UUID rotationId(byte[] bytes) {
        if (headerLength(bytes) != HEADER_BYTES) {
            return null;
        }
        ByteBuffer header = ByteBuffer.wrap(bytes);
        return new UUID(header.getLong(8), header.getLong(16));
    }

    @Nullable
    static UUID readRotationId(Path file) throws IOException {
        return readHeader(file).id();
    }

    record Header(UUID id, int version) {
    }

    static Header readHeader(Path file) throws IOException {
        byte[] prefix;
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
            ByteBuffer header = ByteBuffer.allocate(HEADER_BYTES);
            while (header.hasRemaining() && channel.read(header) != -1) {
                // only the header is needed, regardless of how large the journal grows
            }
            prefix = Arrays.copyOf(header.array(), header.position());
        }
        if (headerLength(prefix) < 0) {
            throw new CorruptDataException("Unreadable vault sync journal header: " + file.getFileName());
        }
        return new Header(rotationId(prefix), ByteBuffer.wrap(prefix).getInt(4));
    }

    // disk worker: appends one journal record for a cell through a
    // channel held open for the vault's lifetime (no per-cell stat/open/close syscalls),
    // writing to the path recorded in vault.walFile. returns whether the record hit the
    // disk. records are durable across process crashes without an explicit sync; power
    // loss may drop recent appends, which is accepted - snapshots (the records' final
    // resting place) are forced to disk before the journal holding them is released
    static boolean appendCell(String vaultId, SyncVault vault, VaultCell cell) {
        if (!vault.recoveryComplete) {
            VaultMapper.LOGGER.warn("Cannot append to vault sync journal {} before recovery completes", vaultId);
            return false;
        }
        Path walFile = vault.walFile;
        if (walFile == null) {
            VaultMapper.LOGGER.warn("Cannot append to vault sync journal {}: no journal file set", vaultId);
            return false;
        }
        long position = -1;
        try {
            FileChannel channel = vault.walChannel;
            if (channel == null) {
                channel = openChannel(walFile, vault);
                if (channel == null) {
                    return false;
                }
            }

            byte[] payload = cell.toBuilder().setUnknownFields(UnknownFieldSet.getDefaultInstance()).build().toByteArray();
            ByteBuffer record = ByteBuffer.allocate(8 + payload.length);
            record.putInt(payload.length);
            record.putInt(crc32(payload));
            record.put(payload);
            record.flip();

            position = channel.position();
            writeFully(channel, record);
            return true;
        } catch (Exception e) {
            // a write cut short leaves a partial record at the tail - chop it off so
            // later appends (and the next repair pass) continue from a clean journal
            truncateQuietly(vaultId, vault, position);
            closeChannel(vaultId, vault);
            VaultMapper.LOGGER.warn("Failed to append to vault sync journal {}: {}", vaultId, e.toString());
            return false;
        }
    }

    // One checksummed record contains the entire client batch and its receipt.
    // A torn batch cannot advance the receipt or leave half its cells replayed.
    static void appendUpdate(String vaultId, SyncVault vault, UUID source, long sequence,
                             List<VaultCell> cells) throws IOException {
        if (!vault.recoveryComplete) throw new IOException("Vault recovery is incomplete");
        long committed = vault.durableReceipts.getOrDefault(source, 0L);
        if (sequence <= committed) return;
        if (sequence != committed + 1) throw new IOException("Out-of-order vault update");
        if (!vault.durableReceipts.containsKey(source) && vault.durableReceipts.size() >= 4096) {
            throw new IOException("Vault receipt limit reached");
        }
        if (vault.walFile == null) throw new IOException("No journal path set");
        long position = -1;
        try {
            FileChannel channel = vault.walChannel == null ? openChannel(vault.walFile, vault) : vault.walChannel;
            byte[] batch = Vault.newBuilder().addAllCells(cells).build().toByteArray();
            ByteBuffer envelope = ByteBuffer.allocate(24 + batch.length)
                    .putLong(source.getMostSignificantBits()).putLong(source.getLeastSignificantBits())
                    .putLong(sequence).put(batch);
            byte[] payload = VaultCell.newBuilder().setUnknownFields(UnknownFieldSet.newBuilder()
                    .addField(UPDATE_FIELD, UnknownFieldSet.Field.newBuilder()
                            .addLengthDelimited(ByteString.copyFrom(envelope.array())).build()).build()).build().toByteArray();
            ByteBuffer record = ByteBuffer.allocate(8 + payload.length)
                    .putInt(payload.length).putInt(crc32(payload)).put(payload);
            record.flip();
            position = channel.position();
            writeFully(channel, record);
            channel.force(true); // acknowledge only after the batch and receipt are durable
            vault.durableReceipts.put(source, sequence);
        } catch (IOException e) {
            truncateQuietly(vaultId, vault, position);
            closeChannel(vaultId, vault);
            throw e;
        }
    }

    private record Record(List<VaultCell> cells, UUID source, long sequence) {
    }

    private static Record parseRecord(byte[] payload, boolean supportsReceipts) throws IOException {
        VaultCell cell = VaultCell.parseFrom(payload);
        if (!supportsReceipts || !cell.getUnknownFields().hasField(UPDATE_FIELD)) {
            return new Record(List.of(cell), null, 0);
        }
        List<ByteString> values = cell.getUnknownFields().getField(UPDATE_FIELD).getLengthDelimitedList();
        if (values.size() != 1 || values.get(0).size() <= 24 || !cell.getAllFields().isEmpty()) {
            throw new CorruptDataException("Invalid journal update envelope");
        }
        ByteBuffer bytes = values.get(0).asReadOnlyByteBuffer();
        UUID source = new UUID(bytes.getLong(), bytes.getLong());
        long sequence = bytes.getLong();
        byte[] body = new byte[bytes.remaining()];
        bytes.get(body);
        Vault batch = Vault.parseFrom(body);
        if (sequence <= 0 || batch.getCellsCount() == 0 || batch.getCellsCount() > 128) {
            throw new CorruptDataException("Invalid journal update batch");
        }
        return new Record(batch.getCellsList(), source, sequence);
    }

    // opens (and remembers) the journal channel of a vault, writing the file header when
    // the file is fresh or was truncated to empty by journal repair
    private static FileChannel openChannel(Path walFile, SyncVault vault) throws IOException {
        Files.createDirectories(walFile.getParent());
        FileChannel channel = FileChannel.open(walFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        try {
            repairTail(channel, vault);
            long size = channel.size();
            if (size == 0) {
                vault.walRepairOffset = 0;
                writeFully(channel, ByteBuffer.wrap(header(vault.nextJournalId())));
                channel.force(true);
                vault.walRepairOffset = -1;
            } else {
                channel.position(size);
            }
            vault.walChannel = channel;
            return channel;
        } catch (IOException | RuntimeException e) {
            try {
                channel.close();
            } catch (IOException closeFailure) {
                e.addSuppressed(closeFailure);
            }
            throw e;
        }
    }

    private static void repairTail(FileChannel channel, SyncVault vault) throws IOException {
        if (vault.walRepairOffset < 0) return;
        channel.truncate(vault.walRepairOffset);
        channel.position(channel.size());
        channel.force(true);
        vault.walRepairOffset = -1;
    }

    static void repairPendingTail(Path wal, SyncVault vault) throws IOException {
        if (vault.walRepairOffset < 0) return;
        // A rotation must not move away the damaged file and leave its rollback
        // offset attached to a different, newly-created journal.
        if (!VaultSaveFiles.exists(wal)) {
            vault.walRepairOffset = -1;
            return;
        }
        try (FileChannel channel = FileChannel.open(wal, StandardOpenOption.WRITE)) {
            repairTail(channel, vault);
        }
    }

    private static void writeFully(FileChannel channel, ByteBuffer buffer) throws IOException {
        while (buffer.hasRemaining()) {
            channel.write(buffer);
        }
    }

    // rolls the journal back to its size before a failed append
    private static void truncateQuietly(String vaultId, SyncVault vault, long size) {
        if (size < 0) {
            return;
        }
        vault.walRepairOffset = size;
        try {
            FileChannel channel = vault.walChannel;
            if (channel != null && channel.isOpen()) {
                repairTail(channel, vault);
            }
        } catch (Exception e) {
            VaultMapper.LOGGER.warn("Failed to repair vault sync journal {} after a partial write: {}", vaultId, e.toString());
        }
    }

    // the channel must be closed before the journal is renamed or deleted - an open handle
    // would keep writing into the renamed file (and block renames entirely on Windows)
    static void closeChannel(String vaultId, SyncVault vault) {
        FileChannel channel = vault.walChannel;
        vault.walChannel = null;
        vault.walFile = null;
        if (channel != null) {
            try {
                channel.close();
            } catch (Exception e) {
                VaultMapper.LOGGER.warn("Failed to close vault sync journal {}: {}", vaultId, e.toString());
            }
        }
    }

    // replays a journal into the vault map, then repairs the file if needed so live appends
    // always continue from a clean tail:
    //  - a torn/corrupt tail (crash mid-append) is truncated away at the last intact record
    //  - a file whose header is torn or from an unknown format is truncated to empty
    static int replay(Path file, String vaultId, SyncVault vault) throws IOException {
        try {
            if (!VaultSaveFiles.exists(file)) {
                return 0;
            }
            byte[] bytes = Files.readAllBytes(file);
            if (bytes.length == 0) {
                return 0;
            }

            int goodEnd = scanGoodEnd(bytes);
            if (goodEnd < 0) {
                VaultMapper.LOGGER.warn("Discarding vault sync journal {} of {} with a torn or unknown header", file.getFileName(), vaultId);
                Files.write(file, new byte[0]);
                return 0;
            }

            // the scanned prefix is guaranteed intact - parse it into the map
            int replayed = 0;
            int headerSize = headerLength(bytes);
            boolean supportsReceipts = ByteBuffer.wrap(bytes).getInt(4) >= 3;
            int consumedEnd = headerSize; // end of the parsed prefix, as an offset into bytes
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes, headerSize, goodEnd - headerSize));
            while (in.available() > 0) {
                int length = in.readInt();
                in.readInt(); // crc - already validated by the scan
                byte[] payload = new byte[length];
                in.readFully(payload);
                Record record;
                try {
                    record = parseRecord(payload, supportsReceipts);
                } catch (IOException e) {
                    // bytes are intact but no longer parseable (format changed?) - truncate here
                    VaultMapper.LOGGER.warn("Vault sync journal {} of {} has an unreadable record - recovered {} cells", file.getFileName(), vaultId, replayed);
                    break;
                }
                if (record.source() == null || record.sequence() > vault.durableReceipts.getOrDefault(record.source(), 0L)) {
                    record.cells().forEach(vault::putCell);
                    if (record.source() != null) vault.durableReceipts.put(record.source(), record.sequence());
                }
                replayed += record.cells().size();
                consumedEnd = goodEnd - in.available();
            }

            if (consumedEnd < bytes.length) {
                VaultMapper.LOGGER.warn("Truncating {} broken bytes from vault sync journal {} of {} - recovered {} cells",
                        bytes.length - consumedEnd, file.getFileName(), vaultId, replayed);
                Files.write(file, Arrays.copyOf(bytes, consumedEnd));
            }
            return replayed;
        } catch (IOException e) {
            VaultMapper.LOGGER.warn("Failed to replay vault sync journal {} of {}: {}", file.getFileName(), vaultId, e.toString());
            throw e;
        }
    }

    // scans a journal buffer without building anything, returns the end offset of the last
    // intact record - the header length if only the header is intact, -1 if the header is bad
    static int scanGoodEnd(byte[] bytes) {
        int headerSize = headerLength(bytes);
        if (headerSize < 0) {
            return -1;
        }
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes, headerSize, bytes.length - headerSize));
            // only regarded as intact once the entire record (header + payload, crc-checked)
            // has been consumed - a torn record must not move the boundary past its header
            int goodEnd = headerSize;
            while (in.available() >= 8) {
                int recordStart = bytes.length - in.available();
                int length = in.readInt();
                int crc = in.readInt();
                // a real cell never serializes to zero bytes, so length 0 is corruption,
                // not a record (it would otherwise slip through with a matching crc of 0)
                if (length <= 0 || length > in.available()) {
                    break; // torn record
                }
                byte[] payload = new byte[length];
                in.readFully(payload);
                if (crc32(payload) != crc) {
                    break; // corrupt record
                }
                goodEnd = recordStart + 8 + length;
            }
            return goodEnd;
        } catch (Exception e) {
            return -1;
        }
    }

    // Unlike a CRC-only scan, this does not copy an unreadable old record ahead of
    // newer live records when two journals are merged. Replay also stops at this point.
    static int scanParsableEnd(byte[] bytes) {
        int goodEnd = scanGoodEnd(bytes);
        if (goodEnd < 0) {
            return -1;
        }
        int headerSize = headerLength(bytes);
        boolean supportsReceipts = ByteBuffer.wrap(bytes).getInt(4) >= 3;
        int parsedEnd = headerSize;
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes, headerSize, goodEnd - headerSize));
            while (in.available() > 0) {
                int length = in.readInt();
                in.readInt(); // crc checked by scanGoodEnd
                byte[] payload = new byte[length];
                in.readFully(payload);
                try {
                    parseRecord(payload, supportsReceipts);
                } catch (IOException e) {
                    break;
                }
                parsedEnd = goodEnd - in.available();
            }
        } catch (Exception ignored) {
            // return the last parseable record boundary
        }
        return parsedEnd;
    }

    private static int crc32(byte[] data) {
        return crc32(data, 0, data.length);
    }

    private static int crc32(byte[] data, int offset, int length) {
        CRC32 crc = new CRC32();
        crc.update(data, offset, length);
        return (int) crc.getValue();
    }
}
