package cloudpage.scan;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Abstraction over a virus-scanning engine. The default implementation talks to ClamAV, but the
 * interface keeps the engine swappable so other backends can be added without touching the scan
 * service or controller.
 */
public interface VirusScanner {

  /**
   * Scans a single file.
   *
   * @param file the file to scan
   * @return the verdict, and a threat name when infected
   * @throws IOException if the file cannot be read
   */
  ScanResult scan(Path file) throws IOException;

  /** Verdict for a single file, with an optional threat name when infected. */
  record ScanResult(ScanVerdict verdict, String threat) {

    public static ScanResult clean() {
      return new ScanResult(ScanVerdict.CLEAN, null);
    }

    public static ScanResult infected(String threat) {
      return new ScanResult(ScanVerdict.INFECTED, threat);
    }

    public static ScanResult error(String detail) {
      return new ScanResult(ScanVerdict.ERROR, detail);
    }
  }
}
