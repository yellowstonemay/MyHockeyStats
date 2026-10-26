package com.myhockeystats.service;

import com.myhockeystats.model.PlayerProfile;
import com.myhockeystats.model.User;
import com.myhockeystats.model.UserPlayer;
import com.myhockeystats.repository.PlayerProfileRepository;
import com.myhockeystats.repository.UserPlayerRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Player profiles are shared entities: one login can manage several players and
 * one player can be attached to several logins. All access checks therefore go
 * through the {@code user_players} link table rather than the profile's
 * {@code created_by_user_id}.
 */
@Service
public class PlayerProfileService {
    private final PlayerProfileRepository playerProfileRepository;
    private final UserPlayerRepository userPlayerRepository;

    public PlayerProfileService(PlayerProfileRepository playerProfileRepository,
                                UserPlayerRepository userPlayerRepository) {
        this.playerProfileRepository = playerProfileRepository;
        this.userPlayerRepository = userPlayerRepository;
    }

    // ── reads ──────────────────────────────────────────────────────────────

    /** All players attached to a login, primary first then alphabetical. */
    @Transactional(readOnly = true)
    public List<PlayerProfile> listPlayersForUser(Long userId) {
        List<UserPlayer> links = userPlayerRepository.findByUserId(userId);
        Map<Long, Boolean> primaryById = new LinkedHashMap<>();
        List<PlayerProfile> players = new ArrayList<>();
        for (UserPlayer link : links) {
            PlayerProfile player = link.getPlayer();
            if (player == null || primaryById.containsKey(player.getId())) {
                continue;
            }
            primaryById.put(player.getId(), link.isPrimary());
            players.add(player);
        }
        players.sort(Comparator
            .comparing((PlayerProfile p) -> !Boolean.TRUE.equals(primaryById.get(p.getId())))
            .thenComparing(p -> p.getFullName() == null ? "" : p.getFullName().toLowerCase()));
        return players;
    }

    /**
     * playerId -> relation (SELF / PARENT / GUARDIAN / FAN) for every player
     * attached to this login. The players list needs it to tell a player the
     * login manages apart from one it only follows.
     */
    @Transactional(readOnly = true)
    public Map<Long, String> relationByPlayerForUser(Long userId) {
        Map<Long, String> relations = new LinkedHashMap<>();
        for (UserPlayer link : userPlayerRepository.findByUserId(userId)) {
            if (link.getPlayer() != null && link.getRelation() != null) {
                relations.putIfAbsent(link.getPlayer().getId(), link.getRelation());
            }
        }
        return relations;
    }

    /** Whether this login may view this player. */
    @Transactional(readOnly = true)
    public boolean isLinked(Long userId, Long playerId) {
        if (userId == null || playerId == null) {
            return false;
        }
        return userPlayerRepository.existsByUserIdAndPlayerId(userId, playerId);
    }

    /**
     * Whether this login may edit the player (profile, manual stats, refresh).
     * Admins may edit any player, the owner may edit their own player, and any
     * other login needs an explicit {@code can_edit} grant from the owner.
     */
    @Transactional(readOnly = true)
    public boolean canEditPlayer(User user, Long playerId) {
        if (user == null || user.getId() == null || playerId == null) {
            return false;
        }
        if (user.isAdmin()) {
            return true;
        }
        Optional<PlayerProfile> profile = playerProfileRepository.findById(playerId);
        if (profile.isEmpty()) {
            return false;
        }
        if (user.getId().equals(profile.get().getOwnerUserId())) {
            return true;
        }
        return userPlayerRepository.findByUserIdAndPlayerId(user.getId(), playerId)
            .map(UserPlayer::isCanEdit)
            .orElse(false);
    }

    /**
     * Whether this login owns the player, or is an admin. Owners and admins may
     * hand out edit rights to the other logins attached to the player.
     */
    @Transactional(readOnly = true)
    public boolean isOwnerOrAdmin(User user, Long playerId) {
        if (user == null || user.getId() == null || playerId == null) {
            return false;
        }
        if (user.isAdmin()) {
            return true;
        }
        return playerProfileRepository.findById(playerId)
            .map(profile -> user.getId().equals(profile.getOwnerUserId()))
            .orElse(false);
    }

    /**
     * Hand a player to another login. The new owner is attached to the player
     * (if it was not already) and always keeps edit rights.
     */
    @Transactional
    public PlayerProfile transferOwnership(User newOwner, Long playerId) {
        PlayerProfile profile = playerProfileRepository.findById(playerId)
            .orElseThrow(() -> new IllegalArgumentException("Player not found"));

        profile.setOwnerUserId(newOwner.getId());
        profile.setUpdatedAt(Instant.now());
        PlayerProfile saved = playerProfileRepository.save(profile);

        UserPlayer link = linkPlayer(newOwner, saved, null);
        if (!link.isCanEdit()) {
            link.setCanEdit(true);
            userPlayerRepository.save(link);
        }
        return saved;
    }

