package com.rpgjr.mirana.model.dto.account;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;

public final class AccountDtos {

    private AccountDtos() {
    }

    public record AccountResponse(Long id, String email, String displayName, boolean emailVerified, Instant createdAt,
            Instant passwordChangedAt) {
    }

    public record UpdateProfileRequest(
            @NotBlank(message = "Enter your name.") @Size(max = 100, message = "Name is too long.") String displayName) {
    }

    public record ChangePasswordRequest(
            @NotBlank(message = "Enter your current password.") @Size(max = 200, message = "Password is too long.") String currentPassword,
            @NotBlank(message = "Enter a new password.") @Size(max = 200, message = "Password is too long.") String newPassword) {
    }

    public record DeviceResponse(Long id, String userAgent, Instant createdAt, Instant lastSeenAt, boolean current) {
    }
}
