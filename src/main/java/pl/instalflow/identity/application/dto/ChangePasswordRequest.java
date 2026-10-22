package pl.instalflow.identity.application.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public record ChangePasswordRequest(@NotBlank @Size(max = 128) String password,
                                    @NotNull @PositiveOrZero Long version) {}
