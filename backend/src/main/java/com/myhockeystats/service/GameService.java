package com.myhockeystats.service;

import com.myhockeystats.model.Game;
import com.myhockeystats.model.Season;
import com.myhockeystats.repository.GameRepository;
import org.springframework.stereotype.Service;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Service
public class GameService {
    private final GameRepository gameRepository;

    public GameService(GameRepository gameRepository) {
        this.gameRepository = gameRepository;
    }

    public Game createGame(Season season, LocalDate date, String opponent, String venue, String finalScore) {
        Game game = new Game();
        game.setSeason(season);
        game.setDate(date);
        game.setOpponent(opponent);
        game.setVenue(venue);
        game.setFinalScore(finalScore);
        return gameRepository.save(game);
    }

    public List<Game> getGamesBySeason(Long seasonId) {
        return gameRepository.findBySeasonIdOrderByDateDesc(seasonId);
    }

    public Optional<Game> getGameById(Long gameId) {
        return gameRepository.findById(gameId);
    }
}