package cloudpage.scan;

import java.util.concurrent.Executor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/** Wires the virus-scan configuration properties and the background executor that runs scans. */
@Configuration
@EnableConfigurationProperties(VirusScanProperties.class)
public class VirusScanConfig {

  @Bean(name = "virusScanExecutor")
  public Executor virusScanExecutor(VirusScanProperties properties) {
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(1);
    executor.setMaxPoolSize(Math.max(1, properties.getMaxConcurrentScans()));
    executor.setQueueCapacity(50);
    executor.setThreadNamePrefix("virus-scan-");
    executor.initialize();
    return executor;
  }
}
