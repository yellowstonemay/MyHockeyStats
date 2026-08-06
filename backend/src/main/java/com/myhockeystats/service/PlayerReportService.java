package com.myhockeystats.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Unified player report — merges every confirmed identity link
 * (AYHL/THF/AHF/NJHS) into one career view with season groups, totals,
 * per-league split, rankings and recent games.
 *
 * Feature 1 = free for every user (pure local data + SQL, no AI call).
 */
@Service
public class PlayerReportService {

    /** source -> {career table, has pim column} */
    private static final Map<String, String[]> CAREER = Map.of(
        "AYHL", new String[]{"ayhl_player_career", "1"},
        "THF",  new String[]{"thf_player_career", "1"},
        "AHF",  new String[]{"ahf_player_career", "1"},
        "NJHS", new String[]{"njhs_player_career", "0"}
    );

    /** source -> game-history table (recent games). */
    private static final Map<String, String> GAMES = Map.of(
        "AYHL", "ayhl_player_games",
        "THF",  "thf_player_games",
        "AHF",  "ahf_player_games",
        "NJHS", "njhs_player_games"
    );

    private static final Pattern SEASON_YEAR = Pattern.compile("(\\d{4})");

    private final JdbcTemplate jdbcTemplate;
    private final RankingLookupService rankingLookupService;

    public PlayerReportService(JdbcTemplate jdbcTemplate, RankingLookupService rankingLookupService) {
        this.jdbcTemplate = jdbcTemplate;
        this.rankingLookupService = rankingLookupService;
    }

    /** Normalized career row (one source/league/season). */
    public record CareerRow(int seasonYear, String source, String league, String team,
                            Integer games, Integer goals, Integer assists, Integer points, Integer pim) {}

    /** Confirmed identity links for a user: source + source_player_id. */
    public List<Map<String, Object>> links(long userId) {
        return jdbcTemplate.queryForList(
            "SELECT source, source_player_id FROM player_identity_map " +
            "WHERE user_id = ? AND link_state = 'CONFIRMED'", userId);
    }

    /** Load + normalize every career row across the user's confirmed links. */
    public List<CareerRow> careerRows(long userId) {
        List<CareerRow> out = new ArrayList<>();
        for (Map<String, Object> link : links(userId)) {
            String source = (String) link.get("source");
            String playerId = (String) link.get("source_player_id");
            if (source == null || playerId == null) continue;
            String[] meta = CAREER.get(source);
            if (meta == null) continue;
            boolean hasPim = meta[1].equals("1");
            String sql = hasPim
                ? "SELECT season_label, league_name, team_name, games_played, goals, assists, points, pim FROM " + meta[0] + " WHERE source_player_id = ?"
                : "SELECT season_label, league_name, team_name, games_played, goals, assists, points FROM " + meta[0] + " WHERE source_player_id = ?";
            try {
                jdbcTemplate.query(sql, (rs, rn) -> {
                    String label = rs.getString("season_label");
                    Integer pim = hasPim ? intOrNull(rs, "pim") : null;
                    out.add(new CareerRow(
                        seasonStartYear(label),
                        source,
                        rs.getString("league_name"),
                        rs.getString("team_name"),
                        intOrNull(rs, "games_played"),
                        intOrNull(rs, "goals"),
                        intOrNull(rs, "assists"),
                        intOrNull(rs, "points"),
                        pim
                    ));
                    return null;
                }, playerId);
            } catch (Exception e) {
                // source table may not exist / no rows; skip
            }
        }
        return out;
    }

