package com.myhockeystats.service.integration;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Reads MYHockeyRankings games for a player profile.
 *
 * MHR games are not reachable through {@code player_source_links}: the scrape
 * stores the profile id it resolved next to the scraped player name, so games
 * are matched by profile id or by canonicalized player name — the same matching
 * {@link GameHistoryLookupService} uses for the game history tab. Manual
 * goals/assists overrides win over the scraped (usually empty) values.
 */
@Service
public class MhrGameLookupService {

    private static final String SOURCE = "MHR";
    private static final String TABLE = "mhr_player_games";
    private static final String CANONICAL_NAME_SQL =
        "LOWER(REGEXP_REPLACE(COALESCE(t.player_name, ''), '[[:space:],.''-]', '', 'g'))";

    private final JdbcTemplate jdbcTemplate;

    /** Cached "does this table exist" answers (null = not checked yet). */
    private Boolean tableAvailable;
    private Boolean overrideTableAvailable;

    public MhrGameLookupService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** One MHR game with any manual stats already applied. */
    public record MhrGame(Long profileId, String gameId, Integer seasonYear, LocalDate date,
                          String teamFor, String opponent, Integer goals, Integer assists,
                          Integer points, Integer pim, Integer scoreFor, Integer scoreAgainst,
                          boolean statsEdited) {}

    /**
     * Newest MHR games for one player profile, matched by profile id or by the
     * canonical player name (rows scraped before a profile merge keep the old id).
     */
    public List<MhrGame> recentGames(Long profileId, String playerName, int limit) {
        if (profileId == null || limit <= 0 || !tableAvailable()) {
            return List.of();
        }
        Set<String> canonicalNames = buildCanonicalLookupKeys(playerName);
        boolean hasOverrides = overrideTableAvailable();

        List<Object> params = new ArrayList<>();
        String overrideJoin = "";
        if (hasOverrides) {
            overrideJoin = " LEFT JOIN game_stat_overrides o ON o.player_profile_id = ? AND o.source = '" + SOURCE + "'"
                + " AND o.season_year = t.season_year AND o.game_id = t.game_id";
            params.add(profileId);
        }
        String goalsColumn = hasOverrides ? "COALESCE(o.goals, t.goals)" : "t.goals";
        String assistsColumn = hasOverrides ? "COALESCE(o.assists, t.assists)" : "t.assists";
        String pimColumn = hasOverrides ? "COALESCE(o.pim, t.pim)" : "t.pim";
        String editedColumn = hasOverrides ? ", (o.id IS NOT NULL) AS stats_edited" : ", false AS stats_edited";

        String nameClause = "";
        if (!canonicalNames.isEmpty()) {
            nameClause = " OR " + CANONICAL_NAME_SQL + " IN ("
                + canonicalNames.stream().map(n -> "?").collect(Collectors.joining(",")) + ")";
        }

        String sql = "SELECT t.game_id, t.season_year, t.game_date, t.team_for, t.team_against,"
            + " " + goalsColumn + " AS goals, " + assistsColumn + " AS assists, t.points AS scraped_points,"
            + " " + pimColumn + " AS pim, t.score_for, t.score_against, t.scraped_at" + editedColumn
            + " FROM " + TABLE + " t" + overrideJoin
            + " WHERE (t.player_id = ?" + nameClause + ") AND t.game_date IS NOT NULL"
            + " ORDER BY t.game_date DESC, t.game_id DESC LIMIT " + limit;
        params.add(String.valueOf(profileId));
        params.addAll(canonicalNames);

        return jdbcTemplate.query(sql, ps -> {
            for (int i = 0; i < params.size(); i++) {
                ps.setObject(i + 1, params.get(i));
            }
        }, (rs, rowNum) -> {
            Integer goals = (Integer) rs.getObject("goals");
            Integer assists = (Integer) rs.getObject("assists");
            boolean edited = rs.getBoolean("stats_edited");
            // MHR publishes no per-player goals/assists — only a manual entry has them.
            Integer points = (Integer) rs.getObject("scraped_points");
            if (edited && (goals != null || assists != null)) {
                points = (goals == null ? 0 : goals) + (assists == null ? 0 : assists);
            }
            Date date = rs.getDate("game_date");
            return new MhrGame(
                profileId,
                rs.getString("game_id"),
                (Integer) rs.getObject("season_year"),
                date == null ? null : date.toLocalDate(),
                rs.getString("team_for"),
                rs.getString("team_against"),
                goals,
                assists,
                points,
                (Integer) rs.getObject("pim"),
                (Integer) rs.getObject("score_for"),
                (Integer) rs.getObject("score_against"),
                edited
            );
        });
    }

    /** Last scrape time for a profile's MHR games. */
    public Instant lastScrapedAt(Long profileId, String playerName) {
        if (profileId == null || !tableAvailable()) {
            return null;
        }
        Set<String> canonicalNames = buildCanonicalLookupKeys(playerName);
        List<Object> params = new ArrayList<>();
        String nameClause = "";
        if (!canonicalNames.isEmpty()) {
            nameClause = " OR " + CANONICAL_NAME_SQL + " IN ("
                + canonicalNames.stream().map(n -> "?").collect(Collectors.joining(",")) + ")";
        }
        params.add(String.valueOf(profileId));
        params.addAll(canonicalNames);
        try {
            List<Timestamp> ts = jdbcTemplate.query(
                "SELECT MAX(t.scraped_at) FROM " + TABLE + " t WHERE (t.player_id = ?" + nameClause + ")",
                ps -> {
                    for (int i = 0; i < params.size(); i++) {
                        ps.setObject(i + 1, params.get(i));
                    }
                },
                (rs, rowNum) -> rs.getTimestamp(1));
            return ts.isEmpty() || ts.get(0) == null ? null : ts.get(0).toInstant();
        } catch (Exception e) {
            return null;
        }
    }

    private boolean tableAvailable() {
        if (tableAvailable == null) {
            tableAvailable = tableExists(TABLE);
        }
        return tableAvailable;
    }

    private boolean overrideTableAvailable() {
        if (overrideTableAvailable == null) {
            overrideTableAvailable = tableExists("game_stat_overrides");
        }
        return overrideTableAvailable;
    }

    private boolean tableExists(String tableName) {
        Integer exists = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = current_schema() AND table_name = ?",
            Integer.class,
            tableName
        );
        return exists != null && exists > 0;
    }

    private static String normalizeName(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return value.toLowerCase().trim().replaceAll("['-.]", "").replaceAll("\\s+", " ");
    }

    private static String canonicalName(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return value.toLowerCase().trim().replaceAll("[\\s,.'-]", "");
    }

    private static Set<String> buildCanonicalLookupKeys(String playerName) {
        String normalized = normalizeName(playerName);
        if (normalized.isBlank()) {
            return Collections.emptySet();
        }
        Set<String> keys = new LinkedHashSet<>();
        keys.add(canonicalName(normalized));
        String[] parts = normalized.split(" ");
        if (parts.length == 2) {
            keys.add(canonicalName(parts[1] + "," + parts[0])); // handle "Last,First"
        }
        if (normalized.contains(",")) {
            String[] csv = normalized.split(",", 2);
            if (csv.length == 2) {
                keys.add(canonicalName(csv[1] + " " + csv[0]));
            }
        }
        return keys.stream().filter(s -> !s.isBlank()).collect(Collectors.toCollection(LinkedHashSet::new));
    }
}
