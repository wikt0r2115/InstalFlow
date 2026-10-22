package pl.instalflow.identity.web;

import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import pl.instalflow.identity.application.dto.ChangePasswordRequest;
import pl.instalflow.identity.application.dto.ChangeRoleRequest;
import pl.instalflow.identity.application.dto.CreateUserRequest;
import pl.instalflow.identity.application.dto.TechnicianResponse;
import pl.instalflow.identity.application.dto.UserResponse;
import pl.instalflow.identity.application.UserService;
import pl.instalflow.identity.application.dto.VersionRequest;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/users")
public class UserController {
    private static final Map<String, String> SORT_FIELDS = Map.of(
            "employeeNumber", "userNumber",
            "createdAt", "createdAt",
            "email", "emailNormalized",
            "role", "role",
            "active", "active");

    private final UserService users;

    public UserController(UserService users) {
        this.users = users;
    }

    @PostMapping
    public ResponseEntity<UserResponse> create(@Valid @RequestBody CreateUserRequest request) {
        UserResponse created = users.create(request);
        return ResponseEntity.created(URI.create("/api/v1/users/" + created.id())).body(created);
    }

    @GetMapping
    public PageResponse<UserResponse> list(@RequestParam(defaultValue = "0") int page,
                                           @RequestParam(defaultValue = "20") int size,
                                           @RequestParam(defaultValue = "employeeNumber") String sort,
                                           @RequestParam(defaultValue = "asc") String direction) {
        return PageResponse.from(users.list(pageable(page, size, sort, direction, false)));
    }

    @GetMapping("/technicians")
    public PageResponse<TechnicianResponse> technicians(@RequestParam(defaultValue = "0") int page,
                                                        @RequestParam(defaultValue = "20") int size,
                                                        @RequestParam(defaultValue = "employeeNumber") String sort,
                                                        @RequestParam(defaultValue = "asc") String direction) {
        return PageResponse.from(users.technicians(pageable(page, size, sort, direction, true)));
    }

    @GetMapping("/{id}")
    public UserResponse get(@PathVariable UUID id) {
        return users.get(id);
    }

    @PatchMapping("/{id}")
    public UserResponse changeRole(@PathVariable UUID id, @Valid @RequestBody ChangeRoleRequest request) {
        return users.changeRole(id, request);
    }

    @PostMapping("/{id}/password")
    public ResponseEntity<Void> changePassword(@PathVariable UUID id,
                                               @Valid @RequestBody ChangePasswordRequest request) {
        users.changePassword(id, request);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/deactivate")
    public ResponseEntity<Void> deactivate(@PathVariable UUID id, @Valid @RequestBody VersionRequest request) {
        users.deactivate(id, request);
        return ResponseEntity.noContent().build();
    }

    private static Pageable pageable(int page, int size, String sort, String direction, boolean technicians) {
        if (page < 0 || size < 1 || size > 100) {
            throw new IllegalArgumentException("Page must be nonnegative and size must be between 1 and 100");
        }
        if (technicians && !List.of("employeeNumber").contains(sort)) {
            throw new IllegalArgumentException("Unsupported technician sort field");
        }
        String field = SORT_FIELDS.get(sort);
        if (field == null) {
            throw new IllegalArgumentException("Unsupported sort field");
        }
        Sort.Direction order;
        try {
            order = Sort.Direction.fromString(direction);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Sort direction must be asc or desc");
        }
        return PageRequest.of(page, size, Sort.by(order, field).and(Sort.by("id")));
    }

    public record PageResponse<T>(List<T> content, int page, int size, long totalElements, int totalPages) {
        static <T> PageResponse<T> from(Page<T> source) {
            return new PageResponse<>(source.getContent(), source.getNumber(), source.getSize(),
                    source.getTotalElements(), source.getTotalPages());
        }
    }
}
