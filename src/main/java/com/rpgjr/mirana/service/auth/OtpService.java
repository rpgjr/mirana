package com.rpgjr.mirana.service.auth;

import com.rpgjr.mirana.config.AuthProperties;
import com.rpgjr.mirana.exception.ApiException;

import com.rpgjr.mirana.service.mail.MailService;
import com.rpgjr.mirana.model.entity.user.AppUser;
import com.rpgjr.mirana.model.entity.user.EmailOtp;
import com.rpgjr.mirana.repository.user.EmailOtpRepository;
import com.rpgjr.mirana.enums.OtpPurpose;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(noRollbackFor = ApiException.class)
public class OtpService {

    private final EmailOtpRepository otps;
    private final MailService mail;
    private final AuthProperties props;
    private final SecureRandom random = new SecureRandom();

    public OtpService(EmailOtpRepository otps, MailService mail, AuthProperties props) {
        this.otps = otps;
        this.mail = mail;
        this.props = props;
    }

    /** Sends a fresh code. Returns false when the resend cooldown or hourly cap blocks it. */
    public boolean issue(AppUser user, OtpPurpose purpose) {
        Instant now = Instant.now();
        var last = otps.findFirstByUserIdAndPurposeOrderByIdDesc(user.getId(), purpose);
        if (last.isPresent() && last.get().getCreatedAt().plus(AuthProperties.OTP_COOLDOWN).isAfter(now)) {
            return false;
        }
        if (otps.countByUserIdAndPurposeAndCreatedAtAfter(user.getId(), purpose, now.minusSeconds(3600))
                >= AuthProperties.OTP_MAX_PER_HOUR) {
            return false;
        }
        otps.consumeOpen(user.getId(), purpose, now);
        String code = "%06d".formatted(random.nextInt(1_000_000));
        EmailOtp otp = new EmailOtp();
        otp.setUserId(user.getId());
        otp.setPurpose(purpose);
        otp.setCodeHash(hash(user.getId(), purpose, code));
        otp.setExpiresAt(now.plus(AuthProperties.OTP_TTL));
        otps.save(otp);
        mail.sendOtp(user.getEmail(), purpose, code);
        return true;
    }

    public void verify(AppUser user, OtpPurpose purpose, String code) {
        EmailOtp otp = otps.findFirstByUserIdAndPurposeAndConsumedAtIsNullOrderByIdDesc(user.getId(), purpose)
                .orElseThrow(OtpService::invalid);
        Instant now = Instant.now();
        if (otp.getExpiresAt().isBefore(now) || otp.getAttempts() >= AuthProperties.OTP_MAX_ATTEMPTS) {
            otp.setConsumedAt(now);
            throw invalid();
        }
        boolean match = MessageDigest.isEqual(
                hash(user.getId(), purpose, code == null ? "" : code).getBytes(StandardCharsets.UTF_8),
                otp.getCodeHash().getBytes(StandardCharsets.UTF_8));
        if (!match) {
            otp.setAttempts(otp.getAttempts() + 1);
            if (otp.getAttempts() >= AuthProperties.OTP_MAX_ATTEMPTS) {
                otp.setConsumedAt(now);
            }
            throw invalid();
        }
        otp.setConsumedAt(now);
    }

    private String hash(Long userId, OtpPurpose purpose, String code) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(props.otpSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal((userId + ":" + purpose + ":" + code).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static ApiException invalid() {
        return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_CODE", "That code is not right or has expired.");
    }
}
