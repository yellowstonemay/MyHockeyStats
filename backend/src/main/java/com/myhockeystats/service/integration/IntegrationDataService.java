package com.myhockeystats.service.integration;

import com.myhockeystats.repository.PlayerProfileRepository;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
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

    public List<CareerHistoryRecord> findCareerHistory(PlayerContext context, String seasonLabel) {
        String normalizedExpr = """
                TRIM(REGEXP_REPLACE(
                    LOWER(REGEXP_REPLACE(COALESCE(player_name, ''), '[^a-zA-Z0-9 ]', ' ', 'g')),
                    '\\s+',
                    ' ',
                    'g'
                ))
                """;

        String sql = """
            SELECT source, source_player_id, season_label, source_club_name, source_team_name,
                   jersey_number, games_played, goals, assists, points, penalties, pim
                FROM (
                    SELECT
                        'AYHL' AS source,
                        source_player_id,
                        season_label,
                        COALESCE(league_name, 'AYHL') AS source_club_name,
                        COALESCE(team_name, 'Unknown Team') AS source_team_name,
                        jersey_number,
                        games_played,
                        goals,
                        assists,
                        points,
                        penalties,
                        CAST(pim AS DOUBLE PRECISION) AS pim,
                        %s AS normalized_name
                    FROM ayhl_player_career

                    UNION ALL

                    SELECT
                        'THF' AS source,
                        source_player_id,
                        season_label,
                        COALESCE(league_name, 'THF') AS source_club_name,
                        COALESCE(team_name, 'Unknown Team') AS source_team_name,
                        jersey_number,
                        games_played,
                        goals,
                        assists,
                        points,
                        penalties,
                        CAST(pim AS DOUBLE PRECISION) AS pim,
                        %s AS normalized_name
                    FROM thf_player_career

                    UNION ALL

                    SELECT
                        'AHF' AS source,
                        source_player_id,
                        season_label,
                        COALESCE(league_name, 'AHF') AS source_club_name,
                        COALESCE(team_name, 'Unknown Team') AS source_team_name,
                        jersey_number,
                        games_played,
                        goals,
                        assists,
                        points,
                        penalties,
                        CAST(pim AS DOUBLE PRECISION) AS pim,
                        %s AS normalized_name
                    FROM ahf_player_career
                ) careers
                WHERE %%s
                """.formatted(normalizedExpr, normalizedExpr, normalizedExpr);

            NameMatchClause nameMatchClause = buildNameMatchClause(context.normalizedName());
            sql = sql.formatted(nameMatchClause.sql());
            List<Object> args = new ArrayList<>(nameMatchClause.args());
        if (seasonLabel != null && !seasonLabel.isBlank()) {
            sql += " AND season_label = ?";
            args.add(seasonLabel);
        }
        sql += " ORDER BY season_label DESC, source, source_team_name";

        return jdbcTemplate.query(sql, (rs, rowNum) -> new CareerHistoryRecord(
                rs.getString("source"),
                rs.getString("source_player_id"),
                rs.getString("season_label"),
                rs.getString("source_club_name"),
            rs.getString("source_team_name"),
            rs.getString("jersey_number"),
            (Integer) rs.getObject("games_played"),
            (Integer) rs.getObject("goals"),
            (Integer) rs.getObject("assists"),
            (Integer) rs.getObject("points"),
            (Integer) rs.getObject("penalties"),
            (Double) rs.getObject("pim")), args.toArray());
    }

    public List<String> findCareerAvailableSeasons(PlayerContext context) {
        return findCareerHistory(context, null).stream()
                .map(CareerHistoryRecord::seasonLabel)
                .distinct()
                .toList();
    }

    public List<PlayerIdentityLink> findPersistedIdentityLinks(Long userId) {
        String sql = """
                SELECT source, source_player_id, link_state, last_verified_at
                FROM player_identity_map
                WHERE user_id = ?
                ORDER BY source, last_verified_at DESC NULLS LAST
                """;
        return jdbcTemplate.query(sql, (rs, rowNum) -> new PlayerIdentityLink(
                rs.getString("source"),
                rs.getString("source_player_id"),
                rs.getString("link_state"),
                toOffsetDateTime(rs.getTimestamp("last_verified_at"))), userId);
    }

    public int replaceConfirmedIdentityLinks(Long userId, List<ConfirmedIdentitySelection> selections) {
        int insertedCount = 0;
        Set<String> processedSources = new LinkedHashSet<>();

        for (ConfirmedIdentitySelection selection : selections) {
            if (selection.source() == null || selection.source().isBlank()
                    || selection.sourcePlayerId() == null || selection.sourcePlayerId().isBlank()) {
                continue;
            }

            String source = selection.source().trim().toUpperCase();
            if (!processedSources.add(source)) {
                continue;
            }

            jdbcTemplate.update("DELETE FROM player_identity_map WHERE user_id = ? AND source = ?", userId, source);

            insertedCount += jdbcTemplate.update(
                    """
                    INSERT INTO player_identity_map (
                        id, user_id, source, source_player_id, link_state,
                        match_method, confidence_score, confirmed_at, last_verified_at,
                        created_at, updated_at
                    )
                    VALUES (?, ?, ?, ?, ?, ?, ?, NOW(), NOW(), NOW(), NOW())
                    """,
                    UUID.randomUUID(),
                    userId,
                    source,
                    selection.sourcePlayerId(),
                    "CONFIRMED",
                    "USER_SELECTION",
                    1.0);
        }

        return insertedCount;
    }

    public List<CareerCandidateRecord> findCareerCandidates(PlayerContext context) {
        String normalizedExpr = """
                TRIM(REGEXP_REPLACE(
                    LOWER(REGEXP_REPLACE(COALESCE(player_name, ''), '[^a-zA-Z0-9 ]', ' ', 'g')),
                    '\\s+',
                    ' ',
                    'g'
                ))
                """;

        String sql = """
                SELECT DISTINCT ON (source, source_player_id)
                    source,
                    source_player_id,
                    COALESCE(player_name, '') AS player_name,
                    season_label
                FROM (
                    SELECT
                        'AYHL' AS source,
                        source_player_id,
                        player_name,
                        season_label,
                        %s AS normalized_name
                    FROM ayhl_player_career

                    UNION ALL

                    SELECT
                        'THF' AS source,
                        source_player_id,
                        player_name,
                        season_label,
                        %s AS normalized_name
                    FROM thf_player_career

                    UNION ALL

                    SELECT
                        'AHF' AS source,
                        source_player_id,
                        player_name,
                        season_label,
                        %s AS normalized_name
                    FROM ahf_player_career
                ) careers
                WHERE %%s
                ORDER BY source, source_player_id, season_label DESC
                """.formatted(normalizedExpr, normalizedExpr, normalizedExpr);

        NameMatchClause nameMatchClause = buildNameMatchClause(context.normalizedName());
        sql = sql.formatted(nameMatchClause.sql());

        return jdbcTemplate.query(sql, (rs, rowNum) -> new CareerCandidateRecord(
                rs.getString("source"),
                rs.getString("source_player_id"),
                rs.getString("player_name"),
                rs.getString("season_label")), nameMatchClause.args().toArray());
    }

    private NameMatchClause buildNameMatchClause(String normalizedName) {
        List<String> tokens = Arrays.stream((normalizedName == null ? "" : normalizedName).trim().split("\\s+"))
                .map(String::trim)
                .filter(token -> !token.isBlank())
                .distinct()
                .toList();

        if (tokens.isEmpty()) {
            return new NameMatchClause("normalized_name = ''", List.of());
        }

        StringBuilder sql = new StringBuilder();
        List<Object> args = new ArrayList<>();
        for (String token : tokens) {
            if (!sql.isEmpty()) {
                sql.append(" AND ");
            }
            sql.append("normalized_name ~ ?");
            args.add("(^| )" + token + "( |$)");
        }
        return new NameMatchClause(sql.toString(), args);
    }

    public List<CareerHistoryRecord> findCareerHistoryByIdentityLinks(List<PlayerIdentityLink> identityLinks, String seasonLabel) {
        if (identityLinks == null || identityLinks.isEmpty()) {
            return List.of();
        }

        StringBuilder where = new StringBuilder();
        List<Object> args = new ArrayList<>();
        for (PlayerIdentityLink link : identityLinks) {
            if (link.source() == null || link.source().isBlank() || link.sourcePlayerId() == null || link.sourcePlayerId().isBlank()) {
                continue;
            }
            if (!where.isEmpty()) {
                where.append(" OR ");
            }
            where.append("(source = ? AND source_player_id = ?)");
            args.add(link.source().trim().toUpperCase());
            args.add(link.sourcePlayerId());
        }
        if (where.isEmpty()) {
            return List.of();
        }

        String sql = """
                SELECT source, source_player_id, season_label, source_club_name, source_team_name,
                       jersey_number, games_played, goals, assists, points, penalties, pim
                FROM (
                    SELECT
                        'AYHL' AS source,
                        source_player_id,
                        season_label,
                        COALESCE(league_name, 'AYHL') AS source_club_name,
                        COALESCE(team_name, 'Unknown Team') AS source_team_name,
                        jersey_number,
                        games_played,
                        goals,
                        assists,
                        points,
                        penalties,
                        CAST(pim AS DOUBLE PRECISION) AS pim
                    FROM ayhl_player_career

                    UNION ALL

                    SELECT
                        'THF' AS source,
                        source_player_id,
                        season_label,
                        COALESCE(league_name, 'THF') AS source_club_name,
                        COALESCE(team_name, 'Unknown Team') AS source_team_name,
                        jersey_number,
                        games_played,
                        goals,
                        assists,
                        points,
                        penalties,
                        CAST(pim AS DOUBLE PRECISION) AS pim
                    FROM thf_player_career

                    UNION ALL

                    SELECT
                        'AHF' AS source,
                        source_player_id,
                        season_label,
                        COALESCE(league_name, 'AHF') AS source_club_name,
                        COALESCE(team_name, 'Unknown Team') AS source_team_name,
                        jersey_number,
                        games_played,
                        goals,
                        assists,
                        points,
                        penalties,
                        CAST(pim AS DOUBLE PRECISION) AS pim
                    FROM ahf_player_career
                ) careers
                WHERE
                """ + where;

        if (seasonLabel != null && !seasonLabel.isBlank()) {
            sql += " AND season_label = ?";
            args.add(seasonLabel);
        }
        sql += " ORDER BY season_label DESC, source, source_team_name";

        return jdbcTemplate.query(sql, (rs, rowNum) -> new CareerHistoryRecord(
                rs.getString("source"),
                rs.getString("source_player_id"),
                rs.getString("season_label"),
                rs.getString("source_club_name"),
            rs.getString("source_team_name"),
            rs.getString("jersey_number"),
            (Integer) rs.getObject("games_played"),
            (Integer) rs.getObject("goals"),
            (Integer) rs.getObject("assists"),
            (Integer) rs.getObject("points"),
            (Integer) rs.getObject("penalties"),
            (Double) rs.getObject("pim")), args.toArray());
    }

    public List<String> findCareerAvailableSeasonsByIdentityLinks(List<PlayerIdentityLink> identityLinks) {
        return findCareerHistoryByIdentityLinks(identityLinks, null).stream()
                .map(CareerHistoryRecord::seasonLabel)
                .distinct()
                .toList();
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

    public int countTrackedPlayersBySource(String source) {
        String normalizedSource = source == null ? "" : source.trim().toUpperCase();

        String sql = switch (normalizedSource) {
            case "AYHL" -> "SELECT COUNT(DISTINCT source_player_id) FROM ayhl_player_career";
            case "THF" -> "SELECT COUNT(DISTINCT source_player_id) FROM thf_player_career";
            case "AHF" -> "SELECT COUNT(DISTINCT source_player_id) FROM ahf_player_career";
            default -> null;
        };

        if (sql == null) {
            return 0;
        }

        Integer count = jdbcTemplate.queryForObject(sql, Integer.class);
        return count == null ? 0 : count;
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

    public record PlayerIdentityLink(String source, String sourcePlayerId, String linkState, OffsetDateTime lastVerifiedAt) {
    }

    public record ConfirmedIdentitySelection(String source, String sourcePlayerId) {
    }

    public record CareerCandidateRecord(String source, String sourcePlayerId, String playerName, String seasonLabel) {
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

    public record CareerHistoryRecord(
            String source,
            String sourcePlayerId,
            String seasonLabel,
            String sourceClubName,
            String sourceTeamName,
            String jerseyNumber,
            Integer gamesPlayed,
            Integer goals,
            Integer assists,
            Integer points,
            Integer penalties,
            Double pim) {
    }

    private record NameMatchClause(String sql, List<Object> args) {
    }
}