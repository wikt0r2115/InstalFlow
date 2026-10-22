package pl.instalflow.identity.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "app_user")
public class AppUser {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_number", nullable = false,
            insertable = false, updatable = false)
    @Generated(event = EventType.INSERT)
    private Long userNumber;

    @Column(name = "email_normalized", nullable = false, length = 254)
    private String emailNormalized;

    @Column(name = "password_hash", nullable = false, length = 255)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 16)
    private RoleEnum role;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @Column(name = "created_at", nullable = false,
            insertable = false, updatable = false)
    @Generated(event = EventType.INSERT)
    private Instant createdAt;

    protected AppUser() {}

    private AppUser(String emailNormalized, String passwordHash, RoleEnum role) {
        this.emailNormalized = emailNormalized;
        this.passwordHash = passwordHash;
        this.role = role;
    }

    public static AppUser create(String emailNormalized, String passwordHash, RoleEnum role) {
        return new AppUser(emailNormalized, passwordHash, role);
    }

    public UUID getId() { return id; }
    public Long getUserNumber() { return userNumber; }
    public String getEmailNormalized() { return emailNormalized; }
    public String getPasswordHash() { return passwordHash; }
    public RoleEnum getRole() { return role; }
    public boolean isActive() { return active; }
    public Long getVersion() { return version; }
    public Instant getCreatedAt() { return createdAt; }

    public void changeRole(RoleEnum newRole) { this.role = newRole; }
    public void changePassword(String newPasswordHash) { this.passwordHash = newPasswordHash; }
    public void deactivate() { this.active = false; }
}
