package com.myhockeystats.api;

import com.myhockeystats.model.User;
import com.myhockeystats.repository.UserRepository;
import com.myhockeystats.security.JwtUtil;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.sql.Timestamp;
import java.util.*;

/**
 * "Follow a player" — let a signed-in user follow other players (by name, with
 * auto-match across AYHL/THF/AHF/NJHS) and see their recent performance.
 *
 * Follows are private per user and are refreshed daily by the deep-dive
 * pipeline (scripts/deep_dive/follows_deep.py). A per-user cap bounds the
 * daily scrape load so we don't hammer the source sites.
 */
@RestController
@RequestMapping("/api/follows")
public class FollowController {

    /** Max players one user may follow (protects source-site scrape load). */
    private static final int MAX_FOLLOWS = 20;

    private static final List<String> SOURCES = List.of("AYHL", "THF", "AHF", "NJHS");

    /** source -> {roster table, player-name column, season column}. */
    private static final Map<String, String[]> ROSTER = Map.of(
        "AYHL", new String[]{"ayhl_roster", "player_name_raw", "season_label"},
        "THF",  new String[]{"thf_rosters", "player_name", "season_year"},
        "AHF",  new String[]{"ahf_rosters", "player_name", "season_year"},
        "NJHS", new String[]{"njhs_rosters", "player_name", "season_label"}
    );

    /** source -> {career table, has team_name column}. */
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

    private final JwtUtil jwtUtil;
    private final UserRepository userRepository;
    private final JdbcTemplate jdbcTemplate;

    /** (source, player_id) -> display name + whether it's the signed-in user. */
    private record PlayerMeta(String name, boolean isMe) {}

    public FollowController(JwtUtil jwtUtil, UserRepository userRepository, JdbcTemplate jdbcTemplate) {
        this.jwtUtil = jwtUtil;
        this.userRepository = userRepository;
        this.jdbcTemplate = jdbcTemplate;
    }

