package com.myhockeystats.repository;

import com.myhockeystats.model.UserPlayer;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UserPlayerRepository extends JpaRepository<UserPlayer, UUID> {

    List<UserPlayer> findByUserId(Long userId);

    Optional<UserPlayer> findByUserIdAndPlayerId(Long userId, Long playerId);

    boolean existsByUserIdAndPlayerId(Long userId, Long playerId);

    Optional<UserPlayer> findFirstByUserIdAndPrimaryTrue(Long userId);

    /** Every login attached to a player — used to notify all of them. */
    List<UserPlayer> findByPlayerId(Long playerId);
}
