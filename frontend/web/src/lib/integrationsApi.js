import { api } from './utils'

export const integrationsApi = {
  getMatchStatus() {
    return api.fetchWithAuth('/integrations/me/match-status')
  },

  confirmMatches(selectedCandidates) {
    return api.fetchWithAuth('/integrations/me/matches/confirm', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ selectedCandidates }),
    })
  },

  getHistory(season) {
    const query = season ? `?season=${encodeURIComponent(season)}` : ''
    return api.fetchWithAuth(`/integrations/me/history${query}`)
  },

  getParentHistory(playerUserId, season, linkedPlayerUserId) {
    const query = season ? `?season=${encodeURIComponent(season)}` : ''
    const linkedQuery = linkedPlayerUserId
      ? `${query ? '&' : '?'}linkedPlayerUserId=${encodeURIComponent(linkedPlayerUserId)}`
      : ''
    return api.fetchWithAuth(`/integrations/players/${playerUserId}/history${query}${linkedQuery}`)
  },

  runImport(sources) {
    return api.fetchWithAuth('/integrations/imports/run', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ sources, triggerType: 'OPERATOR_MANUAL' }),
    })
  },

  getImportRun(runId) {
    return api.fetchWithAuth(`/integrations/imports/${runId}`)
  },

  getLatestDailyRun() {
    return api.fetchWithAuth('/integrations/imports/daily/latest')
  },

  // New simplified seasons API for currently authenticated player
  fetchMySeasons(season, playerId) {
    const params = new URLSearchParams()
    if (season) params.set('season', season)
    if (playerId) params.set('playerId', playerId)
    const query = params.toString() ? `?${params.toString()}` : ''
    return api.fetchWithAuth(`/players/me/seasons${query}`)
  },

  // Update a user-editable (skeleton/empty) season record's stats
  updateMySeason(source, sourcePlayerId, seasonLabel, stats) {
    return api.fetchWithAuth(
      `/players/me/seasons/${encodeURIComponent(source)}/${encodeURIComponent(sourcePlayerId)}/${encodeURIComponent(seasonLabel)}`,
      {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(stats),
      }
    )
  },

  // Admin: all registered players (registration desc) with last login + deep-dive
  fetchAdminPlayers() {
    return api.fetchWithAuth('/admin/players')
  },

  // Admin: enqueue a FULL (all-seasons) deep-dive for a user
  triggerDeepDive(userId) {
    return api.fetchWithAuth(`/admin/deep-dive/${encodeURIComponent(userId)}`, {
      method: 'POST',
    })
  },

  // Notifications (e.g. "stats being retrieved" while a deep-dive runs)
  fetchNotifications() {
    return api.fetchWithAuth('/notifications')
  },

  dismissNotification(id) {
    return api.fetchWithAuth(`/notifications/${encodeURIComponent(id)}/dismiss`, {
      method: 'POST',
    })
  },

  // Team / league rankings per season for a player on this account
  fetchRankings(playerId) {
    const qs = playerId ? `?playerId=${encodeURIComponent(playerId)}` : ''
    return api.fetchWithAuth(`/rankings${qs}`)
  },

  // Current user's own player profile
  fetchMyProfile() {
    return api.fetchWithAuth('/players/me')
  },

  updateMyProfile(data) {
    return api.fetchWithAuth('/players/me', {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(data),
    })
  },

  // ── Players on this account (1 login -> many players) ─────────────────────
  fetchMyPlayers() {
    return api.fetchWithAuth('/players')
  },

  createPlayer(data) {
    return api.fetchWithAuth('/players', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(data),
    })
  },

  updatePlayer(playerId, data) {
    return api.fetchWithAuth(`/players/${encodeURIComponent(playerId)}`, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(data),
    })
  },

  // Attach an EXISTING player to this login (many logins -> same player)
  linkPlayer(playerId, relation) {
    return api.fetchWithAuth('/players/link', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ playerId, relation }),
    })
  },

  unlinkPlayer(playerId) {
    return api.fetchWithAuth(`/players/${encodeURIComponent(playerId)}/link`, {
      method: 'DELETE',
    })
  },

  setPrimaryPlayer(playerId) {
    return api.fetchWithAuth(`/players/${encodeURIComponent(playerId)}/primary`, {
      method: 'POST',
    })
  },

  // Refresh one player's stats (deduped per player across all logins)
  requestPlayerDeepDive(playerId) {
    return api.fetchWithAuth(`/players/${encodeURIComponent(playerId)}/deep-dive`, {
      method: 'POST',
    })
  },

  // Manual G/A/PIM for one game (needs edit rights on the player)
  saveGameStats(playerId, game) {
    return api.fetchWithAuth(`/players/${encodeURIComponent(playerId)}/game-stats`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(game),
    })
  },

  // Drop the manual entry so the scraped value shows again
  resetGameStats(playerId, game) {
    return api.fetchWithAuth(`/players/${encodeURIComponent(playerId)}/game-stats/reset`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(game),
    })
  },

  // Owner or admin: grant/revoke edit rights for a login attached to the player
  setPlayerEditor(playerId, { email, userId, canEdit }) {
    return api.fetchWithAuth(`/players/${encodeURIComponent(playerId)}/editors`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ email, userId, canEdit }),
    })
  },

  // Admin: hand a player to another login
  setPlayerOwner(playerId, { email, userId }) {
    return api.fetchWithAuth(`/players/${encodeURIComponent(playerId)}/owner`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ email, userId }),
    })
  },

  // Admin: every player profile with its owner and attached logins
  fetchAdminProfiles() {
    return api.fetchWithAuth('/admin/profiles')
  },

  // Admin: fold a duplicate profile into the surviving one, then delete it
  mergePlayerProfile(sourceId, targetProfileId) {
    return api.fetchWithAuth(`/admin/profiles/${encodeURIComponent(sourceId)}/merge`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ targetProfileId }),
    })
  },

  // Enqueue a deep-dive for the current user ("refresh my stats")
  requestDeepDive() {
    return api.fetchWithAuth('/players/me/deep-dive', {
      method: 'POST',
    })
  },

  // Full team roster for a season, ordered by the ranking criteria
  fetchTeamRankings(source, season, teamId) {
    const qs = `?source=${encodeURIComponent(source)}&season=${encodeURIComponent(season)}&teamId=${encodeURIComponent(teamId)}`
    return api.fetchWithAuth(`/rankings/team${qs}`)
  },

  fetchMyGameHistory(seasonYear, playerId) {
    const params = new URLSearchParams()
    if (seasonYear) params.set('seasonYear', seasonYear)
    if (playerId) params.set('playerId', playerId)
    const query = params.toString() ? `?${params.toString()}` : ''
    return api.fetchWithAuth(`/players/me/game-history${query}`)
  },

  // ── Follow a player ──────────────────────────────────────────────────────
  fetchFollows() {
    return api.fetchWithAuth('/follows')
  },

  searchFollows(q) {
    return api.fetchWithAuth(`/follows/search?q=${encodeURIComponent(q)}`)
  },

  addFollow(data) {
    return api.fetchWithAuth('/follows', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(data),
    })
  },

  deleteFollow(id) {
    return api.fetchWithAuth(`/follows/${encodeURIComponent(id)}`, {
      method: 'DELETE',
    })
  },

  // Merged recent-games feed (one player + followed players) with freshness timestamp
  fetchActivity(limit, playerId) {
    const params = new URLSearchParams()
    if (limit) params.set('limit', limit)
    if (playerId) params.set('playerId', playerId)
    const qs = params.toString() ? `?${params.toString()}` : ''
    return api.fetchWithAuth(`/follows/activity${qs}`)
  },

  // ── Elite Prospects (EP) on-tap lookup fallback ────────────────────────
  createEpLookup(name) {
    return api.fetchWithAuth('/ep/lookup', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ name }),
    })
  },

  getEpLookup(requestId) {
    return api.fetchWithAuth(`/ep/lookup/${encodeURIComponent(requestId)}`)
  },

  enqueueEpCareer(epPlayerId, playerName) {
    return api.fetchWithAuth('/ep/career', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ epPlayerId, playerName }),
    })
  },

  getEpCareer(epPlayerId) {
    return api.fetchWithAuth(`/ep/career/${encodeURIComponent(epPlayerId)}`)
  },

  // ── Support / report to admin ───────────────────────────────────────────
  sendSupportMessage(data) {
    return api.fetchWithAuth('/support/messages', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(data),
    })
  },

  fetchMySupportMessages() {
    return api.fetchWithAuth('/support/messages')
  },

  fetchAdminMessages() {
    return api.fetchWithAuth('/admin/messages')
  },

  resolveAdminMessage(id) {
    return api.fetchWithAuth(`/admin/messages/${encodeURIComponent(id)}/resolve`, {
      method: 'POST',
    })
  },
}
