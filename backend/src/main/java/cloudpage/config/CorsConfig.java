package cloudpage.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

/**
 * Global CORS configuration for the application.
 *
 * <p>This configuration allows the frontend application running on a different origin (e.g.,
 * http://localhost:4200) to make HTTP requests to the backend without being blocked by the
 * browser's same-origin policy.
 *
 * <p>It allows all HTTP methods, headers, and credentials to be sent.
 */
@Configuration
public class CorsConfig {

  /**
   * Defines a CORS filter that applies the CORS configuration to all endpoints.
   *
   * @return CorsFilter instance that intercepts requests and adds necessary CORS headers for
   *     allowed origins, methods, headers, and credentials.
   */
  @Bean
  public CorsFilter corsFilter() {
    CorsConfiguration config = new CorsConfiguration();
    config.addAllowedOrigin("http://localhost:4200");
    config.addAllowedHeader("*");
    config.addAllowedMethod("*");
    config.setAllowCredentials(true);

    UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
    source.registerCorsConfiguration("/**", config);

    return new CorsFilter(source);
  }
}
