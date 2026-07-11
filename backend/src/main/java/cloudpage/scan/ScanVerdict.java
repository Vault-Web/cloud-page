package cloudpage.scan;

/** Outcome of scanning a single file. */
public enum ScanVerdict {
  /** The file was scanned and no threat was found. */
  CLEAN,
  /** The file was scanned and a threat was detected. */
  INFECTED,
  /** The file could not be scanned (scanner unavailable, I/O error, etc.). */
  ERROR
}
