package com.myhockeystats.service.integration;

import com.myhockeystats.api.dto.integration.IntegrationDtos;
import com.myhockeystats.model.integration.AhfPlayerCareer;
import com.myhockeystats.model.integration.AyhlPlayerCareer;
import com.myhockeystats.model.integration.ThfPlayerCareer;
import com.myhockeystats.repository.integration.AhfPlayerCareerRepository;
import com.myhockeystats.repository.integration.AyhlPlayerCareerRepository;
import com.myhockeystats.repository.integration.ThfPlayerCareerRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Service for runtime career data lookup by normalized player name.
 * Queries AYHL, THF, AHF career tables and returns unified results.
 * 
 * Simplified approach: No persistent identity mapping.
 * Direct lookup by normalized name (exact match, stateless).
 */
@Service
@Slf4j
public class CareerLookupService {
    
    @Autowired
    private AyhlPlayerCareerRepository ayhlRepo;
    
    @Autowired
    private ThfPlayerCareerRepository thfRepo;
    
    @Autowired
    private AhfPlayerCareerRepository ahfRepo;
    
    /**
     * Normalize player name for lookup.
     * Rules:
     * - Convert to lowercase
     * - Trim whitespace
     * - Remove common punctuation (-, ', .)
     * - Normalize internal whitespace
     * 
     * Examples:
     * - "Jean-Pierre O'Brien" -> "jeanpierre obrien"
     * - "Mary  Jane  Smith" -> "mary jane smith"
     * - "J.D. MacLeod" -> "jd macleod"
     */
    public static String normalizePlayerName(String fullName) {
        if (fullName == null || fullName.isBlank()) {
            return "";
        }
        
        return fullName
            .toLowerCase()
            .trim()
            .replaceAll("['-.]", "")           // Remove common punctuation
            .replaceAll("\\s+", " ");          // Normalize internal whitespace
    }
    
    /**
     * Lookup all career records from all sources by normalized player name.
     * Returns union of matching records sorted by season DESC, then source ASC.
     * 
     * @param playerName full player name (e.g., "John Smith")
     * @return response with all matching records and metadata
     */
    public IntegrationDtos.SeasonsResponseDto lookupCareerRecordsByName(String playerName) {
        String normalized = normalizePlayerName(playerName);
        
        if (normalized.isBlank()) {
            log.warn("Career lookup attempted with empty player name");
            return createEmptyResponse();
        }
        
        log.info("Career lookup: player='{}', normalized='{}'", playerName, normalized);
        
        // Query all three repositories
        List<AyhlPlayerCareer> ayhlRecords = ayhlRepo.findByNormalizedName(normalized);
        List<ThfPlayerCareer> thfRecords = thfRepo.findByNormalizedName(normalized);
        List<AhfPlayerCareer> ahfRecords = ahfRepo.findByNormalizedName(normalized);
        
        log.info("Found records: AYHL={}, THF={}, AHF={}", 
            ayhlRecords.size(), thfRecords.size(), ahfRecords.size());
        
        // Convert to unified DTOs
        List<IntegrationDtos.SeasonCareerRecordDto> allRecords = new ArrayList<>();
        allRecords.addAll(toSeasonDtos(ayhlRecords, "AYHL"));
        allRecords.addAll(toSeasonDtos(thfRecords, "THF"));
        allRecords.addAll(toSeasonDtos(ahfRecords, "AHF"));
        
        // Sort by season DESC, then source ASC
        allRecords.sort(Comparator
            .comparing(IntegrationDtos.SeasonCareerRecordDto::season).reversed()
            .thenComparing(IntegrationDtos.SeasonCareerRecordDto::source));
        
        // Detect ambiguity: multiple distinct (source, sourcePlayerId) pairs
        Set<String> uniqueSourcePlayerIds = allRecords.stream()
            .map(r -> r.source() + ":" + r.sourcePlayerId())
            .collect(Collectors.toSet());
        boolean hasAmbiguity = uniqueSourcePlayerIds.size() > 1;
        
        // Build response
        Set<String> availableSources = allRecords.stream()
            .map(IntegrationDtos.SeasonCareerRecordDto::source)
            .collect(Collectors.toSet());
        Set<String> emptySources = Stream.of("AYHL", "THF", "AHF")
            .filter(s -> !availableSources.contains(s))
            .collect(Collectors.toSet());
        
        String ambiguityNote = hasAmbiguity 
            ? "Multiple players found with this name. Verify by team/season."
            : null;
        
        return new IntegrationDtos.SeasonsResponseDto(
            null,  // playerId set by controller
            null,  // playerName set by controller
            allRecords,
            hasAmbiguity,
            ambiguityNote,
            availableSources,
            emptySources,
            OffsetDateTime.now(ZoneOffset.UTC),
            "private, max-age=300"
        );
    }
    
