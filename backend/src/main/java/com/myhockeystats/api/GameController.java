package com.myhockeystats.api;

import com.myhockeystats.model.Game;
import com.myhockeystats.service.GameService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/games")
public class GameController {
    private final GameService gameService;

    public GameController(GameService gameService) {
        this.gameService = gameService;
    }

    record GameResponse(Long id, String date, String opponent, String venue, String finalScore) {}

    @GetMapping("/{gameId}")
    public ResponseEntity<?> getGame(@PathVariable Long gameId) {
        Optional<Game> game = gameService.getGameById(gameId);
        if (game.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        Game g = game.get();
        return ResponseEntity.ok(new GameResponse(g.getId(), g.getDate().toString(), g.getOpponent(), 
                                                   g.getVenue(), g.getFinalScore()));
    }
}