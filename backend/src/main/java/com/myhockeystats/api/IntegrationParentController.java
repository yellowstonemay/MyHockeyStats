package com.myhockeystats.api;

import com.myhockeystats.api.dto.integration.IntegrationDtos.UnifiedHistoryDto;
import com.myhockeystats.model.User;
import com.myhockeystats.security.IntegrationAccessGuard;
import com.myhockeystats.security.JwtUtil;
import com.myhockeystats.service.UserService;
import com.myhockeystats.service.integration.ParentHistoryService;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/integrations/players")
public class IntegrationParentController {

    private final ParentHistoryService parentHistoryService;
    private final UserService userService;
    private final JwtUtil jwtUtil;
    private final IntegrationAccessGuard integrationAccessGuard;

    public IntegrationParentController(
            ParentHistoryService parentHistoryService,
            UserService userService,
            JwtUtil jwtUtil,
            IntegrationAccessGuard integrationAccessGuard) {
        this.parentHistoryService = parentHistoryService;
        this.userService = userService;
        this.jwtUtil = jwtUtil;
        this.integrationAccessGuard = integrationAccessGuard;
    }

    @GetMapping("/{playerUserId}/history")
    public ResponseEntity<?> getParentHistory(
            @PathVariable Long playerUserId,
            @RequestParam(value = "season", required = false) String season,
            @RequestParam(value = "linkedPlayerUserId", required = false) Long linkedPlayerUserId,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        User user = resolveUser(authHeader);
        if (user == null) {
            return ResponseEntity.status(401).body(Map.of("error", "Invalid token"));
        }

        boolean allowed = integrationAccessGuard.canAccessLinkedPlayerData(
                user.getId(),
                playerUserId,
                linkedPlayerUserId,
                integrationAccessGuard.hasRole("ADMIN"));
        if (!allowed) {
            return ResponseEntity.status(403).body(Map.of("error", "Forbidden"));
        }

        UnifiedHistoryDto response = parentHistoryService.getChildHistory(playerUserId, season);
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
