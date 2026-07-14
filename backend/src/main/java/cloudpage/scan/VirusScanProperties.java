package cloudpage.scan;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the folder virus-scan feature. Scanning is disabled by default so that a fresh
 * deployment without a ClamAV daemon does not attempt (and time out on) network connections; set
 * {@code cloudpage.virus-scan.enabled=true} and point {@code host}/{@code port} at a reachable
 * {@code clamd} instance to enable it.
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "cloudpage.virus-scan")
public class VirusScanProperties {

  /**
   * Whether virus scanning is enabled. When false, scan requests report the scanner as disabled.
   */
  private boolean enabled = false;

  /** Hostname of the clamd daemon. */
  private String host = "localhost";

  /** TCP port of the clamd daemon (clamd default is 3310). */
  private int port = 3310;

  /** Socket connect/read timeout in milliseconds. */
  private int timeoutMs = 30000;

  /** Maximum number of folder scans that may run concurrently across the instance. */
  private int maxConcurrentScans = 2;

  /**
   * How long a finished (completed or failed) scan job is retained in memory before it is evicted
   * by the periodic cleanup.
   */
  private int retentionMinutes = 60;
}
