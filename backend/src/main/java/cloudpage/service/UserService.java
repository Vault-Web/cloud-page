package cloudpage.service;

import cloudpage.exceptions.ResourceNotFoundException;
import cloudpage.exceptions.UnauthorizedAccessException;
import cloudpage.model.User;
import cloudpage.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

/** Service for resolving the currently authenticated user from the Spring Security context. */
@Service
@RequiredArgsConstructor
public class UserService {

  private final UserRepository userRepository;

  /**
   * Returns the user associated with the current security context.
   *
   * @return the authenticated {@link User}
   * @throws UnauthorizedAccessException if no authenticated user is present in the security context
   * @throws ResourceNotFoundException if the authenticated username has no matching user record
   */
  public User getCurrentUser() {
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    if (auth == null || !auth.isAuthenticated()) {
      throw new UnauthorizedAccessException("User is not authenticated");
    }

    String userName = auth.getName();
    return userRepository
        .findByUsername(userName)
        .orElseThrow(() -> new ResourceNotFoundException("User", "Username", userName));
  }
}
