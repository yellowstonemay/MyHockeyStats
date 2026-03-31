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

    /**
     * Check if user can view a player's seasons data.
     * Authorization: User is the player themselves, OR user is a linked parent.
     * 
     * @param userId current authenticated user ID (as String UUID)
     * @param playerId player ID to check (as String UUID)
     * @return true if user is authorized to view the player's seasons
     */
    public boolean canViewSeasons(String userId, String playerId) {
        if (userId == null || playerId == null) {
            return false;
        }
        
        // Case 1: Player viewing their own data
        if (userId.equals(playerId)) {
            return true;
        }
        
        // Case 2: Parent viewing linked child's data (via AccountLink)
        // Note: AccountLink checking requires repository injection
        // For now, check via method that should be implemented in AccountLinkService
        return canAccessLinkedChild(userId, playerId);
    }
    
    /**
     * Check if user (parent) can access child player data via account link.
     * This method delegates to AccountLinkService which queries the AccountLink table.
     * 
     * @param parentUserIdString parent user ID
     * @param childPlayerIdString child player ID  
     * @return true if account link exists and parent is linked to child
     */
    private boolean canAccessLinkedChild(String parentUserIdString, String childPlayerIdString) {
        // This will be properly implemented when AccountLinkService/Repository is available
        // For now, return false - to be fully implemented in integration with account linking system
        // TODO: Inject AccountLinkRepository and check:
        // return accountLinkRepository.findByParentIdAndChildId(parentUserIdString, childPlayerIdString).isPresent();
        return false;
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
