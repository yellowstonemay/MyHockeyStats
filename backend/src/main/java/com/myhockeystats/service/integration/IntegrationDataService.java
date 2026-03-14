package com.myhockeystats.service.integration;

import com.myhockeystats.repository.PlayerProfileRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;

@Service
public class IntegrationDataService {

    private final JdbcTemplate jdbcTemplate;
    private final PlayerProfileRepository playerProfileRepository;
    private final IdentityNormalizationService identityNormalizationService;

    public IntegrationDataService(
            JdbcTemplate jdbcTemplate,
            PlayerProfileRepository playerProfileRepository,
            IdentityNormalizationService identityNormalizationService) {
        this.jdbcTemplate = jdbcTemplate;
        this.playerProfileRepository = playerProfileRepository;
        this.identityNormalizationService = identityNormalizationService;
    }

    public Optional<PlayerContext> getPlayerContext(Long userId, String fallbackFullName, int fallbackBirthYear, int fallbackBirthMonth) {
        return playerProfileRepository.findByUserId(userId)
                .map(profile -> new PlayerContext(
                        userId,
                        profile.getFullName(),
                        identityNormalizationService.normalizeName(profile.getFullName()),
                        profile.getBirthdate().getYear(),
                        profile.getBirthdate().getMonthValue()))
                .or(() -> {
                    if (fallbackFullName == null || fallbackFullName.isBlank() || fallbackBirthYear <= 0 || fallbackBirthMonth <= 0) {
                        return Optional.empty();
                    }
                    return Optional.of(new PlayerContext(
                            userId,
                            fallbackFullName,
                            identityNormalizationService.normalizeName(fallbackFullName),
                            fallbackBirthYear,
                            fallbackBirthMonth));
                });
    }

    public List<ImportedPlayerRecord> findExactMatches(PlayerContext context) {
        return findExactMatches(context, null);
    }

    public List<ImportedPlayerRecord> findExactMatches(PlayerContext context, String seasonLabel) {
        String baseSql = """
                SELECT id, source, player_name_raw, player_name_normalized, birth_month, birth_year,
                       season_label, source_team_id, COALESCE(source_team_name, source_team_id) AS source_team_name,
                       source_club_id, COALESCE(source_club_name, source) AS source_club_name
                FROM integration_imported_player_record
                WHERE player_name_normalized = ? AND birth_year = ? AND birth_month = ?
                """;

        List<Object> args = new ArrayList<>(List.of(context.normalizedName(), context.birthYear(), context.birthMonth()));
        if (seasonLabel != null && !seasonLabel.isBlank()) {
            baseSql += " AND season_label = ?";
            args.add(seasonLabel);
        }
        baseSql += " ORDER BY season_label DESC, source, source_team_name";
        return jdbcTemplate.query(baseSql, importedPlayerRecordRowMapper(), args.toArray());
    }

    public List<ImportedPlayerRecord> findExactNameYearMatches(PlayerContext context) {
        return findExactNameYearMatches(context, null);
    }

    public List<ImportedPlayerRecord> findExactNameYearMatches(PlayerContext context, String seasonLabel) {
        String baseSql = """
                SELECT id, source, player_name_raw, player_name_normalized, birth_month, birth_year,
                       season_label, source_team_id, COALESCE(source_team_name, source_team_id) AS source_team_name,
                       source_club_id, COALESCE(source_club_name, source) AS source_club_name
                FROM integration_imported_player_record
                WHERE player_name_normalized = ? AND birth_year = ?
                """;

        List<Object> args = new ArrayList<>(List.of(context.normalizedName(), context.birthYear()));
        if (seasonLabel != null && !seasonLabel.isBlank()) {
            baseSql += " AND season_label = ?";
            args.add(seasonLabel);
        }
        baseSql += " ORDER BY season_label DESC, source, source_team_name";
        return jdbcTemplate.query(baseSql, importedPlayerRecordRowMapper(), args.toArray());
    }

