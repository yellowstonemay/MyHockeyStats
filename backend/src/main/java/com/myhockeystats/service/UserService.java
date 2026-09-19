package com.myhockeystats.service;

import com.myhockeystats.model.OAuthAccount;
import com.myhockeystats.model.User;
import com.myhockeystats.repository.OAuthAccountRepository;
import com.myhockeystats.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

@Service
public class UserService {

    private static final Logger log = LoggerFactory.getLogger(UserService.class);

    private final UserRepository userRepository;
    private final OAuthAccountRepository oauthAccountRepository;
    private final PasswordEncoder passwordEncoder;

    public UserService(UserRepository userRepository,
                       OAuthAccountRepository oauthAccountRepository,
                       PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.oauthAccountRepository = oauthAccountRepository;
        this.passwordEncoder = passwordEncoder;
    }

    // ── email / password ───────────────────────────────────────────────────

    /**
     * Create a password account. The address is NOT trusted yet: the account
     * starts unverified and cannot sign in until the emailed link is opened
     * (see {@link EmailVerificationService}).
     */
    public User register(String email, String rawPassword, String fullName) {
        if (userRepository.existsByEmail(email)) {
            throw new IllegalArgumentException("Email already registered");
        }
        User u = new User();
        u.setEmail(email);
        u.setFullName(fullName);
        u.setPassword(passwordEncoder.encode(rawPassword));
        u.setEmailVerified(false);
        return userRepository.save(u);
    }

    public Optional<User> findByEmail(String email) {
        return userRepository.findByEmail(email);
    }

    public Optional<User> findById(Long id) {
        return userRepository.findById(id);
    }

    public boolean verifyPassword(User user, String rawPassword) {
        if (user.getPassword() == null || rawPassword == null) {
            // Social-only account: there is no password to match against.
            return false;
        }
        return passwordEncoder.matches(rawPassword, user.getPassword());
    }

    public void touchLastLogin(User user) {
        user.setLastLoginAt(Instant.now());
        userRepository.save(user);
    }

    /**
     * Remove a just-created account. Used to undo a signup when the
     * verification email could not be delivered, so no unusable account is
     * left behind.
     */
    @Transactional
    public void deleteAccount(Long userId) {
        if (userId != null) {
            userRepository.deleteById(userId);
        }
    }

    // ── social login ───────────────────────────────────────────────────────

    /** What the caller should do after a provider sign-in. */
    public enum LinkOutcome {
        /** Account resolved (existing link, claimed unverified account, or new). */
        SIGNED_IN,
        /** A verified email/password account exists — ask for its password. */
        LINK_REQUIRES_CONFIRMATION,
        /** An account exists but ownership can't be proven or claimed. */
        LINK_NOT_POSSIBLE
    }

    public record ProviderLoginResult(LinkOutcome outcome, User user, String message) {}

