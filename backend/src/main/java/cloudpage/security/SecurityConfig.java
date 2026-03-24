package cloudpage.security;

import static org.springframework.security.config.Customizer.withDefaults;

import lombok.AllArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
@AllArgsConstructor
public class SecurityConfig {

  private final JwtAuthFilter jwtAuthFilter;

  /**
   * Configures the security filter chain for HTTP requests. This method sets up the security
   * policies for the application, including:
   *
   * <p>- Disabling CSRF protection because the app is stateless and typically uses tokens (like
   * JWT). - Configuring the session management to be stateless, meaning the server does not keep
   * any session data between requests. - Defining authorization rules: * The specified endpoints
   * for authentication (/login, /register) and API documentation (Swagger UI and OpenAPI docs) are
   * publicly accessible without authentication. * All other requests require authentication.
   *
   * <p>This configuration ensures that only authorized users can access protected endpoints, while
   * allowing free access to login, registration, and API docs.
   *
   * @param http the HttpSecurity object used to configure web based security for specific http
   *     requests
   * @return the configured SecurityFilterChain instance
   * @throws Exception if an error occurs while building the security filter chain
   */
  @Bean
  public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
    http.cors(withDefaults())
        .csrf(csrf -> csrf.disable())
        .sessionManagement(sess -> sess.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(
            auth ->
                auth.requestMatchers(
                        "/api/auth/**",
                        "/v3/api-docs/**",
                        "/swagger-ui/**",
                        "/swagger-ui.html",
                        "/swagger-resources/**",
                        "/webjars/**",
                        "/docs/**",
                        "/ws-chat/**")
                    .permitAll()
                    .anyRequest()
                    .authenticated())
        .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);

    return http.build();
  }
}
