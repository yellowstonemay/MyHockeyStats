package com.myhockeystats.repository.integration;

import com.myhockeystats.model.integration.AyhlPlayerCareer;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Repository
public interface AyhlPlayerCareerRepository extends JpaRepository<AyhlPlayerCareer, UUID> {
    
        // Canonicalizes names by removing whitespace and common punctuation so
        // both "Ethan Yan" and "Yan,Ethan" can match.
    @Query("SELECT r FROM AyhlPlayerCareer r " +
            "WHERE LOWER(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(TRIM(r.playerName), ' ', ''), ',', ''), '''', ''), '-', ''), '.', '')) IN :canonicalNames " +
           "ORDER BY r.seasonLabel DESC")
        List<AyhlPlayerCareer> findByCanonicalNames(@Param("canonicalNames") Collection<String> canonicalNames);
}
