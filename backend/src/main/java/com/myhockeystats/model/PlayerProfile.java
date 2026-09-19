package com.myhockeystats.model;

import jakarta.persistence.*;
import java.time.LocalDate;
import java.time.Instant;

@Entity
@Table(name = "player_profiles")
public class PlayerProfile {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * The login that originally created this player. Ownership/access is NOT
     * derived from this column — it is derived from the {@code user_players}
     * link table, because a player can be attached to many logins.
     */
    @ManyToOne
    @JoinColumn(name = "created_by_user_id")
    private User createdBy;

    /**
     * The login that owns this player: the account that may edit the profile,
     * enter manual stats and hand out edit rights. Null on legacy rows that
     * were created before ownership existed.
     */
    @Column(name = "owner_user_id")
    private Long ownerUserId;

    @Column(nullable = false)
    private String fullName;

    @Column(nullable = false)
    private LocalDate birthdate;

    private String location;
    private String position;
    private String photoUrl;

    private Instant createdAt = Instant.now();
    private Instant updatedAt = Instant.now();

    // getters and setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public User getCreatedBy() { return createdBy; }
    public void setCreatedBy(User createdBy) { this.createdBy = createdBy; }

    public Long getOwnerUserId() { return ownerUserId; }
    public void setOwnerUserId(Long ownerUserId) { this.ownerUserId = ownerUserId; }

    /** @deprecated use {@link #getCreatedBy()} — access is via user_players now. */
    @Deprecated
    public User getUser() { return createdBy; }

    /** @deprecated use {@link #setCreatedBy(User)}. */
    @Deprecated
    public void setUser(User user) { this.createdBy = user; }

    public String getFullName() { return fullName; }
    public void setFullName(String fullName) { this.fullName = fullName; }

    public LocalDate getBirthdate() { return birthdate; }
    public void setBirthdate(LocalDate birthdate) { this.birthdate = birthdate; }

    public String getLocation() { return location; }
    public void setLocation(String location) { this.location = location; }

    public String getPosition() { return position; }
    public void setPosition(String position) { this.position = position; }

    public String getPhotoUrl() { return photoUrl; }
    public void setPhotoUrl(String photoUrl) { this.photoUrl = photoUrl; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}