package com.moneytransfer.transfer.domain.saga;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RetryPolicyTest {

    private final List<Duration> sleeps = new ArrayList<>();
    private final RetryPolicy policy = new RetryPolicy(3, Duration.ofMillis(100), Duration.ofMillis(1000),
            sleeps::add, new Random(42));

    @Test
    void retriesUnavailableUpToMaxAttemptsThenGivesUp() {
        AtomicInteger calls = new AtomicInteger();
        assertThrows(DownstreamUnavailableException.class, () -> policy.run("op", () -> {
            calls.incrementAndGet();
            throw new DownstreamUnavailableException("ledger", "connection refused", null);
        }));
        assertEquals(3, calls.get());
        assertEquals(2, sleeps.size(), "waits between attempts, not after the last one");
    }

    @Test
    void neverRetriesARejection() {
        AtomicInteger calls = new AtomicInteger();
        assertThrows(DownstreamRejectedException.class, () -> policy.run("op", () -> {
            calls.incrementAndGet();
            throw new DownstreamRejectedException("account", 409, "INSUFFICIENT_FUNDS", "not enough");
        }));
        assertEquals(1, calls.get());
        assertTrue(sleeps.isEmpty());
    }

    @Test
    void succeedsWhenAFailureIsTransient() {
        AtomicInteger calls = new AtomicInteger();
        policy.run("op", () -> {
            if (calls.incrementAndGet() == 1) {
                throw new DownstreamUnavailableException("account", "timeout", null);
            }
        });
        assertEquals(2, calls.get());
    }

    @Test
    void backoffGrowsExponentiallyWithJitterAndIsCapped() {
        for (int i = 0; i < 200; i++) {
            assertBetween(policy.backoffBeforeRetry(1), 50, 100);
            assertBetween(policy.backoffBeforeRetry(2), 100, 200);
            assertBetween(policy.backoffBeforeRetry(3), 200, 400);
            assertBetween(policy.backoffBeforeRetry(10), 500, 1000);
        }
    }

    private static void assertBetween(Duration d, long min, long max) {
        long ms = d.toMillis();
        assertTrue(ms >= min && ms <= max, ms + " ms not in [" + min + ", " + max + "]");
    }
}
