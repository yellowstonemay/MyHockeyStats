package com.myhockeystats.service;

import com.myhockeystats.model.PlayerProfile;
import com.myhockeystats.model.User;
import com.myhockeystats.repository.PlayerProfileRepository;
import org.springframework.stereotype.Service;
import java.time.LocalDate;
import java.time.Instant;
import java.util.Optional;

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