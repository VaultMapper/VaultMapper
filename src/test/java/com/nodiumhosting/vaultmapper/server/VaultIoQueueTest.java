package com.nodiumhosting.vaultmapper.server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class VaultIoQueueTest {
    @TempDir Path dir;

    @Test
    void slowMaintenancePausesOnlyItsOwnFileLane() throws Exception {
        ExecutorService maintenance = Executors.newSingleThreadExecutor();
        ExecutorService journal = Executors.newSingleThreadExecutor();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            Path first = dir.resolve("first.wal");
            CompletableFuture<Void> slow = VaultIoQueue.submit(first, maintenance, () -> {
                entered.countDown();
                assertTrue(release.await(10, TimeUnit.SECONDS));
                return null;
            });
            assertTrue(entered.await(10, TimeUnit.SECONDS));
            CompletableFuture<Integer> same = VaultIoQueue.submit(first, journal, () -> 1);
            CompletableFuture<Integer> other = VaultIoQueue.submit(dir.resolve("other.wal"), journal, () -> 2);
            assertEquals(2, other.get(10, TimeUnit.SECONDS));
            assertFalse(same.isDone());
            release.countDown();
            slow.get(10, TimeUnit.SECONDS);
            assertEquals(1, same.get(10, TimeUnit.SECONDS));
        } finally {
            release.countDown();
            VaultIoQueue.awaitAll();
            maintenance.shutdown();
            journal.shutdown();
        }
    }

    @Test
    void queueOverflowRejectsWithoutBlockingAndRetryWorksAfterDrain() throws Exception {
        ExecutorService worker = Executors.newSingleThreadExecutor();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            Path path = dir.resolve("vault.wal");
            List<CompletableFuture<Integer>> queued = new ArrayList<>();
            queued.add(VaultIoQueue.submit(path, worker, () -> {
                entered.countDown();
                assertTrue(release.await(10, TimeUnit.SECONDS));
                return 0;
            }));
            assertTrue(entered.await(10, TimeUnit.SECONDS));
            for (int i = 1; i < 256; i++) {
                int value = i;
                queued.add(VaultIoQueue.submit(path, worker, () -> value));
            }
            CompletableFuture<Integer> rejected = VaultIoQueue.submit(path, worker, () -> 999);
            assertTrue(rejected.isCompletedExceptionally());
            assertThrows(ExecutionException.class, rejected::get);
            release.countDown();
            for (int i = 0; i < queued.size(); i++) assertEquals(i, queued.get(i).get(10, TimeUnit.SECONDS));
            assertEquals(999, VaultIoQueue.submit(path, worker, () -> 999).get(10, TimeUnit.SECONDS));
        } finally {
            release.countDown();
            VaultIoQueue.awaitAll();
            worker.shutdown();
        }
    }

    @Test
    void failedTaskDoesNotPoisonLaterOperationsOnTheSamePath() throws Exception {
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            Path path = dir.resolve("vault.wal");
            CompletableFuture<Void> failed = VaultIoQueue.submit(path, worker, () -> { throw new IllegalStateException("failed"); });
            CompletableFuture<String> next = VaultIoQueue.submit(path, worker, () -> "recovered");
            assertThrows(ExecutionException.class, failed::get);
            assertEquals("recovered", next.get(10, TimeUnit.SECONDS));
        } finally {
            VaultIoQueue.awaitAll();
            worker.shutdown();
        }
    }
}
