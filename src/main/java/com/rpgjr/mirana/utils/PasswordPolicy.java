package com.rpgjr.mirana.utils;

import com.rpgjr.mirana.config.AuthProperties;
import com.rpgjr.mirana.exception.ApiException;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** NIST 800-63B style: length over composition rules, reject known-bad passwords. */
@Component
public class PasswordPolicy {

    public static final int MIN = 12;
    public static final int MAX = 128;

    private static final Logger log = LoggerFactory.getLogger(PasswordPolicy.class);
    private static final Set<String> COMMON = Set.of(
            "password1234", "password12345", "123456789012", "1234567890123", "qwertyuiop12",
            "qwertyuiopas", "letmein12345", "iloveyou1234", "admin1234567", "welcome12345",
            "changeme1234", "passw0rd1234", "abcdefghijkl", "111111111111", "000000000000");

    private final AuthProperties props;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

    public PasswordPolicy(AuthProperties props) {
        this.props = props;
    }

    public void validate(String password, String email) {
        String p = password == null ? "" : password;
        String reason = null;
        if (p.length() < MIN) {
            reason = "Use at least " + MIN + " characters.";
        } else if (p.length() > MAX) {
            reason = "Use " + MAX + " characters or fewer.";
        } else if (p.chars().distinct().count() < 4 || COMMON.contains(p.toLowerCase(Locale.ROOT))) {
            reason = "That password is too common. Pick something less guessable.";
        } else if (containsEmailName(p, email)) {
            reason = "Do not include your email name in the password.";
        } else if (props.breachCheck() && isBreached(p)) {
            reason = "That password appeared in a known data leak. Pick a different one.";
        }
        if (reason != null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "WEAK_PASSWORD", reason, java.util.Map.of("password", reason));
        }
    }

    private static boolean containsEmailName(String p, String email) {
        if (email == null || !email.contains("@")) {
            return false;
        }
        String name = email.substring(0, email.indexOf('@')).toLowerCase(Locale.ROOT);
        return name.length() > 2 && p.toLowerCase(Locale.ROOT).contains(name);
    }

    /** HaveIBeenPwned k-anonymity range API. Only a 5 character hash prefix leaves the server. Fails open. */
    boolean isBreached(String password) {
        try {
            String sha1 = HexFormat.of().withUpperCase()
                    .formatHex(MessageDigest.getInstance("SHA-1").digest(password.getBytes(StandardCharsets.UTF_8)));
            HttpRequest req = HttpRequest.newBuilder(URI.create("https://api.pwnedpasswords.com/range/" + sha1.substring(0, 5)))
                    .timeout(Duration.ofSeconds(2)).header("Add-Padding", "true").GET().build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 200) {
                return false;
            }
            String suffix = sha1.substring(5);
            for (String line : res.body().split("\\r?\\n")) {
                if (line.startsWith(suffix + ":")) {
                    return Long.parseLong(line.substring(suffix.length() + 1).trim()) > 0;
                }
            }
            return false;
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.warn("Breach check skipped: {}", e.toString());
            return false;
        }
    }
}