    /** Build the full unified report for a user. */
    public Map<String, Object> buildReport(long userId) {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("profile", profile(userId));

        List<CareerRow> career = careerRows(userId);

        // Totals across all sources.
        Map<String, Object> totals = new LinkedHashMap<>();
        int tg = 0, tg_ = 0, ta = 0, tp = 0, tpi = 0;
        for (CareerRow r : career) {
            tg += nz(r.games()); tg_ += nz(r.goals()); ta += nz(r.assists()); tp += nz(r.points()); tpi += nz(r.pim());
        }
        totals.put("games", tg); totals.put("goals", tg_); totals.put("assists", ta);
        totals.put("points", tp); totals.put("pim", tpi);
        report.put("totals", totals);

        // Group career rows by season year.
        Map<Integer, List<CareerRow>> bySeason = new LinkedHashMap<>();
        for (CareerRow r : career) {
            bySeason.computeIfAbsent(r.seasonYear(), k -> new ArrayList<>()).add(r);
        }
        List<Integer> years = new ArrayList<>(bySeason.keySet());
        years.sort((a, b) -> Integer.compare(b, a)); // newest first

        List<Map<String, Object>> seasons = new ArrayList<>();
        for (int y : years) {
            List<CareerRow> rows = bySeason.get(y);
            Map<String, Object> season = new LinkedHashMap<>();
            season.put("seasonYear", y);
            season.put("combined", combined(rows));
            List<Map<String, Object>> rowList = new ArrayList<>();
            for (CareerRow r : rows) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("source", r.source());
                m.put("league", r.league());
                m.put("team", r.team());
                m.put("games", r.games());
                m.put("goals", r.goals());
                m.put("assists", r.assists());
                m.put("points", r.points());
                m.put("pim", r.pim());
                rowList.add(m);
            }
            season.put("rows", rowList);
            seasons.add(season);
        }
        report.put("seasons", seasons);

        // Per-league split.
        Map<String, int[]> byLeague = new LinkedHashMap<>();
        for (CareerRow r : career) {
            int[] acc = byLeague.computeIfAbsent(r.league() == null ? "—" : r.league(), k -> new int[6]);
            acc[0]++;                    // seasons count
            acc[1] += nz(r.games());
            acc[2] += nz(r.goals());
            acc[3] += nz(r.assists());
            acc[4] += nz(r.points());
            acc[5] += nz(r.pim());
        }
        List<Map<String, Object>> leagueList = new ArrayList<>();
        for (Map.Entry<String, int[]> e : byLeague.entrySet()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("league", e.getKey());
            m.put("seasons", e.getValue()[0]);
            m.put("games", e.getValue()[1]);
            m.put("goals", e.getValue()[2]);
            m.put("assists", e.getValue()[3]);
            m.put("points", e.getValue()[4]);
            m.put("pim", e.getValue()[5]);
            leagueList.add(m);
        }
        leagueList.sort((a, b) -> Integer.compare((Integer) b.get("points"), (Integer) a.get("points")));
        report.put("byLeague", leagueList);

        // Rankings (team + league percentiles) per season.
        report.put("rankings", rankingLookupService.forUser(userId));

        // Recent games (last 10 across sources).
        report.put("recentGames", recentGames(userId, 10));

        // Available seasons for the AI selector.
        List<Integer> avail = new ArrayList<>(years);
        avail.sort(Integer::compareTo);
        report.put("availableSeasons", avail);
        report.put("latestSeason", avail.isEmpty() ? null : avail.get(avail.size() - 1));

