package pl.instalflow.identity.application;

import java.util.UUID;

public interface UserSessionInvalidator {
    void invalidate(UUID userId);
}
