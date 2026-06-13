package cloudpage.ratelimit;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for {@link RateLimitFilter}, bound from {@code cloudpage.rate-limit.*} (and
 * therefore overridable via environment variables, e.g. {@code
 * CLOUDPAGE_RATE_LIMIT_PER_CLIENT_UPLOAD_CAPACITY}).
 *
 * <p>Two independent tiers are supported:
 *
 * <ul>
 *   <li>{@code per-client} – a separate budget for every caller, keyed by authenticated username
 *       when available and falling back to the client IP otherwise (per-user / per-IP policies);
 *   <li>{@code global} – a single instance-wide budget per category, disabled by default (capacity
 *       {@code 0}) so it never throttles legitimate multi-user traffic unless an operator opts in.
 * </ul>
 *
 * <p>A non-positive {@code capacity} disables limiting for that category.
 */
@ConfigurationProperties(prefix = "cloudpage.rate-limit")
public class RateLimitProperties {

  /** Master switch; when {@code false} the filter passes every request straight through. */
  private boolean enabled = true;

  private Tier perClient = Tier.defaults();

  private Tier global = Tier.disabled();

  public boolean isEnabled() {
    return enabled;
  }

  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
  }

  public Tier getPerClient() {
    return perClient;
  }

  public void setPerClient(Tier perClient) {
    this.perClient = perClient;
  }

  public Tier getGlobal() {
    return global;
  }

  public void setGlobal(Tier global) {
    this.global = global;
  }

  /** A set of per-category {@link Policy policies}. */
  public static class Tier {

    private Policy upload = new Policy();
    private Policy download = new Policy();
    private Policy listing = new Policy();

    static Tier defaults() {
      Tier tier = new Tier();
      tier.upload = new Policy(30, Duration.ofMinutes(1));
      tier.download = new Policy(120, Duration.ofMinutes(1));
      tier.listing = new Policy(240, Duration.ofMinutes(1));
      return tier;
    }

    static Tier disabled() {
      return new Tier();
    }

    public Policy forCategory(RateLimitCategory category) {
      return switch (category) {
        case UPLOAD -> upload;
        case DOWNLOAD -> download;
        case LISTING -> listing;
      };
    }

    public Policy getUpload() {
      return upload;
    }

    public void setUpload(Policy upload) {
      this.upload = upload;
    }

    public Policy getDownload() {
      return download;
    }

    public void setDownload(Policy download) {
      this.download = download;
    }

    public Policy getListing() {
      return listing;
    }

    public void setListing(Policy listing) {
      this.listing = listing;
    }
  }

  /** A token-bucket policy: {@code capacity} tokens, fully refilled every {@code refillPeriod}. */
  public static class Policy {

    private int capacity;
    private Duration refillPeriod = Duration.ofMinutes(1);

    public Policy() {}

    public Policy(int capacity, Duration refillPeriod) {
      this.capacity = capacity;
      this.refillPeriod = refillPeriod;
    }

    /** A non-positive capacity (or refill period) disables limiting for this category. */
    public boolean isEnabled() {
      return capacity > 0
          && refillPeriod != null
          && !refillPeriod.isZero()
          && !refillPeriod.isNegative();
    }

    public int getCapacity() {
      return capacity;
    }

    public void setCapacity(int capacity) {
      this.capacity = capacity;
    }

    public Duration getRefillPeriod() {
      return refillPeriod;
    }

    public void setRefillPeriod(Duration refillPeriod) {
      this.refillPeriod = refillPeriod;
    }
  }
}
