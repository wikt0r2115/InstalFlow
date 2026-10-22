package pl.instalflow.jobs.api;

public record AddressSnapshot(String street, String postalCode, String city) {

    public AddressSnapshot {
        ScopeLine.requireText(street, "street");
        ScopeLine.requireText(postalCode, "postalCode");
        ScopeLine.requireText(city, "city");
    }
}
