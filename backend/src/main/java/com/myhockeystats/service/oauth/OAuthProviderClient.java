package com.myhockeystats.service.oauth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.myhockeystats.config.OAuthProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Minimal, dependency-free OAuth 2.0 authorization-code client for Google and
 * Facebook.
 *
 * Keeping this in-house (rather than spring-boot-starter-oauth2-client) means
 * the SPA handoff stays stateless: the backend exchanges the code, resolves the
 * profile, and returns a normal MyHockeyStats JWT — no server session needed.
 */
@Component
public class OAuthProviderClient {

    private static final Logger log = LoggerFactory.getLogger(OAuthProviderClient.class);

    /** Normalised profile returned by every provider. */
    public record SocialProfile(String provider, String providerUserId, String email,
                                String displayName, String avatarUrl, boolean emailVerified) {}

    private final OAuthProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public OAuthProviderClient(OAuthProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    }

    public boolean isConfigured(String providerId) {
        OAuthProperties.Provider provider = properties.provider(providerId);
        return provider != null && provider.isConfigured();
    }

    /** Where to send the browser to start the consent flow. */
    public String authorizationUrl(String providerId, String state) {
        OAuthProperties.Provider provider = require(providerId);
        String id = providerId.toLowerCase();

        Map<String, String> params = new LinkedHashMap<>();
        params.put("client_id", provider.getClientId());
        params.put("redirect_uri", provider.getRedirectUri());
        params.put("response_type", "code");
        params.put("state", state);

        String authorizeEndpoint;
        if ("google".equals(id)) {
            authorizeEndpoint = "https://accounts.google.com/o/oauth2/v2/auth";
            params.put("scope", "openid email profile");
            params.put("access_type", "online");
            params.put("prompt", "select_account");
        } else if ("facebook".equals(id)) {
            authorizeEndpoint = "https://www.facebook.com/v19.0/dialog/oauth";
            params.put("scope", "email,public_profile");
        } else {
            throw new IllegalArgumentException("Unsupported provider: " + providerId);
        }
        return authorizeEndpoint + "?" + encode(params);
    }

    /** Exchange the authorization code and fetch the provider profile. */
    public SocialProfile exchangeCode(String providerId, String code) {
        OAuthProperties.Provider provider = require(providerId);
        String id = providerId.toLowerCase();

        if ("google".equals(id)) {
            return resolveGoogleProfile(exchangeCodeGoogle(provider, code));
        }
        return fetchProfileFacebook(exchangeCodeFacebook(provider, code));
    }

    // ── Google ─────────────────────────────────────────────────────────────

    /** Access token plus the OIDC id_token (a JWT carrying the profile). */
    private record GoogleTokens(String accessToken, String idToken) {}

    private GoogleTokens exchangeCodeGoogle(OAuthProperties.Provider provider, String code) {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("code", code);
        form.put("client_id", provider.getClientId());
        form.put("client_secret", provider.getClientSecret());
        form.put("redirect_uri", provider.getRedirectUri());
        form.put("grant_type", "authorization_code");

        JsonNode json = postForm("https://oauth2.googleapis.com/token", form);
        String accessToken = json.path("access_token").asText(null);
        if (accessToken == null || accessToken.isBlank()) {
            throw new IllegalStateException("Google did not return an access token");
        }
        return new GoogleTokens(accessToken, json.path("id_token").asText(null));
    }

    /**
     * Read the profile from the id_token.
     *
     * The id_token arrives in the response to our own TLS-authenticated call to
     * Google's token endpoint, so its claims can be trusted as-is; parsing it
     * locally avoids a second request to www.googleapis.com. That host was
     * unreachable from this network (SSLHandshakeException) while
     * oauth2.googleapis.com worked fine, so not depending on it matters.
     */
    private SocialProfile resolveGoogleProfile(GoogleTokens tokens) {
        if (tokens.idToken() != null && !tokens.idToken().isBlank()) {
            SocialProfile fromToken = parseGoogleIdToken(tokens.idToken());
            if (fromToken != null) {
                return fromToken;
            }
        }
        // No id_token (unexpected: we request the "openid" scope) — fall back to
        // the userinfo endpoint and hope that host is reachable.
        log.warn("Google returned no usable id_token; falling back to the userinfo endpoint");
        return fetchProfileGoogleUserInfo(tokens.accessToken());
    }

