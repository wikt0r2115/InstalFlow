package pl.instalflow.identity.application.error;

public class LastOwnerException extends RuntimeException {
    public LastOwnerException() {
        super("The last active owner cannot be removed");
    }
}