    /** Grant or revoke edit rights for one of the logins attached to a player. */
    @Transactional
    public boolean setEditRights(Long userId, Long playerId, boolean canEdit) {
        Optional<UserPlayer> link = userPlayerRepository.findByUserIdAndPlayerId(userId, playerId);
        if (link.isEmpty()) {
            return false;
        }
        link.get().setCanEdit(canEdit);
        userPlayerRepository.save(link.get());
        return true;
    }

    /** The player shown by default for a login (the one flagged primary). */
    @Transactional(readOnly = true)
    public Optional<PlayerProfile> getPrimaryPlayerForUser(Long userId) {
        Optional<UserPlayer> primary = userPlayerRepository.findFirstByUserIdAndPrimaryTrue(userId);
        if (primary.isPresent() && primary.get().getPlayer() != null) {
            return Optional.of(primary.get().getPlayer());
        }
        List<UserPlayer> links = userPlayerRepository.findByUserId(userId);
        // No explicit default: prefer a player the login manages, so a player it
        // only FOLLOWS never becomes the one the dashboard opens with.
        for (UserPlayer link : links) {
            if (link.getPlayer() != null
                && !UserPlayer.RELATION_FAN.equalsIgnoreCase(link.getRelation())) {
                return Optional.of(link.getPlayer());
            }
        }
        for (UserPlayer link : links) {
            if (link.getPlayer() != null) {
                return Optional.of(link.getPlayer());
            }
        }
        // Legacy fallback: a profile created before the link table was backfilled.
        return playerProfileRepository.findFirstByCreatedById(userId);
    }

    /** @deprecated use {@link #getPrimaryPlayerForUser(Long)}. */
    @Deprecated
    @Transactional(readOnly = true)
    public Optional<PlayerProfile> getProfileByUserId(Long userId) {
        return getPrimaryPlayerForUser(userId);
    }

    @Transactional(readOnly = true)
    public Optional<PlayerProfile> getProfileById(Long profileId) {
        return playerProfileRepository.findById(profileId);
    }

    /** Every login currently attached to a player (used when notifying). */
    @Transactional(readOnly = true)
    public List<Long> getUserIdsForPlayer(Long playerId) {
        List<Long> ids = new ArrayList<>();
        for (UserPlayer link : userPlayerRepository.findByPlayerId(playerId)) {
            if (link.getUser() != null) {
                ids.add(link.getUser().getId());
            }
        }
        return ids;
    }

