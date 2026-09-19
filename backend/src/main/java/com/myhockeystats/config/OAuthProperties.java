package com.myhockeystats.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Social-login configuration (Google / Facebook).
 *
 * Client ids/secrets are supplied through environment variables so they never
 * live in the repository:
 *
 *   GOOGLE_CLIENT_ID / GOOGLE_CLIENT_SECRET / GOOGLE_REDIRECT_URI
 *   FACEBOOK_CLIENT_ID / FACEBOOK_CLIENT_SECRET / FACEBOOK_REDIRECT_URI
 *
 * A provider is only offered to the UI when it has a client id + secret.
 */
@ConfigurationProperties(prefix = "app.oauth")
public class OAuthProperties {

    /** Where the browser is sent once the backend has authenticated the user. */
    private String frontendCallbackUrl = "http://localhost:5173/oauth/callback";

    /** Provider key -> credentials. Keys are "google" and "facebook". */
    private Map<String, Provider> providers = new LinkedHashMap<>();

    public String getFrontendCallbackUrl() { return frontendCallbackUrl; }
    public void setFrontendCallbackUrl(String frontendCallbackUrl) {
        this.frontendCallbackUrl = frontendCallbackUrl;
    }

    public Map<String, Provider> getProviders() { return providers; }
    public void setProviders(Map<String, Provider> providers) { this.providers = providers; }

    public Provider provider(String id) {
        return providers.get(id == null ? "" : id.toLowerCase());
    }

    public static class Provider {
        private String clientId;
        private String clientSecret;
        private String redirectUri;

        public boolean isConfigured() {
            return clientId != null && !clientId.isBlank()
                && clientSecret != null && !clientSecret.isBlank()
                && redirectUri != null && !redirectUri.isBlank();
        }

        public String getClientId() { return clientId; }
        public void setClientId(String clientId) { this.clientId = clientId; }

        public String getClientSecret() { return clientSecret; }
        public void setClientSecret(String clientSecret) { this.clientSecret = clientSecret; }

        public String getRedirectUri() { return redirectUri; }
        public void setRedirectUri(String redirectUri) { this.redirectUri = redirectUri; }
    }
}
