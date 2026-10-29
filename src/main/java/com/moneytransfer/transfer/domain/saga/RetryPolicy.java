package com.moneytransfer.transfer.domain.saga;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Objects;
import java.util.random.RandomGenerator;

/**
 * Retries a downstream call with exponential backoff and jitter.
 *
 * Only {@link DownstreamUnavailableException} is retried. A {@link DownstreamRejectedException}
 * is a definite answer and is returned to the caller at once.
 *
 * Retrying is safe because every downstream call carries the transferId and is idempotent there,
 * so a retry after a timeout never applies the same effect twice.
 */
public final class RetryPolicy {

    @FunctionalInterface
    public interface Sleeper {
        void sleep(Duration duration) throws InterruptedException;
    }

    private static final Logger log = LoggerFactory.getLogger(RetryPolicy.class);

    private final int maxAttempts;
    private final Duration baseDelay;
    private final Duration maxDelay;
    private final Sleeper sleeper;
    private final RandomGenerator random;

    public RetryPolicy(int maxAttempts, Duration baseDelay, Duration maxDelay, Sleeper sleeper, RandomGenerator random) {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be at least 1");
        }
        this.maxAttempts = maxAttempts;
        this.baseDelay = Objects.requireNonNull(baseDelay);
        this.maxDelay = Objects.requireNonNull(maxDelay);
        this.sleeper = Objects.requireNonNull(sleeper);
        this.random = Objects.requireNonNull(random);
    }

    public static RetryPolicy of(int maxAttempts, Duration baseDelay, Duration maxDelay) {
        return new RetryPolicy(maxAttempts, baseDelay, maxDelay,
                d -> Thread.sleep(d.toMillis()), RandomGenerator.getDefault());
    }

    public void run(String operation, Runnable action) {
        for (int attempt = 1; ; attempt++) {
            try {
                action.run();
                if (attempt > 1) {
                    log.info("{} succeeded on attempt {}/{}", operation, attempt, maxAttempts);
                }
                return;
            } catch (DownstreamUnavailableException e) {
                if (attempt >= maxAttempts) {
                    log.warn("{} failed on attempt {}/{}, giving up: {}", operation, attempt, maxAttempts, e.getMessage());
                    throw e;
                }
                Duration wait = backoffBeforeRetry(attempt);
                log.warn("{} failed on attempt {}/{}, retrying in {} ms: {}",
                        operation, attempt, maxAttempts, wait.toMillis(), e.getMessage());
                pause(wait, e);
            }
        }
    }

    /**
     * Delay before retry number {@code failedAttempt}: the exponential step base * 2^(failedAttempt-1),
     * capped at maxDelay, of which the upper half is randomised ("equal jitter"). Jitter spreads out
     * clients that failed at the same moment so they don't all retry at the same moment too.
     */
    Duration backoffBeforeRetry(int failedAttempt) {
        long base = baseDelay.toMillis();
        long exponential = base << Math.min(failedAttempt - 1, 20);
        long capped = Math.min(exponential, maxDelay.toMillis());
        long half = capped / 2;
        long jitter = half == 0 ? 0 : random.nextLong(half + 1);
        return Duration.ofMillis(capped - half + jitter);
    }

    public int maxAttempts() {
        return maxAttempts;
    }

    private void pause(Duration wait, DownstreamUnavailableException cause) {
        try {
            sleeper.sleep(wait);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw cause;
        }
    }
}
