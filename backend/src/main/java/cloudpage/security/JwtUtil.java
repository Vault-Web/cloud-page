package cloudpage.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import javax.crypto.SecretKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Utility class for creating and parsing JSON Web Tokens (JWT). A JWT is a compact, URL-safe token
 * format consisting of three parts: header, payload, and signature.
 *
 * <ul>
 *   <li><b>Header:</b> contains metadata about the token, such as the signing algorithm (e.g.,
 *       HS256) and token type.
 *   <li><b>Payload:</b> contains <b>claims</b> — pieces of information about the user or the token
 *       itself.
 *   <li><b>Signature:</b> cryptographic signature to ensure token integrity and authenticity.
 * </ul>
 *
 * <b>Claims</b> are key-value pairs embedded inside the JWT payload that provide data such as:
 *
 * <ul>
 *   <li><i>Registered claims</i> like <code>sub</code> (subject, often the username), <code>iat
 *       </code> (issued at), and <code>exp</code> (expiration time).
 *   <li><i>Public claims</i> which can be custom, e.g. user roles, email, etc.
 *   <li><i>Private claims</i> defined by your application for specific needs.
 * </ul>
 *
 * <p>In this class, the "role" claim is a custom public claim used to store the user's role for
 * authorization purposes.
 *
 * <p>The token is cryptographically signed using the secret key to ensure its integrity and
 * authenticity.
 */
@Component
public class JwtUtil {

  /** Secret key used for signing the JWT. Generated using HS256 (HMAC with SHA-256) algorithm. */
  private final SecretKey SECRET_KEY;

  public JwtUtil(@Value("${jwt.secret}") String secret) {
    this.SECRET_KEY = Keys.hmacShaKeyFor(secret.getBytes());
  }

  public String extractUsername(String token) {
    return extractClaim(token, Claims::getSubject);
  }

  public Long extractUserId(String token) {
    return extractClaim(token, claims -> claims.get("userId", Long.class));
  }

  public <T> T extractClaim(String token, java.util.function.Function<Claims, T> claimsResolver) {
    final Claims claims = Jwts.parser().setSigningKey(SECRET_KEY).parseClaimsJws(token).getBody();
    return claimsResolver.apply(claims);
  }

  public boolean validateToken(String token) {
    try {
      Jwts.parser().setSigningKey(SECRET_KEY).parseClaimsJws(token);
      return true;
    } catch (JwtException e) {
      return false;
    }
  }
}
