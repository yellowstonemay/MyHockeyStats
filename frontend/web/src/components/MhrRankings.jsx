import React, { useEffect, useState } from 'react'
import { integrationsApi } from '../lib/integrationsApi'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from './Card'
import { Button } from './Button'
import { ExternalLink, Loader2, RefreshCw, TrendingDown, TrendingUp } from 'lucide-react'

const MHR_BASE = 'https://myhockeyrankings.com'

// "2026-2027 Season" -> "2026-27"
function shortSeason(seasonValue) {
  const m = String(seasonValue ?? '').match(/(\d{4})\D+(\d{4})/)
  return m ? `${m[1]}-${m[2].slice(2)}` : String(seasonValue ?? '')
}

function ordinal(value) {
  const n = Number(value)
  if (!Number.isFinite(n)) return '—'
  const suffix = n % 100 >= 11 && n % 100 <= 13 ? 'th' : ['th', 'st', 'nd', 'rd'][n % 10] || 'th'
  return `${n}${suffix}`
}

function Trend({ trend }) {
  if (trend === 'up') return <TrendingUp className="w-3.5 h-3.5 text-emerald-600" title="Up since last week" />
  if (trend === 'down') return <TrendingDown className="w-3.5 h-3.5 text-red-600" title="Down since last week" />
  return <span className="w-3.5" />
}

/**
 * The MYHockeyRankings position of the player's TEAM(s) — the same "Rankings"
 * card MHR puts on the team page we scrape the schedule from (state / national /
 * tier categories, with MHR's week-over-week arrow). MHR ranks teams, never
 * individual players.
 */
export default function MhrRankings({ playerId }) {
  const [seasons, setSeasons] = useState([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [refreshing, setRefreshing] = useState(false)
  const [refreshMsg, setRefreshMsg] = useState(null)

  const load = async () => {
    setLoading(true)
    setError('')
    try {
      const resp = await integrationsApi.fetchMyMhrRankings(playerId)
      setSeasons(resp.seasons || [])
    } catch (err) {
      setError(err.message || 'Failed to load rankings')
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    load()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [playerId])

  const refresh = async () => {
    setRefreshing(true)
    setRefreshMsg(null)
    try {
      // Scope the refresh to the player being viewed so we don't re-scrape the
      // whole account just because one player's card was on screen.
      const resp = playerId
        ? await integrationsApi.requestPlayerDeepDive(playerId)
        : await integrationsApi.requestDeepDive()
      setRefreshMsg({ kind: 'ok', text: resp.message || 'Your stats are being refreshed.' })
    } catch (err) {
      setRefreshMsg({ kind: 'err', text: err.message || 'Failed to start refresh' })
    } finally {
      setRefreshing(false)
    }
  }

  return (
    <Card>
      <CardHeader className="flex flex-col sm:flex-row sm:items-start sm:justify-between gap-3">
        <div>
          <CardTitle>MHR Team Ranking</CardTitle>
          <CardDescription>
            MYHockeyRankings ranks teams, not individual players. Each row is your team&apos;s place
            in that category, and the arrow shows movement since last week.
          </CardDescription>
        </div>
        {/* Only the refresh stays: the player report already has its own tab in the
            sidebar / mobile nav, so a shortcut here was pure duplication. */}
        <Button variant="outline" onClick={refresh} disabled={refreshing}>
          {refreshing ? <Loader2 className="w-4 h-4 animate-spin mr-2" /> : <RefreshCw className="w-4 h-4 mr-2" />}
          Refresh my stats
        </Button>
      </CardHeader>
      <CardContent>
        {refreshMsg && (
          <div
            className={`mb-4 p-3 border rounded-md text-sm ${
              refreshMsg.kind === 'ok'
                ? 'bg-emerald-50 border-emerald-300 text-emerald-700'
                : 'bg-red-100 border-red-400 text-red-700'
            }`}
          >
            {refreshMsg.text}
          </div>
        )}
        {error && <p className="text-sm text-red-700 mb-3">{error}</p>}
        {loading ? (
          <div className="flex items-center justify-center py-6 text-slate-500">
            <Loader2 className="w-4 h-4 animate-spin mr-2" /> Loading rankings...
          </div>
        ) : seasons.length === 0 ? (
          <p className="text-sm text-slate-500 text-center py-6">
            No MHR team ranking yet. MHR only ranks whole teams — once your team is ranked (and the daily
            deep-dive has read its page) the categories appear here.
          </p>
        ) : (
          <div className="space-y-5">
            {seasons.map((season) => (
              <div key={`${season.seasonYear}-${season.teamId}`}>
                <div className="flex flex-wrap items-baseline gap-x-2 gap-y-1 mb-2">
                  <span className="font-medium text-slate-900 text-sm">{season.teamName || 'Your team'}</span>
                  {season.division && <span className="text-xs text-slate-500">{season.division}</span>}
                  <span className="text-[11px] text-slate-400">{shortSeason(season.seasonLabel)}</span>
                </div>
                <ul className="divide-y divide-slate-100 border border-slate-200 rounded-lg overflow-hidden">
                  {(season.entries || []).map((entry) => (
                    <li
                      key={entry.category}
                      className="flex items-center gap-3 px-3 py-2"
                    >
                      <span className="w-4 shrink-0 flex justify-center">
                        <Trend trend={entry.trend} />
                      </span>
                      <span className="text-sm font-semibold text-slate-900 w-12 text-right shrink-0">
                        {ordinal(entry.rank)}
                      </span>
                      <span className="text-sm text-slate-700 flex-1">{entry.category}</span>
                      {entry.url && (
                        <a
                          href={`${MHR_BASE}${entry.url}`}
                          target="_blank"
                          rel="noreferrer"
                          className="text-slate-400 hover:text-primary-600 shrink-0"
                          title="See this ranking list on MYHockeyRankings"
                        >
                          <ExternalLink className="w-3.5 h-3.5" />
                        </a>
                      )}
                    </li>
                  ))}
                </ul>
              </div>
            ))}
            <p className="text-[11px] text-slate-400">
              A MHR team ranking — every player on the team shares it.
            </p>
          </div>
        )}
      </CardContent>
    </Card>
  )
}
