package com.rpgjr.mirana.service.account;

import com.rpgjr.mirana.model.dto.account.AccountDtos;

import com.rpgjr.mirana.model.dto.account.AccountDtos.AccountResponse;
import com.rpgjr.mirana.model.dto.account.AccountDtos.DeviceResponse;
import com.rpgjr.mirana.exception.ApiException;
import com.rpgjr.mirana.service.auth.DeviceService;
import com.rpgjr.mirana.utils.PasswordPolicy;
import com.rpgjr.mirana.utils.RateLimiter;
import com.rpgjr.mirana.service.auth.SessionService;
import com.rpgjr.mirana.model.entity.user.AppUser;
import com.rpgjr.mirana.repository.user.AppUserRepository;
import com.rpgjr.mirana.repository.user.TrustedDeviceRepository;
import com.rpgjr.mirana.model.entity.user.UserCredential;
import com.rpgjr.mirana.repository.user.UserCredentialRepository;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(noRollbackFor = ApiException.class)
public class AccountService {

    private final AppUserRepository users;
    private final UserCredentialRepository credentials;
    private final TrustedDeviceRepository devices;
    private final DeviceService deviceService;
    private final SessionService sessions;
    private final PasswordEncoder encoder;
    private final PasswordPolicy policy;
    private final RateLimiter limiter;

    public AccountService(AppUserRepository users, UserCredentialRepository credentials, TrustedDeviceRepository devices,
            DeviceService deviceService, SessionService sessions, PasswordEncoder encoder, PasswordPolicy policy,
            RateLimiter limiter) {
        this.users = users;
        this.credentials = credentials;
        this.devices = devices;
        this.deviceService = deviceService;
        this.sessions = sessions;
        this.encoder = encoder;
        this.policy = policy;
        this.limiter = limiter;
    }

    public AccountResponse get(Long userId) {
        AppUser u = user(userId);
        Instant changed = credentials.findById(userId).map(UserCredential::getPasswordChangedAt).orElse(null);
        return new AccountResponse(u.getId(), u.getEmail(), u.getDisplayName(), u.isVerified(), u.getCreatedAt(), changed);
    }

    public AccountResponse rename(Long userId, String displayName) {
        AppUser u = user(userId);
        u.setDisplayName(displayName.trim());
        return get(userId);
    }

    /** Keeps the current session, signs out every other one. */
    public void changePassword(Long userId, String current, String next, HttpServletRequest req) {
        limiter.check("pwchange:" + userId, 5, Duration.ofMinutes(15));
        UserCredential cred = credentials.findById(userId)
                .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "NO_PASSWORD", "This account has no password set."));
        if (!encoder.matches(current, cred.getPasswordHash())) {
            String msg = "That is not your current password.";
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_CURRENT_PASSWORD", msg, Map.of("currentPassword", msg));
        }
        if (encoder.matches(next, cred.getPasswordHash())) {
            String msg = "Pick a password you have not used on this account.";
            throw new ApiException(HttpStatus.BAD_REQUEST, "WEAK_PASSWORD", msg, Map.of("newPassword", msg));
        }
        try {
            policy.validate(next, user(userId).getEmail());
        } catch (ApiException e) {
            throw new ApiException(e.status(), e.code(), e.getMessage(), Map.of("newPassword", e.getMessage()));
        }
        cred.setPasswordHash(encoder.encode(next));
        cred.setPasswordChangedAt(Instant.now());
        cred.setFailedAttempts(0);
        cred.setLockedUntil(null);
        var session = req.getSession(false);
        sessions.revokeOthers(userId, session == null ? null : session.getId());
    }

    public List<DeviceResponse> devices(Long userId, HttpServletRequest req) {
        String current = deviceService.currentTokenHash(req);
        return devices.findByUserIdOrderByLastSeenAtDesc(userId).stream()
                .map(d -> new DeviceResponse(d.getId(), d.getUserAgent(), d.getCreatedAt(), d.getLastSeenAt(),
                        current != null && current.equals(d.getTokenHash())))
                .toList();
    }

    public void removeDevice(Long userId, Long deviceId) {
        var d = devices.findByIdAndUserId(deviceId, userId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Device not found."));
        devices.delete(d);
    }

    public void signOutOthers(Long userId, HttpServletRequest req) {
        var session = req.getSession(false);
        sessions.revokeOthers(userId, session == null ? null : session.getId());
    }

    private AppUser user(Long id) {
        return users.findById(id).orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Sign in to continue."));
    }
}
