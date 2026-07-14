package cloudpage.controller;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import cloudpage.model.User;
import cloudpage.ratelimit.RateLimitFilter;
import cloudpage.scan.FileScanResult;
import cloudpage.scan.ScanJob;
import cloudpage.scan.ScanStatus;
import cloudpage.scan.VirusScanService;
import cloudpage.security.JwtAuthFilter;
import cloudpage.security.JwtUtil;
import cloudpage.service.UserService;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(VirusScanController.class)
@AutoConfigureMockMvc(addFilters = false)
class VirusScanControllerTest {

  @Autowired private MockMvc mockMvc;

  @MockitoBean private VirusScanService virusScanService;
  @MockitoBean private UserService userService;
  @MockitoBean private JwtAuthFilter jwtAuthFilter;
  @MockitoBean private JwtUtil jwtUtil;
  @MockitoBean private RateLimitFilter rateLimitFilter;

  private User testUser;

  @BeforeEach
  void setUp() {
    testUser = new User();
    testUser.setId("user-1");
    testUser.setUsername("testuser");
    testUser.setRootFolderPath("/root");
    when(userService.getCurrentUser()).thenReturn(testUser);
  }

  @Test
  void startScan_returns202WithJobId() throws Exception {
    ScanJob job = new ScanJob("job-1", "user-1", "", Instant.now());
    when(virusScanService.startScan(eq("/root"), eq("user-1"), any())).thenReturn(job);

    mockMvc
        .perform(post("/api/files/scan").param("path", ""))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.jobId").value("job-1"))
        .andExpect(jsonPath("$.status").value("PENDING"));
  }

  @Test
  void getScan_returnsStatusAndFindings() throws Exception {
    ScanJob job = new ScanJob("job-1", "user-1", "docs", Instant.now());
    job.recordResult(FileScanResult.infected("docs/evil.txt", "Test-Threat"));
    job.setStatus(ScanStatus.COMPLETED);
    when(virusScanService.getJob("job-1", "user-1")).thenReturn(job);

    mockMvc
        .perform(get("/api/files/scan/job-1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("COMPLETED"))
        .andExpect(jsonPath("$.infectedCount").value(1))
        .andExpect(jsonPath("$.findings[0].verdict").value("INFECTED"))
        .andExpect(jsonPath("$.findings[0].path").value("docs/evil.txt"))
        .andExpect(jsonPath("$.findings[0].detail").value("Test-Threat"));
  }
}
