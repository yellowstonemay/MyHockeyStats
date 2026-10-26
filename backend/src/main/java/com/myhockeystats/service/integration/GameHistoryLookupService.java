package com.myhockeystats.service.integration;

import com.myhockeystats.api.dto.integration.IntegrationDtos;
import com.myhockeystats.service.PlayerSourceLinkService;
import java.sql.Date;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class GameHistoryLookupService {

    private static final Map<String, String> SOURCE_TABLES = Map.of(
        "AYHL", "ayhl_player_games",
        "THF", "thf_player_games",
        "AHF", "ahf_player_games",
        "NJHS", "njhs_player_games",
        "MHR", "mhr_player_games"
    );

    /**
     * Sources whose tables carry team scores.
     *
     * MHR takes them from the team pages it scrapes; AYHL gets them from the
     * league scoreboard (scripts/ayhl/scrape_scores.py), which publishes a
     * GAME# that matches ayhl_player_games.game_id exactly. Every other source
     * exposes per-player stats only, so their score columns are null.
     */
    private static final Set<String> SCORE_SOURCES = Set.of("MHR", "AYHL");

    /**
     * source -> season-aggregate table.
     *
     * A season can exist here long before the source publishes its game sheets:
     * a new season starts with roster GP/G/A totals while the per-game rows only
     * appear once the games are played and scored. Those seasons used to be
     * missing from the season picker entirely, which read as "this player has no
     * current season".
     */
    private static final Map<String, String> CAREER_TABLES = Map.of(
        "AYHL", "ayhl_player_career",
        "THF", "thf_player_career",
        "AHF", "ahf_player_career",
        "NJHS", "njhs_player_career"
    );

    private static final Pattern SEASON_YEAR = Pattern.compile("(\\d{4})");

    /** Whether a source key is one this lookup can read. */
    public static boolean isKnownSource(String source) {
        return source != null && SOURCE_TABLES.containsKey(source.trim().toUpperCase(Locale.ROOT));
    }

    private final JdbcTemplate jdbcTemplate;

    /** Cached "does the override table exist" answer (null = not checked yet). */
    private Boolean overrideTableAvailable;

    public GameHistoryLookupService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public IntegrationDtos.GameHistoryResponseDto lookupByPlayerName(
        String playerId,
        String playerName,
        Integer requestedSeasonYear
    ) {
        return lookupByPlayerName(playerId, playerName, requestedSeasonYear, List.of());
    }

    /**
     * Game history for a player, with the season picker widened by the seasons
     * of the player's linked identities (so a season that only has season
     * totals so far still appears, with an empty game list).
     */
    public IntegrationDtos.GameHistoryResponseDto lookupByPlayerName(
        String playerId,
        String playerName,
        Integer requestedSeasonYear,
        List<PlayerSourceLinkService.PlayerLink> links
    ) {
        Set<String> canonicalNames = buildCanonicalLookupKeys(playerName);
        List<IntegrationDtos.GameHistoryGameDto> allGames = new ArrayList<>();
        Long overridePlayerId = parseProfileId(playerId);

        for (Map.Entry<String, String> entry : SOURCE_TABLES.entrySet()) {
            String source = entry.getKey();
            String tableName = entry.getValue();
            if (!tableExists(tableName) || canonicalNames.isEmpty()) {
                continue;
            }
            allGames.addAll(loadGamesForSource(source, tableName, canonicalNames, overridePlayerId));
        }

        // Seasons with game rows first (they keep their label), then any extra
        // season we only know from the career aggregates.
        Map<Integer, IntegrationDtos.GameHistorySeasonOptionDto> seasonsByYear = new LinkedHashMap<>();
        allGames.stream()
            .filter(g -> g.seasonYear() != null)
            .forEach(g -> seasonsByYear.putIfAbsent(
                g.seasonYear(),
                new IntegrationDtos.GameHistorySeasonOptionDto(g.seasonYear(), g.seasonLabel())));
        for (IntegrationDtos.GameHistorySeasonOptionDto option : careerSeasonOptions(links)) {
            seasonsByYear.putIfAbsent(option.seasonYear(), option);
        }

        List<IntegrationDtos.GameHistorySeasonOptionDto> seasonOptions = seasonsByYear.values()
            .stream()
            .sorted(Comparator.comparing(IntegrationDtos.GameHistorySeasonOptionDto::seasonYear).reversed())
            .toList();

        Integer selectedSeasonYear = requestedSeasonYear;
        if (selectedSeasonYear == null && !seasonOptions.isEmpty()) {
            selectedSeasonYear = seasonOptions.get(0).seasonYear();
        }

        Integer finalSelectedSeasonYear = selectedSeasonYear;
        List<IntegrationDtos.GameHistoryGameDto> filteredGames = allGames.stream()
            .filter(g -> finalSelectedSeasonYear == null || finalSelectedSeasonYear.equals(g.seasonYear()))
            .sorted(Comparator
                .comparing(IntegrationDtos.GameHistoryGameDto::gameDate, Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(IntegrationDtos.GameHistoryGameDto::gameTime,
                               Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(IntegrationDtos.GameHistoryGameDto::source)
                .thenComparing(IntegrationDtos.GameHistoryGameDto::gameId, Comparator.nullsLast(String::compareTo))
            )
            .toList();

        String selectedSeasonLabel = seasonOptions.stream()
            .filter(s -> finalSelectedSeasonYear != null && finalSelectedSeasonYear.equals(s.seasonYear()))
            .map(IntegrationDtos.GameHistorySeasonOptionDto::seasonLabel)
            .findFirst()
            .orElse(null);

        return new IntegrationDtos.GameHistoryResponseDto(
            playerId,
            playerName,
            finalSelectedSeasonYear,
            selectedSeasonLabel,
            seasonOptions,
            filteredGames,
            OffsetDateTime.now(ZoneOffset.UTC),
            "private, max-age=300"
        );
    }

    private List<IntegrationDtos.GameHistoryGameDto> loadGamesForSource(
        String source,
        String tableName,
        Collection<String> canonicalNames,
        Long overridePlayerId
    ) {
        String placeholders = canonicalNames.stream().map(x -> "?").collect(Collectors.joining(","));
        boolean hasScores = SCORE_SOURCES.contains(source);
        boolean hasOverrides = overridePlayerId != null && overrideTableAvailable();

        String scoreColumns = hasScores
            ? ", t.score_for, t.score_against"
            : ", NULL AS score_for, NULL AS score_against";
        // Only MHR publishes a start time; the other sources have no such column.
        String timeColumn = "MHR".equals(source) ? ", t.game_time" : ", NULL AS game_time";
        String overrideJoin = hasOverrides
            ? " LEFT JOIN game_stat_overrides o ON o.player_profile_id = ? AND o.source = ?"
                + " AND o.season_year = t.season_year AND o.game_id = t.game_id"
            : "";
        String editedColumn = hasOverrides ? ", (o.id IS NOT NULL) AS stats_edited" : ", false AS stats_edited";
        // A manual entry wins over the scraped value; either one may be null.
        String goalsColumn = hasOverrides ? "COALESCE(o.goals, t.goals)" : "t.goals";
        String assistsColumn = hasOverrides ? "COALESCE(o.assists, t.assists)" : "t.assists";
        String pimColumn = hasOverrides ? "COALESCE(o.pim, t.pim)" : "t.pim";

        String sql = "SELECT t.season_year, t.season_label, t.game_id, t.game_date, t.game_type, t.league,"
            + " t.team_for, t.team_against,"
            + " " + goalsColumn + " AS goals, " + assistsColumn + " AS assists, t.points AS scraped_points,"
            + " " + pimColumn + " AS pim"
            + scoreColumns + timeColumn + editedColumn + " "
            + "FROM " + tableName + " t" + overrideJoin + " "
            + "WHERE LOWER(REGEXP_REPLACE(COALESCE(t.player_name, ''), '[[:space:],.''-]', '', 'g')) IN (" + placeholders + ")";

        List<Object> params = new ArrayList<>();
        if (hasOverrides) {
            params.add(overridePlayerId);
            params.add(source);
        }
        params.addAll(canonicalNames);

        return jdbcTemplate.query(sql, ps -> {
            for (int i = 0; i < params.size(); i++) {
                ps.setObject(i + 1, params.get(i));
            }
        }, (rs, rowNum) -> {
            Date date = rs.getDate("game_date");
            Integer goals = (Integer) rs.getObject("goals");
            Integer assists = (Integer) rs.getObject("assists");
            boolean edited = rs.getBoolean("stats_edited");
            // Points are only stored by the source tables; recompute them when a
            // human replaced the goals/assists of this game.
            Integer points = (Integer) rs.getObject("scraped_points");
            if (edited && (goals != null || assists != null)) {
                points = (goals == null ? 0 : goals) + (assists == null ? 0 : assists);
            }
            return new IntegrationDtos.GameHistoryGameDto(
                source,
                (Integer) rs.getObject("season_year"),
                rs.getString("season_label"),
                rs.getString("game_id"),
                date != null ? date.toLocalDate() : null,
                rs.getString("game_time"),
                rs.getString("game_type"),
                rs.getString("league"),
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

    /**
     * Season options from the players' linked career rows. A source can publish
     * a season's totals before its game sheets, so these fill the gaps that the
     * game tables cannot cover yet.
     */
    private List<IntegrationDtos.GameHistorySeasonOptionDto> careerSeasonOptions(
        List<PlayerSourceLinkService.PlayerLink> links
    ) {
        List<IntegrationDtos.GameHistorySeasonOptionDto> out = new ArrayList<>();
        if (links == null) {
            return out;
        }
        for (PlayerSourceLinkService.PlayerLink link : links) {
            String source = link.source() == null ? "" : link.source().trim().toUpperCase(Locale.ROOT);
            String tableName = CAREER_TABLES.get(source);
            if (tableName == null || link.sourcePlayerId() == null || link.sourcePlayerId().isBlank()
                || !tableExists(tableName)) {
                continue;
            }
            try {
                List<String> labels = jdbcTemplate.queryForList(
                    "SELECT DISTINCT season_label FROM " + tableName + " WHERE source_player_id = ?",
                    String.class, link.sourcePlayerId());
                for (String label : labels) {
                    Integer year = seasonStartYear(label);
                    if (year != null) {
                        out.add(new IntegrationDtos.GameHistorySeasonOptionDto(year, label));
                    }
                }
            } catch (Exception e) {
                // table shape differs per source; a missing season is not fatal
            }
        }
        return out;
    }

    /** Start year of a season label ("2026-2027 Season" -> 2026). */
    private static Integer seasonStartYear(String label) {
        if (label == null) {
            return null;
        }
        Matcher m = SEASON_YEAR.matcher(label);
        return m.find() ? Integer.valueOf(m.group(1)) : null;
    }

    private boolean tableExists(String tableName) {
        Integer exists = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = current_schema() AND table_name = ?",
            Integer.class,
            tableName
        );
        return exists != null && exists > 0;
    }

    private boolean overrideTableAvailable() {
        if (overrideTableAvailable == null) {
            overrideTableAvailable = tableExists("game_stat_overrides");
        }
        return overrideTableAvailable;
    }

    /** The player id used by game history is the numeric player profile id. */
    private static Long parseProfileId(String playerId) {
        if (playerId == null || playerId.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(playerId.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String normalizePlayerName(String fullName) {
        if (fullName == null || fullName.isBlank()) {
            return "";
        }

        return fullName
            .toLowerCase()
            .trim()
            .replaceAll("['-.]", "")
            .replaceAll("\\s+", " ");
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
            return Set.of();
        }

        Set<String> keys = new LinkedHashSet<>();
        keys.add(canonicalName(normalized));

        String[] parts = normalized.split(" ");
        if (parts.length == 2) {
            keys.add(canonicalName(parts[1] + "," + parts[0]));
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
