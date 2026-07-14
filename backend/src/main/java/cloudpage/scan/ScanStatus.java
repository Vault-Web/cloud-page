package cloudpage.scan;

/** Lifecycle state of an asynchronous folder scan job. */
public enum ScanStatus {
  PENDING,
  RUNNING,
  COMPLETED,
  FAILED
}
