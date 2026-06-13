package cloudpage.ratelimit;

/**
 * Outcome of a single rate-limit check.
 *
 * @param allowed whether the request may proceed
 * @param remainingTokens tokens left in the consulted bucket, surfaced through the {@code
 *     X-Rate-Limit-Remaining} header
 * @param retryAfterSeconds when {@code allowed} is {@code false}, the suggested back-off in seconds
 *     for the {@code Retry-After} header
 */
public record RateLimitDecision(boolean allowed, long remainingTokens, long retryAfterSeconds) {

  static RateLimitDecision allowed(long remainingTokens) {
    return new RateLimitDecision(true, remainingTokens, 0L);
  }

  static RateLimitDecision denied(long retryAfterSeconds) {
    return new RateLimitDecision(false, 0L, retryAfterSeconds);
  }
}
