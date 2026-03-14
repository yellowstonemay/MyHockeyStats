package com.myhockeystats.model.integration;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

public final class ImportDomainEntities {
    private ImportDomainEntities() {
    }

    public enum IntegrationSource {
        THF,
        AYHL,
        GAMESHEET
    }

    public enum ImportTriggerType {
        SCHEDULED_DAILY,
        OPERATOR_MANUAL
    }

    public enum ImportRunStatus {
        RUNNING,
        COMPLETED,
        FAILED,
        PARTIAL
    }

    public enum LinkState {
        PENDING_SELECTION,
        CONFIRMED,
        REVERIFY_REQUIRED,
        UNLINKED
    }

    public enum MatchMethod {
        EXACT_NORMALIZED,
        FUZZY_CONFIRMED
    }

    public enum ConflictType {
        GAME_STAT,
        TEAM_ASSIGNMENT,
        SCORE,
        OTHER
    }

    public record ImportRunSummary(
            UUID runId,
            IntegrationSource source,
            ImportRunStatus status,
            Instant startedAt,
            Instant endedAt,
            int processedCount,
            int acceptedCount,
            int rejectedCount,
            int duplicateSkippedCount,
            String errorSummary) {
    }

    public record ImportedPlayerIdentity(
            UUID importedPlayerRecordId,
            IntegrationSource source,
            String playerNameRaw,
            String playerNameNormalized,
            int birthMonth,
            int birthYear,
            String seasonLabel,
            String sourceHash) {
    }

    public record ImportedGameStat(
            UUID importedGameRecordId,
            UUID importedPlayerRecordId,
            String logicalGameKey,
            LocalDate gameDate,
            String opponentName,
            Integer goals,
            Integer assists,
            Integer points,
            String finalScoreText) {
    }

    public record SourceConflictSnapshot(
            UUID conflictId,
            long userId,
            String logicalRecordKey,
            ConflictType conflictType,
            String fieldName,
            Map<String, String> sourceValues,
            boolean active,
            Instant detectedAt) {
    }
}