    private SocialProfile parseGoogleIdToken(String idToken) {
        try {
            String[] parts = idToken.split("\\.");
            if (parts.length < 2) {
                return null;
            }
            byte[] payload = Base64.getUrlDecoder().decode(parts[1]);
            JsonNode json = objectMapper.readTree(payload);
            return new SocialProfile(
                "google",
                json.path("sub").asText(null),
                json.path("email").asText(null),
                json.path("name").asText(null),
                json.path("picture").asText(null),
                json.path("email_verified").asBoolean(false));
        } catch (Exception e) {
            log.warn("Could not parse the Google id_token: {}", e.getMessage());
            return null;
        }
    }

    private SocialProfile fetchProfileGoogleUserInfo(String accessToken) {
        JsonNode json = getJson("https://www.googleapis.com/oauth2/v3/userinfo", accessToken);
        return new SocialProfile(
            "google",
            json.path("sub").asText(null),
            json.path("email").asText(null),
            json.path("name").asText(null),
            json.path("picture").asText(null),
            json.path("email_verified").asBoolean(false));
    }

    // ── Facebook ───────────────────────────────────────────────────────────

    private String exchangeCodeFacebook(OAuthProperties.Provider provider, String code) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("client_id", provider.getClientId());
        params.put("client_secret", provider.getClientSecret());
        params.put("redirect_uri", provider.getRedirectUri());
        params.put("code", code);

        JsonNode json = getJson("https://graph.facebook.com/v19.0/oauth/access_token?"
            + encode(params), null);
        String token = json.path("access_token").asText(null);
        if (token == null || token.isBlank()) {
            throw new IllegalStateException("Facebook did not return an access token");
        }
        return token;
    }

    private SocialProfile fetchProfileFacebook(String accessToken) {
        String url = "https://graph.facebook.com/me?fields=id,name,email,picture.type(large)"
            + "&access_token=" + urlEncode(accessToken);
        JsonNode json = getJson(url, null);
        JsonNode picture = json.path("picture").path("data").path("url");
        String email = json.path("email").asText(null);
        // Facebook only returns an address it has already verified, but it can
        // omit the field entirely when the user has no confirmed email.
        return new SocialProfile(
            "facebook",
            json.path("id").asText(null),
            email,
            json.path("name").asText(null),
            picture.isMissingNode() ? null : picture.asText(null),
            email != null && !email.isBlank());
    }

    // ── plumbing ───────────────────────────────────────────────────────────

    private OAuthProperties.Provider require(String providerId) {
        OAuthProperties.Provider provider = properties.provider(providerId);
        if (provider == null) {
            throw new IllegalArgumentException("Unsupported provider: " + providerId);
        }
        if (!provider.isConfigured()) {
            throw new IllegalStateException(
                "Provider '" + providerId + "' is not configured. Set its client id/secret env vars.");
        }
        return provider;
    }

    private JsonNode postForm(String url, Map<String, String> form) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(20))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header("Accept", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(encode(form)))
            .build();
        return send(request, url);
    }

    private JsonNode getJson(String url, String bearerToken) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(20))
            .header("Accept", "application/json")
            .GET();
        if (bearerToken != null) {
            builder.header("Authorization", "Bearer " + bearerToken);
        }
        return send(builder.build(), url);
    }

    private JsonNode send(HttpRequest request, String url) {
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                throw new IllegalStateException(
                    "OAuth provider call failed (" + response.statusCode() + "): " + response.body());
            }
            return objectMapper.readTree(response.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("OAuth provider call interrupted: " + url, e);
        } catch (Exception e) {
            throw new IllegalStateException("OAuth provider call failed: " + url + " (" + e.getMessage() + ")", e);
        }
    }

    private static String encode(Map<String, String> params) {
        return params.entrySet().stream()
            .map(e -> urlEncode(e.getKey()) + "=" + urlEncode(e.getValue() == null ? "" : e.getValue()))
            .collect(Collectors.joining("&"));
    }

    private static String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
