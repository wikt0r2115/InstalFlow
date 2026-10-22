package pl.instalflow.identity.application.error;

public class InvalidUserStateException extends RuntimeException {
    public InvalidUserStateException(String message) {
        super(message);
    }
}
