package cloudpage.service;

import cloudpage.exceptions.ResourceNotFoundException;
import cloudpage.exceptions.UnauthorizedAccessException;
import cloudpage.model.User;
import cloudpage.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;

    public User getCurrentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            throw new UnauthorizedAccessException("User is not authenticated");
        }

        String userName = auth.getName();
        return userRepository.findByUsername(userName)
                .orElseThrow(() -> new ResourceNotFoundException("User", "Username", userName));
    }
}