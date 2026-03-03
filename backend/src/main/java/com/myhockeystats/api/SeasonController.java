package com.myhockeystats.api;

import com.myhockeystats.model.Game;
import com.myhockeystats.model.Season;
import com.myhockeystats.model.PlayerProfile;
import com.myhockeystats.service.GameService;
import com.myhockeystats.service.SeasonService;
import com.myhockeystats.service.PlayerProfileService;
import com.myhockeystats.security.JwtUtil;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/seasons")
public class SeasonController {
    private final SeasonService seasonService;
    private final GameService gameService;
    private final PlayerProfileService playerProfileService;
    private final JwtUtil jwtUtil;

    public SeasonController(SeasonService seasonService, GameService gameService, 
                           PlayerProfileService playerProfileService, JwtUtil jwtUtil) {
        this.seasonService = seasonService;
        this.gameService = gameService;
        this.playerProfileService = playerProfileService;
        this.jwtUtil = jwtUtil;
    }

    record SeasonResponse(Long id, Integer yearStart, Integer yearEnd, String teamName, String clubName, Integer totalGames) {}
    record CreateSeasonRequest(Long playerProfileId, Integer yearStart, Integer yearEnd, String teamName, String clubName) {}

    @PostMapping
    public ResponseEntity<?> createSeason(@RequestBody CreateSeasonRequest req, @RequestHeader(value = "Authorization", required = false) String authHeader) {
        try {
            // Extract user from token
            String email = null;
            if (authHeader != null && authHeader.startsWith("Bearer ")) {
                String token = authHeader.replace("Bearer ", "");
                email = jwtUtil.extractEmail(token);
            }
            if (email == null) return ResponseEntity.status(401).body(Map.of("error", "Invalid or missing token"));

            // Verify that the requested player profile belongs to the authenticated user
            Optional<PlayerProfile> profile = playerProfileService.getProfileById(req.playerProfileId());
            if (profile.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "Player profile not found"));
            }
            
            // Verify ownership (optional security check)
            PlayerProfile playerProfile = profile.get();
            if (playerProfile.getUser() != null && !playerProfile.getUser().getEmail().equals(email)) {
                return ResponseEntity.status(403).body(Map.of("error", "Not authorized to create season for this player"));
            }
            
            Season season = seasonService.createSeason(req.playerProfileId(), req.yearStart(), req.yearEnd(), 
                                                       req.teamName(), req.clubName());
            return ResponseEntity.ok(new SeasonResponse(season.getId(), season.getYearStart(), season.getYearEnd(),
                                                       season.getTeamName(), season.getClubName(), season.getTotalGames()));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/{seasonId}")
    public ResponseEntity<?> getSeason(@PathVariable Long seasonId) {
        Optional<Season> season = seasonService.getSeasonById(seasonId);
        if (season.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        Season s = season.get();
        return ResponseEntity.ok(new SeasonResponse(s.getId(), s.getYearStart(), s.getYearEnd(), 
                                                     s.getTeamName(), s.getClubName(), s.getTotalGames()));
    }

    @GetMapping("/{seasonId}/games")
    public ResponseEntity<?> getGamesBySeason(@PathVariable Long seasonId) {
        List<Game> games = gameService.getGamesBySeason(seasonId);
        List<java.util.LinkedHashMap<String, Object>> response = games.stream()
            .map(g -> {
                java.util.LinkedHashMap<String, Object> map = new java.util.LinkedHashMap<>();
                map.put("gameId", g.getId());
                map.put("date", g.getDate());
                map.put("opponent", g.getOpponent());
                map.put("venue", g.getVenue() != null ? g.getVenue() : "");
                map.put("finalScore", g.getFinalScore() != null ? g.getFinalScore() : "");
                return map;
            })
            .collect(Collectors.toList());
        return ResponseEntity.ok(response);
    }
}