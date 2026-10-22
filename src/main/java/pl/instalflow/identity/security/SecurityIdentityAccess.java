package pl.instalflow.identity.security;

import pl.instalflow.identity.application.IdentityAccess;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

@Component
class SecurityIdentityAccess implements IdentityAccess {
    @Override
    public void requireOwner() {
        require("ROLE_OWNER");
    }

    @Override
    public void requireOwnerOrOffice() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth.getAuthorities().stream().noneMatch(
                role -> role.getAuthority().equals("ROLE_OWNER") || role.getAuthority().equals("ROLE_OFFICE"))) {
            throw new AccessDeniedException("Brak uprawnień");
        }
    }

    private void require(String authority) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth.getAuthorities().stream().noneMatch(
                role -> role.getAuthority().equals(authority))) {
            throw new AccessDeniedException("Brak uprawnień");
        }
    }
}
