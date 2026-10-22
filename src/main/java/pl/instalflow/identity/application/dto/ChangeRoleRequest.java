package pl.instalflow.identity.application.dto;

import pl.instalflow.identity.domain.RoleEnum;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

public record ChangeRoleRequest(@NotNull RoleEnum role, @NotNull @PositiveOrZero Long version) {}
