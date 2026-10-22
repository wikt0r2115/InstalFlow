package pl.instalflow.identity.application;

public interface IdentityAccess {
    void requireOwner();
    void requireOwnerOrOffice();
}
