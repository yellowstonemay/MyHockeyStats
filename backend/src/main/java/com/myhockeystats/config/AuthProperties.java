package com.myhockeystats.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Email-verification settings for password signup.
 *
 * SMTP itself is configured the standard Spring way (SPRING_MAIL_HOST,
 * SPRING_MAIL_PORT, SPRING_MAIL_USERNAME, SPRING_MAIL_PASSWORD) — deliberately
 * absent from application.yml so no mail sender is created when it is not set.
 */
@ConfigurationProperties(prefix = "app.auth")
public class AuthProperties {

    /** Refuse password sign-in until the emailed link has been opened. */
    private boolean requireEmailVerification = true;

    /** Public origin used to build the verification link. */
    private String publicBaseUrl = "https://youthhockeystats.us";

    /** From: header on outgoing mail. */
    private String mailFrom = "MyHockeyStats <no-reply@youthhockeystats.us>";

    /** How long a verification link stays valid. */
    private int verificationTtlHours = 24;

    /** How long a password-reset link stays valid (shorter: higher risk). */
    private int resetTtlMinutes = 60;

    /** Minimum gap between two verification emails for the same account. */
    private int resendCooldownSeconds = 60;

    public boolean isRequireEmailVerification() { return requireEmailVerification; }
    public void setRequireEmailVerification(boolean requireEmailVerification) {
        this.requireEmailVerification = requireEmailVerification;
    }

    public String getPublicBaseUrl() { return publicBaseUrl; }
    public void setPublicBaseUrl(String publicBaseUrl) { this.publicBaseUrl = publicBaseUrl; }

    public String getMailFrom() { return mailFrom; }
    public void setMailFrom(String mailFrom) { this.mailFrom = mailFrom; }

    public int getVerificationTtlHours() { return verificationTtlHours; }
    public void setVerificationTtlHours(int verificationTtlHours) {
        this.verificationTtlHours = verificationTtlHours;
    }

    public int getResetTtlMinutes() { return resetTtlMinutes; }
    public void setResetTtlMinutes(int resetTtlMinutes) { this.resetTtlMinutes = resetTtlMinutes; }

    public int getResendCooldownSeconds() { return resendCooldownSeconds; }
    public void setResendCooldownSeconds(int resendCooldownSeconds) {
        this.resendCooldownSeconds = resendCooldownSeconds;
    }
}
