package com.myhockeystats.api.dto.integration;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

public final class IntegrationDtos {
    private IntegrationDtos() {
    }

    public record MatchCandidateDto(
            String candidateId,
            String source,
            String displayName,
            String seasonLabel,
            String matchMethod,
            double score,
            List<String> reasons) {
    }

    public record MatchLinkDto(
            String source,
            String sourcePlayerId,
            String linkState,
            OffsetDateTime lastVerifiedAt) {
    }

    public record MatchStatusDto(
            String status,
            String birthMonthYear,
            List<MatchCandidateDto> candidates,
            List<MatchLinkDto> links) {
    }

    public record ConfirmMatchRequest(List<ConfirmCandidateDto> selectedCandidates) {
    }

        public record ConfirmCandidateDto(String source, String candidateId) {
    }

    public record ConfirmMatchResponse(String status, int updatedLinks) {
    }

    public record TeamHistoryDto(
            String source,
            String sourcePlayerId,
            String club,
            String team,
            String jerseyNumber,
            Integer gamesPlayed,
            Integer goals,
            Integer assists,
            Integer points,
            Integer penalties,
            Double pim) {
    }

    public record GameConflictDto(String field, Map<String, Integer> values) {
    }

    public record GameSourceValueDto(Integer goals, Integer assists, String finalScore) {
    }

    public record UnifiedGameDto(
            String logicalGameKey,
            LocalDate date,
            String opponent,
            Map<String, GameSourceValueDto> sourceValues,
            boolean hasConflict,
            List<GameConflictDto> conflicts) {
    }

    public record UnifiedHistoryDto(
            String season,
            List<String> sources,
            List<String> missingSources,
            List<TeamHistoryDto> teamHistory,
            List<UnifiedGameDto> games,
            List<String> availableSeasons) {
    }

    // Simplified career lookup DTOs (002-integrate-player-data)
    // Represents a single player career record from one source (AYHL, THF, AHF)
    public record SeasonCareerRecordDto(
            String source,                    // "AYHL", "THF", or "AHF"
            String sourcePlayerId,            // Original ID from source league
            String playerName,                // Original player name from source
            String season,                    // e.g. "2024-2025"
            String club,                      // Club/association name
            String team,                      // Team name
            String jerseyNumber,              // Optional
            Integer gamesPlayed,
            Integer goals,
            Integer assists,
            Integer points,                   // Computed: goals + assists
            Integer penalties,
            Double pim,                       // Penalty in minutes
            OffsetDateTime importedAt,        // When data was imported
            boolean isAmbiguousMembership,    // true if multiple players found
            String ambiguityNote              // Helpful message if ambiguity
    ) {
    }

    // Response wrapper for career lookup with metadata
    public record SeasonsResponseDto(
            String playerId,                  // UUID of queried player
            String playerName,                // From player profile
            List<SeasonCareerRecordDto> records,  // All matching records
            boolean hasAmbiguity,             // true if multiple players found
            String ambiguityNote,             // Helpful message if ambiguity
            java.util.Set<String> availableSources,  // Which sources have data
            java.util.Set<String> emptySources,     // Which sources have no data
            OffsetDateTime fetchedAt,         // Timestamp of query
            String cacheControl               // "private, max-age=300"
    ) {
    }

    public record GameHistorySeasonOptionDto(
            Integer seasonYear,
            String seasonLabel
    ) {
    }

    public record GameHistoryGameDto(
            String source,
            Integer seasonYear,
            String seasonLabel,
            String gameId,
            LocalDate gameDate,
            String gameType,
            String league,
            String teamFor,
            String teamAgainst,
            Integer goals,
            Integer assists,
            Integer points,
            Integer pim
    ) {
    }

    public record GameHistoryResponseDto(
            String playerId,
            String playerName,
            Integer selectedSeasonYear,
            String selectedSeasonLabel,
            List<GameHistorySeasonOptionDto> availableSeasons,
            List<GameHistoryGameDto> games,
            OffsetDateTime fetchedAt,
            String cacheControl
    ) {
    }
}
