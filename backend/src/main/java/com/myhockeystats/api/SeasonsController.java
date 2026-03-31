package com.myhockeystats.api;

import com.myhockeystats.api.dto.integration.IntegrationDtos;
import com.myhockeystats.model.PlayerProfile;
import com.myhockeystats.security.IntegrationAccessGuard;
import com.myhockeystats.service.PlayerProfileService;
import com.myhockeystats.service.integration.CareerLookupService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * REST Controller for player career seasons lookup.
 * 
 * Simplified endpoint that returns all matching career records by player name
 * from all sources (AYHL, THF, AHF).
 */
@RestController
@RequestMapping("/api/players/{playerId}/seasons")
@Slf4j
public class SeasonsController {
    
    @Autowired
    private PlayerProfileService playerProfileService;
    
    @Autowired
    private CareerLookupService careerLookupService;
    
    @Autowired
    private IntegrationAccessGuard accessGuard;
    
    /**
     * GET /api/players/{playerId}/seasons
     * 
     * Returns all career records for a player from all sources (AYHL, THF, AHF)
     * matched by normalized player name. Results include all source records
     * with source attribution. No filtering or consolidation.
     * 
     * Authorization: Authenticated user must be the player or a linked parent.
     * Caching: HTTP Cache-Control: private, max-age=300 (5 minutes browser cache)
     * 
     * @param playerId UUID of player whose seasons to retrieve
     * @return 200 OK with SeasonsResponseDto, or 401/404/500 error
     */
    @GetMapping
    public ResponseEntity<?> getPlayerSeasons(@PathVariable UUID playerId) {
        try {
            // Get current user ID from security context
            String userId = getCurrentUserId();
            if (userId == null) {
                log.warn("Seasons endpoint called without authentication for playerId={}", playerId);
                return ResponseEntity.status(401)
                    .body(new ErrorResponse("UNAUTHORIZED", "Authentication required."));
            }
            
            // Check authorization
            if (!accessGuard.canViewSeasons(userId, playerId.toString())) {
                log.warn("Unauthorized seasons access attempt: userId={}, playerId={}", userId, playerId);
                return ResponseEntity.status(401)
                    .body(new ErrorResponse("UNAUTHORIZED", 
                        "You do not have permission to view this player's seasons."));
            }
            
            // Get player profile
            Optional<PlayerProfile> playerOpt = playerProfileService.getPlayerProfile(playerId.toString());
            if (playerOpt.isEmpty()) {
                log.info("Player not found for playerId={}", playerId);
                return ResponseEntity.status(404)
                    .body(new ErrorResponse("PLAYER_NOT_FOUND", "Player not found."));
            }
            
            PlayerProfile player = playerOpt.get();
            log.info("Seasons lookup: userId={}, playerId={}, playerName={}", 
                userId, playerId, player.getFullName());
            
            // Lookup career records
            try {
                IntegrationDtos.SeasonsResponseDto response = careerLookupService
                    .lookupCareerRecordsByName(player.getFullName());
                
                // Set player info in response
                response = new IntegrationDtos.SeasonsResponseDto(
                    playerId.toString(),
                    player.getFullName(),
                    response.records(),
                    response.hasAmbiguity(),
                    response.ambiguityNote(),
                    response.availableSources(),
                    response.emptySources(),
                    response.fetchedAt(),
                    response.cacheControl()
                );
                
                log.info("Seasons lookup succeeded: playerId={}, recordCount={}", 
                    playerId, response.records().size());
                
                // Return response with 5-minute cache-control header
                return ResponseEntity.ok()
                    .cacheControl(CacheControl.maxAge(300, TimeUnit.SECONDS).cachePrivate())
                    .body(response);
                    
            } catch (Exception e) {
                log.error("Career lookup failed: playerId={}, error={}", playerId, e.getMessage(), e);
                
                if (isDatabaseTimeoutException(e)) {
                    return ResponseEntity.status(504)
                        .body(new ErrorResponse("CAREER_LOOKUP_TIMEOUT", 
                            "Career data lookup timed out. Please try again."));
                } else {
                    return ResponseEntity.status(500)
                        .body(new ErrorResponse("CAREER_LOOKUP_ERROR", 
                            "Failed to retrieve career data. Please try again."));
                }
            }
            
        } catch (Exception e) {
            log.error("Unexpected error in seasons endpoint: error={}", e.getMessage(), e);
            return ResponseEntity.status(500)
                .body(new ErrorResponse("INTERNAL_ERROR", 
                    "An unexpected error occurred. Please try again."));
        }
    }
    
    /**
     * Get current authenticated user ID from security context.
     */
    private String getCurrentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated()) {
            return auth.getName();  // Usually the user ID or username
        }
        return null;
    }
    
    /**
     * Check if exception is a database timeout.
     */
    private boolean isDatabaseTimeoutException(Exception e) {
        // Check exception type or message for timeout indicators
        return e instanceof java.util.concurrent.TimeoutException 
            || (e.getMessage() != null && e.getMessage().toLowerCase().contains("timeout"));
    }
    
    /**
     * Simple error response DTO.
     */
    public record ErrorResponse(String code, String message) {}
}
