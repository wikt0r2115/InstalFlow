package pl.instalflow.identity.application.error;

public class EmailAlreadyUsedException extends RuntimeException {
    public EmailAlreadyUsedException() {
        super("Email is already used");
    }
}
