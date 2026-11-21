package com.rpgjr.mirana.model.dto.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public final class AuthDtos {

    private AuthDtos() {
    }

    public record RegisterRequest(
            @NotBlank(message = "Enter your email.") String email,
            @NotBlank(message = "Enter a password.") @Size(max = 200, message = "Password is too long.") String password,
            @NotBlank(message = "Enter your name.") @Size(max = 100, message = "Name is too long.") String displayName) {
    }

    public record LoginRequest(
            @NotBlank(message = "Enter your email.") String email,
            @NotBlank(message = "Enter your password.") @Size(max = 200, message = "Password is too long.") String password) {
    }

    public record EmailRequest(@NotBlank(message = "Enter your email.") String email) {
    }

    public record CodeRequest(
            @NotBlank(message = "Enter your email.") String email,
            @NotBlank(message = "Enter the 6 digit code.") @Size(max = 12, message = "Code is too long.") String code) {
    }

    public record ResendRequest(
            @NotBlank(message = "Enter your email.") String email,
            @NotBlank(message = "Missing purpose.") String purpose) {
    }

    public record ResetRequest(
            @NotBlank(message = "Enter your email.") String email,
            @NotBlank(message = "Enter the 6 digit code.") @Size(max = 12, message = "Code is too long.") String code,
            @NotBlank(message = "Enter a new password.") @Size(max = 200, message = "Password is too long.") String newPassword) {
    }

    public record UserResponse(Long id, String email, String displayName) {
    }

    public record StatusResponse(String code, String message) {
    }
}
