package com.example.balance.support;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Runs a batch of tasks on a fixed pool, holding every worker at a start gate until all of them are ready.
 * Releasing them at once makes the tasks hit the service at the same moment, which is what gives races
 * a realistic chance to show up.
 */
public final class ConcurrentRunner {

    private ConcurrentRunner() {
    }

    public static Outcomes run(int threads, List<Runnable> tasks) throws InterruptedException {
        return run(threads, tasks, 60, TimeUnit.SECONDS);
    }

    public static Outcomes run(int threads, List<Runnable> tasks, long timeout, TimeUnit unit)
            throws InterruptedException {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(Math.min(threads, tasks.size()));
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Throwable>> futures = new ArrayList<>(tasks.size());
            for (Runnable task : tasks) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    try {
                        task.run();
                        return null;
                    } catch (Throwable t) {
                        return t;
                    }
                }));
            }
            if (!ready.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Workers did not start in time");
            }
            start.countDown();

            long deadline = System.nanoTime() + unit.toNanos(timeout);
            List<Throwable> results = new ArrayList<>(tasks.size());
            for (Future<Throwable> future : futures) {
                long remaining = deadline - System.nanoTime();
                try {
                    results.add(future.get(Math.max(remaining, 0), TimeUnit.NANOSECONDS));
                } catch (TimeoutException e) {
                    throw new AssertionError("Tasks did not finish within " + timeout + " " + unit
                            + " (deadlock?)", e);
                } catch (ExecutionException e) {
                    throw new AssertionError("Worker failed unexpectedly", e.getCause());
                }
            }
            return new Outcomes(results);
        } finally {
            pool.shutdownNow();
        }
    }

    /** Per-task results, in submission order. A {@code null} entry means the task succeeded. */
    public record Outcomes(List<Throwable> results) {

        public boolean succeeded(int index) {
            return results.get(index) == null;
        }

        public long successCount() {
            return results.stream().filter(r -> r == null).count();
        }

        public long failureCount(Class<? extends Throwable> type) {
            return results.stream().filter(type::isInstance).count();
        }

        /** Failures that are not of the given expected type; should normally be empty. */
        public List<Throwable> unexpectedFailures(Class<?>... expected) {
            return results.stream()
                    .filter(r -> r != null)
                    .filter(f -> {
                        for (Class<?> type : expected) {
                            if (type.isInstance(f)) {
                                return false;
                            }
                        }
                        return true;
                    })
                    .toList();
        }
    }
}
