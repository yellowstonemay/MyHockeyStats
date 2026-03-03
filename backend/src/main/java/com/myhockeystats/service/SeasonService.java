package com.myhockeystats.service;

import com.myhockeystats.model.Season;
import com.myhockeystats.model.PlayerProfile;
import com.myhockeystats.repository.SeasonRepository;
import com.myhockeystats.repository.PlayerProfileRepository;
import org.springframework.stereotype.Service;
import java.util.List;
import java.util.Optional;

@Service
public class SeasonService {
    private final SeasonRepository seasonRepository;
    private final PlayerProfileRepository playerProfileRepository;

    public SeasonService(SeasonRepository seasonRepository, PlayerProfileRepository playerProfileRepository) {
        this.seasonRepository = seasonRepository;
        this.playerProfileRepository = playerProfileRepository;
    }

    public Season createSeason(com.myhockeystats.model.PlayerProfile playerProfile, 
                                Integer yearStart, Integer yearEnd, 
                                String teamName, String clubName) {
        Season season = new Season();
        season.setPlayerProfile(playerProfile);
        season.setYearStart(yearStart);
        season.setYearEnd(yearEnd);
        season.setTeamName(teamName);
        season.setClubName(clubName);
        season.setTotalGames(0);
        return seasonRepository.save(season);
    }

    public Season createSeason(Long playerProfileId, Integer yearStart, Integer yearEnd, 
                               String teamName, String clubName) {
        PlayerProfile profile = playerProfileRepository.findById(playerProfileId)
            .orElseThrow(() -> new IllegalArgumentException("Player profile not found"));
        return createSeason(profile, yearStart, yearEnd, teamName, clubName);
    }

    public List<Season> getSeasonsByPlayer(Long playerProfileId) {
        return seasonRepository.findByPlayerProfileId(playerProfileId);
    }

    public Optional<Season> getSeasonById(Long seasonId) {
        return seasonRepository.findById(seasonId);
    }

    public Season updateSeasonStats(Season season, Integer totalGames) {
        season.setTotalGames(totalGames);
        return seasonRepository.save(season);
    }
}