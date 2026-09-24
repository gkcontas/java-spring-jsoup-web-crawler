package com.gkcontas.crawler.fetch;

import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * Keeps a minimum pause between two requests to the same host.
 *
 * <p>A crawler with no throttle is indistinguishable from a denial-of-service attempt,
 * and from the other side of the wire nobody can tell the difference between enthusiasm
 * and an attack. The limit is per host because that is where the load actually lands.
 *
 * <p>Blocking here is deliberate and cheap: the callers run on virtual threads, so a
 * thread parked waiting its turn costs essentially nothing. On platform threads this
 * same design would burn a pooled thread per host being polite to.
 */
@Component
public class HostRateLimiter {

    private final Map<String, Long> nextAllowedNanosByHost = new ConcurrentHashMap<>();

    public void acquire(String host, Duration minimumDelay) throws InterruptedException {
        if (minimumDelay == null || minimumDelay.isZero() || minimumDelay.isNegative()) {
            return;
        }
        String key = host.toLowerCase(Locale.ROOT);
        long delayNanos = minimumDelay.toNanos();
        long now = System.nanoTime();

        // compute() runs atomically for the key, so each caller reserves a distinct slot
        // and the returned value is its own turn. Reading the last timestamp and writing
        // it back separately would let two threads reserve the same instant.
        long myTurnNanos = nextAllowedNanosByHost.compute(key, (ignored, nextAllowed) -> {
            long earliest = (nextAllowed == null || nextAllowed - now < 0) ? now : nextAllowed;
            return earliest + delayNanos;
        }) - delayNanos;

        long waitNanos = myTurnNanos - now;
        if (waitNanos > 0) {
            Thread.sleep(Duration.ofNanos(waitNanos));
        }
    }
}