    public List<ImportedPlayerRecord> findBestMatches(PlayerContext context, String seasonLabel) {
        List<ImportedPlayerRecord> exactMatches = findExactMatches(context, seasonLabel);
        if (!exactMatches.isEmpty()) {
            return exactMatches;
        }
        return findExactNameYearMatches(context, seasonLabel);
    }

    public List<ImportedPlayerRecord> findBirthMonthYearMatches(int birthYear, int birthMonth) {
        String sql = """
                SELECT id, source, player_name_raw, player_name_normalized, birth_month, birth_year,
                       season_label, source_team_id, COALESCE(source_team_name, source_team_id) AS source_team_name,
                       source_club_id, COALESCE(source_club_name, source) AS source_club_name
                FROM integration_imported_player_record
                WHERE birth_year = ? AND birth_month = ?
                ORDER BY season_label DESC, source, player_name_raw
                LIMIT 50
                """;
        return jdbcTemplate.query(sql, importedPlayerRecordRowMapper(), birthYear, birthMonth);
    }

    public List<MatchLinkRow> findPersistedLinks(Long userId) {
        String sql = """
                SELECT ml.source, ml.imported_player_record_id, ml.link_state, ml.last_verified_at
                FROM integration_match_link ml
                WHERE ml.user_id = ?
                ORDER BY ml.source, ml.last_verified_at DESC NULLS LAST
                """;
        return jdbcTemplate.query(sql, (rs, rowNum) -> new MatchLinkRow(
                rs.getString("source"),
                UUID.fromString(rs.getString("imported_player_record_id")),
                rs.getString("link_state"),
                toOffsetDateTime(rs.getTimestamp("last_verified_at"))), userId);
    }

    public ImportedPlayerRecord findImportedRecord(UUID importedPlayerRecordId) {
        String sql = """
                SELECT id, source, player_name_raw, player_name_normalized, birth_month, birth_year,
                       season_label, source_team_id, COALESCE(source_team_name, source_team_id) AS source_team_name,
                       source_club_id, COALESCE(source_club_name, source) AS source_club_name
                FROM integration_imported_player_record
                WHERE id = ?
                """;
        return jdbcTemplate.queryForObject(sql, importedPlayerRecordRowMapper(), importedPlayerRecordId);
    }

    public List<ImportedPlayerRecord> findEquivalentRecords(ImportedPlayerRecord selectedRecord) {
        String sql = """
                SELECT id, source, player_name_raw, player_name_normalized, birth_month, birth_year,
                       season_label, source_team_id, COALESCE(source_team_name, source_team_id) AS source_team_name,
                       source_club_id, COALESCE(source_club_name, source) AS source_club_name
                FROM integration_imported_player_record
                WHERE source = ?
                  AND player_name_normalized = ?
                  AND birth_year = ?
                  AND birth_month = ?
                ORDER BY season_label DESC, source_team_name
                """;
        return jdbcTemplate.query(
                sql,
                importedPlayerRecordRowMapper(),
                selectedRecord.source(),
                selectedRecord.playerNameNormalized(),
                selectedRecord.birthYear(),
                selectedRecord.birthMonth());
    }

    public int replaceConfirmedLinks(Long userId, List<UUID> selectedRecordIds) {
        int insertedCount = 0;
        Set<String> processedSources = new LinkedHashSet<>();

        for (UUID selectedRecordId : selectedRecordIds) {
            ImportedPlayerRecord selectedRecord = findImportedRecord(selectedRecordId);
            if (!processedSources.add(selectedRecord.source())) {
                continue;
            }

            jdbcTemplate.update("DELETE FROM integration_match_link WHERE user_id = ? AND source = ?", userId, selectedRecord.source());

            for (ImportedPlayerRecord equivalentRecord : findEquivalentRecords(selectedRecord)) {
                insertedCount += jdbcTemplate.update(
                        """
                        INSERT INTO integration_match_link (
                            id, user_id, source, imported_player_record_id, link_state,
                            match_method, confidence_score, confirmed_at, last_verified_at, identity_fingerprint
                        )
                        VALUES (?, ?, ?, ?, ?, ?, ?, NOW(), NOW(), ?)
                        """,
                        UUID.randomUUID(),
                        userId,
                        equivalentRecord.source(),
                        equivalentRecord.id(),
                        "CONFIRMED",
                        "EXACT_NORMALIZED",
                        1.0,
                        equivalentRecord.playerNameNormalized() + "|" + equivalentRecord.birthYear() + "-" + equivalentRecord.birthMonth());
            }
        }

        return insertedCount;
    }

