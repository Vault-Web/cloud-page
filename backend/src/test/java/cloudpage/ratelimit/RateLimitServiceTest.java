package cloudpage.ratelimit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import org.junit.jupiter.api.Test;

class RateLimitServiceTest {

  private final AtomicLong clock = new AtomicLong(0L);
  private final LongSupplier nanoClock = clock::get;

  private RateLimitService serviceWith(RateLimitProperties properties) {
    return new RateLimitService(properties, Optional.empty(), nanoClock);
  }

  private RateLimitProperties perClientUpload(int capacity, Duration refillPeriod) {
    RateLimitProperties properties = new RateLimitProperties();
    properties.getPerClient().setUpload(new RateLimitProperties.Policy(capacity, refillPeriod));
    return properties;
  }

  @Test
  void allowsUpToCapacityThenThrottles() {
    RateLimitService service = serviceWith(perClientUpload(3, Duration.ofMinutes(1)));

    assertTrue(service.tryAcquire(RateLimitCategory.UPLOAD, "user:alice").allowed());
    assertTrue(service.tryAcquire(RateLimitCategory.UPLOAD, "user:alice").allowed());
    assertTrue(service.tryAcquire(RateLimitCategory.UPLOAD, "user:alice").allowed());

    RateLimitDecision denied = service.tryAcquire(RateLimitCategory.UPLOAD, "user:alice");
    assertFalse(denied.allowed());
    assertTrue(denied.retryAfterSeconds() >= 1L);
  }

  @Test
  void refillsAfterRefillPeriod() {
    RateLimitService service = serviceWith(perClientUpload(2, Duration.ofMinutes(1)));
    assertTrue(service.tryAcquire(RateLimitCategory.UPLOAD, "user:alice").allowed());
    assertTrue(service.tryAcquire(RateLimitCategory.UPLOAD, "user:alice").allowed());
    assertFalse(service.tryAcquire(RateLimitCategory.UPLOAD, "user:alice").allowed());

    // advance well past one full refill period; the bucket clamps back to capacity
    clock.addAndGet(Duration.ofMinutes(2).toNanos());

    assertTrue(service.tryAcquire(RateLimitCategory.UPLOAD, "user:alice").allowed());
    assertTrue(service.tryAcquire(RateLimitCategory.UPLOAD, "user:alice").allowed());
    assertFalse(service.tryAcquire(RateLimitCategory.UPLOAD, "user:alice").allowed());
  }

  @Test
  void perClientBudgetsAreIsolated() {
    RateLimitService service = serviceWith(perClientUpload(1, Duration.ofMinutes(1)));
    assertTrue(service.tryAcquire(RateLimitCategory.UPLOAD, "user:alice").allowed());
    assertFalse(service.tryAcquire(RateLimitCategory.UPLOAD, "user:alice").allowed());

    // a different client has its own bucket
    assertTrue(service.tryAcquire(RateLimitCategory.UPLOAD, "user:bob").allowed());
  }

  @Test
  void categoriesAreIsolated() {
    RateLimitProperties properties = new RateLimitProperties();
    properties.getPerClient().setUpload(new RateLimitProperties.Policy(1, Duration.ofMinutes(1)));
    properties.getPerClient().setDownload(new RateLimitProperties.Policy(1, Duration.ofMinutes(1)));
    RateLimitService service = serviceWith(properties);

    assertTrue(service.tryAcquire(RateLimitCategory.UPLOAD, "user:alice").allowed());
    assertFalse(service.tryAcquire(RateLimitCategory.UPLOAD, "user:alice").allowed());

    // download has a separate budget
    assertTrue(service.tryAcquire(RateLimitCategory.DOWNLOAD, "user:alice").allowed());
  }

  @Test
  void nonPositiveCapacityDisablesLimiting() {
    RateLimitProperties properties = new RateLimitProperties();
    properties.getPerClient().setUpload(new RateLimitProperties.Policy(0, Duration.ofMinutes(1)));
    RateLimitService service = serviceWith(properties);

    for (int i = 0; i < 1000; i++) {
      assertTrue(service.tryAcquire(RateLimitCategory.UPLOAD, "user:alice").allowed());
    }
  }

  @Test
  void globalTierThrottlesAcrossClients() {
    RateLimitProperties properties = new RateLimitProperties();
    // generous per-client budget, tight global budget: the global cap binds first
    properties.getPerClient().setUpload(new RateLimitProperties.Policy(100, Duration.ofMinutes(1)));
    properties.getGlobal().setUpload(new RateLimitProperties.Policy(2, Duration.ofMinutes(1)));
    RateLimitService service = serviceWith(properties);

    assertTrue(service.tryAcquire(RateLimitCategory.UPLOAD, "user:alice").allowed());
    assertTrue(service.tryAcquire(RateLimitCategory.UPLOAD, "user:bob").allowed());
    // the instance-wide budget of 2 is now exhausted regardless of which client asks
    assertFalse(service.tryAcquire(RateLimitCategory.UPLOAD, "user:carol").allowed());
  }

  @Test
  void perClientDenialDoesNotDrainGlobalBudget() {
    RateLimitProperties properties = new RateLimitProperties();
    // alice can make a single per-client request; the global budget has room for three.
    properties.getPerClient().setUpload(new RateLimitProperties.Policy(1, Duration.ofMinutes(1)));
    properties.getGlobal().setUpload(new RateLimitProperties.Policy(3, Duration.ofMinutes(1)));
    RateLimitService service = serviceWith(properties);

    // alice's first request is admitted (and charges one global token)
    assertTrue(service.tryAcquire(RateLimitCategory.UPLOAD, "user:alice").allowed());
    // alice is now per-client throttled; these denials must not consume global tokens
    assertFalse(service.tryAcquire(RateLimitCategory.UPLOAD, "user:alice").allowed());
    assertFalse(service.tryAcquire(RateLimitCategory.UPLOAD, "user:alice").allowed());

    // the global budget still has two tokens left for other clients
    assertTrue(service.tryAcquire(RateLimitCategory.UPLOAD, "user:bob").allowed());
    assertTrue(service.tryAcquire(RateLimitCategory.UPLOAD, "user:carol").allowed());
    // now the instance-wide budget of three is exhausted
    assertFalse(service.tryAcquire(RateLimitCategory.UPLOAD, "user:dave").allowed());
  }

  @Test
  void evictionDropsReplenishedButKeepsActiveBuckets() {
    RateLimitService service = serviceWith(perClientUpload(2, Duration.ofMinutes(1)));

    // alice consumes one token, so her bucket is no longer at full capacity
    service.tryAcquire(RateLimitCategory.UPLOAD, "user:alice");
    service.evictReplenishedBuckets();
    assertEquals(1, service.trackedClientBucketCount());

    // after a full refill period the bucket is replenished and can be safely dropped
    clock.addAndGet(Duration.ofMinutes(2).toNanos());
    service.evictReplenishedBuckets();
    assertEquals(0, service.trackedClientBucketCount());
  }
}
