package com.myhockeystats.api;

import com.myhockeystats.model.PlayerProfile;
import com.myhockeystats.model.User;
import com.myhockeystats.model.UserPlayer;
import com.myhockeystats.repository.UserRepository;
import com.myhockeystats.security.JwtUtil;
import com.myhockeystats.service.DeepDiveService;
import com.myhockeystats.service.PlayerProfileService;
import com.myhockeystats.service.PlayerSourceLinkService;
import com.myhockeystats.service.integration.MhrGameLookupService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

/**
 * "Follow a player" — let a signed-in user follow other players (by name, with
 * auto-match across AYHL/THF/AHF/NJHS) and see their recent performance.
 *
 * Follows are private per user and are refreshed daily by the deep-dive
 * pipeline (scripts/deep_dive/follows_deep.py). A per-user cap bounds the
 * daily scrape load so we don't hammer the source sites.
 *
 * Following also gives the login a READ-ONLY view of the player (a FAN link on
 * the profile that already carries that source identity), because a followed
 * player has to be selectable to be looked at — a name in a list is not a view.
 */
@RestController
@RequestMapping("/api/follows")
public class FollowController {

    private static final Logger log = LoggerFactory.getLogger(FollowController.class);

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

    /**
     * source -> raw schedule table. THF/AHF per-player tables carry no score, so
     * the feed joins the schedule to show the final result.
     */
    private static final Map<String, String> RAW_SCORE_TABLES = Map.of(
        "THF", "thf_games",
        "AHF", "ahf_games"
    );

    private final JwtUtil jwtUtil;
    private final UserRepository userRepository;
    private final JdbcTemplate jdbcTemplate;
    private final PlayerSourceLinkService playerSourceLinkService;
    private final PlayerProfileService playerProfileService;
    private final MhrGameLookupService mhrGameLookupService;
    private final DeepDiveService deepDiveService;

    /** (source, player_id) -> display name + whether it's the signed-in user. */
    private record PlayerMeta(String name, boolean isMe) {}

