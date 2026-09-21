package com.myhockeystats.service;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.util.List;

/**
 * Queues deep-dive ("refresh my stats") jobs.
 *
 * Jobs are keyed by PLAYER, not by login. A player can be attached to several
 * logins (two parents, player + guardian, …) so:
 *   - a request from any of those logins queues exactly one scrape, and
 *   - the "retrieving stats" notification is raised for every attached login.
 *
 * The Mac mini poller (scripts/deep_dive/run_deep_dive_requests.py) picks the
 * rows up by player_id and clears the notifications for all attached logins
 * when it finishes.
 */
@Service
public class DeepDiveService {

    /** Min minutes between two full refreshes of the same player (protects sources). */
    private static final long COOLDOWN_MINUTES = 30;

    private final JdbcTemplate jdbcTemplate;
    private final PlayerProfileService playerProfileService;

    public DeepDiveService(JdbcTemplate jdbcTemplate, PlayerProfileService playerProfileService) {
        this.jdbcTemplate = jdbcTemplate;
        this.playerProfileService = playerProfileService;
    }

    public record QueueResult(boolean queued, String message) {}

    /**
     * Queue a full (all-seasons) refresh for one player.
     *
     * @param playerId          player to refresh
     * @param requestedByUserId login that asked for it (recorded for auditing)
     */
    public QueueResult enqueueForPlayer(Long playerId, Long requestedByUserId) {
        if (playerId == null) {
            return new QueueResult(false, "Select a player first.");
        }

        List<Integer> pending = jdbcTemplate.queryForList(
            "SELECT 1 FROM deep_dive_requests WHERE player_id = ? AND status IN ('PENDING','RUNNING') LIMIT 1",
            Integer.class, playerId);
        if (!pending.isEmpty()) {
            return new QueueResult(false, "These stats are already being refreshed.");
        }

        List<Timestamp> lastRequest = jdbcTemplate.queryForList(
            "SELECT requested_at FROM deep_dive_requests WHERE player_id = ? " +
            "ORDER BY requested_at DESC LIMIT 1",
            Timestamp.class, playerId);
        if (!lastRequest.isEmpty() && lastRequest.get(0) != null) {
            long waitedMinutes = (System.currentTimeMillis() - lastRequest.get(0).getTime()) / 60000L;
            if (waitedMinutes < COOLDOWN_MINUTES) {
                long remaining = COOLDOWN_MINUTES - waitedMinutes;
                return new QueueResult(false,
                    "Stats were just refreshed. You can refresh again in " + remaining + " min.");
            }
        }

        try {
            jdbcTemplate.update(
                "INSERT INTO deep_dive_requests (id, player_id, user_id, scope, status, requested_at) " +
                "VALUES (gen_random_uuid(), ?, ?, 'ALL_SEASONS', 'PENDING', NOW())",
                playerId, requestedByUserId);
        } catch (DataIntegrityViolationException e) {
            // uq_deep_dive_requests_active_player: a request for this player was
            // inserted between the check above and this insert (double-click, or
            // the poller claiming the row). The existing request wins.
            return new QueueResult(false, "These stats are already being refreshed.");
        }

        // Notify every login attached to this player, not just the requester.
        for (Long userId : playerProfileService.getUserIdsForPlayer(playerId)) {
            jdbcTemplate.update(
                "INSERT INTO notifications (id, user_id, type, title, message, status) " +
                "VALUES (gen_random_uuid(), ?, 'DEEP_DIVE', ?, ?, 'ACTIVE') " +
                "ON CONFLICT (user_id, type, status) DO NOTHING",
                userId, "Stats are being retrieved",
                "We're pulling together this player's game history and season stats. " +
                "This usually takes a few minutes.");
        }

        return new QueueResult(true, "We're refreshing these stats — this can take a few minutes.");
    }

    /** Queue a refresh for every player attached to a login. */
    public QueueResult enqueueForUser(Long userId) {
        List<Long> playerIds = playerProfileService.listPlayersForUser(userId).stream()
            .map(p -> p.getId())
            .toList();
        if (playerIds.isEmpty()) {
            return new QueueResult(false, "Add a player profile first, then we can retrieve their stats.");
        }

        boolean queuedAny = false;
        String lastMessage = null;
        for (Long playerId : playerIds) {
            QueueResult result = enqueueForPlayer(playerId, userId);
            queuedAny = queuedAny || result.queued();
            lastMessage = result.message();
        }
        if (queuedAny) {
            return new QueueResult(true,
                playerIds.size() > 1
                    ? "We're refreshing stats for your " + playerIds.size() + " players."
                    : "We're refreshing these stats — this can take a few minutes.");
        }
        return new QueueResult(false, lastMessage);
    }
}