    /**
     * Resolve the account behind a Google/Facebook sign-in.
     *
     * Resolution order:
     *  1. an existing OAuth account for this (provider, providerUserId)
     *  2. an account with the same email — handled carefully:
     *     - local account UNVERIFIED and the provider vouches for the address →
     *       the provider wins. This is the squatting case: an unverified row
     *       proves nothing, so it is handed over and its password cleared so the
     *       squatter cannot sign back in.
     *     - local account VERIFIED with a password → ask for that password
     *       before attaching; never link silently.
     *     - verified but social-only → tell them which provider to use.
     *  3. otherwise a brand-new account is created
     */
    @Transactional
    public ProviderLoginResult resolveProviderLogin(String provider, String providerUserId, String email,
                                                    String displayName, String avatarUrl,
                                                    boolean providerEmailVerified) {
        String normalizedProvider = provider == null ? "" : provider.toLowerCase();
        if (providerUserId == null || providerUserId.isBlank()) {
            throw new IllegalArgumentException("Provider did not return a user id");
        }

        // 1. Routine repeat sign-in.
        Optional<OAuthAccount> existingLink =
            oauthAccountRepository.findByProviderAndProviderUserId(normalizedProvider, providerUserId);
        if (existingLink.isPresent()) {
            OAuthAccount account = existingLink.get();
            User user = account.getUser();
            applyAccountProfile(account, email, displayName, avatarUrl);
            oauthAccountRepository.save(account);
            markVerifiedIfProviderDid(user, providerEmailVerified);
            user.setLastLoginAt(Instant.now());
            return new ProviderLoginResult(LinkOutcome.SIGNED_IN, userRepository.save(user), null);
        }

        // 2. Email already on file.
        Optional<User> existingUser = (email == null || email.isBlank())
            ? Optional.empty()
            : userRepository.findByEmail(email);
        if (existingUser.isPresent()) {
            User user = existingUser.get();

            if (!user.isEmailVerified() && providerEmailVerified) {
                // Nobody ever proved they own this address locally. The provider
                // just did, so the address belongs to them: attach the social
                // login, clear the unproven password, mark it verified.
                log.warn("Claiming unverified account {} for provider {} (address proven by provider)",
                    user.getId(), normalizedProvider);
                user.setPassword(null);
                user.setEmailVerified(true);
                user.setEmailVerifiedAt(Instant.now());
                attachAccount(user, normalizedProvider, providerUserId, email, displayName, avatarUrl);
                user.setLastLoginAt(Instant.now());
                return new ProviderLoginResult(LinkOutcome.SIGNED_IN, userRepository.save(user), null);
            }

            if (!user.isEmailVerified() || user.getPassword() == null || user.getPassword().isBlank()) {
                String otherProvider = oauthAccountRepository.findByUserId(user.getId()).stream()
                    .map(OAuthAccount::getProvider)
                    .filter(p -> !p.equals(normalizedProvider))
                    .findFirst()
                    .orElse(null);
                String message = otherProvider != null
                    ? "This email is already registered with " + capitalize(otherProvider)
                        + " sign-in. Please sign in with " + capitalize(otherProvider) + " instead."
                    : "This email is already registered but not confirmed. "
                        + "Please sign in with your password and confirm your email first.";
                return new ProviderLoginResult(LinkOutcome.LINK_NOT_POSSIBLE, user, message);
            }

            return new ProviderLoginResult(LinkOutcome.LINK_REQUIRES_CONFIRMATION, user, null);
        }

        // 3. Brand-new account.
        User created = new User();
        created.setEmail(email != null && !email.isBlank()
            ? email
            : normalizedProvider + "_" + providerUserId + "@users.noreply.local");
        created.setFullName(displayName != null && !displayName.isBlank() ? displayName : "Player");
        created.setPassword(null); // social-only account
        created.setEmailVerified(providerEmailVerified);
        created.setEmailVerifiedAt(providerEmailVerified ? Instant.now() : null);
        created.setLastLoginAt(Instant.now());
        created = userRepository.save(created);

        attachAccount(created, normalizedProvider, providerUserId, email, displayName, avatarUrl);
        return new ProviderLoginResult(LinkOutcome.SIGNED_IN, created, null);
    }

    /**
     * Attach a provider account to an existing user AFTER ownership has been
     * proven (password check). See {@link #resolveProviderLogin}.
     */
    @Transactional
    public User completeProviderLink(User user, String provider, String providerUserId, String email,
                                     String displayName, String avatarUrl, boolean providerEmailVerified) {
        attachAccount(user, provider == null ? "" : provider.toLowerCase(), providerUserId,
            email, displayName, avatarUrl);
        markVerifiedIfProviderDid(user, providerEmailVerified);
        user.setLastLoginAt(Instant.now());
        return userRepository.save(user);
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private void attachAccount(User user, String provider, String providerUserId, String email,
                               String displayName, String avatarUrl) {
        OAuthAccount account = oauthAccountRepository
            .findByUserIdAndProvider(user.getId(), provider)
            .orElseGet(OAuthAccount::new);
        account.setUser(user);
        account.setProvider(provider);
        account.setProviderUserId(providerUserId);
        applyAccountProfile(account, email, displayName, avatarUrl);
        oauthAccountRepository.save(account);

        if ((user.getFullName() == null || user.getFullName().isBlank())
            && displayName != null && !displayName.isBlank()) {
            user.setFullName(displayName);
        }
    }

    private void markVerifiedIfProviderDid(User user, boolean providerEmailVerified) {
        if (providerEmailVerified && !user.isEmailVerified()) {
            user.setEmailVerified(true);
            user.setEmailVerifiedAt(Instant.now());
        }
    }

    private void applyAccountProfile(OAuthAccount account, String email, String displayName, String avatarUrl) {
        if (email != null && !email.isBlank()) account.setEmail(email);
        if (displayName != null && !displayName.isBlank()) account.setDisplayName(displayName);
        if (avatarUrl != null && !avatarUrl.isBlank()) account.setAvatarUrl(avatarUrl);
        account.setUpdatedAt(Instant.now());
    }

    private static String capitalize(String value) {
        if (value == null || value.isBlank()) return value;
        return Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }
}
