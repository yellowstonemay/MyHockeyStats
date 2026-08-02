package com.myhockeystats.service.integration;

import com.myhockeystats.api.dto.integration.IntegrationDtos;
import com.myhockeystats.model.integration.AhfPlayerCareer;
import com.myhockeystats.model.integration.AyhlPlayerCareer;
import com.myhockeystats.model.integration.ThfPlayerCareer;
import com.myhockeystats.repository.integration.AhfPlayerCareerRepository;
import com.myhockeystats.repository.integration.AyhlPlayerCareerRepository;
import com.myhockeystats.repository.integration.ThfPlayerCareerRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
public class CareerLookupService {

    private static final Logger log = LoggerFactory.getLogger(CareerLookupService.class);
    
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

    private static String canonicalName(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return value
            .toLowerCase()
            .trim()
            .replaceAll("[\\s,.'-]", "");
    }

    private static Set<String> buildCanonicalLookupKeys(String playerName) {
        String normalized = normalizePlayerName(playerName);
        if (normalized.isBlank()) {
            return Collections.emptySet();
        }

        Set<String> keys = new LinkedHashSet<>();
        keys.add(canonicalName(normalized));

        String[] parts = normalized.split(" ");
        if (parts.length == 2) {
            // Also support source format like "Last,First".
            keys.add(canonicalName(parts[1] + "," + parts[0]));
        }
        return keys;
    }
    
    /**
     * Lookup all career records from all sources by normalized player name.
     * Returns union of matching records sorted by season DESC, then source ASC.
     * 
     * @param playerName full player name (e.g., "John Smith")
     * @return response with all matching records and metadata
     */
    public IntegrationDtos.SeasonsResponseDto lookupCareerRecordsByName(String playerName) {
        Set<String> canonicalNames = buildCanonicalLookupKeys(playerName);

        if (canonicalNames.isEmpty()) {
            log.warn("Career lookup attempted with empty player name");
            return createEmptyResponse();
        }

        log.info("Career lookup: player='{}', canonicalKeys={}", playerName, canonicalNames);
        
        // Query all three repositories
        List<AyhlPlayerCareer> ayhlRecords = ayhlRepo.findByCanonicalNames(canonicalNames);
        List<ThfPlayerCareer> thfRecords = thfRepo.findByCanonicalNames(canonicalNames);
        List<AhfPlayerCareer> ahfRecords = ahfRepo.findByCanonicalNames(canonicalNames);
        
        log.info("Found records: AYHL={}, THF={}, AHF={}", 
            ayhlRecords.size(), thfRecords.size(), ahfRecords.size());
        
        // Convert to unified DTOs
        List<IntegrationDtos.SeasonCareerRecordDto> allRecords = new ArrayList<>();
        allRecords.addAll(toSeasonDtosFromAyhl(ayhlRecords, "AYHL"));
        allRecords.addAll(toSeasonDtosFromThf(thfRecords, "THF"));
        allRecords.addAll(toSeasonDtosFromAhf(ahfRecords, "AHF"));
        
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
    private List<IntegrationDtos.SeasonCareerRecordDto> toSeasonDtosFromAyhl(
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
                Boolean.TRUE.equals(r.getIsUserModified()),  // isUserModified
                r.getCreatedAt() != null ? r.getCreatedAt().atOffset(ZoneOffset.UTC) : null,
                false,  // isAmbiguousMembership - set to false here (ambiguity at response level)
                null    // ambiguityNote - set at response level
            ))
            .collect(Collectors.toList());
    }
    
    /**
     * Convert THF entities to unified DTOs with source label.
     */
    private List<IntegrationDtos.SeasonCareerRecordDto> toSeasonDtosFromThf(
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
                Boolean.TRUE.equals(r.getIsUserModified()),  // isUserModified
                r.getCreatedAt() != null ? r.getCreatedAt().atOffset(ZoneOffset.UTC) : null,
                false,  // isAmbiguousMembership
                null    // ambiguityNote
            ))
            .collect(Collectors.toList());
    }
    
    /**
     * Convert AHF entities to unified DTOs with source label.
     */
    private List<IntegrationDtos.SeasonCareerRecordDto> toSeasonDtosFromAhf(
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
                Boolean.TRUE.equals(r.getIsUserModified()),  // isUserModified
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
