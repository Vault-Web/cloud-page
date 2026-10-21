package cloudpage.util;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public final class ChecksumUtils {

  private ChecksumUtils() {}

  public static String calculateChecksum(InputStream inputStream, String algorithm)
      throws IOException {
    try {
      MessageDigest digest = MessageDigest.getInstance(algorithm);
      byte[] buffer = new byte[8192];
      int read;

      while ((read = inputStream.read(buffer)) != -1) {
        digest.update(buffer, 0, read);
      }

      return toHex(digest.digest());
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(algorithm + " algorithm not available", e);
    }
  }

  private static String toHex(byte[] bytes) {
    StringBuilder hexString = new StringBuilder();

    for (byte b : bytes) {
      hexString.append(String.format("%02x", b));
    }

    return hexString.toString();
  }
}
