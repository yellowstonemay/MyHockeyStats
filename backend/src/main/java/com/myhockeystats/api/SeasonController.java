package com.myhockeystats.api;

import com.myhockeystats.model.Game;
import com.myhockeystats.model.Season;
import com.myhockeystats.service.GameService;
import com.myhockeystats.service.SeasonService;
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

    public SeasonController(SeasonService seasonService, GameService gameService) {
        this.seasonService = seasonService;
        this.gameService = gameService;
    }

    record SeasonResponse(Long id, Integer yearStart, Integer yearEnd, String teamName, String clubName, Integer totalGames) {}

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
        List<Map<String, Object>> response = games.stream()
            .map(g -> Map.of(
                "gameId", g.getId(),
                "date", g.getDate(),
                "opponent", g.getOpponent(),
                "venue", g.getVenue() != null ? g.getVenue() : "",
                "finalScore", g.getFinalScore() != null ? g.getFinalScore() : ""
            ))
            .collect(Collectors.toList());
        return ResponseEntity.ok(response);
    }
}