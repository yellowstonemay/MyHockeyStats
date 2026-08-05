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
  fetchMySeasons(season) {
    const query = season ? `?season=${encodeURIComponent(season)}` : ''
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

  // Team / league rankings per season for the current player
  fetchRankings() {
    return api.fetchWithAuth('/rankings')
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

  fetchMyGameHistory(seasonYear) {
    const query = seasonYear ? `?seasonYear=${encodeURIComponent(seasonYear)}` : ''
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

  // Merged recent-games feed (me + followed players) with freshness timestamp
  fetchActivity(limit) {
    const qs = limit ? `?limit=${limit}` : ''
    return api.fetchWithAuth(`/follows/activity${qs}`)
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
