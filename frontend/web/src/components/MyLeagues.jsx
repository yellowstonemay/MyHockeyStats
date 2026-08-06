import React, { useEffect, useMemo, useState } from 'react'
import { integrationsApi } from '../lib/integrationsApi'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from './Card'
import { Button } from './Button'
import { FileText, Loader2, RefreshCw } from 'lucide-react'

const SOURCE_META = {
  AYHL: { label: 'AYHL', color: 'bg-indigo-100 text-indigo-700' },
  THF: { label: 'THF', color: 'bg-sky-100 text-sky-700' },
  AHF: { label: 'AHF', color: 'bg-emerald-100 text-emerald-700' },
  NJHS: { label: 'NJ HS', color: 'bg-amber-100 text-amber-700' },
}

function startYear(season) {
  const m = String(season || '').match(/(\d{4})/)
  return m ? Number(m[1]) : 0
}

export default function MyLeagues({ onViewReport }) {
  const [rankings, setRankings] = useState([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [refreshing, setRefreshing] = useState(false)
  const [refreshMsg, setRefreshMsg] = useState(null)

  const load = async () => {
    setLoading(true)
    setError('')
    try {
      const resp = await integrationsApi.fetchRankings()
      setRankings(resp.rankings || [])
    } catch (err) {
      setError(err.message || 'Failed to load your leagues')
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    load()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  // Latest season per source
  const leagues = useMemo(() => {
    const bySource = {}
    rankings.forEach((r) => {
      const cur = bySource[r.source]
      if (!cur || startYear(r.season) > startYear(cur.season)) {
        bySource[r.source] = r
      }
    })
    return Object.values(bySource).sort((a, b) => a.source.localeCompare(b.source))
  }, [rankings])

  const refresh = async () => {
    setRefreshing(true)
    setRefreshMsg(null)
    try {
      const resp = await integrationsApi.requestDeepDive()
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
          <CardTitle>My Leagues</CardTitle>
          <CardDescription>
            Leagues you're linked to, with your latest team ranking (refreshed by the daily deep-dive)
          </CardDescription>
        </div>
        <div className="flex flex-col sm:flex-row gap-2">
          <Button variant="outline" onClick={refresh} disabled={refreshing}>
            {refreshing ? <Loader2 className="w-4 h-4 animate-spin mr-2" /> : <RefreshCw className="w-4 h-4 mr-2" />}
            Refresh my stats
          </Button>
          {onViewReport && (
            <Button onClick={onViewReport}>
              <FileText className="w-4 h-4 mr-2" /> View your player report
            </Button>
          )}
        </div>
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
            <Loader2 className="w-4 h-4 animate-spin mr-2" /> Loading leagues...
          </div>
        ) : leagues.length === 0 ? (
          <p className="text-sm text-slate-500 text-center py-6">
            No leagues linked yet. Make sure your profile has your birthdate and New Jersey location,
            then hit <strong>Refresh my stats</strong>.
          </p>
        ) : (
          <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-3">
            {leagues.map((l) => {
              const meta = SOURCE_META[l.source] || { label: l.source, color: 'bg-slate-100 text-slate-600' }
              return (
                <div key={l.source} className="border border-slate-200 rounded-lg p-4">
                  <span className={`inline-block text-xs font-semibold px-2 py-0.5 rounded-full ${meta.color}`}>
                    {meta.label}
                  </span>
                  <div className="mt-2 font-medium text-slate-900 text-sm truncate" title={l.team || ''}>
                    {l.team || '—'}
                  </div>
                  <div className="text-xs text-slate-500 mt-0.5">{l.season}</div>
                  <div className="mt-3 flex items-center justify-between text-xs">
                    <span className="text-slate-500">PTS</span>
                    <span className="font-semibold text-slate-900">{l.points ?? '—'}</span>
                  </div>
                  <div className="flex items-center justify-between text-xs">
                    <span className="text-slate-500">Team rank</span>
                    <span className="font-semibold text-slate-900">
                      {l.teamRank && l.teamSize ? `#${l.teamRank} / ${l.teamSize}` : '—'}
                    </span>
                  </div>
                  <div className="flex items-center justify-between text-xs">
                    <span className="text-slate-500">League %</span>
                    <span className="font-semibold text-slate-900">
                      {l.leaguePercentile ? `${l.leaguePercentile}%` : '—'}
                    </span>
                  </div>
                </div>
              )
            })}
          </div>
        )}
      </CardContent>
    </Card>
  )
}
