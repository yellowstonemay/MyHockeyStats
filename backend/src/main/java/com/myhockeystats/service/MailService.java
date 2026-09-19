package com.myhockeystats.service;

import com.myhockeystats.config.AuthProperties;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

/**
 * Outgoing transactional email (address confirmation, password reset).
 *
 * The mail sender only exists when SMTP env vars are present, so it is injected
 * through an {@link ObjectProvider} — the app still starts (and reports mail as
 * unavailable) when no provider is configured.
 */
@Service
public class MailService {

    private static final Logger log = LoggerFactory.getLogger(MailService.class);

    private final ObjectProvider<JavaMailSender> mailSenderProvider;
    private final AuthProperties authProperties;

    /**
     * Presence of the host property (not just the bean) decides whether mail is
     * usable: Spring creates a JavaMailSender even when the host is an empty
     * string, so the bean alone is not a reliable signal.
     */
    @Value("${spring.mail.host:}")
    private String mailHost;

    public MailService(ObjectProvider<JavaMailSender> mailSenderProvider, AuthProperties authProperties) {
        this.mailSenderProvider = mailSenderProvider;
        this.authProperties = authProperties;
    }

    public boolean isConfigured() {
        return mailHost != null && !mailHost.isBlank()
            && mailSenderProvider.getIfAvailable() != null;
    }

    /** "Confirm your email address" message for a new signup. */
    public boolean sendVerificationEmail(String toEmail, String displayName, String verifyUrl) {
        String greeting = greeting(displayName);
        String plain = greeting + ",\n\n"
            + "Confirm this email address to finish setting up your MyHockeyStats account:\n\n"
            + verifyUrl + "\n\n"
            + "The link expires in " + authProperties.getVerificationTtlHours() + " hours.\n"
            + "If you didn't create this account you can ignore this message.\n";

        String html = """
            <div style="font-family:-apple-system,Segoe UI,Roboto,Helvetica,Arial,sans-serif;max-width:520px;margin:0 auto;color:#0f172a">
              <h2 style="margin:0 0 8px;font-size:20px">Confirm your email</h2>
              <p style="margin:0 0 20px;color:#475569">%s, confirm this address to finish setting up your
                 MyHockeyStats account.</p>
              <p style="margin:0 0 24px">
                <a href="%s" style="display:inline-block;background:#1d4ed8;color:#fff;text-decoration:none;
                   padding:12px 20px;border-radius:6px;font-weight:600">Confirm my email</a>
              </p>
              <p style="margin:0 0 8px;color:#475569;font-size:13px">Or paste this link into your browser:</p>
              <p style="margin:0 0 20px;font-size:13px;word-break:break-all"><a href="%s" style="color:#1d4ed8">%s</a></p>
              <p style="margin:0;color:#94a3b8;font-size:12px">The link expires in %d hours. If you didn't create
                 this account, you can ignore this message.</p>
            </div>
            """.formatted(greeting, verifyUrl, verifyUrl, verifyUrl,
                          authProperties.getVerificationTtlHours());

        return deliver(toEmail, "Confirm your MyHockeyStats email", plain, html);
    }

    /** "Reset your password" message. */
    public boolean sendPasswordResetEmail(String toEmail, String displayName, String resetUrl) {
        String greeting = greeting(displayName);
        String plain = greeting + ",\n\n"
            + "Someone asked to reset the password for this MyHockeyStats account. "
            + "Choose a new password here:\n\n"
            + resetUrl + "\n\n"
            + "The link expires in " + authProperties.getResetTtlMinutes() + " minutes and can be used once.\n"
            + "If this wasn't you, you can ignore this message — your password has not changed.\n";

        String html = """
            <div style="font-family:-apple-system,Segoe UI,Roboto,Helvetica,Arial,sans-serif;max-width:520px;margin:0 auto;color:#0f172a">
              <h2 style="margin:0 0 8px;font-size:20px">Reset your password</h2>
              <p style="margin:0 0 20px;color:#475569">%s, someone asked to reset the password for this
                 MyHockeyStats account. Choose a new one below.</p>
              <p style="margin:0 0 24px">
                <a href="%s" style="display:inline-block;background:#1d4ed8;color:#fff;text-decoration:none;
                   padding:12px 20px;border-radius:6px;font-weight:600">Choose a new password</a>
              </p>
              <p style="margin:0 0 8px;color:#475569;font-size:13px">Or paste this link into your browser:</p>
              <p style="margin:0 0 20px;font-size:13px;word-break:break-all"><a href="%s" style="color:#1d4ed8">%s</a></p>
              <p style="margin:0;color:#94a3b8;font-size:12px">The link expires in %d minutes and can only be used
                 once. If this wasn't you, ignore this message — your password has not changed.</p>
            </div>
            """.formatted(greeting, resetUrl, resetUrl, resetUrl,
                          authProperties.getResetTtlMinutes());

        return deliver(toEmail, "Reset your MyHockeyStats password", plain, html);
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private boolean deliver(String toEmail, String subject, String plainBody, String htmlBody) {
        JavaMailSender sender = mailSenderProvider.getIfAvailable();
        if (sender == null) {
            log.warn("Mail is not configured (set SPRING_MAIL_HOST/USERNAME/PASSWORD) — "
                + "cannot send '{}' to {}", subject, mask(toEmail));
            return false;
        }
        if (toEmail == null || toEmail.isBlank()) {
            log.warn("Cannot send '{}': no recipient address", subject);
            return false;
        }

        try {
            MimeMessage message = sender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setFrom(authProperties.getMailFrom());
            helper.setTo(toEmail);
            helper.setSubject(subject);
            helper.setText(plainBody, htmlBody);
            sender.send(message);
            log.info("Sent '{}' to {}", subject, mask(toEmail));
            return true;
        } catch (Exception e) {
            log.error("Failed to send '{}' to {}: {}", subject, mask(toEmail), e.getMessage());
            return false;
        }
    }

    private static String greeting(String displayName) {
        if (displayName == null || displayName.isBlank()) {
            return "Hi";
        }
        return "Hi " + displayName.trim().split("\\s+")[0];
    }

    /** Never write a full address to the logs. */
    private static String mask(String email) {
        if (email == null || email.isBlank()) {
            return "(none)";
        }
        int at = email.indexOf('@');
        if (at <= 1) {
            return "***" + (at < 0 ? "" : email.substring(at));
        }
        return email.charAt(0) + "***" + email.substring(at);
    }
}
