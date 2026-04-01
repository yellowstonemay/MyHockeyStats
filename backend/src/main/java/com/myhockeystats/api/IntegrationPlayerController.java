package com.myhockeystats.api;

import com.myhockeystats.api.dto.integration.IntegrationDtos.ConfirmMatchRequest;
import com.myhockeystats.api.dto.integration.IntegrationDtos.ConfirmMatchResponse;
import com.myhockeystats.api.dto.integration.IntegrationDtos.MatchStatusDto;
import com.myhockeystats.api.dto.integration.IntegrationDtos.UnifiedHistoryDto;
import com.myhockeystats.model.User;
import com.myhockeystats.security.JwtUtil;
import com.myhockeystats.service.PlayerProfileService;
import com.myhockeystats.service.UserService;
import com.myhockeystats.service.integration.MatchConfirmationService;
import com.myhockeystats.service.integration.MatchStatusService;
import com.myhockeystats.service.integration.UnifiedHistoryService;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/integrations/me")
public class IntegrationPlayerController {

    private final MatchStatusService matchStatusService;
    private final MatchConfirmationService matchConfirmationService;
    private final UnifiedHistoryService unifiedHistoryService;
    private final UserService userService;
    private final PlayerProfileService playerProfileService;
    private final JwtUtil jwtUtil;

    public IntegrationPlayerController(
            MatchStatusService matchStatusService,
            MatchConfirmationService matchConfirmationService,
            UnifiedHistoryService unifiedHistoryService,
            UserService userService,
            PlayerProfileService playerProfileService,
            JwtUtil jwtUtil) {
        this.matchStatusService = matchStatusService;
        this.matchConfirmationService = matchConfirmationService;
        this.unifiedHistoryService = unifiedHistoryService;
        this.userService = userService;
        this.playerProfileService = playerProfileService;
        this.jwtUtil = jwtUtil;
    }

    @GetMapping("/match-status")
    public ResponseEntity<?> getMatchStatus(@RequestHeader(value = "Authorization", required = false) String authHeader) {
        User user = resolveUser(authHeader);
        if (user == null) {
            return ResponseEntity.status(401).body(Map.of("error", "Invalid token"));
        }

        var profile = playerProfileService.getProfileByUserId(user.getId());
        String fullName = profile.map(p -> p.getFullName() == null || p.getFullName().isBlank() ? user.getFullName() : p.getFullName())
            .orElseGet(() -> user.getFullName() == null || user.getFullName().isBlank() ? user.getEmail() : user.getFullName());
        int birthYear = profile.map(p -> p.getBirthdate().getYear()).orElse(0);
        int birthMonth = profile.map(p -> p.getBirthdate().getMonthValue()).orElse(0);
        MatchStatusDto status = matchStatusService.getStatusForUser(user.getId(), fullName, birthYear, birthMonth);
        return ResponseEntity.ok(status);
    }

    @PostMapping("/matches/confirm")
    public ResponseEntity<?> confirmMatches(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestBody ConfirmMatchRequest request) {
        User user = resolveUser(authHeader);
        if (user == null) {
            return ResponseEntity.status(401).body(Map.of("error", "Invalid token"));
        }
        if (request == null || request.selectedCandidates() == null || request.selectedCandidates().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "selectedCandidates is required"));
        }

        ConfirmMatchResponse response = matchConfirmationService.confirm(user.getId(), request.selectedCandidates());
        return ResponseEntity.ok(response);
    }

    @GetMapping("/history")
    public ResponseEntity<?> getHistory(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestParam(value = "season", required = false) String season) {
        User user = resolveUser(authHeader);
        if (user == null) {
            return ResponseEntity.status(401).body(Map.of("error", "Invalid token"));
        }

        UnifiedHistoryDto response = unifiedHistoryService.getUserHistory(user.getId(), season);
        return ResponseEntity.ok(response);
    }

    private User resolveUser(String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return null;
        }
        String token = authHeader.substring("Bearer ".length());
        String email = jwtUtil.extractEmail(token);
        if (email == null) {
            return null;
        }
        return userService.findByEmail(email).orElse(null);
    }
}
