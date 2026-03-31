package com.myhockeystats.repository.integration;

import com.myhockeystats.model.integration.ThfPlayerCareer;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface ThfPlayerCareerRepository extends JpaRepository<ThfPlayerCareer, UUID> {
    
    /**
     * Find all career records where player name matches normalized name.
     * Normalization: LOWER(TRIM(player_name))
     * Uses functional index for performance.
     * 
     * @param normalizedName lowercase, trimmed player name (without punctuation)
     * @return list of matching career records, ordered by season DESC
     */
    @Query("SELECT r FROM ThfPlayerCareer r " +
           "WHERE LOWER(TRIM(r.playerName)) = :normalizedName " +
           "ORDER BY r.seasonLabel DESC")
    List<ThfPlayerCareer> findByNormalizedName(@Param("normalizedName") String normalizedName);
}