    public List<String> findAvailableSeasons(PlayerContext context) {
        List<ImportedPlayerRecord> bestMatches = findBestMatches(context, null);
        return bestMatches.stream().map(ImportedPlayerRecord::seasonLabel).distinct().toList();
    }

    public Optional<ImportRunRow> findImportRun(UUID runId) {
        String sql = """
                SELECT id, source, status, started_at, ended_at,
                       processed_count, accepted_count, rejected_count, duplicate_skipped_count, error_summary
                FROM integration_import_run
                WHERE id = ?
                """;
        List<ImportRunRow> results = jdbcTemplate.query(sql, importRunRowMapper(), runId);
        return results.stream().findFirst();
    }

    public Optional<ImportRunRow> findLatestImportRunBySource(String source) {
        String sql = """
                SELECT id, source, status, started_at, ended_at,
                       processed_count, accepted_count, rejected_count, duplicate_skipped_count, error_summary
                FROM integration_import_run
                WHERE source = ?
                ORDER BY started_at DESC
                LIMIT 1
                """;
        List<ImportRunRow> results = jdbcTemplate.query(sql, importRunRowMapper(), source);
        return results.stream().findFirst();
    }

    public int countImportedPlayersBySource(String source) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM integration_imported_player_record WHERE source = ?",
                Integer.class,
                source);
        return count == null ? 0 : count;
    }

    private RowMapper<ImportedPlayerRecord> importedPlayerRecordRowMapper() {
        return (rs, rowNum) -> new ImportedPlayerRecord(
                UUID.fromString(rs.getString("id")),
                rs.getString("source"),
                rs.getString("player_name_raw"),
                rs.getString("player_name_normalized"),
                rs.getInt("birth_month"),
                rs.getInt("birth_year"),
                rs.getString("season_label"),
                rs.getString("source_team_id"),
                rs.getString("source_team_name"),
                rs.getString("source_club_id"),
                rs.getString("source_club_name"));
    }

    private RowMapper<ImportRunRow> importRunRowMapper() {
        return (rs, rowNum) -> new ImportRunRow(
                UUID.fromString(rs.getString("id")),
                rs.getString("source"),
                rs.getString("status"),
                toOffsetDateTime(rs.getTimestamp("started_at")),
                toOffsetDateTime(rs.getTimestamp("ended_at")),
                rs.getInt("processed_count"),
                rs.getInt("accepted_count"),
                rs.getInt("rejected_count"),
                rs.getInt("duplicate_skipped_count"),
                rs.getString("error_summary"));
    }

    private OffsetDateTime toOffsetDateTime(Timestamp timestamp) {
        if (timestamp == null) {
            return null;
        }
        return timestamp.toInstant().atOffset(ZoneOffset.UTC);
    }

    public record PlayerContext(Long userId, String displayName, String normalizedName, int birthYear, int birthMonth) {
    }

    public record ImportedPlayerRecord(
            UUID id,
            String source,
            String playerNameRaw,
            String playerNameNormalized,
            int birthMonth,
            int birthYear,
            String seasonLabel,
            String sourceTeamId,
            String sourceTeamName,
            String sourceClubId,
            String sourceClubName) {
    }

    public record MatchLinkRow(String source, UUID importedPlayerRecordId, String linkState, OffsetDateTime lastVerifiedAt) {
    }

    public record ImportRunRow(
            UUID runId,
            String source,
            String status,
            OffsetDateTime startedAt,
            OffsetDateTime endedAt,
            int processed,
            int accepted,
            int rejected,
            int duplicateSkipped,
            String errorSummary) {
    }
}