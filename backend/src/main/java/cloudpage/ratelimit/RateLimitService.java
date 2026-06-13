package cloudpage.ratelimit;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
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
   * instance-wide budget is checked first; the per-client budget is only charged when the request
   * clears it.
   */
  public RateLimitDecision tryAcquire(RateLimitCategory category, String clientKey) {
    RateLimitProperties.Policy globalPolicy = properties.getGlobal().forCategory(category);
    if (globalPolicy.isEnabled()) {
      TokenBucket globalBucket =
          globalBuckets.computeIfAbsent(category, c -> newBucket(globalPolicy));
      RateLimitDecision globalDecision = globalBucket.tryConsume();
      if (!globalDecision.allowed()) {
        recordThrottle(category);
        return globalDecision;
      }
    }

    RateLimitProperties.Policy clientPolicy = properties.getPerClient().forCategory(category);
    if (!clientPolicy.isEnabled()) {
      return RateLimitDecision.allowed(Long.MAX_VALUE);
    }
    TokenBucket bucket =
        perClientBuckets.computeIfAbsent(
            category.name() + '|' + clientKey, key -> newBucket(clientPolicy));
    RateLimitDecision decision = bucket.tryConsume();
    if (!decision.allowed()) {
      recordThrottle(category);
    }
    return decision;
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
}
