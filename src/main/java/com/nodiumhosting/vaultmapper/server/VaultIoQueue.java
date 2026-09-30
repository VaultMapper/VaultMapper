package com.nodiumhosting.vaultmapper.server;

import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

// Per-vault ordering without making a journal thread wait for another vault's
// recovery/rotation. Waiting tasks are bounded and never block the server thread.
final class VaultIoQueue {
    private static final int MAX_PER_VAULT = 256;
    private static final int MAX_TOTAL = 4096;
    private static final Object DRAIN = new Object();
    private static int total;
    private static final Map<Path, VaultIoQueue> LANES = new HashMap<>();
    private Path key;
    private int pending;
    private CompletableFuture<Void> tail = CompletableFuture.completedFuture(null);

    static <T> CompletableFuture<T> submit(Path path, Executor executor, Callable<T> action) {
        synchronized (LANES) {
            Path key = path.toAbsolutePath().normalize();
            VaultIoQueue queue = LANES.computeIfAbsent(key, ignored -> {
                VaultIoQueue created = new VaultIoQueue();
                created.key = key;
                return created;
            });
            CompletableFuture<T> result = queue.submit(executor, action);
            if (queue.pending == 0) LANES.remove(key, queue);
            return result;
        }
    }

    synchronized <T> CompletableFuture<T> submit(Executor executor, Callable<T> action) {
        if (pending >= MAX_PER_VAULT) {
            return CompletableFuture.failedFuture(new RejectedExecutionException("Vault disk queue is full"));
        }
        CompletableFuture<T> result = reserve();
        if (result.isDone()) return result;
        pending++;
        CompletableFuture<Void> preceding = tail;
        // Install the tail before registering callbacks: a preceding task may already
        // have completed, and callbacks must not escape the ordering boundary.
        tail = result.handle((value, error) -> null);
        result.whenComplete((value, error) -> {
            synchronized (LANES) {
                synchronized (this) {
                    pending--;
                    if (pending == 0 && key != null) LANES.remove(key, this);
                }
            }
        });
        preceding.whenComplete((value, error) -> dispatch(executor, action, result));
        return result;
    }

    static <T> CompletableFuture<T> independent(Executor executor, Callable<T> action) {
        CompletableFuture<T> result = reserve();
        if (!result.isDone()) dispatch(executor, action, result);
        return result;
    }

    // Reserve an entire multi-stage operation as well, so shutdown cannot observe
    // an idle gap between rotation completing and snapshot writing being scheduled.
    static <T> CompletableFuture<T> reserve() {
        synchronized (DRAIN) {
            if (total >= MAX_TOTAL) {
                return CompletableFuture.failedFuture(new RejectedExecutionException("Vault disk queues are full"));
            }
            total++;
        }
        CompletableFuture<T> result = new CompletableFuture<>();
        result.whenComplete((value, error) -> {
            synchronized (DRAIN) {
                total--;
                DRAIN.notifyAll();
            }
        });
        return result;
    }

    private static <T> void dispatch(Executor executor, Callable<T> action, CompletableFuture<T> result) {
        try {
            executor.execute(() -> {
                try {
                    result.complete(action.call());
                } catch (Throwable e) {
                    result.completeExceptionally(e);
                }
            });
        } catch (RuntimeException e) {
            result.completeExceptionally(e);
        }
    }

    static void awaitAll() {
        boolean interrupted = false;
        synchronized (DRAIN) {
            while (total > 0) {
                try {
                    DRAIN.wait();
                } catch (InterruptedException e) {
                    interrupted = true;
                }
            }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }
}
