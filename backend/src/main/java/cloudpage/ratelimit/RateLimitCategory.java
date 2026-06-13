package cloudpage.ratelimit;

/** Classes of file operations that each carry an independent rate-limit budget. */
public enum RateLimitCategory {
  UPLOAD,
  DOWNLOAD,
  LISTING
}
