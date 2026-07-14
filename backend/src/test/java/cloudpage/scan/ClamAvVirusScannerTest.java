package cloudpage.scan;

import static org.junit.jupiter.api.Assertions.*;

import cloudpage.scan.VirusScanner.ScanResult;
import org.junit.jupiter.api.Test;

class ClamAvVirusScannerTest {

  @Test
  void parseResponse_ok_isClean() {
    ScanResult result = ClamAvVirusScanner.parseResponse("stream: OK");
    assertEquals(ScanVerdict.CLEAN, result.verdict());
    assertNull(result.threat());
  }

  @Test
  void parseResponse_found_extractsThreatName() {
    ScanResult result = ClamAvVirusScanner.parseResponse("stream: Eicar-Test-Signature FOUND");
    assertEquals(ScanVerdict.INFECTED, result.verdict());
    assertEquals("Eicar-Test-Signature", result.threat());
  }

  @Test
  void parseResponse_emptyOrBlank_isError() {
    assertEquals(ScanVerdict.ERROR, ClamAvVirusScanner.parseResponse("").verdict());
    assertEquals(ScanVerdict.ERROR, ClamAvVirusScanner.parseResponse(null).verdict());
  }

  @Test
  void parseResponse_unexpectedText_isError() {
    ScanResult result = ClamAvVirusScanner.parseResponse("ERROR: something went wrong");
    assertEquals(ScanVerdict.ERROR, result.verdict());
  }
}
