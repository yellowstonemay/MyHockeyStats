package com.myhockeystats.service;

import com.myhockeystats.model.PlayerProfile;
import com.myhockeystats.model.User;
import com.myhockeystats.repository.PlayerProfileRepository;
import org.springframework.stereotype.Service;
import java.time.LocalDate;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Service
public class PlayerProfileService {
    private final PlayerProfileRepository playerProfileRepository;

    public PlayerProfileService(PlayerProfileRepository playerProfileRepository) {
        this.playerProfileRepository = playerProfileRepository;
    }

    public PlayerProfile createProfile(User user, String fullName, LocalDate birthdate, String location) {
        PlayerProfile profile = new PlayerProfile();
        profile.setUser(user);
        profile.setFullName(fullName);
        profile.setBirthdate(birthdate);
        profile.setLocation(location);
        profile.setCreatedAt(Instant.now());
        profile.setUpdatedAt(Instant.now());
        return playerProfileRepository.save(profile);
    }

    public Optional<PlayerProfile> getProfileByUserId(Long userId) {
        return playerProfileRepository.findByUserId(userId);
    }

    public Optional<PlayerProfile> getProfileById(Long profileId) {
        return playerProfileRepository.findById(profileId);
    }

    /**
     * Get player profile by player ID (UUID as String).
     * Used by SeasonsController to retrieve player name for career lookup.
     * 
     * @param playerId player ID as String UUID
     * @return Optional containing PlayerProfile if found
     */
    public Optional<PlayerProfile> getPlayerProfile(String playerId) {
        try {
            UUID uuid = UUID.fromString(playerId);
            // Currently, profiles are stored with Long IDs
            // This method should be adapted based on actual ID scheme
            // For now, returning empty - to be implemented with proper ID resolution
            return Optional.empty();
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    public PlayerProfile updateProfile(PlayerProfile profile, String fullName, LocalDate birthdate, String location, String position, String photoUrl) {
        if (fullName != null) profile.setFullName(fullName);
        if (birthdate != null) profile.setBirthdate(birthdate);
        if (location != null) profile.setLocation(location);
        if (position != null) profile.setPosition(position);
        if (photoUrl != null) profile.setPhotoUrl(photoUrl);
        profile.setUpdatedAt(Instant.now());
        return playerProfileRepository.save(profile);
    }
}