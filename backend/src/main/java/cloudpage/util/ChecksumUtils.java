package cloudpage.util;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public final class ChecksumUtils {

  private ChecksumUtils() {}

  public static String calculateChecksum(InputStream inputStream, String algorithm)
      throws IOException {
    MessageDigest digest = newDigest(algorithm);
    byte[] buffer = new byte[8192];
    int read;

    while ((read = inputStream.read(buffer)) != -1) {
      digest.update(buffer, 0, read);
    }

    return HexFormat.of().formatHex(digest.digest());
  }

  public static MessageDigest newDigest(String algorithm) {
    try {
      return MessageDigest.getInstance(algorithm);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(algorithm + " algorithm not available", e);
    }
  }
}
