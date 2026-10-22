package pl.instalflow.identity.security;

import pl.instalflow.identity.application.UserService;
import pl.instalflow.identity.application.dto.CreateUserRequest;
import pl.instalflow.identity.domain.AppUser;
import pl.instalflow.identity.domain.RoleEnum;
import pl.instalflow.identity.persistence.AppUserRepository;

import jakarta.validation.Validator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** One-time first account, configured only through the process environment. */
@Component
@Profile("!test")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
class FirstOwnerBootstrap implements ApplicationRunner {
    private final AppUserRepository users;
    private final PasswordEncoder passwords;
    private final Validator validator;
    private final String configuredEmail;
    private final String configuredPassword;

    FirstOwnerBootstrap(AppUserRepository users, PasswordEncoder passwords, Validator validator,
            @Value("${instalflow.bootstrap.owner-email:${INSTALFLOW_BOOTSTRAP_OWNER_EMAIL:}}") String configuredEmail,
            @Value("${instalflow.bootstrap.owner-password:${INSTALFLOW_BOOTSTRAP_OWNER_PASSWORD:}}") String configuredPassword) {
        this.users = users;
        this.passwords = passwords;
        this.validator = validator;
        this.configuredEmail = configuredEmail;
        this.configuredPassword = configuredPassword;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        boolean emailPresent = !configuredEmail.isBlank();
        boolean passwordPresent = !configuredPassword.isBlank();
        if (emailPresent != passwordPresent) {
            throw new IllegalStateException("Set both bootstrap OWNER email and password, or neither");
        }
        if (users.count() > 0) {
            if (users.countByRoleAndActiveTrue(RoleEnum.OWNER) == 0) {
                throw new IllegalStateException("No active OWNER account; restore access through an audited procedure");
            }
            return;
        }
        if (!emailPresent) {
            throw new IllegalStateException("First startup requires bootstrap OWNER email and password");
        }
        String email;
        try {
            email = UserService.normalizeEmail(configuredEmail);
        } catch (IllegalArgumentException invalid) {
            throw new IllegalStateException("Invalid bootstrap OWNER credentials", invalid);
        }
        if (!validator.validate(new CreateUserRequest(email, configuredPassword, RoleEnum.OWNER)).isEmpty()) {
            throw new IllegalStateException("Invalid bootstrap OWNER credentials");
        }
        users.saveAndFlush(AppUser.create(email, passwords.encode(configuredPassword), RoleEnum.OWNER));
    }
}
