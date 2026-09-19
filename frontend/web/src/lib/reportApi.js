import { api } from './utils'

export const reportApi = {
  // Feature 1: unified player report (free for every user)
  fetchMyReport(playerId) {
    const qs = playerId ? `?playerId=${encodeURIComponent(playerId)}` : ''
    return api.fetchWithAuth(`/players/me/report${qs}`)
  },

  // Feature 2: cached AI season report for a season
  fetchInsights(season, playerId) {
    const params = new URLSearchParams()
    if (season) params.set('season', season)
    if (playerId) params.set('playerId', playerId)
    const qs = params.toString() ? `?${params.toString()}` : ''
    return api.fetchWithAuth(`/players/me/report/insights${qs}`)
  },

  // Feature 2: generate the AI season report (weekly/daily cost-capped)
  generateInsights(season, playerId) {
    return api.fetchWithAuth('/players/me/report/insights', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ season, playerId: playerId || null }),
    })
  },
}
