package com.myhockeystats.repository;

import com.myhockeystats.model.PlayerProfile;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PlayerProfileRepository extends JpaRepository<PlayerProfile, Long> {

    /** Players this login originally created (access is decided by user_players). */
    List<PlayerProfile> findByCreatedById(Long userId);

    Optional<PlayerProfile> findFirstByCreatedById(Long userId);
}