    /**
     * Convert AYHL entities to unified DTOs with source label.
     */
    private List<IntegrationDtos.SeasonCareerRecordDto> toSeasonDtos(
            List<AyhlPlayerCareer> records, String source) {
        return records.stream()
            .map(r -> new IntegrationDtos.SeasonCareerRecordDto(
                source,
                r.getSourcePlayerId(),
                r.getPlayerName(),
                r.getSeasonLabel(),
                r.getLeagueName(),
                r.getTeamName(),
                r.getJerseyNumber(),
                r.getGamesPlayed(),
                r.getGoals(),
                r.getAssists(),
                r.getPoints(),
                r.getPenalties(),
                r.getPim() != null ? r.getPim().doubleValue() : null,
                r.getCreatedAt() != null ? r.getCreatedAt().atOffset(ZoneOffset.UTC) : null,
                false,  // isAmbiguousMembership - set to false here (ambiguity at response level)
                null    // ambiguityNote - set at response level
            ))
            .collect(Collectors.toList());
    }
    
    /**
     * Convert THF entities to unified DTOs with source label.
     */
    private List<IntegrationDtos.SeasonCareerRecordDto> toSeasonDtos(
            List<ThfPlayerCareer> records, String source) {
        return records.stream()
            .map(r -> new IntegrationDtos.SeasonCareerRecordDto(
                source,
                r.getSourcePlayerId(),
                r.getPlayerName(),
                r.getSeasonLabel(),
                r.getLeagueName(),
                r.getTeamName(),
                r.getJerseyNumber(),
                r.getGamesPlayed(),
                r.getGoals(),
                r.getAssists(),
                r.getPoints(),
                r.getPenalties(),
                r.getPim(),
                r.getCreatedAt() != null ? r.getCreatedAt().atOffset(ZoneOffset.UTC) : null,
                false,  // isAmbiguousMembership
                null    // ambiguityNote
            ))
            .collect(Collectors.toList());
    }
    
    /**
     * Convert AHF entities to unified DTOs with source label.
     */
    private List<IntegrationDtos.SeasonCareerRecordDto> toSeasonDtos(
            List<AhfPlayerCareer> records, String source) {
        return records.stream()
            .map(r -> new IntegrationDtos.SeasonCareerRecordDto(
                source,
                r.getSourcePlayerId(),
                r.getPlayerName(),
                r.getSeasonLabel(),
                r.getLeagueName(),
                r.getTeamName(),
                r.getJerseyNumber(),
                r.getGamesPlayed(),
                r.getGoals(),
                r.getAssists(),
                r.getPoints(),
                r.getPenalties(),
                r.getPim(),
                r.getCreatedAt() != null ? r.getCreatedAt().atOffset(ZoneOffset.UTC) : null,
                false,  // isAmbiguousMembership
                null    // ambiguityNote
            ))
            .collect(Collectors.toList());
    }
    
    /**
     * Create empty response when player name is blank.
     */
    private IntegrationDtos.SeasonsResponseDto createEmptyResponse() {
        return new IntegrationDtos.SeasonsResponseDto(
            null,
            null,
            Collections.emptyList(),
            false,
            null,
            Collections.emptySet(),
            Stream.of("AYHL", "THF", "AHF").collect(Collectors.toSet()),
            OffsetDateTime.now(ZoneOffset.UTC),
            "private, max-age=300"
        );
    }
}
