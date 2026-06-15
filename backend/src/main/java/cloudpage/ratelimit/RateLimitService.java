package cloudpage.ratelimit;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Resolves and evaluates the token buckets that back {@link RateLimitFilter}.
 *
 * <p>Buckets are created lazily and cached: one per category for the global tier, and one per
 * (category, client) pair for the per-client tier.
 */
@Service
public class RateLimitService {

  private final RateLimitProperties properties;
  private final Optional<MeterRegistry> meterRegistry;
  private final LongSupplier nanoClock;

  private final ConcurrentHashMap<String, TokenBucket> perClientBuckets = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<RateLimitCategory, TokenBucket> globalBuckets =
      new ConcurrentHashMap<>();

  public RateLimitService(RateLimitProperties properties, Optional<MeterRegistry> meterRegistry) {
    this(properties, meterRegistry, System::nanoTime);
  }

  RateLimitService(
      RateLimitProperties properties,
      Optional<MeterRegistry> meterRegistry,
      LongSupplier nanoClock) {
    this.properties = properties;
    this.meterRegistry = meterRegistry;
    this.nanoClock = nanoClock;
  }

  /**
   * Attempts to admit one request in {@code category} for the given {@code clientKey}. The
   * per-client budget is evaluated first, so a client that is already over its own limit cannot
   * drain the shared global budget on behalf of everyone else; the global budget is only charged
   * once the per-client tier admits the request.
   */
  public RateLimitDecision tryAcquire(RateLimitCategory category, String clientKey) {
    RateLimitProperties.Policy clientPolicy = properties.getPerClient().forCategory(category);
    if (clientPolicy.isEnabled()) {
      TokenBucket bucket =
          perClientBuckets.computeIfAbsent(
              category.name() + '|' + clientKey, key -> newBucket(clientPolicy));
      RateLimitDecision clientDecision = bucket.tryConsume();
      if (!clientDecision.allowed()) {
        recordThrottle(category);
        return clientDecision;
      }
      RateLimitDecision globalDecision = tryConsumeGlobal(category);
      if (!globalDecision.allowed()) {
        recordThrottle(category);
        return globalDecision;
      }
      return clientDecision;
    }

    // Per-client limiting disabled for this category: only the global tier (if any) applies.
    RateLimitDecision globalDecision = tryConsumeGlobal(category);
    if (!globalDecision.allowed()) {
      recordThrottle(category);
    }
    return globalDecision;
  }

  private RateLimitDecision tryConsumeGlobal(RateLimitCategory category) {
    RateLimitProperties.Policy globalPolicy = properties.getGlobal().forCategory(category);
    if (!globalPolicy.isEnabled()) {
      return RateLimitDecision.unlimited();
    }
    TokenBucket globalBucket =
        globalBuckets.computeIfAbsent(category, c -> newBucket(globalPolicy));
    return globalBucket.tryConsume();
  }

  private TokenBucket newBucket(RateLimitProperties.Policy policy) {
    long refillNanos = Math.max(1L, policy.getRefillPeriod().toNanos());
    return new TokenBucket(policy.getCapacity(), refillNanos, nanoClock);
  }

  private void recordThrottle(RateLimitCategory category) {
    meterRegistry.ifPresent(
        registry ->
            registry
                .counter("cloudpage.ratelimit.throttled", "category", category.name())
                .increment());
  }

  /**
   * Periodically drops per-client buckets that have refilled back to full. An idle client's bucket
   * carries no state worth keeping — re-creating it on the next request yields an identical full
   * bucket — so evicting it bounds the cache to currently-active clients instead of letting it grow
   * for every distinct client (or IP) ever seen.
   */
  @Scheduled(fixedDelayString = "${cloudpage.rate-limit.cleanup-interval-ms:300000}")
  void evictReplenishedBuckets() {
    perClientBuckets.values().removeIf(TokenBucket::isReplenished);
  }

  int trackedClientBucketCount() {
    return perClientBuckets.size();
  }
}
