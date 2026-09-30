package com.nodiumhosting.vaultmapper.server;

import com.nodiumhosting.vaultmapper.proto.VaultCell;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class VaultJournalTest {
    @TempDir Path dir;

    @Test
    void failedPartialWriteAndRollbackMustBeRepairedBeforeAnotherBatchIsAcknowledged() throws Exception {
        Path wal = dir.resolve("vault.wal");
        Files.write(wal, VaultJournal.header(new UUID(1, 1)));
        SyncVault vault = new SyncVault();
        vault.walFile = wal;
        FileChannel channel = FileChannel.open(wal, StandardOpenOption.WRITE);
        channel.position(channel.size());
        vault.walChannel = new FailedWriteAndRollbackChannel(channel);
        UUID failedSource = UUID.randomUUID();
        assertThrows(IOException.class, () -> VaultJournal.appendUpdate("vault", vault, failedSource, 1,
                List.of(cell(1, "failed"))));
        assertEquals(VaultJournal.HEADER_BYTES, vault.walRepairOffset);
        assertEquals(VaultJournal.HEADER_BYTES + 7, Files.size(wal));
        assertFalse(vault.durableReceipts.containsKey(failedSource));
        assertNull(vault.walChannel);

        // The next open must repair the known bad prefix, not append at its EOF.
        UUID savedSource = UUID.randomUUID();
        vault.walFile = wal;
        VaultJournal.appendUpdate("vault", vault, savedSource, 1, List.of(cell(2, "saved")));
        assertEquals(-1, vault.walRepairOffset);
        assertEquals(1L, vault.durableReceipts.get(savedSource));
        VaultJournal.closeChannel("vault", vault);
        SyncVault recovered = new SyncVault();
        VaultJournal.replay(wal, "vault", recovered);
        assertEquals("saved", recovered.cells.get("2,0").getRoomName());
        assertFalse(recovered.cells.containsKey("1,0"));
        assertEquals(1L, recovered.durableReceipts.get(savedSource));
        assertFalse(recovered.durableReceipts.containsKey(failedSource));
    }

    @Test
    void unresolvedRollbackCannotRotateAwayItsRepairRequirement() throws Exception {
        Path wal = VaultSaveStore.walPath(dir, "vault");
        Files.createDirectory(wal); // a filesystem error, not corrupt journal bytes
        SyncVault vault = new SyncVault();
        vault.putCell(cell(1, "pending"));
        vault.walRepairOffset = VaultJournal.HEADER_BYTES;
        vault.flushInFlight.set(true);
        VaultSaveStore.flushAsync("vault", vault, dir);
        VaultSaveStore.awaitFlushDrain();
        assertEquals(VaultJournal.HEADER_BYTES, vault.walRepairOffset);
        assertFalse(Files.exists(VaultSaveStore.snapshotPath(dir, "vault")));
        assertFalse(vault.flushInFlight.get());
        UUID source = UUID.randomUUID();
        vault.walFile = wal;
        assertThrows(IOException.class, () -> VaultJournal.appendUpdate("vault", vault, source, 1,
                List.of(cell(1, "pending"))));
        assertFalse(vault.durableReceipts.containsKey(source));
        Files.delete(wal);
        vault.walFile = wal;
        VaultJournal.appendUpdate("vault", vault, source, 1, List.of(cell(1, "pending")));
        assertEquals(1L, vault.durableReceipts.get(source));
        VaultJournal.closeChannel("vault", vault);
    }

    private static VaultCell cell(int x, String name) {
        return VaultCell.newBuilder().setX(x).setRoomName(name).build();
    }

    // Inject the otherwise difficult-to-reproduce disk fault: a short write,
    // followed by I/O errors on the remaining bytes and on rollback truncation.
    private static final class FailedWriteAndRollbackChannel extends FileChannel {
        private final FileChannel delegate;
        private boolean wrote;

        FailedWriteAndRollbackChannel(FileChannel delegate) {
            this.delegate = delegate;
        }

        @Override public int write(ByteBuffer src) throws IOException {
            if (wrote) throw new IOException("Injected write failure");
            wrote = true;
            int limit = src.limit();
            src.limit(src.position() + 7);
            try { return delegate.write(src); } finally { src.limit(limit); }
        }
        @Override public FileChannel truncate(long size) throws IOException { throw new IOException("Injected rollback failure"); }
        @Override public long position() throws IOException { return delegate.position(); }
        @Override public FileChannel position(long position) throws IOException { delegate.position(position); return this; }
        @Override public long size() throws IOException { return delegate.size(); }
        @Override public void force(boolean metadata) throws IOException { delegate.force(metadata); }
        @Override protected void implCloseChannel() throws IOException { delegate.close(); }
        @Override public int read(ByteBuffer dst) throws IOException { return delegate.read(dst); }
        @Override public long read(ByteBuffer[] dsts, int offset, int length) throws IOException { return delegate.read(dsts, offset, length); }
        @Override public int read(ByteBuffer dst, long position) throws IOException { return delegate.read(dst, position); }
        @Override public long write(ByteBuffer[] srcs, int offset, int length) throws IOException { return delegate.write(srcs, offset, length); }
        @Override public int write(ByteBuffer src, long position) throws IOException { return delegate.write(src, position); }
        @Override public long transferTo(long position, long count, WritableByteChannel target) throws IOException { return delegate.transferTo(position, count, target); }
        @Override public long transferFrom(ReadableByteChannel src, long position, long count) throws IOException { return delegate.transferFrom(src, position, count); }
        @Override public MappedByteBuffer map(MapMode mode, long position, long size) throws IOException { return delegate.map(mode, position, size); }
        @Override public FileLock lock(long position, long size, boolean shared) throws IOException { return delegate.lock(position, size, shared); }
        @Override public FileLock tryLock(long position, long size, boolean shared) throws IOException { return delegate.tryLock(position, size, shared); }
    }
}
