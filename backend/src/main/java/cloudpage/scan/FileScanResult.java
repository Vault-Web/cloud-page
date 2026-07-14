package cloudpage.scan;

/**
 * Result of scanning a single file. {@code path} is relative to the user's root so no host
 * filesystem path is exposed. {@code detail} carries the threat name for {@link
 * ScanVerdict#INFECTED} results, or a short error description for {@link ScanVerdict#ERROR}
 * results; it is {@code null} for clean files.
 */
public record FileScanResult(String path, ScanVerdict verdict, String detail) {

  public static FileScanResult infected(String path, String threat) {
    return new FileScanResult(path, ScanVerdict.INFECTED, threat);
  }

  public static FileScanResult error(String path, String detail) {
    return new FileScanResult(path, ScanVerdict.ERROR, detail);
  }
}