    /**
     * Legacy string-based lookup used by SeasonsController.
     * Player profile ids are numeric; accept the numeric id as a string.
     */
    @Transactional(readOnly = true)
    public Optional<PlayerProfile> getPlayerProfile(String playerId) {
        if (playerId == null || playerId.isBlank()) {
            return Optional.empty();
        }
        try {
            return playerProfileRepository.findById(Long.parseLong(playerId.trim()));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    // ── writes ─────────────────────────────────────────────────────────────

    /**
     * Create a brand-new player profile and attach it to the creating login.
     */
    @Transactional
    public PlayerProfile createPlayer(User user, String fullName, LocalDate birthdate,
                                      String location, String position, String relation) {
        PlayerProfile profile = new PlayerProfile();
        profile.setCreatedBy(user);
        profile.setOwnerUserId(user.getId());
        profile.setFullName(fullName);
        profile.setBirthdate(birthdate);
        profile.setLocation(location);
        profile.setPosition(position);
        profile.setCreatedAt(Instant.now());
        profile.setUpdatedAt(Instant.now());
        PlayerProfile saved = playerProfileRepository.save(profile);

        linkPlayer(user, saved, relation == null ? UserPlayer.RELATION_SELF : relation);
        return saved;
    }

    /** Outcome of adding a player: reused an existing profile, or created one. */
    public record AttachResult(PlayerProfile player, boolean created, boolean alreadyAttached) {}

    /**
     * Create the profile of a player this login only FOLLOWS.
     *
     * <p>A follow has to be a real profile to be selectable — the dashboard and
     * every stats tab are scoped by player id. Followed players have no owner
     * (nobody manages them) and usually no birthdate (THF/AHF publish none), so
     * the profile is "tracked": ownerless, read-only, and adopted later if
     * somebody claims the player as their own.
     */
    @Transactional
    public PlayerProfile createTrackedPlayer(User follower, String fullName, LocalDate birthdate) {
        PlayerProfile profile = new PlayerProfile();
        profile.setCreatedBy(follower);
        profile.setOwnerUserId(null);
        profile.setFullName(fullName == null ? "" : fullName.trim());
        profile.setBirthdate(birthdate);
        profile.setCreatedAt(Instant.now());
        profile.setUpdatedAt(Instant.now());
        PlayerProfile saved = playerProfileRepository.save(profile);
        linkPlayer(follower, saved, UserPlayer.RELATION_FAN);
        return saved;
    }

    /**
     * Add a player without duplicating it.
     *
     * <p>Several logins may manage the same kid (two parents, a parent and a
     * guardian, …). Rather than inserting a second profile — which split the
     * manual stats, AI reports and seasons across copies — an existing profile
     * with the same name and birthdate is reused and the caller is simply
     * attached to it. Only when nothing matches is a new profile created.
     */
    @Transactional
    public AttachResult createOrAttach(User user, String fullName, LocalDate birthdate,
                                       String location, String position, String relation) {
        String name = fullName == null ? "" : fullName.trim();
        if (!name.isEmpty() && birthdate != null) {
            Optional<PlayerProfile> existing = playerProfileRepository
                .findFirstByFullNameIgnoreCaseAndBirthdateOrderByIdAsc(name, birthdate);
            if (existing.isPresent()) {
                PlayerProfile player = existing.get();
                boolean alreadyAttached = userPlayerRepository
                    .existsByUserIdAndPlayerId(user.getId(), player.getId());
                if (!alreadyAttached) {
                    linkPlayer(user, player, relation == null ? UserPlayer.RELATION_PARENT : relation);
                }
                return new AttachResult(player, false, alreadyAttached);
            }
            // Nobody owns a profile with this name and no birthdate: that is the
            // "tracked" profile a follow created. Claim it (and fill in the
            // birthdate) instead of creating a second profile for the same kid.
            Optional<PlayerProfile> tracked = playerProfileRepository
                .findFirstByFullNameIgnoreCaseAndBirthdateIsNullAndOwnerUserIdIsNullOrderByIdAsc(name);
            if (tracked.isPresent()) {
                PlayerProfile player = tracked.get();
                player.setBirthdate(birthdate);
                if (location != null && !location.isBlank()) {
                    player.setLocation(location);
                }
                if (position != null && !position.isBlank()) {
                    player.setPosition(position);
                }
                player.setOwnerUserId(user.getId());
                player.setUpdatedAt(Instant.now());
                playerProfileRepository.save(player);
                linkPlayer(user, player, relation == null ? UserPlayer.RELATION_PARENT : relation);
                return new AttachResult(player, false, false);
            }
        }
        return new AttachResult(createPlayer(user, name, birthdate, location, position, relation), true, false);
    }

    /**
     * Every login attached to a player, so one owner can see who else manages
     * the same kid instead of assuming the profile is theirs alone.
     */
    @Transactional(readOnly = true)
    public List<UserPlayer> loginsForPlayer(Long playerId) {
        return userPlayerRepository.findByPlayerId(playerId);
    }

    /** Attach an existing player to a login (multiple logins, same player). */
    @Transactional
    public UserPlayer linkPlayer(User user, PlayerProfile player, String relation) {
        Optional<UserPlayer> existing = userPlayerRepository.findByUserIdAndPlayerId(user.getId(), player.getId());
        if (existing.isPresent()) {
            UserPlayer link = existing.get();
            if (relation != null) {
                link.setRelation(relation);
            }
            return userPlayerRepository.save(link);
        }

        UserPlayer link = new UserPlayer();
        link.setUser(user);
        link.setPlayer(player);
        link.setRelation(relation == null ? UserPlayer.RELATION_PARENT : relation);
        link.setCreatedAt(Instant.now());
        // First player for this login becomes the primary one — but a player you
        // only follow must never become the default the dashboard opens with.
        link.setPrimary(!UserPlayer.RELATION_FAN.equalsIgnoreCase(link.getRelation())
            && userPlayerRepository.findByUserId(user.getId()).isEmpty());
        return userPlayerRepository.save(link);
    }

    /** Detach a player from a login. The player row itself is kept. */
    @Transactional
    public boolean unlinkPlayer(Long userId, Long playerId) {
        Optional<UserPlayer> link = userPlayerRepository.findByUserIdAndPlayerId(userId, playerId);
        if (link.isEmpty()) {
            return false;
        }
        boolean wasPrimary = link.get().isPrimary();
        userPlayerRepository.delete(link.get());
        if (wasPrimary) {
            List<UserPlayer> remaining = userPlayerRepository.findByUserId(userId);
            if (!remaining.isEmpty()) {
                UserPlayer next = remaining.get(0);
                next.setPrimary(true);
                userPlayerRepository.save(next);
            }
        }
        return true;
    }

    /** Make this player the default one for the login. */
    @Transactional
    public void setPrimaryPlayer(Long userId, Long playerId) {
        for (UserPlayer link : userPlayerRepository.findByUserId(userId)) {
            boolean shouldBePrimary = link.getPlayer() != null && playerId.equals(link.getPlayer().getId());
            if (link.isPrimary() != shouldBePrimary) {
                link.setPrimary(shouldBePrimary);
                userPlayerRepository.save(link);
            }
        }
    }

    public PlayerProfile updateProfile(PlayerProfile profile, String fullName, LocalDate birthdate,
                                       String location, String position, String photoUrl) {
        if (fullName != null) profile.setFullName(fullName);
        if (birthdate != null) profile.setBirthdate(birthdate);
        if (location != null) profile.setLocation(location);
        if (position != null) profile.setPosition(position);
        if (photoUrl != null) profile.setPhotoUrl(photoUrl);
        profile.setUpdatedAt(Instant.now());
        return playerProfileRepository.save(profile);
    }

    /** Legacy helper kept for existing callers. */
    public PlayerProfile createProfile(User user, String fullName, LocalDate birthdate, String location) {
        return createPlayer(user, fullName, birthdate, location, null, UserPlayer.RELATION_SELF);
    }
}
