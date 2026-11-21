package com.rpgjr.mirana.service.auth;

import com.rpgjr.mirana.config.AuthProperties;

import com.rpgjr.mirana.model.entity.user.TrustedDevice;
import com.rpgjr.mirana.repository.user.TrustedDeviceRepository;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class DeviceService {

    static final String COOKIE = "mirana_device";

    private final TrustedDeviceRepository devices;
    private final AuthProperties props;
    private final SecureRandom random = new SecureRandom();

    public DeviceService(TrustedDeviceRepository devices, AuthProperties props) {
        this.devices = devices;
        this.props = props;
    }

    public boolean isTrusted(Long userId, HttpServletRequest req) {
        String token = cookie(req);
        if (token == null) {
            return false;
        }
        var device = devices.findByUserIdAndTokenHash(userId, sha256(token));
        device.ifPresent(d -> d.setLastSeenAt(Instant.now()));
        return device.isPresent();
    }

    public void trust(Long userId, HttpServletRequest req, HttpServletResponse res) {
        byte[] raw = new byte[32];
        random.nextBytes(raw);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        TrustedDevice d = new TrustedDevice();
        d.setUserId(userId);
        d.setTokenHash(sha256(token));
        String ua = req.getHeader(HttpHeaders.USER_AGENT);
        d.setUserAgent(ua == null ? null : ua.substring(0, Math.min(ua.length(), 300)));
        devices.save(d);
        res.addHeader(HttpHeaders.SET_COOKIE, ResponseCookie.from(COOKIE, token)
                .httpOnly(true).secure(props.secureCookies()).sameSite("Lax").path("/")
                .maxAge(Duration.ofDays(365)).build().toString());
    }

    /** Hash of the device cookie on this request, or null. Used to flag the current browser in the device list. */
    public String currentTokenHash(HttpServletRequest req) {
        String token = cookie(req);
        return token == null ? null : sha256(token);
    }

    private static String cookie(HttpServletRequest req) {
        if (req.getCookies() == null) {
            return null;
        }
        for (Cookie c : req.getCookies()) {
            if (COOKIE.equals(c.getName()) && !c.getValue().isBlank()) {
                return c.getValue();
            }
        }
        return null;
    }

    static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
