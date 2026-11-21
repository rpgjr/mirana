package com.rpgjr.mirana.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("mirana.auth")
public record AuthProperties(
        String otpSecret,
        boolean checkMx,
        boolean breachCheck,
        boolean secureCookies,
        String mailFrom,
        String appUrl,
        boolean logOtp) {

    public static final Duration OTP_TTL = Duration.ofMinutes(10);
    public static final Duration OTP_COOLDOWN = Duration.ofSeconds(60);
    public static final int OTP_MAX_ATTEMPTS = 5;
    public static final int OTP_MAX_PER_HOUR = 5;
    public static final int MAX_FAILED_LOGINS = 10;
    public static final Duration LOCK_TIME = Duration.ofMinutes(15);
}
