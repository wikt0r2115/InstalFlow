package pl.instalflow.jobs.api;

/** Contact fields may be null when the customer did not provide them. */
public record CustomerSnapshot(String name, String contactEmail, String phone) {

    public CustomerSnapshot {
        ScopeLine.requireText(name, "name");
    }
}
