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

  fetchMyGameHistory(seasonYear) {
    const query = seasonYear ? `?seasonYear=${encodeURIComponent(seasonYear)}` : ''
    return api.fetchWithAuth(`/players/me/game-history${query}`)
  },
}
