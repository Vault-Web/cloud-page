package cloudpage.scan;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * {@link VirusScanner} backed by a ClamAV {@code clamd} daemon using the {@code INSTREAM} command.
 * The file content is streamed to the daemon in chunks and never written anywhere else, so nothing
 * is sent to a third-party service. When scanning is disabled or the daemon is unreachable the scan
 * returns an {@link ScanVerdict#ERROR} result rather than throwing, so a folder scan can report
 * per-file scanner problems instead of failing outright.
 */
@Component
public class ClamAvVirusScanner implements VirusScanner {

  private static final Logger log = LoggerFactory.getLogger(ClamAvVirusScanner.class);
  private static final int CHUNK_SIZE = 2048;

  private final VirusScanProperties properties;

  public ClamAvVirusScanner(VirusScanProperties properties) {
    this.properties = properties;
  }

  @Override
  public ScanResult scan(Path file) throws IOException {
    if (!properties.isEnabled()) {
      return ScanResult.error("virus scanning is disabled");
    }

    try (Socket socket = new Socket()) {
      socket.connect(
          new InetSocketAddress(properties.getHost(), properties.getPort()),
          properties.getTimeoutMs());
      socket.setSoTimeout(properties.getTimeoutMs());

      try (OutputStream rawOut = socket.getOutputStream();
          OutputStream out = new BufferedOutputStream(rawOut);
          InputStream fileIn = Files.newInputStream(file)) {

        out.write("zINSTREAM\0".getBytes(StandardCharsets.US_ASCII));

        byte[] buffer = new byte[CHUNK_SIZE];
        int read;
        while ((read = fileIn.read(buffer)) != -1) {
          if (read == 0) {
            continue;
          }
          out.write(ByteBuffer.allocate(4).putInt(read).array());
          out.write(buffer, 0, read);
        }
        // A zero-length chunk signals the end of the stream.
        out.write(new byte[] {0, 0, 0, 0});
        out.flush();

        String response = readResponse(socket.getInputStream());
        return parseResponse(response);
      }
    } catch (IOException e) {
      // Connection refused, timeout, etc. Report as a scanner error for this file rather than
      // failing the whole folder scan.
      log.warn("ClamAV scan failed for a file: {}", e.getMessage());
      return ScanResult.error("scanner unavailable");
    }
  }

  private static String readResponse(InputStream in) throws IOException {
    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    int b;
    while ((b = in.read()) != -1) {
      if (b == 0) {
        break; // clamd terminates its reply with a NUL byte.
      }
      buffer.write(b);
    }
    return buffer.toString(StandardCharsets.US_ASCII).trim();
  }

  /**
   * Parses a clamd {@code INSTREAM} reply. Examples: {@code "stream: OK"}, {@code "stream:
   * Eicar-Test-Signature FOUND"}, or an error string.
   */
  static ScanResult parseResponse(String response) {
    if (response == null || response.isBlank()) {
      return ScanResult.error("empty scanner response");
    }
    if (response.endsWith("OK")) {
      return ScanResult.clean();
    }
    if (response.endsWith("FOUND")) {
      // Format: "stream: <threat name> FOUND"
      int colon = response.indexOf(':');
      String middle = colon >= 0 ? response.substring(colon + 1) : response;
      String threat = middle.substring(0, middle.length() - "FOUND".length()).trim();
      return ScanResult.infected(threat.isEmpty() ? "unknown" : threat);
    }
    return ScanResult.error(response);
  }
}
