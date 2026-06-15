package cloudpage.ratelimit;

import java.util.function.LongSupplier;

/**
 * A thread-safe token bucket.
 *
 * <p>The bucket starts full with {@code capacity} tokens and refills continuously so that it goes
 * from empty back to full over exactly one refill period. Time is read from an injectable
 * nanosecond clock, which keeps refill behaviour deterministic under test (no {@code
 * Thread.sleep}).
 */
final class TokenBucket {

  private final double capacity;
  private final double tokensPerNano;
  private final LongSupplier nanoClock;

  private double availableTokens;
  private long lastRefillNanos;

  TokenBucket(long capacity, long refillPeriodNanos, LongSupplier nanoClock) {
    this.capacity = capacity;
    this.tokensPerNano = (double) capacity / refillPeriodNanos;
    this.nanoClock = nanoClock;
    this.availableTokens = capacity;
    this.lastRefillNanos = nanoClock.getAsLong();
  }

  /** Attempts to consume a single token, refilling first based on elapsed time. */
  synchronized RateLimitDecision tryConsume() {
    refill();
    if (availableTokens >= 1.0d) {
      availableTokens -= 1.0d;
      return RateLimitDecision.allowed((long) availableTokens);
    }
    double missingTokens = 1.0d - availableTokens;
    long nanosToWait = (long) Math.ceil(missingTokens / tokensPerNano);
    long retryAfterSeconds = Math.max(1L, (long) Math.ceil(nanosToWait / 1_000_000_000.0d));
    return RateLimitDecision.denied(retryAfterSeconds);
  }

  /**
   * Whether the bucket has refilled back to full capacity — i.e. the client has been idle for at
   * least one refill period. Such a bucket carries no state worth keeping, so it is safe to evict.
   */
  synchronized boolean isReplenished() {
    refill();
    return availableTokens >= capacity;
  }

  private void refill() {
    long now = nanoClock.getAsLong();
    long elapsed = now - lastRefillNanos;
    if (elapsed <= 0L) {
      return;
    }
    availableTokens = Math.min(capacity, availableTokens + elapsed * tokensPerNano);
    lastRefillNanos = now;
  }
}
