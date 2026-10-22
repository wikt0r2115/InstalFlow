package pl.instalflow.identity.security;

import pl.instalflow.identity.domain.AppUser;
import pl.instalflow.identity.persistence.AppUserRepository;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/** Rechecks mutable account state on every request, including failed session deletion. */
@Component
class CurrentUserFilter extends OncePerRequestFilter {
    private final AppUserRepository users;

    CurrentUserFilter(AppUserRepository users) {
        this.users = users;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.isAuthenticated()
                && !(authentication instanceof AnonymousAuthenticationToken)) {
            boolean valid = false;
            try {
                UUID userId = UUID.fromString(authentication.getName());
                AppUser user = users.findById(userId).orElse(null);
                valid = user != null && user.isActive()
                    && user.getVersion().equals(authentication.getDetails())
                    && authentication.getAuthorities().size() == 1
                    && authentication.getAuthorities().stream().anyMatch(authority ->
                        authority.getAuthority().equals("ROLE_" + user.getRole().name()));
            } catch (IllegalArgumentException ignored) {
                // A session with an unexpected principal is never trusted.
            }
            if (!valid) {
                SecurityContextHolder.clearContext();
                if (request.getSession(false) != null) {
                    request.getSession(false).invalidate();
                }
            }
        }
        chain.doFilter(request, response);
    }
}
