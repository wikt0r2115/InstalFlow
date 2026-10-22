package pl.instalflow.identity.application.dto;

import pl.instalflow.identity.domain.RoleEnum;

import java.time.Instant;
import java.util.UUID;

public record UserResponse(UUID id, String employeeNumber, String email, RoleEnum role,
                           boolean active, Long version, Instant createdAt) {}
