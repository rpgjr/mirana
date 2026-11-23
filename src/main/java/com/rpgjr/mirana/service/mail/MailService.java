package com.rpgjr.mirana.service.mail;

import com.rpgjr.mirana.config.AuthProperties;
import com.rpgjr.mirana.enums.OtpPurpose;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import jakarta.mail.internet.MimeMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Service
public class MailService {

    private static final Logger log = LoggerFactory.getLogger(MailService.class);

    private final JavaMailSender sender;
    private final AuthProperties props;

    public MailService(JavaMailSender sender, AuthProperties props) {
        this.sender = sender;
        this.props = props;
    }

    @Async
    public void sendOtp(String to, OtpPurpose purpose, String code) {
        if (props.logOtp()) {
            log.warn("DEV OTP for {} ({}): {}", to, purpose, code);
        }
        String subject;
        String heading;
        String intro;
        switch (purpose) {
            case VERIFY_EMAIL -> {
                subject = "Confirm your email for Mirana";
                heading = "Confirm your email";
                intro = "Use this code to finish creating your Mirana account.";
            }
            case RESET_PASSWORD -> {
                subject = "Reset your Mirana password";
                heading = "Reset your password";
                intro = "Use this code to set a new password.";
            }
            default -> {
                subject = "Approve this device for Mirana";
                heading = "Approve this device";
                intro = "Someone signed in from a new browser. Use this code to approve it.";
            }
        }
        String note = "The code expires in 10 minutes. Never share it with anyone. Mirana will never ask for it.";
        String text = intro + "\n\nYour code: " + code
                + "\n\nIt expires in 10 minutes. If this was not you, ignore this email and your account stays safe.";
        send(to, subject, text, MailTemplate.render(intro + " Code expires in 10 minutes.", heading, intro, code, note, null, null));
    }

    @Async
    public void sendAccountExists(String to) {
        String url = props.appUrl() + "/login";
        String intro = "Someone tried to sign up with this email, but an account already exists.";
        String note = "Forgot your password? Use Forgot password on the sign in page. If this was not you, ignore this email.";
        send(to, "You already have a Mirana account",
                intro + "\n\nSign in at " + url + ". If you forgot your password, use Forgot password on that page.\n\n"
                        + "If this was not you, ignore this email.",
                MailTemplate.render(intro, "You already have an account", intro, null, note, "Sign in to Mirana", url));
    }

    private void send(String to, String subject, String text, String html) {
        try {
            MimeMessage m = sender.createMimeMessage();
            MimeMessageHelper h = new MimeMessageHelper(m, true, "UTF-8");
            h.setFrom(props.mailFrom());
            h.setTo(to);
            h.setSubject(subject);
            h.setText(text, html);
            sender.send(m);
        } catch (Exception e) {
            Throwable root = e;
            while (root.getCause() != null && root.getCause() != root) {
                root = root.getCause();
            }
            log.error("Could not send '{}' mail: {} (cause: {})", subject, e.toString(), root.toString());
        }
    }
}
