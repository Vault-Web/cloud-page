package cloudpage.security;

import java.util.List;

/**
 * Every path pattern reachable without authentication, shared between JwtAuthFilter (which must
 * skip these before Spring Security's own authorization rules even run) and SecurityConfig's
 * permitAll matchers. A single list means the two can no longer drift apart the way they did in
 * #109, and again for the /s/ short-link route.
 *
 * <p>Entries here are prefixes (matched via String#startsWith by JwtAuthFilter, and suffixed with
 * "**" for SecurityConfig's Ant-style matchers). /error is included for SecurityConfig's benefit
 * only — JwtAuthFilter, being a OncePerRequestFilter, never re-runs on Spring's internal forward to
 * /error, so its presence here is inert but harmless for the filter, and keeps this list the single
 * complete source of truth.
 */
public final class PublicPaths {

  public static final List<String> PREFIXES =
      List.of(
          "/api/auth/",
          "/api/public/",
          "/s/",
          "/v3/api-docs",
          "/swagger-ui",
          "/swagger-ui.html",
          "/swagger-resources/",
          "/webjars/",
          "/docs/",
          "/ws-chat/",
          "/error");

  private PublicPaths() {}
}
