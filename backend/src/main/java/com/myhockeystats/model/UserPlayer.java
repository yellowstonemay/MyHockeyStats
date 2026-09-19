package com.myhockeystats.model;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * Link between a login ({@link User}) and a player profile.
 *
 * Many logins may point at the same player (two parents, a player and a
 * guardian, …) and one login may manage several players (siblings). This join
 * table is the single source of truth for "who may see this player".
 */
@Entity
@Table(name = "user_players",
       uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "player_id"}))
public class UserPlayer {

    public static final String RELATION_SELF = "SELF";
    public static final String RELATION_PARENT = "PARENT";
    public static final String RELATION_GUARDIAN = "GUARDIAN";
    public static final String RELATION_FAN = "FAN";

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(optional = false)
    @JoinColumn(name = "player_id", nullable = false)
    private PlayerProfile player;

    /** How the login relates to the player. Defaults to PARENT for added players. */
    @Column(nullable = false)
    private String relation = RELATION_PARENT;

    /** The player shown by default for this login (its own profile, usually). */
    @Column(name = "is_primary", nullable = false)
    private boolean primary = false;

    /**
     * Whether this login may edit the player (profile and manual stats).
     * False by default: being linked only grants read access. The player's
     * owner and any admin can edit regardless of this flag.
     */
    @Column(name = "can_edit", nullable = false)
    private boolean canEdit = false;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public User getUser() { return user; }
    public void setUser(User user) { this.user = user; }

    public PlayerProfile getPlayer() { return player; }
    public void setPlayer(PlayerProfile player) { this.player = player; }

    public String getRelation() { return relation; }
    public void setRelation(String relation) { this.relation = relation; }

    public boolean isPrimary() { return primary; }
    public void setPrimary(boolean primary) { this.primary = primary; }

    public boolean isCanEdit() { return canEdit; }
    public void setCanEdit(boolean canEdit) { this.canEdit = canEdit; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
