package cloudpage.scan;

import static org.junit.jupiter.api.Assertions.*;

import cloudpage.scan.VirusScanner.ScanResult;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Exercises the real {@link ClamAvVirusScanner} socket/INSTREAM client against an in-process fake
 * {@code clamd} that speaks the protocol. This validates connect, chunk framing, and response
 * parsing end-to-end without needing a real ClamAV daemon (the actual signature matching is
 * ClamAV's job, not this client's).
 */
class ClamAvVirusScannerIntegrationTest {

  @TempDir Path tempDir;

  private ExecutorService serverPool;
  private ServerSocket serverSocket;

  @AfterEach
  void tearDown() throws IOException {
    if (serverSocket != null && !serverSocket.isClosed()) {
      serverSocket.close();
    }
    if (serverPool != null) {
      serverPool.shutdownNow();
    }
  }

  /** Starts a fake clamd that consumes an INSTREAM upload and replies with the given response. */
  private int startFakeClamd(String cannedResponse) throws IOException {
    serverSocket = new ServerSocket(0);
    serverPool = Executors.newSingleThreadExecutor();
    serverPool.submit(
        () -> {
          try (Socket client = serverSocket.accept()) {
            InputStream in = client.getInputStream();
            // Consume the command line, terminated by a NUL byte (e.g. "zINSTREAM\0").
            int b;
            while ((b = in.read()) != -1 && b != 0) {
              // discard command bytes
            }
            // Consume length-prefixed chunks until the zero-length terminator.
            DataInputStream din = new DataInputStream(in);
            int len;
            while ((len = din.readInt()) != 0) {
              din.readFully(new byte[len]);
            }
            OutputStream out = client.getOutputStream();
            out.write(cannedResponse.getBytes(StandardCharsets.US_ASCII));
            out.write(0);
            out.flush();
          } catch (IOException ignored) {
            // server closed during teardown
          }
        });
    return serverSocket.getLocalPort();
  }

  private ClamAvVirusScanner scannerFor(int port) {
    VirusScanProperties props = new VirusScanProperties();
    props.setEnabled(true);
    props.setHost("localhost");
    props.setPort(port);
    props.setTimeoutMs(5000);
    return new ClamAvVirusScanner(props);
  }

  @Test
  void scan_cleanResponse_returnsClean() throws IOException {
    int port = startFakeClamd("stream: OK");
    Path file = Files.writeString(tempDir.resolve("clean.txt"), "harmless content");

    ScanResult result = scannerFor(port).scan(file);

    assertEquals(ScanVerdict.CLEAN, result.verdict());
  }

  @Test
  void scan_foundResponse_returnsInfectedWithThreat() throws IOException {
    int port = startFakeClamd("stream: Win.Test.EICAR_HDB-1 FOUND");
    Path file =
        Files.writeString(tempDir.resolve("eicar.txt"), "X5O!P%@AP[4\\PZX54(P^)7CC)7}$EICAR");

    ScanResult result = scannerFor(port).scan(file);

    assertEquals(ScanVerdict.INFECTED, result.verdict());
    assertEquals("Win.Test.EICAR_HDB-1", result.threat());
  }

  @Test
  void scan_disabled_returnsErrorWithoutConnecting() throws IOException {
    VirusScanProperties props = new VirusScanProperties();
    props.setEnabled(false);
    props.setPort(1); // would fail if a connection were attempted
    Path file = Files.writeString(tempDir.resolve("x.txt"), "data");

    ScanResult result = new ClamAvVirusScanner(props).scan(file);

    assertEquals(ScanVerdict.ERROR, result.verdict());
  }

  @Test
  void scan_daemonUnreachable_returnsErrorNotThrow() throws IOException {
    VirusScanProperties props = new VirusScanProperties();
    props.setEnabled(true);
    props.setHost("localhost");
    props.setPort(1); // nothing listening
    props.setTimeoutMs(500);
    Path file = Files.writeString(tempDir.resolve("y.txt"), "data");

    ScanResult result = new ClamAvVirusScanner(props).scan(file);

    assertEquals(ScanVerdict.ERROR, result.verdict());
  }
}
