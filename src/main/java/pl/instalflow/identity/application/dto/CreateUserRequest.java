package pl.instalflow.identity.application.dto;

import pl.instalflow.identity.domain.RoleEnum;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreateUserRequest(
        @NotBlank String email,
        @NotBlank @Size(max = 128) String password,
        @NotNull RoleEnum role) {}
