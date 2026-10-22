package pl.instalflow.identity.application;

import pl.instalflow.identity.application.dto.ChangePasswordRequest;
import pl.instalflow.identity.application.dto.ChangeRoleRequest;
import pl.instalflow.identity.application.dto.CreateUserRequest;
import pl.instalflow.identity.application.dto.TechnicianResponse;
import pl.instalflow.identity.application.dto.UserResponse;
import pl.instalflow.identity.application.dto.VersionRequest;
import pl.instalflow.identity.application.error.EmailAlreadyUsedException;
import pl.instalflow.identity.application.error.InvalidUserStateException;
import pl.instalflow.identity.application.error.LastOwnerException;
import pl.instalflow.identity.application.error.UserNotFoundException;
import pl.instalflow.identity.application.error.VersionConflictException;
import pl.instalflow.identity.domain.AppUser;
import pl.instalflow.identity.domain.RoleEnum;
import pl.instalflow.identity.persistence.AppUserRepository;

import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.Locale;
import java.util.UUID;

@Service
public class UserService {
    private final AppUserRepository repository;
    private final PasswordEncoder passwordEncoder;
    private final IdentityAccess access;
    private final UserSessionInvalidator sessionInvalidator;

    public UserService(AppUserRepository repository, PasswordEncoder passwordEncoder,
                       IdentityAccess access, UserSessionInvalidator sessionInvalidator) {
        this.repository = repository;
        this.passwordEncoder = passwordEncoder;
        this.access = access;
        this.sessionInvalidator = sessionInvalidator;
    }

    @Transactional
    public UserResponse create(CreateUserRequest request) {
        access.requireOwner();
        if (request == null || request.role() == null) {
            throw new IllegalArgumentException("Role is required");
        }
        String email = normalizeEmail(request.email());
        String password = requirePassword(request.password());
        if (repository.existsByEmailNormalized(email)) {
            throw new EmailAlreadyUsedException();
        }
        AppUser user = AppUser.create(email, passwordEncoder.encode(password), request.role());
        try {
            return response(repository.saveAndFlush(user));
        } catch (DataIntegrityViolationException ex) {
            if (isEmailUniqueViolation(ex)) {
                throw new EmailAlreadyUsedException();
            }
            throw ex;
        }
    }

    @Transactional(readOnly = true)
    public Page<UserResponse> list(Pageable pageable) {
        access.requireOwner();
        return repository.findAll(requirePageable(pageable)).map(UserService::response);
    }

    @Transactional(readOnly = true)
    public UserResponse get(UUID id) {
        access.requireOwner();
        return response(findUser(id));
    }

    @Transactional(readOnly = true)
    public Page<TechnicianResponse> technicians(Pageable pageable) {
        access.requireOwnerOrOffice();
        return repository.findByRoleAndActiveTrue(RoleEnum.TECHNICIAN, requirePageable(pageable))
                .map(user -> new TechnicianResponse(user.getId(), employeeNumber(user.getUserNumber())));
    }

    @Transactional
    public UserResponse changeRole(UUID id, ChangeRoleRequest request) {
        access.requireOwner();
        if (request == null || request.role() == null) {
            throw new IllegalArgumentException("Role is required");
        }
        long ownerCount = lockAndCountActiveOwners();
        AppUser user = findUser(id);
        requireActive(user);
        requireVersion(user, request.version());
        if (user.getRole() == request.role()) {
            return response(user);
        }
        if (user.getRole() == RoleEnum.OWNER && ownerCount <= 1) {
            throw new LastOwnerException();
        }
        user.changeRole(request.role());
        flushWithVersionCheck();
        invalidateAfterCommit(user.getId());
        return response(user);
    }

    @Transactional
    public void changePassword(UUID id, ChangePasswordRequest request) {
        access.requireOwner();
        if (request == null) {
            throw new IllegalArgumentException("Request is required");
        }
        String password = requirePassword(request.password());
        AppUser user = findUser(id);
        requireActive(user);
        requireVersion(user, request.version());
        user.changePassword(passwordEncoder.encode(password));
        flushWithVersionCheck();
        invalidateAfterCommit(user.getId());
    }

    @Transactional
    public void deactivate(UUID id, VersionRequest request) {
        access.requireOwner();
        if (request == null) {
            throw new IllegalArgumentException("Request is required");
        }
        long ownerCount = lockAndCountActiveOwners();
        AppUser user = findUser(id);
        requireActive(user);
        requireVersion(user, request.version());
        if (user.getRole() == RoleEnum.OWNER && ownerCount <= 1) {
            throw new LastOwnerException();
        }
        user.deactivate();
        flushWithVersionCheck();
        invalidateAfterCommit(user.getId());
    }

    private AppUser findUser(UUID id) {
        if (id == null) {
            throw new IllegalArgumentException("User ID is required");
        }
        return repository.findById(id).orElseThrow(() -> new UserNotFoundException(id));
    }

    private long lockAndCountActiveOwners() {
        repository.lockActiveOwners(RoleEnum.OWNER);
        return repository.countByRoleAndActiveTrue(RoleEnum.OWNER);
    }

    private void flushWithVersionCheck() {
        try {
            repository.flush();
        } catch (ObjectOptimisticLockingFailureException ex) {
            throw new VersionConflictException();
        }
    }

    private void invalidateAfterCommit(UUID userId) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                sessionInvalidator.invalidate(userId);
            }
        });
    }

    private static void requireActive(AppUser user) {
        if (!user.isActive()) {
            throw new InvalidUserStateException("User is inactive");
        }
    }

    private static void requireVersion(AppUser user, Long expectedVersion) {
        if (expectedVersion == null || expectedVersion < 0) {
            throw new IllegalArgumentException("Nonnegative version is required");
        }
        if (!expectedVersion.equals(user.getVersion())) {
            throw new VersionConflictException();
        }
    }

    public static String normalizeEmail(String email) {
        if (email == null) {
            throw new IllegalArgumentException("Email is required");
        }
        String normalized = email.strip().toLowerCase(Locale.ROOT);
        int separator = normalized.indexOf('@');
        if (normalized.isEmpty() || normalized.length() > 254 || separator <= 0
                || separator == normalized.length() - 1 || separator != normalized.lastIndexOf('@')
                || normalized.chars().anyMatch(Character::isWhitespace)) {
            throw new IllegalArgumentException("Email is invalid");
        }
        return normalized;
    }

    private static String requirePassword(String password) {
        if (password == null || password.isBlank() || password.length() > 128) {
            throw new IllegalArgumentException("Password must contain 1 to 128 characters");
        }
        return password;
    }

    private static Pageable requirePageable(Pageable pageable) {
        if (pageable == null) {
            throw new IllegalArgumentException("Pageable is required");
        }
        return pageable;
    }

    private static boolean isEmailUniqueViolation(DataIntegrityViolationException ex) {
        Throwable cause = ex;
        while (cause != null) {
            if (cause instanceof ConstraintViolationException violation
                    && "uq_app_user_email_normalized".equals(violation.getConstraintName())) {
                return true;
            }
            cause = cause.getCause();
        }
        return false;
    }

    private static UserResponse response(AppUser user) {
        return new UserResponse(user.getId(), employeeNumber(user.getUserNumber()),
                user.getEmailNormalized(), user.getRole(), user.isActive(), user.getVersion(), user.getCreatedAt());
    }

    public static String employeeNumber(Long number) {
        if (number == null || number < 1) {
            throw new IllegalStateException("Persisted user has no employee number");
        }
        return String.format(Locale.ROOT, "%04d", number);
    }
}
