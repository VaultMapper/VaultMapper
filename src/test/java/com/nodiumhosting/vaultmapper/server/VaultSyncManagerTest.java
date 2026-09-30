package com.nodiumhosting.vaultmapper.server;

import com.nodiumhosting.vaultmapper.proto.Message;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.UUID;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VaultSyncManagerTest {
    @TempDir Path dir;

    @Test
    void rejectedRetirementKeepsItsJournalReachableUntilCleanupRetrySucceeds() throws Exception {
        String id = "vault_" + UUID.randomUUID();
        Map<String, SyncVault> vaults = state("vaults");
        SyncVault vault = new SyncVault();
        vault.putCell(com.nodiumhosting.vaultmapper.proto.VaultCell.newBuilder().setX(1).build());
        VaultSaveStore.appendCell(id, vault, vault.cells.get("1,0"), dir).get(10, TimeUnit.SECONDS);
        var channel = vault.walChannel;
        vaults.put(id, vault);
        try {
            VaultSyncManager.retireVault(id, vault,
                    () -> CompletableFuture.failedFuture(new RejectedExecutionException("full")), Runnable::run,
                    () -> { throw new AssertionError("must not recover before deletion"); });
            assertEquals(vault, vaults.get(id));
            assertTrue(channel.isOpen());
            assertTrue(vault.retiring);
            assertFalse(vault.loaded);
            assertFalse(vault.deleteInFlight);
            assertFalse(vault.recoveryComplete);
            VaultSyncManager.retireVault(id, vault, () -> VaultSaveStore.deleteSave(id, vault, dir), Runnable::run,
                    () -> { throw new AssertionError("empty vault must be removed"); });
            VaultSaveStore.awaitFlushDrain();
            assertFalse(vaults.containsKey(id));
            assertFalse(channel.isOpen());
        } finally {
            VaultSaveStore.awaitFlushDrain();
            VaultJournal.closeChannel(id, vault);
            vaults.remove(id);
        }
    }

    @Test
    void joinDuringRetirementWaitsForFreshRecovery() throws Exception {
        String id = "vault_" + UUID.randomUUID();
        Map<String, SyncVault> vaults = state("vaults");
        SyncVault vault = new SyncVault();
        vaults.put(id, vault);
        CompletableFuture<Void> deletion = new CompletableFuture<>();
        AtomicBoolean recovered = new AtomicBoolean();
        try {
            VaultSyncManager.retireVault(id, vault, () -> deletion, Runnable::run, () -> recovered.set(true));
            vault.players.add(UUID.randomUUID());
            assertFalse(vault.loaded);
            assertFalse(recovered.get());
            deletion.complete(null);
            assertEquals(vault, vaults.get(id));
            assertTrue(recovered.get());
            assertFalse(vault.retiring);
        } finally {
            vaults.remove(id);
        }
    }

    @Test
    void shutdownRetriesRejectedRetirementBeforeImmediateRejoinCanRecoverExpiredData() throws Exception {
        String id = "vault_" + UUID.randomUUID();
        UUID source = UUID.randomUUID();
        Map<String, SyncVault> vaults = state("vaults");
        SyncVault vault = new SyncVault();
        var expired = com.nodiumhosting.vaultmapper.proto.VaultCell.newBuilder()
                .setX(1).setRoomName("expired").build();
        vault.putCell(expired);
        VaultSaveStore.appendUpdate(id, vault, source, 1, List.of(expired), dir).get(10, TimeUnit.SECONDS);
        vaults.put(id, vault);
        try {
            VaultSyncManager.retireVault(id, vault,
                    () -> CompletableFuture.failedFuture(new RejectedExecutionException("full")), Runnable::run,
                    () -> { throw new AssertionError("not yet deleted"); });
            assertTrue(java.nio.file.Files.exists(VaultSaveStore.walPath(dir, id)));
            AtomicBoolean deletionRetried = new AtomicBoolean();
            VaultSaveStore.flushAllAtShutdown(vaults,
                    (key, state) -> { throw new AssertionError("must not save expired memory"); },
                    (key, state) -> {
                        deletionRetried.set(true);
                        VaultSaveStore.deleteSave(key, state, dir);
                    });
            assertTrue(deletionRetried.get());
            assertFalse(java.nio.file.Files.exists(VaultSaveStore.walPath(dir, id)));
            vaults.remove(id); // manager clears state only after shutdown finishes

            // The next join makes the startup sweep skip this id. Recovery must
            // still be empty: it cannot rely on that sweep completing the expiry.
            SyncVault fresh = new SyncVault();
            vaults.put(id, fresh);
            VaultSaveStore.sweepAsync(dir, System.currentTimeMillis(), 0, Set.of(id)).get(10, TimeUnit.SECONDS);
            VaultSaveStore.loadAsync(dir, id, fresh).get(10, TimeUnit.SECONDS);
            assertTrue(fresh.cells.isEmpty());
            assertTrue(fresh.durableReceipts.isEmpty());
            assertTrue(fresh.needsStreamReset(source, 2));
        } finally {
            VaultSaveStore.awaitFlushDrain();
            VaultJournal.closeChannel(id, vault);
            vaults.remove(id);
        }
    }

    @Test
    void oldLeaveCannotRemoveNewSessionForTheSameVault() throws Exception {
        UUID player = UUID.randomUUID();
        UUID oldSession = UUID.randomUUID();
        UUID currentSession = UUID.randomUUID();
        String vaultId = "vault_" + UUID.randomUUID();
        Map<UUID, String> playerVaults = state("playerVaults");
        Map<UUID, UUID> playerSessions = state("playerSessions");
        Map<String, SyncVault> vaults = state("vaults");
        SyncVault vault = new SyncVault();
        vault.players.add(player);
        vault.awaitingSnapshot.add(player);
        vault.latestMoves.put(player, Message.getDefaultInstance());
        vault.pendingMoves.put(player, Message.getDefaultInstance());
        playerVaults.put(player, vaultId);
        playerSessions.put(player, currentSession);
        vaults.put(vaultId, vault);
        try {
            VaultSyncManager.handleLeave(player, vaultId, oldSession);
            assertEquals(vaultId, playerVaults.get(player));
            assertEquals(currentSession, playerSessions.get(player));
            assertTrue(vault.players.contains(player));
            assertTrue(vault.awaitingSnapshot.contains(player));

            VaultSyncManager.handleLeave(player, vaultId, currentSession);
            assertFalse(playerVaults.containsKey(player));
            assertFalse(playerSessions.containsKey(player));
            assertTrue(vault.players.isEmpty());
            assertTrue(vault.awaitingSnapshot.isEmpty());
            assertTrue(vault.latestMoves.isEmpty());
            assertTrue(vault.pendingMoves.isEmpty());
        } finally {
            playerVaults.remove(player);
            playerSessions.remove(player);
            vaults.remove(vaultId);
        }
    }

    @SuppressWarnings("unchecked")
    private static <K, V> Map<K, V> state(String name) throws Exception {
        Field field = VaultSyncManager.class.getDeclaredField(name);
        field.setAccessible(true);
        return (Map<K, V>) field.get(null);
    }
}
