package pl.instalflow.identity.security;

import pl.instalflow.identity.application.UserService;
import pl.instalflow.identity.domain.AppUser;
import pl.instalflow.identity.domain.RoleEnum;
import pl.instalflow.identity.persistence.AppUserRepository;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Locale;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/auth")
class AuthController {
    private final AppUserRepository users;
    private final PasswordEncoder passwords;
    private final HttpSessionSecurityContextRepository contextRepository;
    private final CsrfTokenRepository csrfRepository;
    private final LoginAttemptLimiter limiter;
    private final String dummyHash;

    AuthController(AppUserRepository users, PasswordEncoder passwords,
            HttpSessionSecurityContextRepository contextRepository,
            CsrfTokenRepository csrfRepository, LoginAttemptLimiter limiter) {
        this.users = users;
        this.passwords = passwords;
        this.contextRepository = contextRepository;
        this.csrfRepository = csrfRepository;
        this.limiter = limiter;
        this.dummyHash = passwords.encode("nonexistent-account-password");
    }

    @GetMapping("/csrf")
    CsrfResponse csrf(CsrfToken token) {
        return new CsrfResponse(token.getToken(), token.getHeaderName());
    }

    @PostMapping("/login")
    ResponseEntity<MeResponse> login(@Valid @RequestBody LoginRequest body,
            HttpServletRequest request, HttpServletResponse response) {
        String email = body.email().strip().toLowerCase(Locale.ROOT);
        boolean validEmail = true;
        try {
            email = UserService.normalizeEmail(body.email());
        } catch (IllegalArgumentException ignored) {
            validEmail = false;
            email = email.substring(0, Math.min(254, email.length()));
        }
        String remoteAddress = request.getRemoteAddr();
        if (!limiter.tryAcquire(email, remoteAddress)) {
            throw new AuthFailure(429, "LOGIN_RATE_LIMITED", "Zbyt wiele prób logowania");
        }
        AppUser user = validEmail ? users.findByEmailNormalized(email).orElse(null) : null;
        boolean passwordMatches = passwords.matches(body.password(), user == null ? dummyHash : user.getPasswordHash());
        if (user == null || !user.isActive() || !passwordMatches) {
            throw new AuthFailure(401, "INVALID_CREDENTIALS", "Nieprawidłowy e-mail lub hasło");
        }
        limiter.succeeded(email, remoteAddress);
        request.getSession(true);
        request.changeSessionId();
        Authentication authentication = new UsernamePasswordAuthenticationToken(
            user.getId().toString(), null,
            java.util.List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole().name())));
        ((UsernamePasswordAuthenticationToken) authentication).setDetails(user.getVersion());
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        contextRepository.saveContext(context, request, response);
        csrfRepository.saveToken(null, request, response);
        return ResponseEntity.ok(MeResponse.from(user));
    }

    @GetMapping("/me")
    ResponseEntity<MeResponse> me() {
        UUID id = UUID.fromString(SecurityContextHolder.getContext().getAuthentication().getName());
        return users.findById(id)
            .filter(AppUser::isActive)
            .map(user -> ResponseEntity.ok(MeResponse.from(user)))
            .orElseGet(() -> ResponseEntity.status(HttpStatus.UNAUTHORIZED).build());
    }

    @PostMapping("/logout")
    ResponseEntity<Void> logout(HttpServletRequest request, HttpServletResponse response) {
        SecurityContextHolder.clearContext();
        if (request.getSession(false) != null) {
            request.getSession(false).invalidate();
        }
        csrfRepository.saveToken(null, request, response);
        return ResponseEntity.noContent().build();
    }

    @ExceptionHandler(AuthFailure.class)
    ResponseEntity<ProblemDetail> authFailure(AuthFailure error, HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatusCode.valueOf(error.status), error.getMessage());
        problem.setTitle(HttpStatus.valueOf(error.status).getReasonPhrase());
        problem.setInstance(java.net.URI.create(request.getRequestURI()));
        problem.setProperty("code", error.code);
        problem.setProperty("requestId", request.getAttribute("requestId") == null ? "" : request.getAttribute("requestId"));
        return ResponseEntity.status(error.status).body(problem);
    }

    private static final class AuthFailure extends RuntimeException {
        private final int status;
        private final String code;

        private AuthFailure(int status, String code, String message) {
            super(message);
            this.status = status;
            this.code = code;
        }
    }

    record LoginRequest(@NotBlank @Size(max = 1024) String email,
                        @NotBlank @Size(max = 128) String password) { }
    record CsrfResponse(String token, String headerName) { }
    record MeResponse(UUID id, String employeeNumber, String email, RoleEnum role) {
        static MeResponse from(AppUser user) {
            return new MeResponse(user.getId(), String.format(Locale.ROOT, "%04d", user.getUserNumber()),
                user.getEmailNormalized(), user.getRole());
        }
    }
}
