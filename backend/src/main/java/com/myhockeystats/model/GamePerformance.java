package com.myhockeystats.model;

import jakarta.persistence.*;

@Entity
@Table(name = "game_performances")
public class GamePerformance {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "game_id", nullable = false)
    private Game game;

    @ManyToOne
    @JoinColumn(name = "player_profile_id", nullable = false)
    private PlayerProfile playerProfile;

    private Integer goals = 0;
    private Integer assists = 0;
    private String notes;

    // getters and setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Game getGame() { return game; }
    public void setGame(Game game) { this.game = game; }

    public PlayerProfile getPlayerProfile() { return playerProfile; }
    public void setPlayerProfile(PlayerProfile playerProfile) { this.playerProfile = playerProfile; }

    public Integer getGoals() { return goals; }
    public void setGoals(Integer goals) { this.goals = goals; }

    public Integer getAssists() { return assists; }
    public void setAssists(Integer assists) { this.assists = assists; }

    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }

    public Integer getPoints() {
        return (goals != null ? goals : 0) + (assists != null ? assists : 0);
    }
}