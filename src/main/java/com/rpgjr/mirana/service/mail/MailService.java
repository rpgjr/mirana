package com.rpgjr.mirana.service.mail;

import com.rpgjr.mirana.config.AuthProperties;
import com.rpgjr.mirana.enums.OtpPurpose;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
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
        String intro;
        switch (purpose) {
            case VERIFY_EMAIL -> {
                subject = "Confirm your email for Mirana";
                intro = "Use this code to finish creating your Mirana account.";
            }
            case RESET_PASSWORD -> {
                subject = "Reset your Mirana password";
                intro = "Use this code to set a new password.";
            }
            default -> {
                subject = "Approve this device for Mirana";
                intro = "Someone signed in from a new browser. Use this code to approve it.";
            }
        }
        send(to, subject, intro + "\n\nYour code: " + code
                + "\n\nIt expires in 10 minutes. If this was not you, ignore this email and your account stays safe.");
    }

    @Async
    public void sendAccountExists(String to) {
        send(to, "You already have a Mirana account",
                "Someone tried to sign up with this email, but an account already exists.\n\nSign in at "
                        + props.appUrl() + "/login. If you forgot your password, use Forgot password on that page.\n\n"
                        + "If this was not you, ignore this email.");
    }

    private void send(String to, String subject, String body) {
        try {
            SimpleMailMessage m = new SimpleMailMessage();
            m.setFrom(props.mailFrom());
            m.setTo(to);
            m.setSubject(subject);
            m.setText(body);
            sender.send(m);
        } catch (Exception e) {
            log.error("Could not send '{}' mail: {}", subject, e.toString());
        }
    }
}
