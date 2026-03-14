package com.myhockeystats.api.dto.integration;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class IntegrationDtos {
    private IntegrationDtos() {
    }

    public record MatchCandidateDto(
            UUID candidateId,
            String source,
            String displayName,
            String seasonLabel,
            String matchMethod,
            double score,
            List<String> reasons) {
    }

    public record MatchLinkDto(
            String source,
            UUID importedPlayerRecordId,
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

    public record ConfirmCandidateDto(String source, UUID candidateId) {
    }

    public record ConfirmMatchResponse(String status, int updatedLinks) {
    }

    public record TeamHistoryDto(String source, String club, String team) {
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
}
