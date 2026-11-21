package com.rpgjr.mirana.utils;

import com.rpgjr.mirana.config.AuthProperties;
import com.rpgjr.mirana.exception.ApiException;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Hashtable;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import javax.naming.NameNotFoundException;
import javax.naming.NamingException;
import javax.naming.directory.Attributes;
import javax.naming.directory.InitialDirContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** Rejects malformed, disposable and non-existent domains. The OTP is still the real proof of a mailbox. */
@Component
public class EmailPolicy {

    private static final Logger log = LoggerFactory.getLogger(EmailPolicy.class);
    private static final Pattern SYNTAX = Pattern.compile("^[A-Za-z0-9._%+'-]{1,64}@([A-Za-z0-9-]+\\.)+[A-Za-z]{2,24}$");

    private final AuthProperties props;
    private final Set<String> disposable = new HashSet<>();

    public EmailPolicy(AuthProperties props) throws Exception {
        this.props = props;
        try (var r = new BufferedReader(new InputStreamReader(
                new ClassPathResource("disposable-domains.txt").getInputStream(), StandardCharsets.UTF_8))) {
            r.lines().map(String::trim).filter(l -> !l.isEmpty() && !l.startsWith("#")).forEach(disposable::add);
        }
    }

    public static String normalize(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }

    public void validate(String email) {
        if (email.length() > 254 || !SYNTAX.matcher(email).matches()) {
            throw bad("Enter a valid email address, like you@company.com.");
        }
        String domain = email.substring(email.indexOf('@') + 1);
        if (disposable.contains(domain)) {
            throw bad("Disposable email addresses are not allowed. Use your real email.");
        }
        if (props.checkMx() && !domainCanReceiveMail(domain)) {
            throw bad("That email domain cannot receive mail. Check the spelling.");
        }
    }

    private boolean domainCanReceiveMail(String domain) {
        Hashtable<String, String> env = new Hashtable<>();
        env.put("java.naming.factory.initial", "com.sun.jndi.dns.DnsContextFactory");
        env.put("com.sun.jndi.dns.timeout.initial", "2000");
        env.put("com.sun.jndi.dns.timeout.retries", "1");
        try {
            Attributes a = new InitialDirContext(env).getAttributes(domain, new String[] {"MX", "A", "AAAA"});
            return a.size() > 0;
        } catch (NameNotFoundException e) {
            return false;
        } catch (NamingException e) {
            log.warn("MX lookup skipped for {}: {}", domain, e.toString());
            return true;
        }
    }

    private static ApiException bad(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_EMAIL", message, java.util.Map.of("email", message));
    }
}
