package com.myhockeystats.repository;

import com.myhockeystats.model.PlayerProfile;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface PlayerProfileRepository extends JpaRepository<PlayerProfile, Long> {

    /** Players this login originally created (access is decided by user_players). */
    List<PlayerProfile> findByCreatedById(Long userId);

    Optional<PlayerProfile> findFirstByCreatedById(Long userId);

    /**
     * Same kid, added by another login. Name matching ignores case so
     * "Bryan yang" and "Bryan Yang" resolve to the same profile; the birthdate
     * keeps two different children with the same name apart.
     */
    Optional<PlayerProfile> findFirstByFullNameIgnoreCaseAndBirthdateOrderByIdAsc(String fullName, LocalDate birthdate);

    /**
     * A tracked profile: created by following a player, so nobody owns it and
     * the birthdate is usually unknown. Used to adopt the profile when somebody
     * later claims the player as their own instead of creating a duplicate.
     */
    Optional<PlayerProfile> findFirstByFullNameIgnoreCaseAndBirthdateIsNullAndOwnerUserIdIsNullOrderByIdAsc(
        String fullName);
}