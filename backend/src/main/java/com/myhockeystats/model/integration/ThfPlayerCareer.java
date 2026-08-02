package com.myhockeystats.model.integration;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * ThfPlayerCareer entity - represents a single player career record in THF.
 * Maps to thf_player_career table created by scripts/thf-js/scrape_rosters.js
 * One row per (source_player_id, season_label).
 */
@Entity
@Table(name = "thf_player_career", indexes = {
    @Index(name = "idx_thf_career_player_season", columnList = "source_player_id, season_label"),
    @Index(name = "idx_thf_career_normalized_name", columnList = "player_name")  // Functional index on LOWER(TRIM(player_name))
})
public class ThfPlayerCareer {
    
    @Id
    private UUID id;
    
    @Column(name = "source_player_id", nullable = false, length = 128)
    private String sourcePlayerId;
    
    @Column(name = "player_name", length = 255)
    private String playerName;
    
    @Column(name = "season_label", nullable = false, length = 255)
    private String seasonLabel;
    
    @Column(name = "league_name", length = 255)
    private String leagueName;
    
    @Column(name = "team_name", length = 1024)
    private String teamName;
    
    @Column(name = "jersey_number", length = 16)
    private String jerseyNumber;
    
    @Column(name = "games_played")
    private Integer gamesPlayed;
    
    @Column(name = "goals")
    private Integer goals;
    
    @Column(name = "assists")
    private Integer assists;
    
    @Column(name = "points")
    private Integer points;
    
    @Column(name = "penalties")
    private Integer penalties;
    
    @Column(name = "pim")
    private Double pim;

    @Column(name = "is_user_modified", nullable = false)
    private Boolean isUserModified = false;

    @Column(name = "last_scraped_at")
    private Instant lastScrapedAt;
    
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
    
    // Constructors
    public ThfPlayerCareer() {}
    
    // Getters and Setters
    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    
    public String getSourcePlayerId() { return sourcePlayerId; }
    public void setSourcePlayerId(String sourcePlayerId) { this.sourcePlayerId = sourcePlayerId; }
    
    public String getPlayerName() { return playerName; }
    public void setPlayerName(String playerName) { this.playerName = playerName; }
    
    public String getSeasonLabel() { return seasonLabel; }
    public void setSeasonLabel(String seasonLabel) { this.seasonLabel = seasonLabel; }
    
    public String getLeagueName() { return leagueName; }
    public void setLeagueName(String leagueName) { this.leagueName = leagueName; }
    
    public String getTeamName() { return teamName; }
    public void setTeamName(String teamName) { this.teamName = teamName; }
    
    public String getJerseyNumber() { return jerseyNumber; }
    public void setJerseyNumber(String jerseyNumber) { this.jerseyNumber = jerseyNumber; }
    
    public Integer getGamesPlayed() { return gamesPlayed; }
    public void setGamesPlayed(Integer gamesPlayed) { this.gamesPlayed = gamesPlayed; }
    
    public Integer getGoals() { return goals; }
    public void setGoals(Integer goals) { this.goals = goals; }
    
    public Integer getAssists() { return assists; }
    public void setAssists(Integer assists) { this.assists = assists; }
    
    public Integer getPoints() { return points; }
    public void setPoints(Integer points) { this.points = points; }
    
    public Integer getPenalties() { return penalties; }
    public void setPenalties(Integer penalties) { this.penalties = penalties; }
    
    public Double getPim() { return pim; }
    public void setPim(Double pim) { this.pim = pim; }

    public Boolean getIsUserModified() { return isUserModified; }
    public void setIsUserModified(Boolean isUserModified) { this.isUserModified = isUserModified; }
    
    public Instant getLastScrapedAt() { return lastScrapedAt; }
    public void setLastScrapedAt(Instant lastScrapedAt) { this.lastScrapedAt = lastScrapedAt; }
    
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
