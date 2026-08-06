import { api } from './utils'

export const reportApi = {
  // Feature 1: unified player report (free for every user)
  fetchMyReport() {
    return api.fetchWithAuth('/players/me/report')
  },

  // Feature 2: cached AI season report for a season
  fetchInsights(season) {
    const qs = season ? `?season=${encodeURIComponent(season)}` : ''
    return api.fetchWithAuth(`/players/me/report/insights${qs}`)
  },

  // Feature 2: generate the AI season report (weekly/daily cost-capped)
  generateInsights(season) {
    return api.fetchWithAuth('/players/me/report/insights', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ season }),
    })
  },
}
