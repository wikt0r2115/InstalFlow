package pl.instalflow.identity.web;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import pl.instalflow.identity.application.error.EmailAlreadyUsedException;
import pl.instalflow.identity.application.error.InvalidUserStateException;
import pl.instalflow.identity.application.error.LastOwnerException;
import pl.instalflow.identity.application.error.UserNotFoundException;
import pl.instalflow.identity.application.error.VersionConflictException;

import java.net.URI;
import java.util.List;
import java.util.Map;

@RestControllerAdvice
public class ApiExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(UserNotFoundException.class)
    ResponseEntity<ProblemDetail> notFound(UserNotFoundException error, HttpServletRequest request) {
        return problem(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", error.getMessage(), request);
    }

    @ExceptionHandler(AuthenticationException.class)
    ResponseEntity<ProblemDetail> unauthenticated(AuthenticationException error, HttpServletRequest request) {
        return problem(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Authentication is required", request);
    }

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<ProblemDetail> forbidden(AccessDeniedException error, HttpServletRequest request) {
        return problem(HttpStatus.FORBIDDEN, "FORBIDDEN", "Access denied", request);
    }

    @ExceptionHandler(EmailAlreadyUsedException.class)
    ResponseEntity<ProblemDetail> emailUsed(EmailAlreadyUsedException error, HttpServletRequest request) {
        return problem(HttpStatus.CONFLICT, "EMAIL_ALREADY_USED", error.getMessage(), request);
    }

    @ExceptionHandler(LastOwnerException.class)
    ResponseEntity<ProblemDetail> lastOwner(LastOwnerException error, HttpServletRequest request) {
        return problem(HttpStatus.CONFLICT, "LAST_OWNER", error.getMessage(), request);
    }

    @ExceptionHandler(VersionConflictException.class)
    ResponseEntity<ProblemDetail> version(VersionConflictException error, HttpServletRequest request) {
        return problem(HttpStatus.CONFLICT, "VERSION_CONFLICT", error.getMessage(), request);
    }

    @ExceptionHandler(InvalidUserStateException.class)
    ResponseEntity<ProblemDetail> invalidState(InvalidUserStateException error, HttpServletRequest request) {
        return problem(HttpStatus.CONFLICT, "INVALID_USER_STATE", error.getMessage(), request);
    }

    @ExceptionHandler({IllegalArgumentException.class, HttpMessageNotReadableException.class,
            MethodArgumentTypeMismatchException.class})
    ResponseEntity<ProblemDetail> badRequest(Exception error, HttpServletRequest request) {
        String detail = error instanceof IllegalArgumentException ? error.getMessage() : "Invalid request";
        return problem(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", detail, request);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ProblemDetail> validation(MethodArgumentNotValidException error,
                                             HttpServletRequest request) {
        ProblemDetail body = body(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Request validation failed", request);
        List<Map<String, String>> violations = error.getBindingResult().getFieldErrors().stream()
                .map(field -> Map.of("field", field.getField(), "message",
                        field.getDefaultMessage() == null ? "Invalid value" : field.getDefaultMessage()))
                .toList();
        body.setProperty("violations", violations);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    @ExceptionHandler({DataIntegrityViolationException.class, ObjectOptimisticLockingFailureException.class})
    ResponseEntity<ProblemDetail> persistenceConflict(Exception error, HttpServletRequest request) {
        log.warn("Persistence conflict requestId={} type={}", request.getAttribute("requestId"),
                error.getClass().getSimpleName());
        return problem(HttpStatus.CONFLICT, "CONFLICT", "Resource changed or violates a constraint", request);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> unexpected(Exception error, HttpServletRequest request) {
        if (error instanceof ErrorResponse framework && framework.getStatusCode().is4xxClientError()) {
            ProblemDetail body = framework.getBody();
            HttpStatus status = HttpStatus.resolve(framework.getStatusCode().value());
            body.setInstance(URI.create(request.getRequestURI()));
            body.setProperty("code", status == null ? "INVALID_REQUEST" : status.name());
            body.setProperty("requestId", request.getAttribute("requestId"));
            return ResponseEntity.status(framework.getStatusCode()).headers(framework.getHeaders()).body(body);
        }
        log.error("Unhandled request error {}", request.getAttribute("requestId"), error);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Unexpected server error", request);
    }

    private static ResponseEntity<ProblemDetail> problem(HttpStatus status, String code, String detail,
                                                         HttpServletRequest request) {
        return ResponseEntity.status(status).body(body(status, code, detail, request));
    }

    private static ProblemDetail body(HttpStatus status, String code, String detail,
                                      HttpServletRequest request) {
        ProblemDetail body = ProblemDetail.forStatusAndDetail(status, detail);
        body.setInstance(URI.create(request.getRequestURI()));
        body.setProperty("code", code);
        body.setProperty("requestId", request.getAttribute("requestId") == null
                ? "" : request.getAttribute("requestId"));
        return body;
    }
}
