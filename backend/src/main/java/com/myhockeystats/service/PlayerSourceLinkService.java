package com.myhockeystats.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Resolves the (source, source_player_id) identities that back a player's stats.
 *
 * Links live on the player profile ({@code player_source_links}) because a
 * player is shared between logins, so every per-player view must scope through
 * the selected player rather than the login. The legacy
 * {@code player_identity_map} rows (keyed by user) are still consulted as a
 * fallback for accounts created before the link table existed.
 */
@Service
public class PlayerSourceLinkService {

    /** One confirmed source identity of a player. */
    public record PlayerLink(String source, String sourcePlayerId) {}

    private static final String PLAYER_SQL =
        "SELECT DISTINCT source, source_player_id FROM player_source_links " +
        "WHERE player_id = ? AND link_state = 'CONFIRMED'";

    private static final String USER_SQL =
        "SELECT DISTINCT psl.source, psl.source_player_id FROM player_source_links psl " +
        "JOIN user_players up ON up.player_id = psl.player_id " +
        "WHERE up.user_id = ? AND psl.link_state = 'CONFIRMED'";

    private static final String LEGACY_SQL =
        "SELECT DISTINCT source, source_player_id FROM player_identity_map " +
        "WHERE user_id = ? AND link_state = 'CONFIRMED'";

    private final JdbcTemplate jdbcTemplate;

    public PlayerSourceLinkService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** Confirmed links of a single player profile. */
    public List<PlayerLink> forPlayer(long playerId) {
        return query(PLAYER_SQL, playerId);
    }

    /** Confirmed links of every player attached to a login. */
    public List<PlayerLink> forUser(long userId) {
        return query(USER_SQL, userId);
    }

    /**
     * Links to use for a login, scoped to {@code playerId} when one is given.
     * An explicitly selected player with no links yields none (never another
     * player's stats); an unscoped lookup falls back to the legacy identity map.
     */
    public List<PlayerLink> resolve(long userId, Long playerId) {
        if (playerId != null) {
            return forPlayer(playerId);
        }
        List<PlayerLink> links = forUser(userId);
        return links.isEmpty() ? query(LEGACY_SQL, userId) : links;
    }

    private List<PlayerLink> query(String sql, long id) {
        List<PlayerLink> out = new ArrayList<>();
        for (Map<String, Object> row : jdbcTemplate.queryForList(sql, id)) {
            String source = (String) row.get("source");
            String sourcePlayerId = (String) row.get("source_player_id");
            if (source != null && sourcePlayerId != null) {
                out.add(new PlayerLink(source, sourcePlayerId));
            }
        }
        return out;
    }
}
