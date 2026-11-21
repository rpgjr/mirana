package com.rpgjr.mirana.service.auth;

import com.rpgjr.mirana.config.AuthProperties;
import com.rpgjr.mirana.exception.ApiException;
import com.rpgjr.mirana.model.dto.auth.AuthDtos;
import com.rpgjr.mirana.utils.EmailPolicy;
import com.rpgjr.mirana.utils.PasswordPolicy;
import com.rpgjr.mirana.utils.RateLimiter;

import com.rpgjr.mirana.model.dto.auth.AuthDtos.UserResponse;
import com.rpgjr.mirana.service.mail.MailService;
import com.rpgjr.mirana.model.entity.user.AppUser;
import com.rpgjr.mirana.repository.user.AppUserRepository;
import com.rpgjr.mirana.enums.OtpPurpose;
import com.rpgjr.mirana.model.entity.user.UserCredential;
import com.rpgjr.mirana.repository.user.UserCredentialRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(noRollbackFor = ApiException.class)
public class AuthService {

    /** Result of the first sign-in step. */
    public enum LoginResult { SIGNED_IN, OTP_REQUIRED, EMAIL_NOT_VERIFIED }

    public record LoginOutcome(LoginResult result, UserResponse user) {
    }

    private final AppUserRepository users;
    private final UserCredentialRepository credentials;
    private final PasswordEncoder encoder;
    private final PasswordPolicy passwordPolicy;
    private final EmailPolicy emailPolicy;
    private final OtpService otp;
    private final DeviceService devices;
    private final SessionService sessions;
    private final MailService mail;
    private final RateLimiter limiter;
    private final String dummyHash;

    public AuthService(AppUserRepository users, UserCredentialRepository credentials, PasswordEncoder encoder,
            PasswordPolicy passwordPolicy, EmailPolicy emailPolicy, OtpService otp, DeviceService devices,
            SessionService sessions, MailService mail, RateLimiter limiter) {
        this.users = users;
        this.credentials = credentials;
        this.encoder = encoder;
        this.passwordPolicy = passwordPolicy;
        this.emailPolicy = emailPolicy;
        this.otp = otp;
        this.devices = devices;
        this.sessions = sessions;
        this.mail = mail;
        this.limiter = limiter;
        this.dummyHash = encoder.encode("mirana-dummy-password-for-timing");
    }

    public void register(String rawEmail, String password, String displayName, String ip) {
        String email = EmailPolicy.normalize(rawEmail);
        limiter.check("register:ip:" + ip, 10, Duration.ofHours(1));
        emailPolicy.validate(email);
        passwordPolicy.validate(password, email);
        var existing = users.findByEmail(email);
        if (existing.isPresent() && existing.get().isVerified()) {
            mail.sendAccountExists(email);
            return;
        }
        AppUser user = existing.orElseGet(AppUser::new);
        user.setEmail(email);
        user.setDisplayName(displayName.trim());
        user = users.save(user);
        UserCredential cred = credentials.findById(user.getId()).orElseGet(() -> new UserCredential(null, null));
        cred.setUserId(user.getId());
        cred.setPasswordHash(encoder.encode(password));
        cred.setPasswordChangedAt(Instant.now());
        credentials.save(cred);
        otp.issue(user, OtpPurpose.VERIFY_EMAIL);
    }

    public UserResponse verifyEmail(String rawEmail, String code, HttpServletRequest req, HttpServletResponse res) {
        AppUser user = findForOtp(rawEmail, "verify");
        otp.verify(user, OtpPurpose.VERIFY_EMAIL, code);
        user.setEmailVerifiedAt(Instant.now());
        users.save(user);
        devices.trust(user.getId(), req, res);
        sessions.start(user, req, res);
        return toResponse(user);
    }