        return report;
    }

    /** Season-scoped numeric summary used to build the AI prompt. */
    public Map<String, Object> seasonSummary(long userId, int seasonYear) {
        Map<String, Object> out = new LinkedHashMap<>();
        List<CareerRow> career = careerRows(userId);
        List<CareerRow> thisSeason = new ArrayList<>();
        List<CareerRow> prevSeason = new ArrayList<>();
        for (CareerRow r : career) {
            if (r.seasonYear() == seasonYear) thisSeason.add(r);
            else if (r.seasonYear() < seasonYear) prevSeason.add(r);
        }
        Map<String, Object> season = combined(thisSeason);
        season.put("seasonYear", seasonYear);
        out.put("season", season);

        // Previous season = the most recent season strictly before the target.
        Map<Integer, List<CareerRow>> grouped = new LinkedHashMap<>();
        for (CareerRow r : prevSeason) grouped.computeIfAbsent(r.seasonYear(), k -> new ArrayList<>()).add(r);
        int bestPrev = 0;
        List<CareerRow> prevRows = List.of();
        for (Map.Entry<Integer, List<CareerRow>> e : grouped.entrySet()) {
            if (e.getKey() > bestPrev) { bestPrev = e.getKey(); prevRows = e.getValue(); }
        }
        out.put("previousSeason", prevRows.isEmpty() ? null : combined(prevRows));

        // Rankings for this season.
        List<Map<String, Object>> rankings = new ArrayList<>();
        for (Map<String, Object> link : links(userId)) {
            String source = (String) link.get("source");
            String playerId = (String) link.get("source_player_id");
            for (Map<String, Object> r : rankingLookupService.computeRankings(source, playerId)) {
                if (String.valueOf(r.get("season")).startsWith(String.valueOf(seasonYear))) {
                    rankings.add(r);
                }
            }
        }
        out.put("rankings", rankings);
        return out;
    }

    /** Combined totals for a set of rows (per-season or career). */
    private static Map<String, Object> combined(List<CareerRow> rows) {
        Map<String, Object> m = new LinkedHashMap<>();
        int g = 0, g_ = 0, a = 0, p = 0, pi = 0;
        for (CareerRow r : rows) {
            g += nz(r.games()); g_ += nz(r.goals()); a += nz(r.assists()); p += nz(r.points()); pi += nz(r.pim());
        }
        m.put("games", g); m.put("goals", g_); m.put("assists", a); m.put("points", p); m.put("pim", pi);
        return m;
    }

    private Map<String, Object> profile(long userId) {
        Map<String, Object> out = new LinkedHashMap<>();
        try {
            jdbcTemplate.query("SELECT full_name, birthdate, position, location FROM player_profiles WHERE user_id = ?",
                (rs, rn) -> {
                    out.put("fullName", rs.getString("full_name"));
                    out.put("position", rs.getString("position"));
                    out.put("location", rs.getString("location"));
                    LocalDate bd = rs.getDate("birthdate") == null ? null : rs.getDate("birthdate").toLocalDate();
                    out.put("birthdate", bd == null ? null : bd.toString());
                    out.put("age", bd == null ? null : LocalDate.now().getYear() - bd.getYear());
                    return null;
                }, userId);
        } catch (Exception e) {
            // no profile
        }
        return out;
    }

    private List<Map<String, Object>> recentGames(long userId, int limit) {
        List<Map<String, Object>> entries = new ArrayList<>();
        for (Map<String, Object> link : links(userId)) {
            String source = (String) link.get("source");
            String playerId = (String) link.get("source_player_id");
            String table = GAMES.get(source);
            if (table == null) continue;
            try {
                List<Map<String, Object>> rows = jdbcTemplate.query(
                    "SELECT game_date, team_for, team_against, goals, assists, points FROM " + table +
                    " WHERE player_id = ? AND game_date IS NOT NULL ORDER BY game_date DESC, game_id DESC LIMIT " + limit,
                    (rs, rn) -> {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("source", source);
                        m.put("date", rs.getDate("game_date") == null ? null : rs.getDate("game_date").toString());
                        m.put("teamFor", rs.getString("team_for"));
                        m.put("opponent", rs.getString("team_against"));
                        m.put("goals", rs.getObject("goals") == null ? 0 : ((Number) rs.getObject("goals")).intValue());
                        m.put("assists", rs.getObject("assists") == null ? 0 : ((Number) rs.getObject("assists")).intValue());
                        m.put("points", rs.getObject("points") == null ? 0 : ((Number) rs.getObject("points")).intValue());
                        return m;
                    }, playerId);
                entries.addAll(rows);
            } catch (Exception e) {
                // games table may not exist yet
            }
        }
        entries.sort((a, b) -> {
            String da = (String) a.get("date"), db = (String) b.get("date");
            if (da == null && db == null) return 0;
            if (da == null) return 1;
            if (db == null) return -1;
            return db.compareTo(da);
        });
        return entries.size() > limit ? entries.subList(0, limit) : entries;
    }

    private static int seasonStartYear(String label) {
        if (label == null) return 0;
        Matcher m = SEASON_YEAR.matcher(label);
        return m.find() ? Integer.parseInt(m.group(1)) : 0;
    }

    private static Integer intOrNull(ResultSet rs, String col) throws SQLException {
        Object v = rs.getObject(col);
        return v == null ? null : ((Number) v).intValue();
    }

    private static int nz(Integer v) {
        return v == null ? 0 : v;
    }
}
