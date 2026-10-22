package pl.instalflow.identity.application.error;

public class VersionConflictException extends RuntimeException {
    public VersionConflictException() {
        super("User has changed; reload it and retry");
    }
}
