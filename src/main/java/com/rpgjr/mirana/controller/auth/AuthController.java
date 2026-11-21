package com.rpgjr.mirana.controller.auth;

import com.rpgjr.mirana.exception.ApiError;
import com.rpgjr.mirana.exception.ApiException;
import com.rpgjr.mirana.repository.user.AppUserRepository;
import com.rpgjr.mirana.security.AuthUser;
import com.rpgjr.mirana.service.auth.AuthService;

import com.rpgjr.mirana.model.dto.auth.AuthDtos.*;
import com.rpgjr.mirana.enums.OtpPurpose;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService auth;
    private final AppUserRepository users;

    public AuthController(AuthService auth, AppUserRepository users) {
        this.auth = auth;
        this.users = users;
    }

    @PostMapping("/register")
    ResponseEntity<StatusResponse> register(@Valid @RequestBody RegisterRequest r, HttpServletRequest req) {
        auth.register(r.email(), r.password(), r.displayName(), req.getRemoteAddr());
        return accepted("VERIFICATION_SENT", "If that address can receive mail, a 6 digit code is on its way.");
    }

    @PostMapping("/verify")
    UserResponse verify(@Valid @RequestBody CodeRequest r, HttpServletRequest req, HttpServletResponse res) {
        return auth.verifyEmail(r.email(), r.code(), req, res);
    }

    @PostMapping("/login")
    ResponseEntity<?> login(@Valid @RequestBody LoginRequest r, HttpServletRequest req, HttpServletResponse res) {
        var out = auth.login(r.email(), r.password(), req, res, req.getRemoteAddr());
        return switch (out.result()) {
            case SIGNED_IN -> ResponseEntity.ok(out.user());
            case OTP_REQUIRED -> accepted("OTP_REQUIRED", "Enter the code we sent to approve this device.");
            case EMAIL_NOT_VERIFIED -> ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(new ApiError("EMAIL_NOT_VERIFIED", "Confirm your email first. We sent a new code.", java.util.Map.of()));
        };
    }

    @PostMapping("/login/verify")
    UserResponse loginVerify(@Valid @RequestBody CodeRequest r, HttpServletRequest req, HttpServletResponse res) {
        return auth.verifyDevice(r.email(), r.code(), req, res);
    }

    @PostMapping("/resend")
    ResponseEntity<StatusResponse> resend(@Valid @RequestBody ResendRequest r) {
        OtpPurpose purpose;
        try {
            purpose = OtpPurpose.valueOf(r.purpose());
        } catch (IllegalArgumentException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "BAD_REQUEST", "Unknown purpose.");
        }
        auth.resend(r.email(), purpose);
        return accepted("CODE_SENT", "If the account exists, a new code is on its way.");
    }

    @PostMapping("/forgot")
    ResponseEntity<StatusResponse> forgot(@Valid @RequestBody EmailRequest r) {
        auth.resend(r.email(), OtpPurpose.RESET_PASSWORD);
        return accepted("CODE_SENT", "If the account exists, a reset code is on its way.");
    }

    @PostMapping("/reset")
    StatusResponse reset(@Valid @RequestBody ResetRequest r) {
        auth.resetPassword(r.email(), r.code(), r.newPassword());
        return new StatusResponse("PASSWORD_CHANGED", "Password changed. Sign in with your new password.");
    }

    @PostMapping("/logout")
    ResponseEntity<Void> logout(HttpServletRequest req) {
        auth.logout(req);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/me")
    UserResponse me(@AuthenticationPrincipal AuthUser user) {
        if (user == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Sign in to continue.");
        }
        // Read from the database so a rename shows up without signing in again.
        return users.findById(user.id()).map(u -> new UserResponse(u.getId(), u.getEmail(), u.getDisplayName()))
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Sign in to continue."));
    }

    private static ResponseEntity<StatusResponse> accepted(String code, String message) {
        return ResponseEntity.accepted().body(new StatusResponse(code, message));
    }
}