    public FollowController(JwtUtil jwtUtil, UserRepository userRepository, JdbcTemplate jdbcTemplate,
                            PlayerSourceLinkService playerSourceLinkService,
                            PlayerProfileService playerProfileService,
                            MhrGameLookupService mhrGameLookupService,
                            DeepDiveService deepDiveService) {
        this.jwtUtil = jwtUtil;
        this.userRepository = userRepository;
        this.jdbcTemplate = jdbcTemplate;
        this.playerSourceLinkService = playerSourceLinkService;
        this.playerProfileService = playerProfileService;
        this.mhrGameLookupService = mhrGameLookupService;
        this.deepDiveService = deepDiveService;
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

    /** Cached answer for "does the manual-override table exist yet". */
    private Boolean overrideTableAvailable;

    private boolean overrideTableAvailable() {
        if (overrideTableAvailable == null) {
            overrideTableAvailable = tableExistsInDb("game_stat_overrides");
        }
        return overrideTableAvailable;
    }

    private final Map<String, Boolean> tableExistsCache = new java.util.concurrent.ConcurrentHashMap<>();

    private boolean tableExistsInDb(String tableName) {
        return tableExistsCache.computeIfAbsent(tableName, name -> {
            Integer found = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables " +
                "WHERE table_schema = current_schema() AND table_name = ?",
                Integer.class, name);
            return found != null && found > 0;
        });
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
        boolean isEp = "EP".equalsIgnoreCase(source);
        if (source == null || spid == null || spid.isBlank() || (!isEp && !ROSTER.containsKey(source))) {
            return ResponseEntity.badRequest().body(Map.of("error", "source and sourcePlayerId are required"));
        }
        long uid = user.get().getId();

        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM follows WHERE user_id = ?", Integer.class, uid);
        if (count != null && count >= MAX_FOLLOWS) {
            return ResponseEntity.badRequest().body(Map.of("error", "You can follow up to " + MAX_FOLLOWS + " players"));
        }

        if (isEp) {
            // EP follow: the player comes from our EP quicksearch results. If we
            // don't have their career history yet, enqueue a CAREER fetch for the
            // ep_lookup poller.
            List<Integer> have = jdbcTemplate.query(
                "SELECT 1 FROM ep_player_career WHERE ep_player_id = ? LIMIT 1",
                (rs, rn) -> rs.getInt(1), spid);
            if (have.isEmpty()) {
                List<String> pending = jdbcTemplate.query(
                    "SELECT id::text FROM ep_lookup_requests " +
                    "WHERE type = 'CAREER' AND ep_player_id = ? AND status IN ('PENDING','RUNNING') " +
                    "ORDER BY created_at DESC LIMIT 1",
                    (rs, rn) -> rs.getString(1), spid);
                if (pending.isEmpty()) {
                    String ename = (pname == null || pname.isBlank()) ? spid : pname.trim();
                    jdbcTemplate.update(
                        "INSERT INTO ep_lookup_requests (id, user_id, type, name, ep_player_id, status, created_at) " +
                        "VALUES (gen_random_uuid(), ?, 'CAREER', ?, ?, 'PENDING', NOW())",
                        uid, ename, spid);
                }
            }
        } else {
            // Validate the player actually exists in the source roster.
            String[] meta = ROSTER.get(source);
            List<Integer> exists = jdbcTemplate.query(
                "SELECT 1 FROM " + meta[0] + " WHERE player_id = ? LIMIT 1",
                (rs, rn) -> rs.getInt(1), spid);
            if (exists.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "Player not found in " + source));
            }
        }

        String name = (pname == null || pname.isBlank()) ? spid : pname.trim();
        int inserted = jdbcTemplate.update(
            "INSERT INTO follows (id, user_id, source, source_player_id, player_name, created_at) " +
            "VALUES (gen_random_uuid(), ?, ?, ?, ?, NOW()) " +
            "ON CONFLICT (user_id, source, source_player_id) DO NOTHING",
            uid, source, spid, name);
        // Idempotent: also runs when the follow already existed, so a login that
        // followed before this behaviour existed still gets the read-only view.
        Long followedProfileId = attachFollowedPlayer(user.get(), source, spid);
        if (followedProfileId != null) {
            // Pull their career + games now instead of waiting for the nightly
            // job; the poller scrapes the team's schedule before rolling it in.
            deepDiveService.enqueueForPlayer(followedProfileId, uid);
        }
        if (inserted == 0) {
            return ResponseEntity.ok(Map.of("message", "You're already following " + name, "created", false));
        }
        return ResponseEntity.ok(Map.of("message", "Following " + name, "created", true));
    }

    /**
     * Give this login a read-only (FAN) view of a followed player.
     *
     * <p>The profile that already carries the followed source identity is reused
     * and attached. When nothing links that identity yet, a tracked profile is
     * created for it (ownerless, no birthdate — the sources publish none) so the
     * followed player is selectable and their tabs work, instead of the follow
     * only being a name in a list.
     */
    private Long attachFollowedPlayer(User user, String source, String sourcePlayerId) {
        if (user == null || source == null || sourcePlayerId == null || sourcePlayerId.isBlank()) {
            return null;
        }
        String src = source.trim().toUpperCase(Locale.ROOT);
        if (!SOURCES.contains(src)) {
            return null; // EP careers are not source ids a profile can be linked to
        }
        try {
            List<Long> profileIds = jdbcTemplate.queryForList(
                "SELECT player_id FROM player_source_links " +
                "WHERE source = ? AND source_player_id = ? ORDER BY player_id LIMIT 1",
                Long.class, src, sourcePlayerId);
            if (profileIds.isEmpty()) {
                PlayerProfile tracked = playerProfileService.createTrackedPlayer(
                    user, rosterPlayerName(src, sourcePlayerId), rosterBirthdate(src, sourcePlayerId));
                jdbcTemplate.update(
                    "INSERT INTO player_source_links (id, player_id, source, source_player_id, " +
                    "link_state, match_method, confidence_score, confirmed_at, last_verified_at, " +
                    "created_at, updated_at) " +
                    "VALUES (gen_random_uuid(), ?, ?, ?, 'CONFIRMED', 'FOLLOW', 1.0, NOW(), NOW(), NOW(), NOW()) " +
                    "ON CONFLICT (player_id, source) DO UPDATE " +
                    "SET source_player_id = EXCLUDED.source_player_id, updated_at = NOW()",
                    tracked.getId(), src, sourcePlayerId);
                return tracked.getId();
            }
            long profileId = profileIds.get(0);
            if (playerProfileService.isLinked(user.getId(), profileId)) {
                return profileId; // already on the account — never downgrade an owner/parent link
            }
            playerProfileService.getProfileById(profileId).ifPresent(profile ->
                playerProfileService.linkPlayer(user, profile, UserPlayer.RELATION_FAN));
            return profileId;
        } catch (Exception ex) {
            // A follow must not fail because the read-only view could not be attached.
            log.warn("Could not attach followed player {}:{} to user {}: {}",
                source, sourcePlayerId, user.getId(), ex.getMessage());
            return null;
        }
    }

    /** Display name of a roster player, or the source id when the roster moved on. */
    private String rosterPlayerName(String source, String sourcePlayerId) {
        String[] meta = ROSTER.get(source);
        if (meta == null) {
            return sourcePlayerId;
        }
        try {
            List<String> names = jdbcTemplate.queryForList(
                "SELECT " + meta[1] + " FROM " + meta[0] + " WHERE player_id::text = ? " +
                "ORDER BY " + meta[2] + " DESC LIMIT 1", String.class, sourcePlayerId);
            if (!names.isEmpty() && names.get(0) != null && !names.get(0).isBlank()) {
                return displayName(names.get(0).trim());
            }
        } catch (Exception e) {
            log.debug("Could not read roster name for {}:{}: {}", source, sourcePlayerId, e.getMessage());
        }
        return sourcePlayerId;
    }

    /** AYHL publishes "Last,First" — a profile should read "First Last". */
    private static String displayName(String raw) {
        int comma = raw.indexOf(',');
        if (comma <= 0 || comma == raw.length() - 1) {
            return raw;
        }
        String last = raw.substring(0, comma).trim();
        String first = raw.substring(comma + 1).trim();
        return (first + " " + last).trim();
    }

    /**
     * Birthdate from the roster when the source publishes one (only AYHL does;
     * THF/AHF rosters carry no usable value), otherwise null.
     */
    private java.time.LocalDate rosterBirthdate(String source, String sourcePlayerId) {
        if (!"AYHL".equals(source)) {
            return null;
        }
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT birth_month, birth_year FROM ayhl_roster WHERE player_id::text = ? " +
                "AND birth_year IS NOT NULL LIMIT 1", sourcePlayerId);
            if (rows.isEmpty()) {
                return null;
            }
            Object year = rows.get(0).get("birth_year");
            Object month = rows.get(0).get("birth_month");
            int y = ((Number) year).intValue();
            int m = month instanceof Number ? ((Number) month).intValue() : 1;
            if (m < 1 || m > 12) {
                m = 1;
            }
            return java.time.LocalDate.of(y, m, 1);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Drop the read-only view a follow created. Only a FAN link is removed, and
     * never the owner's own link, so unfollowing can never take a player away
     * from the account that owns it.
     */
    private void detachFollowedPlayer(long userId, String source, String sourcePlayerId) {
        if (source == null || sourcePlayerId == null) {
            return;
        }
        try {
            List<Long> profileIds = jdbcTemplate.queryForList(
                "SELECT player_id FROM player_source_links " +
                "WHERE source = ? AND source_player_id = ? ORDER BY player_id LIMIT 1",
                Long.class, source.trim().toUpperCase(Locale.ROOT), sourcePlayerId);
            if (profileIds.isEmpty()) {
                return;
            }
            long profileId = profileIds.get(0);
            Integer removable = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM user_players up " +
                "JOIN player_profiles pp ON pp.id = up.player_id " +
                "WHERE up.user_id = ? AND up.player_id = ? AND up.relation = 'FAN' " +
                "AND (pp.owner_user_id IS NULL OR pp.owner_user_id <> up.user_id)",
                Integer.class, userId, profileId);
            if (removable != null && removable > 0) {
                playerProfileService.unlinkPlayer(userId, profileId);
            }
            deleteIfOrphaned(profileId);
        } catch (Exception ex) {
            log.warn("Could not detach unfollowed player {}:{} from user {}: {}",
                source, sourcePlayerId, userId, ex.getMessage());
        }
    }

    /**
     * Delete a tracked profile nothing points at any more: no owner, no login
     * attached, and no follow row for any of its identities. Only profiles a
     * follow created are eligible, so unfollowing can never remove a player
     * somebody added themselves.
     */
    private void deleteIfOrphaned(long profileId) {
        Integer createdByFollow = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM player_source_links WHERE player_id = ? AND match_method = 'FOLLOW'",
            Integer.class, profileId);
        if (createdByFollow == null || createdByFollow == 0) {
            return;
        }
        Integer attached = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM user_players WHERE player_id = ?", Integer.class, profileId);
        Integer owned = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM player_profiles WHERE id = ? AND owner_user_id IS NOT NULL",
            Integer.class, profileId);
        Integer stillFollowed = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM follows f JOIN player_source_links l " +
            "ON l.source = f.source AND l.source_player_id = f.source_player_id " +
            "WHERE l.player_id = ?", Integer.class, profileId);
        boolean unused = (attached == null || attached == 0)
            && (owned == null || owned == 0)
            && (stillFollowed == null || stillFollowed == 0);
        if (unused) {
            jdbcTemplate.update("DELETE FROM player_profiles WHERE id = ?", profileId);
            log.info("Removed tracked player profile {} (no followers left)", profileId);
        }
    }

    /** DELETE /api/follows/{id} — unfollow. */
    @DeleteMapping("/{id}")
    public ResponseEntity<?> remove(@PathVariable("id") String id,
                                    @RequestHeader(value = "Authorization", required = false) String authHeader) {
        Optional<User> user = resolveUser(authHeader);
        if (user.isEmpty()) {
            return ResponseEntity.status(401).body(Map.of("error", "Authentication required"));
        }
        List<Map<String, Object>> followed = jdbcTemplate.queryForList(
            "SELECT source, source_player_id FROM follows WHERE id = ?::uuid AND user_id = ?",
            id, user.get().getId());
        int deleted = jdbcTemplate.update(
            "DELETE FROM follows WHERE id = ?::uuid AND user_id = ?", id, user.get().getId());
        if (deleted == 0) {
            return ResponseEntity.status(404).body(Map.of("error", "Follow not found"));
        }
        for (Map<String, Object> row : followed) {
            detachFollowedPlayer(user.get().getId(),
                (String) row.get("source"), (String) row.get("source_player_id"));
        }
        return ResponseEntity.ok(Map.of("message", "Unfollowed"));
    }

    /**
     * The account's own player profiles: only the selected one when a player is
     * given, otherwise every profile the login is linked to.
     */
    private List<PlayerProfile> ownPlayers(long uid, Long playerId) {
        if (playerId != null) {
            return playerProfileService.getProfileById(playerId).map(p -> List.of(p)).orElse(List.of());
        }
        return playerProfileService.listPlayersForUser(uid);
    }

    /**
     * GET /api/follows/activity — merged recent games (the selected player +
     * followed players), newest first, with a freshness timestamp (asOf = last
     * scrape across the involved players' game tables). Pass ?playerId= to scope
     * "my" games to one player on the account; follows are always account-level.
     * Pass ?scope=mine to return ONLY the account's own players (the Overview
     * feed) or ?scope=following for ONLY the followed players (the Following
     * tab); omit it for both merged.
     */
    @GetMapping("/activity")
    public ResponseEntity<?> activity(@RequestParam(defaultValue = "30") int limit,
                                      @RequestParam(value = "playerId", required = false) Long playerId,
                                      @RequestParam(value = "scope", required = false) String scope,
                                      @RequestHeader(value = "Authorization", required = false) String authHeader) {
        Optional<User> user = resolveUser(authHeader);
        if (user.isEmpty()) {
            return ResponseEntity.status(401).body(Map.of("error", "Authentication required"));
        }
        long uid = user.get().getId();
        boolean includeMine = scope == null || scope.isBlank()
                              || "mine".equalsIgnoreCase(scope) || "all".equalsIgnoreCase(scope);
        boolean includeFollows = scope == null || scope.isBlank()
                                 || "following".equalsIgnoreCase(scope) || "all".equalsIgnoreCase(scope);
        if (includeMine && playerId != null && !playerProfileService.isLinked(uid, playerId)) {
            return ResponseEntity.status(403).body(Map.of("error", "This player is not on your account"));
        }

        // Involved players per source: the selected player's links + follows.
        Map<String, Map<String, PlayerMeta>> players = new HashMap<>();
        if (includeMine) {
            for (PlayerSourceLinkService.PlayerLink link : playerSourceLinkService.resolve(uid, playerId)) {
                players.computeIfAbsent(link.source(), k -> new HashMap<>())
                       .put(link.sourcePlayerId(), new PlayerMeta("You", true));
            }
        }
        if (includeFollows) {
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
        }

        List<Map<String, Object>> entries = new ArrayList<>();
        Timestamp asOf = null;
        // Manual per-game stats live in game_stat_overrides keyed by profile id, so
        // they can only be applied to the player being viewed.
        boolean joinOverrides = includeMine && playerId != null && overrideTableAvailable();
        for (Map.Entry<String, Map<String, PlayerMeta>> srcEntry : players.entrySet()) {
            String source = srcEntry.getKey();
            String table = GAMES.get(source);
            Map<String, PlayerMeta> byId = srcEntry.getValue();
            if (table == null || byId.isEmpty()) continue;
            List<String> ids = new ArrayList<>(byId.keySet());
            String placeholders = String.join(",", Collections.nCopies(ids.size(), "?"));

            String overrideJoin = joinOverrides
                ? " LEFT JOIN game_stat_overrides o ON o.player_profile_id = ? AND o.source = ?"
                    + " AND o.season_year = t.season_year AND o.game_id = t.game_id"
                : "";
            String goalsColumn = joinOverrides ? "COALESCE(o.goals, t.goals)" : "t.goals";
            String assistsColumn = joinOverrides ? "COALESCE(o.assists, t.assists)" : "t.assists";
            String pimColumn = joinOverrides ? "COALESCE(o.pim, t.pim)" : "t.pim";
            String editedColumn = joinOverrides ? ", (o.id IS NOT NULL) AS stats_edited" : ", false AS stats_edited";

            // Final score. AYHL/MHR keep it on the per-player row; THF/AHF only
            // publish it on the raw schedule, so join that and orient by which
            // side the player's team was on.
            String rawScoreTable = RAW_SCORE_TABLES.get(source);
            boolean rawScores = rawScoreTable != null && tableExistsInDb(rawScoreTable);
            String rawScoreJoin = rawScores
                ? " LEFT JOIN " + rawScoreTable + " rg"
                    + " ON rg.season_year = t.season_year AND rg.game_id = t.game_id"
                : "";
            String scoreColumns;
            if (rawScores) {
                scoreColumns = ", CASE WHEN rg.game_id IS NULL THEN NULL"
                    + " WHEN LOWER(COALESCE(t.team_for, '')) = LOWER(COALESCE(rg.home_team, ''))"
                    + " THEN rg.home_goals ELSE rg.away_goals END AS score_for"
                    + ", CASE WHEN rg.game_id IS NULL THEN NULL"
                    + " WHEN LOWER(COALESCE(t.team_for, '')) = LOWER(COALESCE(rg.home_team, ''))"
                    + " THEN rg.away_goals ELSE rg.home_goals END AS score_against";
            } else {
                scoreColumns = ", t.score_for, t.score_against";
            }

            String sql = "SELECT t.player_id, t.game_id, t.season_year, t.game_date, t.team_for, t.team_against,"
                + " " + goalsColumn + " AS goals, " + assistsColumn + " AS assists, t.points AS scraped_points,"
                + " " + pimColumn + " AS pim, t.game_type, t.league" + scoreColumns + editedColumn
                + " FROM " + table + " t" + overrideJoin + rawScoreJoin
                + " WHERE t.player_id IN (" + placeholders + ")"
                + " AND t.game_date IS NOT NULL ORDER BY t.game_date DESC, t.game_id DESC LIMIT 30";

            List<Object> params = new ArrayList<>();
            if (joinOverrides) {
                params.add(playerId);
                params.add(source);
            }
            params.addAll(ids);

            List<Map<String, Object>> rows = jdbcTemplate.query(sql,
                ps -> {
                    for (int i = 0; i < params.size(); i++) {
                        ps.setObject(i + 1, params.get(i));
                    }
                },
                (rs, rn) -> {
                    String pid = rs.getString("player_id");
                    PlayerMeta meta = byId.get(pid);
                    Map<String, Object> m = new HashMap<>();
                    m.put("source", source);
                    // The (source, seasonYear, gameId) triple keys a manual override,
                    // so the feed can edit a game the same way Game History does.
                    m.put("gameId", rs.getString("game_id"));
                    m.put("seasonYear", (Integer) rs.getObject("season_year"));
                    m.put("league", rs.getString("league"));
                    m.put("gameType", rs.getString("game_type"));
                    m.put("playerName", meta == null ? pid : meta.name());
                    m.put("isMe", meta != null && meta.isMe());
                    m.put("date", rs.getDate("game_date") == null ? null : rs.getDate("game_date").toString());
                    m.put("teamFor", rs.getString("team_for"));
                    m.put("opponent", rs.getString("team_against"));
                    Integer goals = (Integer) rs.getObject("goals");
                    Integer assists = (Integer) rs.getObject("assists");
                    boolean edited = rs.getBoolean("stats_edited");
                    Integer points = (Integer) rs.getObject("scraped_points");
                    // A manual entry replaces the scraped value; recompute points from it.
                    if (edited && (goals != null || assists != null)) {
                        points = (goals == null ? 0 : goals) + (assists == null ? 0 : assists);
                    }
                    m.put("goals", goals);
                    m.put("assists", assists);
                    m.put("points", points);
                    m.put("pim", (Integer) rs.getObject("pim"));
                    // Null when the league has not published a result yet.
                    m.put("scoreFor", (Integer) rs.getObject("score_for"));
                    m.put("scoreAgainst", (Integer) rs.getObject("score_against"));
                    m.put("statsEdited", edited);
                    return m;
                });
            entries.addAll(rows);

            // Freshness: last scrape for these players in this source.
            List<Timestamp> ts = jdbcTemplate.query(
                "SELECT MAX(scraped_at) FROM " + table + " WHERE player_id IN (" + placeholders + ")",
                (rs, rn) -> rs.getTimestamp(1), ids.toArray());
            if (!ts.isEmpty() && ts.get(0) != null && (asOf == null || ts.get(0).after(asOf))) {
                asOf = ts.get(0);
            }
        }

        // MHR games are not reachable through player_source_links — they are
        // resolved per player profile (id, or name for rows scraped before a merge).
        for (PlayerProfile player : includeMine ? ownPlayers(uid, playerId) : List.<PlayerProfile>of()) {
            List<MhrGameLookupService.MhrGame> mhrGames =
                mhrGameLookupService.recentGames(player.getId(), player.getFullName(), 30);
            for (MhrGameLookupService.MhrGame g : mhrGames) {
                Map<String, Object> m = new HashMap<>();
                m.put("source", "MHR");
                m.put("gameId", g.gameId());
                m.put("seasonYear", g.seasonYear());
                // The division names the league that runs the game ("AYHL 11U AAA"),
                // and the type says whether it was league play or an extra — the feed
                // badges a league game with its league instead of the generic MHR.
                m.put("league", g.league());
                m.put("gameType", g.gameType());
                m.put("playerName", player.getFullName());
                m.put("isMe", true);
                m.put("date", g.date() == null ? null : g.date().toString());
                m.put("time", g.gameTime());
                m.put("teamFor", g.teamFor());
                m.put("opponent", g.opponent());
                // MHR publishes no per-player stats, so these stay null until a
                // manual override exists; the feed renders them as "—".
                m.put("goals", g.goals());
                m.put("assists", g.assists());
                m.put("points", g.points());
                m.put("pim", g.pim());
                m.put("scoreFor", g.scoreFor());
                m.put("scoreAgainst", g.scoreAgainst());
                m.put("statsEdited", g.statsEdited());
                entries.add(m);
            }
            Instant scraped = mhrGameLookupService.lastScrapedAt(player.getId(), player.getFullName());
            if (scraped != null && (asOf == null || scraped.isAfter(asOf.toInstant()))) {
                asOf = Timestamp.from(scraped);
            }
        }

        entries.sort((a, b) -> {
            String da = (String) a.get("date");
            String db = (String) b.get("date");
            if (da != null && db != null) {
                int byDate = db.compareTo(da);
                if (byDate != 0) return byDate;
            } else if (da == null && db != null) {
                return 1;
            } else if (da != null) {
                return -1;
            }
            // Same day: latest start time first. Sources that publish no time
            // (AYHL/THF/AHF/NJHS) keep a stable, time-less tail.
            String ta = a.get("time") == null ? "" : (String) a.get("time");
            String tb = b.get("time") == null ? "" : (String) b.get("time");
            return tb.compareTo(ta);
        });
        int cap = Math.max(0, Math.min(limit, entries.size()));

        Map<String, Object> response = new HashMap<>();
        response.put("entries", entries.subList(0, cap));
        response.put("asOf", asOf == null ? null : asOf.toInstant().toString());
        return ResponseEntity.ok(response);
    }

    // ─── enrichment: latest season totals + last 5 games per source ──────────
    private void enrich(Map<String, Object> f) {
        String source = (String) f.get("source");
        String spid = (String) f.get("sourcePlayerId");

        // EP follows: career history from ep_player_career (no game-level data).
        if (source.equals("EP")) {
            enrichEp(f);
            return;
        }

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
            if (!season.isEmpty()) {
                f.put("season", season.get(0));
            } else {
                // No career stats yet — if the player is in the roster, show their
                // current team + season with empty stats instead of "no data".
                Map<String, Object> roster = latestRoster(source, spid);
                if (!roster.isEmpty()) {
                    Map<String, Object> empty = new HashMap<>();
                    empty.put("season", roster.get("season"));
                    empty.put("team", roster.get("team"));
                    empty.put("games", null);
                    empty.put("goals", null);
                    empty.put("assists", null);
                    empty.put("points", null);
                    empty.put("pim", null);
                    f.put("season", empty);
                }
            }
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

    /** Enrich an EP follow: profile link + latest season + full career history. */
    private void enrichEp(Map<String, Object> f) {
        String epId = (String) f.get("sourcePlayerId");
        f.put("profileUrl", "https://www.eliteprospects.com/player/" + epId);
        f.put("recentGames", List.of());

        // Primary (latest) season: latest season, most-games team.
        try {
            List<Map<String, Object>> season = jdbcTemplate.query(
                "SELECT season_label, team_name, league, games_played, goals, assists, points, pim " +
                "FROM ep_player_career WHERE ep_player_id = ? " +
                "ORDER BY season_label DESC, games_played DESC NULLS LAST LIMIT 1",
                (rs, rn) -> {
                    Map<String, Object> m = new HashMap<>();
                    m.put("season", rs.getString("season_label"));
                    m.put("team", rs.getString("team_name"));
                    m.put("league", rs.getString("league"));
                    m.put("games", epInt(rs, "games_played"));
                    m.put("goals", epInt(rs, "goals"));
                    m.put("assists", epInt(rs, "assists"));
                    m.put("points", epInt(rs, "points"));
                    m.put("pim", epInt(rs, "pim"));
                    return m;
                }, epId);
            if (!season.isEmpty()) f.put("season", season.get(0));
        } catch (Exception e) {
            // no career row yet
        }

        // Full career history for the card.
        try {
            List<Map<String, Object>> career = jdbcTemplate.query(
                "SELECT season_label, team_name, league, games_played, goals, assists, points, pim " +
                "FROM ep_player_career WHERE ep_player_id = ? " +
                "ORDER BY season_label, team_name",
                (rs, rn) -> {
                    Map<String, Object> m = new HashMap<>();
                    m.put("season", rs.getString("season_label"));
                    m.put("team", rs.getString("team_name"));
                    m.put("league", rs.getString("league"));
                    m.put("games", epInt(rs, "games_played"));
                    m.put("goals", epInt(rs, "goals"));
                    m.put("assists", epInt(rs, "assists"));
                    m.put("points", epInt(rs, "points"));
                    m.put("pim", epInt(rs, "pim"));
                    return m;
                }, epId);
            f.put("career", career);
        } catch (Exception e) {
            f.put("career", List.of());
        }
    }

    private static Integer epInt(java.sql.ResultSet rs, String col) throws java.sql.SQLException {
        Object v = rs.getObject(col);
        return v == null ? null : ((Number) v).intValue();
    }

    /** Latest roster row for a player (team + season) — used when no career stats exist. */
    private Map<String, Object> latestRoster(String source, String spid) {
        String[] meta = ROSTER.get(source);
        if (meta == null) return Map.of();
        String table = meta[0];
        String seasonCol = meta[2];
        try {
            String sql = "SELECT team_name, " + seasonCol + " AS season " +
                         "FROM " + table + " WHERE player_id = ? " +
                         "ORDER BY season_year DESC LIMIT 1";
            List<Map<String, Object>> rows = jdbcTemplate.query(sql,
                (rs, rn) -> {
                    Map<String, Object> m = new HashMap<>();
                    m.put("team", rs.getString("team_name"));
                    Object s = rs.getObject("season");
                    if (s == null) {
                        m.put("season", null);
                    } else if (seasonCol.equals("season_year")) {
                        int y = ((Number) s).intValue();
                        m.put("season", y + "-" + (y + 1));
                    } else {
                        m.put("season", s.toString());
                    }
                    return m;
                }, spid);
            return rows.isEmpty() ? Map.of() : rows.get(0);
        } catch (Exception e) {
            return Map.of();
        }
    }
}
