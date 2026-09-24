package com.gkcontas.crawler.unit;

import static org.assertj.core.api.Assertions.assertThat;

import com.gkcontas.crawler.fetch.HostRateLimiter;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

class HostRateLimiterTest {

    private final HostRateLimiter rateLimiter = new HostRateLimiter();

    @Test
    void shouldNotWaitOnTheFirstRequestToAHost() throws InterruptedException {
        long start = System.nanoTime();

        rateLimiter.acquire("example.com", Duration.ofMillis(200));

        assertThat(elapsedMillis(start)).isLessThan(100);
    }

    @Test
    void shouldSpaceOutConsecutiveRequestsToTheSameHost() throws InterruptedException {
        rateLimiter.acquire("example.com", Duration.ofMillis(200));
        long start = System.nanoTime();

        rateLimiter.acquire("example.com", Duration.ofMillis(200));

        assertThat(elapsedMillis(start)).isGreaterThanOrEqualTo(150);
    }

    @Test
    void shouldLimitEachHostIndependently() throws InterruptedException {
        rateLimiter.acquire("one.example.com", Duration.ofMillis(300));
        long start = System.nanoTime();

        // Being polite to one host is no reason to make another one wait.
        rateLimiter.acquire("two.example.com", Duration.ofMillis(300));

        assertThat(elapsedMillis(start)).isLessThan(100);
    }

    @Test
    void shouldGiveEachConcurrentCallerItsOwnSlot() throws Exception {
        int callers = 4;
        Duration delay = Duration.ofMillis(100);
        long start = System.nanoTime();

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<Long>> futures = java.util.stream.IntStream.range(0, callers)
                    .mapToObj(i -> executor.submit(() -> {
                        rateLimiter.acquire("example.com", delay);
                        return System.nanoTime();
                    }))
                    .toList();
            for (Future<Long> future : futures) {
                future.get();
            }
        }

        // Four callers releasing one at a time, 100 ms apart, means the last one starts
        // around 300 ms in. If the check and the update were separate operations they
        // would all read the same timestamp and leave together, and this would be ~0.
        assertThat(elapsedMillis(start)).isGreaterThanOrEqualTo(250);
    }

    private static long elapsedMillis(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }
}
