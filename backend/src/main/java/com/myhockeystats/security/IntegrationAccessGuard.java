package com.myhockeystats.security;

import java.util.Collection;
import java.util.Objects;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

@Component
public class IntegrationAccessGuard {

    public boolean canAccessOwnPlayerData(Long requestedUserId) {
        Long authenticatedUserId = getAuthenticatedUserId();
        return authenticatedUserId != null && Objects.equals(authenticatedUserId, requestedUserId);
    }

    public boolean canAccessLinkedPlayerData(Long playerUserId, Long linkedPlayerUserId) {
        if (hasRole("ADMIN")) {
            return true;
        }
        Long authenticatedUserId = getAuthenticatedUserId();
        return authenticatedUserId != null
                && Objects.equals(authenticatedUserId, playerUserId)
                || (authenticatedUserId != null && Objects.equals(linkedPlayerUserId, playerUserId));
    }

    public boolean canAccessLinkedPlayerData(
            Long authenticatedUserId,
            Long playerUserId,
            Long linkedPlayerUserId,
            boolean isAdmin) {
        if (isAdmin) {
            return true;
        }
        if (authenticatedUserId == null || playerUserId == null) {
            return false;
        }
        return Objects.equals(authenticatedUserId, playerUserId)
                || (linkedPlayerUserId != null && Objects.equals(linkedPlayerUserId, playerUserId));
    }

    public boolean canTriggerImportRun() {
        return hasRole("ADMIN") || hasRole("OPERATOR");
    }

    public boolean hasRole(String role) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null) {
            return false;
        }
        Collection<? extends GrantedAuthority> authorities = authentication.getAuthorities();
        if (authorities == null) {
            return false;
        }
        String wanted = role.startsWith("ROLE_") ? role : "ROLE_" + role;
        return authorities.stream().map(GrantedAuthority::getAuthority).anyMatch(wanted::equals);
    }

    private Long getAuthenticatedUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null) {
            return null;
        }
        Object principal = authentication.getPrincipal();
        if (principal instanceof String principalText) {
            try {
                return Long.parseLong(principalText);
            } catch (NumberFormatException ex) {
                return null;
            }
        }
        return null;
    }
}