    private Optional<User> resolveUser(String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return Optional.empty();
        }
        String token = authHeader.replace("Bearer ", "").trim();
        String email = jwtUtil.extractEmail(token);
        if (email == null || email.isBlank()) {
            return Optional.empty();
        }
        return userRepository.findByEmail(email);
    }

    // ─── name matching (mirrors identity_link.py / CareerLookupService) ───────
    private static String normalizeName(String v) {
        if (v == null || v.isBlank()) return "";
        return v.toLowerCase().trim().replaceAll("['-.]", "").replaceAll("\\s+", " ");
    }

    private static String canonicalName(String v) {
        if (v == null || v.isBlank()) return "";
        return v.toLowerCase().trim().replaceAll("[\\s,.'-]", "");
    }

    private static Set<String> buildCanonicalLookupKeys(String name) {
        String norm = normalizeName(name);
        if (norm.isBlank()) return Collections.emptySet();
        Set<String> keys = new LinkedHashSet<>();
        keys.add(canonicalName(norm));
        String[] parts = norm.split(" ");
        if (parts.length == 2) {
            keys.add(canonicalName(parts[1] + "," + parts[0])); // handle "Last,First"
        }
        return keys;
    }

    /** GET /api/follows/search?q=... — auto-match candidates across all sources. */
    @GetMapping("/search")
    public ResponseEntity<?> search(@RequestParam("q") String q,
                                    @RequestHeader(value = "Authorization", required = false) String authHeader) {
        Optional<User> user = resolveUser(authHeader);
        if (user.isEmpty()) {
            return ResponseEntity.status(401).body(Map.of("error", "Authentication required"));
        }
        String name = q == null ? "" : q.trim();
        if (name.length() < 2) {
            return ResponseEntity.badRequest().body(Map.of("error", "Type at least 2 characters to search"));
        }
        Set<String> keys = buildCanonicalLookupKeys(name);
        if (keys.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Could not parse that name"));
        }

        List<Map<String, Object>> candidates = new ArrayList<>();
        for (String source : SOURCES) {
            String[] meta = ROSTER.get(source);
            String table = meta[0], nameCol = meta[1], seasonCol = meta[2];
            String placeholders = String.join(",", Collections.nCopies(keys.size(), "?"));
            String sql =
                "SELECT player_id AS source_player_id, min(" + nameCol + ") AS player_name, " +
                "count(DISTINCT " + seasonCol + ") AS seasons, " +
                "string_agg(DISTINCT team_name, '; ') AS teams " +
                "FROM " + table + " " +
                "WHERE regexp_replace(lower(" + nameCol + "), '[\\s,.''-]', '', 'g') IN (" + placeholders + ") " +
                "GROUP BY player_id ORDER BY player_name LIMIT 12";
            List<Map<String, Object>> rows = jdbcTemplate.query(sql,
                ps -> {
                    int i = 1;
                    for (String k : keys) ps.setString(i++, k);
                },
                (rs, rn) -> {
                    Map<String, Object> m = new HashMap<>();
                    m.put("source", source);
                    m.put("sourcePlayerId", rs.getString("source_player_id"));
                    m.put("playerName", rs.getString("player_name"));
                    m.put("teams", rs.getString("teams"));
                    m.put("seasons", rs.getInt("seasons"));
                    return m;
                });
            candidates.addAll(rows);
        }
        return ResponseEntity.ok(Map.of("candidates", candidates));
    }

    /** GET /api/follows — the user's follows with latest season + last 5 games. */
    @GetMapping
    public ResponseEntity<?> list(@RequestHeader(value = "Authorization", required = false) String authHeader) {
        Optional<User> user = resolveUser(authHeader);
        if (user.isEmpty()) {
            return ResponseEntity.status(401).body(Map.of("error", "Authentication required"));
        }
        long uid = user.get().getId();
        List<Map<String, Object>> follows = jdbcTemplate.query(
            "SELECT id::text, source, source_player_id, player_name, created_at " +
            "FROM follows WHERE user_id = ? ORDER BY created_at DESC",
            (rs, rn) -> {
                Map<String, Object> m = new HashMap<>();
                m.put("id", rs.getString("id"));
                m.put("source", rs.getString("source"));
                m.put("sourcePlayerId", rs.getString("source_player_id"));
                m.put("playerName", rs.getString("player_name"));
                m.put("createdAt", rs.getTimestamp("created_at") == null ? null : rs.getTimestamp("created_at").toInstant().toString());
                return m;
            }, uid);

        for (Map<String, Object> f : follows) {
            enrich(f);
        }
        return ResponseEntity.ok(Map.of("follows", follows, "maxFollows", MAX_FOLLOWS));
    }

    /** POST /api/follows — add a follow (dedupe + cap). */
    @PostMapping
    public ResponseEntity<?> add(@RequestBody Map<String, String> body,
                                 @RequestHeader(value = "Authorization", required = false) String authHeader) {
        Optional<User> user = resolveUser(authHeader);
        if (user.isEmpty()) {
            return ResponseEntity.status(401).body(Map.of("error", "Authentication required"));
        }
        String source = body.get("source");
        String spid = body.get("sourcePlayerId");
        String pname = body.get("playerName");
        if (source == null || spid == null || spid.isBlank() || !ROSTER.containsKey(source)) {
            return ResponseEntity.badRequest().body(Map.of("error", "source and sourcePlayerId are required"));
        }
        long uid = user.get().getId();

        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM follows WHERE user_id = ?", Integer.class, uid);
        if (count != null && count >= MAX_FOLLOWS) {
            return ResponseEntity.badRequest().body(Map.of("error", "You can follow up to " + MAX_FOLLOWS + " players"));
        }

        // Validate the player actually exists in the source roster.
        String[] meta = ROSTER.get(source);
        List<Integer> exists = jdbcTemplate.query(
            "SELECT 1 FROM " + meta[0] + " WHERE player_id = ? LIMIT 1",
            (rs, rn) -> rs.getInt(1), spid);
        if (exists.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Player not found in " + source));
        }

        String name = (pname == null || pname.isBlank()) ? spid : pname.trim();
        int inserted = jdbcTemplate.update(
            "INSERT INTO follows (id, user_id, source, source_player_id, player_name, created_at) " +
            "VALUES (gen_random_uuid(), ?, ?, ?, ?, NOW()) " +
            "ON CONFLICT (user_id, source, source_player_id) DO NOTHING",
            uid, source, spid, name);
        if (inserted == 0) {
            return ResponseEntity.ok(Map.of("message", "You're already following " + name, "created", false));
        }
        return ResponseEntity.ok(Map.of("message", "Following " + name, "created", true));
    }

    /** DELETE /api/follows/{id} — unfollow. */
    @DeleteMapping("/{id}")
    public ResponseEntity<?> remove(@PathVariable("id") String id,
                                    @RequestHeader(value = "Authorization", required = false) String authHeader) {
        Optional<User> user = resolveUser(authHeader);
        if (user.isEmpty()) {
            return ResponseEntity.status(401).body(Map.of("error", "Authentication required"));
        }
        int deleted = jdbcTemplate.update(
            "DELETE FROM follows WHERE id = ?::uuid AND user_id = ?", id, user.get().getId());
        if (deleted == 0) {
            return ResponseEntity.status(404).body(Map.of("error", "Follow not found"));
        }
        return ResponseEntity.ok(Map.of("message", "Unfollowed"));
    }

    /**
     * GET /api/follows/activity — merged recent games (mine + followed players),
     * newest first, with a freshness timestamp (asOf = last scrape across the
     * involved players' game tables).
     */
    @GetMapping("/activity")
    public ResponseEntity<?> activity(@RequestParam(defaultValue = "30") int limit,
                                      @RequestHeader(value = "Authorization", required = false) String authHeader) {
        Optional<User> user = resolveUser(authHeader);
        if (user.isEmpty()) {
            return ResponseEntity.status(401).body(Map.of("error", "Authentication required"));
        }
        long uid = user.get().getId();

        // Involved players per source: my confirmed identity links + follows.
        Map<String, Map<String, PlayerMeta>> players = new HashMap<>();
        List<Map<String, Object>> myLinks = jdbcTemplate.query(
            "SELECT source, source_player_id FROM player_identity_map " +
            "WHERE user_id = ? AND link_state = 'CONFIRMED'",
            (rs, rn) -> {
                Map<String, Object> m = new HashMap<>();
                m.put("source", rs.getString("source"));
                m.put("id", rs.getString("source_player_id"));
                return m;
            }, uid);
        for (Map<String, Object> l : myLinks) {
            players.computeIfAbsent((String) l.get("source"), k -> new HashMap<>())
                   .put((String) l.get("id"), new PlayerMeta("You", true));
        }
        List<Map<String, Object>> follows = jdbcTemplate.query(
            "SELECT source, source_player_id, player_name FROM follows WHERE user_id = ?",
            (rs, rn) -> {
                Map<String, Object> m = new HashMap<>();
                m.put("source", rs.getString("source"));
                m.put("id", rs.getString("source_player_id"));
                m.put("name", rs.getString("player_name"));
                return m;
            }, uid);
        for (Map<String, Object> f : follows) {
            players.computeIfAbsent((String) f.get("source"), k -> new HashMap<>())
                   .putIfAbsent((String) f.get("id"), new PlayerMeta((String) f.get("name"), false));
        }

        List<Map<String, Object>> entries = new ArrayList<>();
        Timestamp asOf = null;
        for (Map.Entry<String, Map<String, PlayerMeta>> srcEntry : players.entrySet()) {
            String source = srcEntry.getKey();
            String table = GAMES.get(source);
            Map<String, PlayerMeta> byId = srcEntry.getValue();
            if (table == null || byId.isEmpty()) continue;
            List<String> ids = new ArrayList<>(byId.keySet());
            String placeholders = String.join(",", Collections.nCopies(ids.size(), "?"));

            String sql = "SELECT player_id, game_date, team_for, team_against, goals, assists, points, pim " +
                         "FROM " + table + " WHERE player_id IN (" + placeholders + ") " +
                         "AND game_date IS NOT NULL ORDER BY game_date DESC, game_id DESC LIMIT 30";
            List<Map<String, Object>> rows = jdbcTemplate.query(sql,
                (rs, rn) -> {
                    String pid = rs.getString("player_id");
                    PlayerMeta meta = byId.get(pid);
                    Map<String, Object> m = new HashMap<>();
                    m.put("source", source);
                    m.put("playerName", meta == null ? pid : meta.name());
                    m.put("isMe", meta != null && meta.isMe());
                    m.put("date", rs.getDate("game_date") == null ? null : rs.getDate("game_date").toString());
                    m.put("teamFor", rs.getString("team_for"));
                    m.put("opponent", rs.getString("team_against"));
                    m.put("goals", rs.getInt("goals"));
                    m.put("assists", rs.getInt("assists"));
                    m.put("points", rs.getInt("points"));
                    m.put("pim", rs.getInt("pim"));
                    return m;
                }, ids.toArray());
            entries.addAll(rows);

            // Freshness: last scrape for these players in this source.
            List<Timestamp> ts = jdbcTemplate.query(
                "SELECT MAX(scraped_at) FROM " + table + " WHERE player_id IN (" + placeholders + ")",
                (rs, rn) -> rs.getTimestamp(1), ids.toArray());
            if (!ts.isEmpty() && ts.get(0) != null && (asOf == null || ts.get(0).after(asOf))) {
                asOf = ts.get(0);
            }
        }

        entries.sort((a, b) -> {
            String da = (String) a.get("date");
            String db = (String) b.get("date");
            if (da == null && db == null) return 0;
            if (da == null) return 1;
            if (db == null) return -1;
            return db.compareTo(da);
        });
        int cap = Math.max(0, Math.min(limit, entries.size()));

        return ResponseEntity.ok(Map.of(
            "entries", entries.subList(0, cap),
            "asOf", asOf == null ? null : asOf.toInstant().toString()
        ));
    }

    // ─── enrichment: latest season totals + last 5 games per source ──────────
    private void enrich(Map<String, Object> f) {
        String source = (String) f.get("source");
        String spid = (String) f.get("sourcePlayerId");

        // Latest season from the career table.
        String[] cmeta = CAREER.get(source);
        String careerTable = cmeta[0];
        boolean hasTeam = cmeta[1].equals("1");
        String teamCol = hasTeam ? ", team_name" : "";
        String sql = "SELECT season_label" + teamCol + ", games_played, goals, assists, points, pim " +
                     "FROM " + careerTable + " WHERE source_player_id = ? " +
                     "ORDER BY season_label DESC LIMIT 1";
        try {
            List<Map<String, Object>> season = jdbcTemplate.query(sql,
                (rs, rn) -> {
                    Map<String, Object> m = new HashMap<>();
                    m.put("season", rs.getString("season_label"));
                    if (hasTeam) m.put("team", rs.getString("team_name"));
                    m.put("games", rs.getInt("games_played"));
                    m.put("goals", rs.getInt("goals"));
                    m.put("assists", rs.getInt("assists"));
                    m.put("points", rs.getInt("points"));
                    Object pim = rs.getObject("pim");
                    m.put("pim", pim == null ? 0 : ((Number) pim).intValue());
                    return m;
                }, spid);
            if (!season.isEmpty()) f.put("season", season.get(0));
        } catch (Exception e) {
            // career table may not have a row yet; leave season null
        }

        // NJHS team comes from the roster (career table has no team_name).
        if (source.equals("NJHS")) {
            List<String> team = jdbcTemplate.query(
                "SELECT team_name FROM njhs_rosters WHERE player_id = ? " +
                "ORDER BY season_year DESC, team_name LIMIT 1",
                (rs, rn) -> rs.getString("team_name"), spid);
            if (!team.isEmpty()) {
                @SuppressWarnings("unchecked")
                Map<String, Object> season = (Map<String, Object>) f.get("season");
                if (season != null) season.put("team", team.get(0));
            }
        }

        // Last 5 games.
        String gamesTable = GAMES.get(source);
        try {
            List<Map<String, Object>> games = jdbcTemplate.query(
                "SELECT game_date, team_for, team_against, goals, assists, points " +
                "FROM " + gamesTable + " WHERE player_id = ? " +
                "ORDER BY game_date DESC, game_id DESC LIMIT 5",
                (rs, rn) -> {
                    Map<String, Object> m = new HashMap<>();
                    m.put("date", rs.getDate("game_date") == null ? null : rs.getDate("game_date").toString());
                    m.put("teamFor", rs.getString("team_for"));
                    m.put("opponent", rs.getString("team_against"));
                    m.put("goals", rs.getInt("goals"));
                    m.put("assists", rs.getInt("assists"));
                    m.put("points", rs.getInt("points"));
                    return m;
                }, spid);
            f.put("recentGames", games);
        } catch (Exception e) {
            f.put("recentGames", List.of());
        }
    }
}
