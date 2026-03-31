package com.myhockeystats.service.integration;

import com.myhockeystats.api.dto.integration.IntegrationDtos;
import com.myhockeystats.model.integration.AhfPlayerCareer;
import com.myhockeystats.model.integration.AyhlPlayerCareer;
import com.myhockeystats.model.integration.ThfPlayerCareer;
import com.myhockeystats.repository.integration.AhfPlayerCareerRepository;
import com.myhockeystats.repository.integration.AyhlPlayerCareerRepository;
import com.myhockeystats.repository.integration.ThfPlayerCareerRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for CareerLookupService.
 * Tests normalization logic, repository queries, and response building.
 */
@DisplayName("CareerLookupService")
class CareerLookupServiceTest {
    
    @Mock
    private AyhlPlayerCareerRepository ayhlRepo;
    
    @Mock
    private ThfPlayerCareerRepository thfRepo;
    
    @Mock
    private AhfPlayerCareerRepository ahfRepo;
    
    @InjectMocks
    private CareerLookupService service;
    
    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
    }
    
    // ===== NORMALIZATION TESTS =====
    
    @Test
    @DisplayName("normalizePlayerName: lowercase and trim")
    void testNormalizeSimple() {
        assertEquals("john smith", CareerLookupService.normalizePlayerName("  JOHN SMITH  "));
    }
    
    @Test
    @DisplayName("normalizePlayerName: remove hyphens")
    void testNormalizeHyphenatedName() {
        assertEquals("jeanpierre obrien", CareerLookupService.normalizePlayerName("Jean-Pierre O'Brien"));
    }
    
    @Test
    @DisplayName("normalizePlayerName: remove apostrophes")
    void testNormalizeApostrophe() {
        assertEquals("patrick oneill", CareerLookupService.normalizePlayerName("Patrick O'Neill"));
    }
    
    @Test
    @DisplayName("normalizePlayerName: remove periods")
    void testNormalizePeriods() {
        assertEquals("jd macleod", CareerLookupService.normalizePlayerName("J.D. MacLeod"));
    }
    
    @Test
    @DisplayName("normalizePlayerName: normalize multiple spaces")
    void testNormalizeMultipleSpaces() {
        assertEquals("mary jane smith", CareerLookupService.normalizePlayerName("Mary  Jane   Smith"));
    }
    
    @Test
    @DisplayName("normalizePlayerName: combined punctuation")
    void testNormalizeCombined() {
        String result = CareerLookupService.normalizePlayerName("Jean-Claude L'Étudiant");
        assertEquals("jeanclaude letudiant", result);
    }
    
    @Test
    @DisplayName("normalizePlayerName: null input")
    void testNormalizeNull() {
        assertEquals("", CareerLookupService.normalizePlayerName(null));
    }
    
    @Test
    @DisplayName("normalizePlayerName: blank input")
    void testNormalizeBlank() {
        assertEquals("", CareerLookupService.normalizePlayerName("   "));
    }
    
    // ===== LOOKUP TESTS =====
    
    @Test
    @DisplayName("lookupCareerRecordsByName: returns records from all sources")
    void testLookupReturnsCombined() {
        // Given
        AyhlPlayerCareer ayhlRecord = createAyhlRecord("john smith", "2024-2025 Season", 10, 5, 3);
        ThfPlayerCareer thfRecord = createThfRecord("john smith", "2024-2025", 8, 4, 2);
        AhfPlayerCareer ahfRecord = createAhfRecord("john smith", "2023-2024", 12, 6, 4);
        
        when(ayhlRepo.findByNormalizedName("john smith"))
            .thenReturn(List.of(ayhlRecord));
        when(thfRepo.findByNormalizedName("john smith"))
            .thenReturn(List.of(thfRecord));
        when(ahfRepo.findByNormalizedName("john smith"))
            .thenReturn(List.of(ahfRecord));
        
        // When
        IntegrationDtos.SeasonsResponseDto response = service.lookupCareerRecordsByName("John Smith");
        
        // Then
        assertNotNull(response);
        assertEquals(3, response.records().size());
        verify(ayhlRepo).findByNormalizedName("john smith");
        verify(thfRepo).findByNormalizedName("john smith");
        verify(ahfRepo).findByNormalizedName("john smith");
    }
    
    @Test
    @DisplayName("lookupCareerRecordsByName: sorts by season DESC")
    void testLookupSortsBySeasonDesc() {
        // Given
        AyhlPlayerCareer older = createAyhlRecord("john green", "2022-2023 Season", 0, 0, 0);
        AyhlPlayerCareer newer = createAyhlRecord("john green", "2024-2025 Season", 0, 0, 0);
        
        when(ayhlRepo.findByNormalizedName("john green"))
            .thenReturn(List.of(older, newer));  // Repository returns unsorted
        when(thfRepo.findByNormalizedName("john green")).thenReturn(Collections.emptyList());
        when(ahfRepo.findByNormalizedName("john green")).thenReturn(Collections.emptyList());
        
        // When
        IntegrationDtos.SeasonsResponseDto response = service.lookupCareerRecordsByName("John Green");
        
        // Then
        assertEquals(2, response.records().size());
        assertEquals("2024-2025 Season", response.records().get(0).season());
        assertEquals("2022-2023 Season", response.records().get(1).season());
    }
    
    @Test
    @DisplayName("lookupCareerRecordsByName: detects ambiguity with multiple sourcePlayerIds")
    void testLookupDetectsAmbiguity() {
        // Given - two different players with same name (different sourcePlayerId)
        AyhlPlayerCareer player1 = createAyhlRecordWithId("uuid1", "id001", "john smith", "2024-2025 Season");
        AyhlPlayerCareer player2 = createAyhlRecordWithId("uuid2", "id002", "john smith", "2024-2025 Season");
        
        when(ayhlRepo.findByNormalizedName("john smith"))
            .thenReturn(List.of(player1, player2));
        when(thfRepo.findByNormalizedName("john smith")).thenReturn(Collections.emptyList());
        when(ahfRepo.findByNormalizedName("john smith")).thenReturn(Collections.emptyList());
        
        // When
        IntegrationDtos.SeasonsResponseDto response = service.lookupCareerRecordsByName("John Smith");
        
        // Then
        assertTrue(response.hasAmbiguity());
        assertNotNull(response.ambiguityNote());
        assertTrue(response.ambiguityNote().toLowerCase().contains("multiple"));
    }
    
    @Test
    @DisplayName("lookupCareerRecordsByName: no ambiguity when same player across sources")
    void testLookupNoAmbiguitySamePlayer() {
        // Given - same player in multiple sources
        AyhlPlayerCareer ayhlRecord = createAyhlRecordWithId("uuid1", "shared_id", "john smith", "2024-2025 Season");
        ThfPlayerCareer thfRecord = createThfRecordWithId("uuid2", "shared_id", "john smith", "2024-2025");
        
        when(ayhlRepo.findByNormalizedName("john smith"))
            .thenReturn(List.of(ayhlRecord));
        when(thfRepo.findByNormalizedName("john smith"))
            .thenReturn(List.of(thfRecord));
        when(ahfRepo.findByNormalizedName("john smith")).thenReturn(Collections.emptyList());
        
        // When
        IntegrationDtos.SeasonsResponseDto response = service.lookupCareerRecordsByName("John Smith");
        
        // Then
        assertFalse(response.hasAmbiguity());  // Same sourcePlayerId across sources
        assertNull(response.ambiguityNote());
    }
    
    @Test
    @DisplayName("lookupCareerRecordsByName: empty player name")
    void testLookupEmptyName() {
        // When
        IntegrationDtos.SeasonsResponseDto response = service.lookupCareerRecordsByName("");
        
        // Then
        assertNotNull(response);
        assertTrue(response.records().isEmpty());
        assertEquals(3, response.emptySources().size());
        
        verify(ayhlRepo, never()).findByNormalizedName(anyString());
        verify(thfRepo, never()).findByNormalizedName(anyString());
        verify(ahfRepo, never()).findByNormalizedName(anyString());
    }
    
    @Test
    @DisplayName("lookupCareerRecordsByName: no records found")
    void testLookupNoRecords() {
        // Given
        when(ayhlRepo.findByNormalizedName(anyString())).thenReturn(Collections.emptyList());
        when(thfRepo.findByNormalizedName(anyString())).thenReturn(Collections.emptyList());
        when(ahfRepo.findByNormalizedName(anyString())).thenReturn(Collections.emptyList());
        
        // When
        IntegrationDtos.SeasonsResponseDto response = service.lookupCareerRecordsByName("Unknown Player");
        
        // Then
        assertNotNull(response);
        assertTrue(response.records().isEmpty());
        assertTrue(response.emptySources().containsAll(List.of("AYHL", "THF", "AHF")));
        assertFalse(response.hasAmbiguity());
    }
    
    @Test
    @DisplayName("lookupCareerRecordsByName: sets cache-control header")
    void testLookupCacheControl() {
        // Given
        when(ayhlRepo.findByNormalizedName(anyString())).thenReturn(Collections.emptyList());
        when(thfRepo.findByNormalizedName(anyString())).thenReturn(Collections.emptyList());
        when(ahfRepo.findByNormalizedName(anyString())).thenReturn(Collections.emptyList());
        
        // When
        IntegrationDtos.SeasonsResponseDto response = service.lookupCareerRecordsByName("Test Player");
        
        // Then
        assertEquals("private, max-age=300", response.cacheControl());
    }
    
    // ===== HELPER METHODS =====
    
    private AyhlPlayerCareer createAyhlRecord(String playerName, String season, 
                                               Integer goals, Integer assists, Integer penalties) {
        AyhlPlayerCareer record = new AyhlPlayerCareer();
        record.setId(UUID.randomUUID());
        record.setSourcePlayerId("ayhl_" + UUID.randomUUID().toString());
        record.setPlayerName(playerName);
        record.setSeasonLabel(season);
        record.setLeagueName("AYHL");
        record.setTeamName("Test Team");
        record.setGamesPlayed(10);
        record.setGoals(goals);
        record.setAssists(assists);
        record.setPoints(goals + assists);
        record.setPenalties(penalties);
        record.setPim(penalties * 2);
        record.setCreatedAt(Instant.now());
        record.setUpdatedAt(Instant.now());
        return record;
    }
    
    private AyhlPlayerCareer createAyhlRecordWithId(String id, String sourceId, String playerName, String season) {
        AyhlPlayerCareer record = createAyhlRecord(playerName, season, 0, 0, 0);
        record.setId(UUID.fromString(id));
        record.setSourcePlayerId(sourceId);
        return record;
    }
    
    private ThfPlayerCareer createThfRecord(String playerName, String season,
                                            Integer goals, Integer assists, Integer penalties) {
        ThfPlayerCareer record = new ThfPlayerCareer();
        record.setId(UUID.randomUUID());
        record.setSourcePlayerId("thf_" + UUID.randomUUID().toString());
        record.setPlayerName(playerName);
        record.setSeasonLabel(season);
        record.setLeagueName("THF");
        record.setTeamName("Test Team");
        record.setGamesPlayed(10);
        record.setGoals(goals);
        record.setAssists(assists);
        record.setPoints(goals + assists);
        record.setPenalties(penalties);
        record.setPim((double) (penalties * 2));
        record.setCreatedAt(Instant.now());
        record.setUpdatedAt(Instant.now());
        return record;
    }
    
    private ThfPlayerCareer createThfRecordWithId(String id, String sourceId, String playerName, String season) {
        ThfPlayerCareer record = createThfRecord(playerName, season, 0, 0, 0);
        record.setId(UUID.fromString(id));
        record.setSourcePlayerId(sourceId);
        return record;
    }
    
    private AhfPlayerCareer createAhfRecord(String playerName, String season,
                                            Integer goals, Integer assists, Integer penalties) {
        AhfPlayerCareer record = new AhfPlayerCareer();
        record.setId(UUID.randomUUID());
        record.setSourcePlayerId("ahf_" + UUID.randomUUID().toString());
        record.setPlayerName(playerName);
        record.setSeasonLabel(season);
        record.setLeagueName("AHF");
        record.setTeamName("Test Team");
        record.setGamesPlayed(10);
        record.setGoals(goals);
        record.setAssists(assists);
        record.setPoints(goals + assists);
        record.setPenalties(penalties);
        record.setPim((double) (penalties * 2));
        record.setCreatedAt(Instant.now());
        record.setUpdatedAt(Instant.now());
        return record;
    }
}
