package com.nodiumhosting.vaultmapper.server;

import com.nodiumhosting.vaultmapper.VaultMapper;
import com.nodiumhosting.vaultmapper.proto.VaultCell;

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
import java.util.zip.CRC32;

// the vault journal (write-ahead log): record format and per-vault journal file I/O.
// knows nothing about indexes, config, or the server's worlds - callers hand it paths.
//
// file layout: int32 signature + int32 version, then records of
//              int32 length + int32 crc32 + serialized VaultCell (last write wins per cell)
final class VaultJournal {
    static final int FILE_SIGNATURE = ('V' << 24) | ('M' << 16) | ('W' << 8) | 'A';
    static final int VERSION = 1;
    static final int HEADER_BYTES = 8;

    // the header a fresh journal file starts with
    static byte[] header() {
        ByteBuffer header = ByteBuffer.allocate(HEADER_BYTES);
        header.putInt(FILE_SIGNATURE);
        header.putInt(VERSION);
        return header.array();
    }

    // appends one journal record for a cell - the only per-cell disk write, through a
    // channel held open for the vault's lifetime (no per-cell stat/open/close syscalls),
    // writing to the path recorded in vault.walFile. returns whether the record hit the
    // disk. records are durable across process crashes without an explicit sync; power
    // loss may drop recent appends, which is accepted - snapshots (the records' final
    // resting place) are forced to disk before the journal holding them is released
    static boolean appendCell(String vaultId, SyncVault vault, VaultCell cell) {
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

            byte[] payload = cell.toByteArray();
            ByteBuffer record = ByteBuffer.allocate(8 + payload.length);
            record.putInt(payload.length);
            record.putInt(crc32(payload));
            record.put(payload);
            record.flip();

            position = channel.position();
            writeFully(channel, record);
            vault.walPending++; // a record actually hit the disk - count towards a flush
            return true;
        } catch (Exception e) {
            // a write cut short leaves a partial record at the tail - chop it off so
            // later appends (and the next repair pass) continue from a clean journal
            truncateQuietly(vaultId, vault, position);
            VaultMapper.LOGGER.warn("Failed to append to vault sync journal {}: {}", vaultId, e.toString());
            return false;
        }
    }

    // opens (and remembers) the journal channel of a vault, writing the file header when
    // the file is fresh or was truncated to empty by journal repair
    @Nullable
    private static FileChannel openChannel(Path walFile, SyncVault vault) throws IOException {
        Files.createDirectories(walFile.getParent());
        FileChannel channel = FileChannel.open(walFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        long size = channel.size();
        if (size == 0) {
            try {
                writeFully(channel, ByteBuffer.wrap(header()));
            } catch (Exception e) {
                // a failed header write leaves a 1-7 byte torn header behind and the
                // channel unowned - empty the file and drop the handle so the next open
                // starts a clean journal instead of appending after the garbage
                try {
                    channel.truncate(0);
                } catch (Exception ignored) {
                }
                try {
                    channel.close();
                } catch (Exception ignored) {
                }
                throw e;
            }
        } else {
            channel.position(size); // sole writer - continue at the end
        }
        vault.walChannel = channel;
        return channel;
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
        try {
            FileChannel channel = vault.walChannel;
            if (channel != null && channel.isOpen()) {
                channel.truncate(size);
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
    //  - a file whose header is torn or from an unknown format is truncated to empty -
    //    this includes VERSION bumps, which therefore discard journaled cells not yet
    //    folded into the snapshot (bounded by the flush threshold) and must be considered
    //    a breaking change
    static int replay(Path file, String vaultId, SyncVault vault) {
        try {
            if (!Files.exists(file)) {
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
            int consumedEnd = HEADER_BYTES; // end of the parsed prefix, as an offset into bytes
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes, HEADER_BYTES, goodEnd - HEADER_BYTES));
            while (in.available() > 0) {
                int length = in.readInt();
                in.readInt(); // crc - already validated by the scan
                byte[] payload = new byte[length];
                in.readFully(payload);
                VaultCell cell;
                try {
                    cell = VaultCell.parseFrom(payload);
                } catch (Exception e) {
                    // bytes are intact but no longer parseable (format changed?) - truncate here
                    VaultMapper.LOGGER.warn("Vault sync journal {} of {} has an unreadable record - recovered {} cells", file.getFileName(), vaultId, replayed);
                    break;
                }
                vault.cells.put(SyncVault.cellKey(cell), cell);
                replayed++;
                consumedEnd = goodEnd - in.available();
            }

            if (consumedEnd < bytes.length) {
                VaultMapper.LOGGER.warn("Truncating {} broken bytes from vault sync journal {} of {} - recovered {} cells",
                        bytes.length - consumedEnd, file.getFileName(), vaultId, replayed);
                Files.write(file, Arrays.copyOf(bytes, consumedEnd));
            }
            return replayed;
        } catch (Exception e) {
            VaultMapper.LOGGER.warn("Failed to replay vault sync journal {} of {}: {}", file.getFileName(), vaultId, e.toString());
            return 0;
        }
    }

    // scans a journal buffer without building anything, returns the end offset of the last
    // intact record - HEADER_BYTES if only the header is intact, -1 if the header itself
    // is torn or unknown
    static int scanGoodEnd(byte[] bytes) {
        if (bytes.length < HEADER_BYTES) {
            return -1;
        }
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes));
            int signature = in.readInt();
            int version = in.readInt();
            if (signature != FILE_SIGNATURE || version != VERSION) {
                return -1;
            }
            // only regarded as intact once the entire record (header + payload, crc-checked)
            // has been consumed - a torn record must not move the boundary past its header
            int goodEnd = HEADER_BYTES;
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

    private static int crc32(byte[] data) {
        CRC32 crc = new CRC32();
        crc.update(data, 0, data.length);
        return (int) crc.getValue();
    }
}