    public LoginOutcome login(String rawEmail, String password, HttpServletRequest req, HttpServletResponse res, String ip) {
        String email = EmailPolicy.normalize(rawEmail);
        limiter.check("login:ip:" + ip, 30, Duration.ofMinutes(15));
        limiter.check("login:email:" + email, 10, Duration.ofMinutes(15));
        AppUser user = users.findByEmail(email).orElse(null);
        UserCredential cred = user == null ? null : credentials.findById(user.getId()).orElse(null);
        if (cred == null) {
            encoder.matches(password, dummyHash);
            throw badCredentials();
        }
        Instant now = Instant.now();
        if (cred.getLockedUntil() != null && cred.getLockedUntil().isAfter(now)) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "ACCOUNT_LOCKED",
                    "Too many failed attempts. Try again in 15 minutes or reset your password.");
        }
        if (!encoder.matches(password, cred.getPasswordHash())) {
            cred.setFailedAttempts(cred.getFailedAttempts() + 1);
            if (cred.getFailedAttempts() >= AuthProperties.MAX_FAILED_LOGINS) {
                cred.setLockedUntil(now.plus(AuthProperties.LOCK_TIME));
                cred.setFailedAttempts(0);
            }
            throw badCredentials();
        }
        cred.setFailedAttempts(0);
        cred.setLockedUntil(null);
        if (!user.isVerified()) {
            otp.issue(user, OtpPurpose.VERIFY_EMAIL);
            return new LoginOutcome(LoginResult.EMAIL_NOT_VERIFIED, null);
        }
        if (devices.isTrusted(user.getId(), req)) {
            sessions.start(user, req, res);
            return new LoginOutcome(LoginResult.SIGNED_IN, toResponse(user));
        }
        otp.issue(user, OtpPurpose.NEW_DEVICE);
        return new LoginOutcome(LoginResult.OTP_REQUIRED, null);
    }

    public UserResponse verifyDevice(String rawEmail, String code, HttpServletRequest req, HttpServletResponse res) {
        AppUser user = findForOtp(rawEmail, "device:" + req.getRemoteAddr());
        if (!user.isVerified()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_CODE", "That code is not right or has expired.");
        }
        otp.verify(user, OtpPurpose.NEW_DEVICE, code);
        devices.trust(user.getId(), req, res);
        sessions.start(user, req, res);
        return toResponse(user);
    }

    /** Always succeeds from the caller's view so the endpoint does not reveal which emails have accounts. */
    public void resend(String rawEmail, OtpPurpose purpose) {
        String email = EmailPolicy.normalize(rawEmail);
        limiter.check("resend:email:" + email, 10, Duration.ofHours(1));
        users.findByEmail(email).ifPresent(user -> {
            boolean ok = switch (purpose) {
                case VERIFY_EMAIL -> !user.isVerified();
                case NEW_DEVICE, RESET_PASSWORD -> user.isVerified();
            };
            if (ok) {
                otp.issue(user, purpose);
            }
        });
    }

    public void resetPassword(String rawEmail, String code, String newPassword) {
        AppUser user = findForOtp(rawEmail, "reset");
        if (!user.isVerified()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_CODE", "That code is not right or has expired.");
        }
        passwordPolicy.validate(newPassword, user.getEmail());
        otp.verify(user, OtpPurpose.RESET_PASSWORD, code);
        UserCredential cred = credentials.findById(user.getId()).orElseGet(() -> new UserCredential(user.getId(), null));
        cred.setPasswordHash(encoder.encode(newPassword));
        cred.setPasswordChangedAt(Instant.now());
        cred.setFailedAttempts(0);
        cred.setLockedUntil(null);
        credentials.save(cred);
        sessions.revokeAll(user.getId());
    }

    public void logout(HttpServletRequest req) {
        sessions.end(req);
    }

    private AppUser findForOtp(String rawEmail, String scope) {
        String email = EmailPolicy.normalize(rawEmail);
        limiter.check("otp:" + scope + ":" + email, 20, Duration.ofMinutes(15));
        return users.findByEmail(email)
                .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "INVALID_CODE", "That code is not right or has expired."));
    }

    private static ApiException badCredentials() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "Email or password is not right.");
    }

    private static UserResponse toResponse(AppUser u) {
        return new UserResponse(u.getId(), u.getEmail(), u.getDisplayName());
    }
}
