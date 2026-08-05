import React, { useEffect, useMemo, useState } from 'react'
import {
  ResponsiveContainer, BarChart, Bar, XAxis, YAxis, Tooltip, Legend,
  CartesianGrid, LineChart, Line,
} from 'recharts'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from './Card'
import { Loader2, AlertCircle } from 'lucide-react'
import { integrationsApi } from '../lib/integrationsApi'

const SOURCE_LABELS = {
  AYHL: 'AYHL',
  THF: 'THF',
  AHF: 'AHF',
  NJHS: 'NJ HS',
}

function seasonStartYear(seasonValue) {
  if (seasonValue == null) return 0
  const m = String(seasonValue).match(/(\d{4})/)
  return m ? Number(m[1]) : 0
}

function cleanOpponent(opp) {
  return String(opp || 'Unknown').replace(/^(vs\.?|@)\s*/i, '').trim() || 'Unknown'
}

function fmt(n) {
  return Number(n ?? 0).toFixed(2)
}

export default function StatisticsTab({ seasonRecords }) {
  const [gameHistory, setGameHistory] = useState([])
  const [loadingGames, setLoadingGames] = useState(true)
  const [gamesError, setGamesError] = useState('')
  const [selectedSeason, setSelectedSeason] = useState('')

  useEffect(() => {
    let cancelled = false
    setLoadingGames(true)
    setGamesError('')
    integrationsApi
      .fetchMyGameHistory('')
      .then((resp) => {
        if (cancelled) return
        setGameHistory(resp.games || [])
        const seasons = resp.availableSeasons || []
        if (seasons.length && !selectedSeason) {
          setSelectedSeason(String(seasons[0].seasonYear))
        }
      })
      .catch((err) => {
        if (!cancelled) setGamesError(err.message || 'Failed to load game history')
      })
      .finally(() => {
        if (!cancelled) setLoadingGames(false)
      })
    return () => {
      cancelled = true
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  // ── Career cards ────────────────────────────────────────────────────────
  const career = useMemo(() => {
    return seasonRecords.reduce(
      (acc, r) => ({
        games: acc.games + (r.gamesPlayed ?? 0),
        goals: acc.goals + (r.goals ?? 0),
        assists: acc.assists + (r.assists ?? 0),
        points: acc.points + (r.points ?? 0),
        pim: (acc.pim ?? 0) + (r.pim ?? 0),
      }),
      { games: 0, goals: 0, assists: 0, points: 0, pim: 0 }
    )
  }, [seasonRecords])

  // ── Season-over-season trend (all leagues combined per season year) ──────
  const seasonTrends = useMemo(() => {
    const byYear = {}
    seasonRecords.forEach((r) => {
      const year = seasonStartYear(r.season)
      if (!year) return
      if (!byYear[year]) {
        byYear[year] = { year, label: r.season, goals: 0, assists: 0, points: 0, games: 0, pim: 0 }
      }
      byYear[year].goals += r.goals ?? 0
      byYear[year].assists += r.assists ?? 0
      byYear[year].points += r.points ?? 0
      byYear[year].games += r.gamesPlayed ?? 0
      byYear[year].pim += r.pim ?? 0
    })
    return Object.values(byYear)
      .sort((a, b) => a.year - b.year)
      .map((d) => ({ ...d, ppg: d.games ? Number((d.points / d.games).toFixed(2)) : 0 }))
  }, [seasonRecords])

  // ── League split ────────────────────────────────────────────────────────
  const leagueSplit = useMemo(() => {
    const map = {}
    seasonRecords.forEach((r) => {
      const src = r.source || 'Unknown'
      if (!map[src]) map[src] = { league: SOURCE_LABELS[src] || src, source: src, seasons: 0, games: 0, goals: 0, assists: 0, points: 0, pim: 0 }
      map[src].seasons += 1
      map[src].games += r.gamesPlayed ?? 0
      map[src].goals += r.goals ?? 0
      map[src].assists += r.assists ?? 0
      map[src].points += r.points ?? 0
      map[src].pim += r.pim ?? 0
    })
    return Object.values(map).sort((a, b) => b.games - a.games)
  }, [seasonRecords])

  // ── Game-level stats (from game history) ────────────────────────────────
  const sortedGames = useMemo(() => {
    return [...gameHistory].sort((a, b) => String(a.gameDate || '').localeCompare(String(b.gameDate || '')))
  }, [gameHistory])

  const seasonOptions = useMemo(() => {
    const map = {}
    sortedGames.forEach((g) => {
      if (g.seasonYear != null) map[g.seasonYear] = g.seasonYear
    })
    return Object.keys(map)
      .map(Number)
      .sort((a, b) => b - a)
  }, [sortedGames])

  const selectedSeasonGames = useMemo(() => {
    const year = Number(selectedSeason)
    if (!year) return sortedGames
    return sortedGames.filter((g) => Number(g.seasonYear) === year)
  }, [sortedGames, selectedSeason])

  const perGameChart = useMemo(() => {
    return selectedSeasonGames.map((g, i) => ({
      idx: i + 1,
      date: g.gameDate || '',
      goals: g.goals ?? 0,
      assists: g.assists ?? 0,
      points: g.points ?? 0,
      pim: g.pim ?? 0,
    }))
  }, [selectedSeasonGames])

  const pointsDistribution = useMemo(() => {
    const dist = [0, 0, 0, 0] // 0, 1, 2, 3+
    sortedGames.forEach((g) => {
      const p = g.points ?? 0
      if (p >= 3) dist[3] += 1
      else dist[p] += 1
    })
    return [
      { label: '0 pts', value: dist[0] },
      { label: '1 pt', value: dist[1] },
      { label: '2 pts', value: dist[2] },
      { label: '3+ pts', value: dist[3] },
    ]
  }, [sortedGames])

  const streaks = useMemo(() => {
    let cur = 0
    let best = 0
    sortedGames.forEach((g) => {
      if ((g.points ?? 0) > 0) {
        cur += 1
        best = Math.max(best, cur)
      } else {
        cur = 0
      }
    })
    return { longest: best, total: sortedGames.length }
  }, [sortedGames])

  const topOpponents = useMemo(() => {
    const map = {}
    sortedGames.forEach((g) => {
      const opp = cleanOpponent(g.teamAgainst || g.opponent)
      if (!map[opp]) map[opp] = { opponent: opp, games: 0, points: 0 }
      map[opp].games += 1
      map[opp].points += g.points ?? 0
    })
    return Object.values(map)
      .sort((a, b) => b.points - a.points)
      .slice(0, 8)
  }, [sortedGames])

  const multiPointGames = useMemo(() => {
    return sortedGames.filter((g) => (g.points ?? 0) >= 2).length
  }, [sortedGames])

  const ppgCareer = career.games ? Number((career.points / career.games).toFixed(2)) : 0

  const StatCard = ({ label, value, sub }) => (
    <Card>
      <CardContent className="p-4 text-center">
        <div className="text-2xl font-bold text-slate-900">{value}</div>
        <div className="text-xs text-slate-500 mt-1">{label}</div>
        {sub ? <div className="text-[11px] text-slate-400 mt-0.5">{sub}</div> : null}
      </CardContent>
    </Card>
  )

  if (loadingGames) {
    return (
      <div className="flex items-center justify-center py-16 text-slate-500">
        <Loader2 className="w-5 h-5 animate-spin mr-2" />
        Loading statistics...
      </div>
    )
  }

  if (gamesError) {
    return (
      <Card>
        <CardContent>
          <div className="flex items-center gap-2 text-red-700 py-4">
            <AlertCircle className="w-5 h-5" />
            <span>{gamesError}</span>
          </div>
        </CardContent>
      </Card>
    )
  }

  return (
    <div className="space-y-4">
      {/* Career header cards */}
      <div className="grid grid-cols-2 md:grid-cols-3 xl:grid-cols-6 gap-3">
        <StatCard label="Career Games" value={career.games} />
        <StatCard label="Goals" value={career.goals} />
        <StatCard label="Assists" value={career.assists} />
        <StatCard label="Points" value={career.points} />
        <StatCard label="PIM" value={career.pim} />
        <StatCard label="Points / Game" value={ppgCareer.toFixed(2)} />
      </div>

      {/* Season-over-season trend */}
      {seasonTrends.length > 0 && (
        <Card>
          <CardHeader>
            <CardTitle>Season-over-Season</CardTitle>
            <CardDescription>Goals, assists, and points per season (all leagues combined)</CardDescription>
          </CardHeader>
          <CardContent>
            <div className="h-72">
              <ResponsiveContainer width="100%" height="100%">
                <BarChart data={seasonTrends} margin={{ top: 8, right: 8, left: -12, bottom: 0 }}>
                  <CartesianGrid strokeDasharray="3 3" stroke="#e2e8f0" />
                  <XAxis dataKey="label" tick={{ fontSize: 12 }} />
                  <YAxis tick={{ fontSize: 12 }} />
                  <Tooltip />
                  <Legend wrapperStyle={{ fontSize: 12 }} />
                  <Bar dataKey="goals" name="Goals" stackId="a" fill="#6366f1" />
                  <Bar dataKey="assists" name="Assists" stackId="a" fill="#38bdf8" />
                  <Line type="monotone" dataKey="ppg" name="PTS/Game" stroke="#f59e0b" strokeWidth={2} dot={{ r: 3 }} />
                </BarChart>
              </ResponsiveContainer>
            </div>
          </CardContent>
        </Card>
      )}

      {/* Per-season game chart */}
      <Card>
        <CardHeader className="flex flex-row items-center justify-between">
          <div>
            <CardTitle>Game-by-Game</CardTitle>
            <CardDescription>Points per game for the selected season</CardDescription>
          </div>
          <select
            value={selectedSeason}
            onChange={(e) => setSelectedSeason(e.target.value)}
            className="text-sm border border-slate-300 rounded-md px-3 py-1.5 bg-white"
          >
            {seasonOptions.length === 0 && <option value="">All seasons</option>}
            {seasonOptions.map((y) => (
              <option key={y} value={y}>
                {y}-{y + 1}
              </option>
            ))}
          </select>
        </CardHeader>
        <CardContent>
          {perGameChart.length === 0 ? (
            <p className="text-slate-500 text-sm text-center py-8">No game-by-game data yet.</p>
          ) : (
            <div className="h-64">
              <ResponsiveContainer width="100%" height="100%">
                <BarChart data={perGameChart} margin={{ top: 8, right: 8, left: -20, bottom: 0 }}>
                  <CartesianGrid strokeDasharray="3 3" stroke="#e2e8f0" />
                  <XAxis dataKey="idx" tick={{ fontSize: 11 }} label={{ value: 'Game', position: 'insideBottom', offset: -2, fontSize: 11 }} />
                  <YAxis tick={{ fontSize: 11 }} />
                  <Tooltip
                    labelFormatter={(v, p) => {
                      const row = p && p[0] ? p[0].payload : null
                      return row ? `${row.date} (game ${v})` : `Game ${v}`
                    }}
                  />
                  <Legend wrapperStyle={{ fontSize: 12 }} />
                  <Bar dataKey="goals" name="Goals" stackId="a" fill="#6366f1" />
                  <Bar dataKey="assists" name="Assists" stackId="a" fill="#38bdf8" />
                </BarChart>
              </ResponsiveContainer>
            </div>
          )}
        </CardContent>
      </Card>

      <div className="grid grid-cols-1 lg:grid-cols-2 gap-4">
        {/* Points distribution */}
        <Card>
          <CardHeader>
            <CardTitle>Points per Game</CardTitle>
            <CardDescription>How often you hit 0, 1, 2, or 3+ points</CardDescription>
          </CardHeader>
          <CardContent>
            <div className="h-52">
              <ResponsiveContainer width="100%" height="100%">
                <BarChart data={pointsDistribution} layout="vertical" margin={{ top: 4, right: 16, left: 8, bottom: 0 }}>
                  <CartesianGrid strokeDasharray="3 3" stroke="#e2e8f0" />
                  <XAxis type="number" allowDecimals={false} tick={{ fontSize: 12 }} />
                  <YAxis type="category" dataKey="label" tick={{ fontSize: 12 }} width={48} />
                  <Tooltip />
                  <Bar dataKey="value" name="Games" fill="#a78bfa" radius={[0, 4, 4, 0]} />
                </BarChart>
              </ResponsiveContainer>
            </div>
            <p className="text-sm text-slate-600 mt-2">
              <strong>{multiPointGames}</strong> multi-point game{(multiPointGames === 1 ? '' : 's')} · longest point streak{' '}
              <strong>{streaks.longest}</strong>
            </p>
          </CardContent>
        </Card>

        {/* League split */}
        <Card>
          <CardHeader>
            <CardTitle>By League</CardTitle>
            <CardDescription>Career breakdown per source</CardDescription>
          </CardHeader>
          <CardContent className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead>
                <tr className="text-slate-500 border-b border-slate-200">
                  <th className="text-left py-2">League</th>
                  <th className="text-right">Seas</th>
                  <th className="text-right">GP</th>
                  <th className="text-right">G</th>
                  <th className="text-right">A</th>
                  <th className="text-right">PTS</th>
                  <th className="text-right">P/GP</th>
                </tr>
              </thead>
              <tbody>
                {leagueSplit.map((l) => (
                  <tr key={l.source} className="border-b border-slate-100">
                    <td className="py-2 font-medium">{l.league}</td>
                    <td className="text-right">{l.seasons}</td>
                    <td className="text-right">{l.games}</td>
                    <td className="text-right">{l.goals}</td>
                    <td className="text-right">{l.assists}</td>
                    <td className="text-right font-medium">{l.points}</td>
                    <td className="text-right">{l.games ? fmt(l.points / l.games) : '—'}</td>
                  </tr>
                ))}
                {leagueSplit.length === 0 && (
                  <tr>
                    <td colSpan={7} className="py-4 text-center text-slate-500">No season data yet.</td>
                  </tr>
                )}
              </tbody>
            </table>
          </CardContent>
        </Card>
      </div>

      {/* Top opponents */}
      {topOpponents.length > 0 && (
        <Card>
          <CardHeader>
            <CardTitle>Best Opponents</CardTitle>
            <CardDescription>Most points scored against a single team</CardDescription>
          </CardHeader>
          <CardContent>
            <div className="h-56">
              <ResponsiveContainer width="100%" height="100%">
                <BarChart data={topOpponents} layout="vertical" margin={{ top: 4, right: 20, left: 8, bottom: 0 }}>
                  <CartesianGrid strokeDasharray="3 3" stroke="#e2e8f0" />
                  <XAxis type="number" tick={{ fontSize: 12 }} />
                  <YAxis type="category" dataKey="opponent" tick={{ fontSize: 11 }} width={120} />
                  <Tooltip />
                  <Bar dataKey="points" name="Points" fill="#34d399" radius={[0, 4, 4, 0]} />
                </BarChart>
              </ResponsiveContainer>
            </div>
          </CardContent>
        </Card>
      )}
    </div>
  )
}
