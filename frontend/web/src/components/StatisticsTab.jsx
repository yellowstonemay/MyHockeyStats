import React, { useEffect, useMemo, useRef, useState } from 'react'
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
  MHR: 'MHR',
}

const LEAGUE_COLORS = {
  AYHL: '#6366f1',
  THF: '#38bdf8',
  AHF: '#34d399',
  NJHS: '#f59e0b',
  MHR: '#a78bfa',
  Unknown: '#94a3b8',
}

function seasonStartYear(seasonValue) {
  if (seasonValue == null) return 0
  const m = String(seasonValue).match(/(\d{4})/)
  return m ? Number(m[1]) : 0
}

function cleanOpponent(opp) {
  return String(opp || 'Unknown').replace(/^(vs\.?|@)\s*/i, '').trim() || 'Unknown'
}

export default function StatisticsTab({ seasonRecords, playerId }) {
  const [gameHistory, setGameHistory] = useState([])
  const [loadingGames, setLoadingGames] = useState(true)
  const [initialLoading, setInitialLoading] = useState(true)
  const [gamesError, setGamesError] = useState('')
  const [selectedSeason, setSelectedSeason] = useState('')
  const [availableSeasons, setAvailableSeasons] = useState([])
  const [rankings, setRankings] = useState([])
  const [loadingRankings, setLoadingRankings] = useState(true)
  const [rankingsError, setRankingsError] = useState('')
  const [teamModal, setTeamModal] = useState(null)   // { source, season, teamId, team, playerId } | null
  const [teamPlayers, setTeamPlayers] = useState([])
  const [teamLoading, setTeamLoading] = useState(false)
  const [teamError, setTeamError] = useState('')

  const openTeam = async (row) => {
    setTeamModal({ source: row.source, season: row.season, teamId: row.teamKey, team: row.team, playerId: row.playerId })
    setTeamPlayers([])
    setTeamError('')
    setTeamLoading(true)
    try {
      const resp = await integrationsApi.fetchTeamRankings(row.source, row.season, row.teamKey)
      setTeamPlayers(resp.players || [])
    } catch (err) {
      setTeamError(err.message || 'Failed to load team')
    } finally {
      setTeamLoading(false)
    }
  }

  const closeTeam = () => setTeamModal(null)

  // Season key already loaded, so picking a season does not refetch
  // the season the API defaulted to.
  const loadedKeyRef = useRef(null)

  useEffect(() => {
    const key = `${playerId ?? ''}|${selectedSeason}`
    if (loadedKeyRef.current === key) return
    loadedKeyRef.current = key

    let cancelled = false
    setLoadingGames(true)
    setGamesError('')
    integrationsApi
      .fetchMyGameHistory(selectedSeason, playerId)
      .then((resp) => {
        if (cancelled) return
        setGameHistory(resp.games || [])
        const seasons = resp.availableSeasons || []
        setAvailableSeasons(seasons)
        const next = selectedSeason || (seasons.length ? String(seasons[0].seasonYear) : '')
        loadedKeyRef.current = `${playerId ?? ''}|${next}`
        if (next && next !== selectedSeason) setSelectedSeason(next)
      })
      .catch((err) => {
        if (!cancelled) setGamesError(err.message || 'Failed to load game history')
      })
      .finally(() => {
        if (!cancelled) {
          setLoadingGames(false)
          setInitialLoading(false)
        }
      })
    return () => {
      cancelled = true
    }
  }, [playerId, selectedSeason])

  useEffect(() => {
    let cancelled = false
    setLoadingRankings(true)
    setRankingsError('')
    integrationsApi
      .fetchRankings(playerId)
      .then((resp) => {
        if (!cancelled) setRankings(resp.rankings || [])
      })
      .catch((err) => {
        if (!cancelled) setRankingsError(err.message || 'Failed to load rankings')
      })
      .finally(() => {
        if (!cancelled) setLoadingRankings(false)
      })
    return () => {
      cancelled = true
    }
  }, [playerId])

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

  // ── League trend: points-per-game per season year, one bar per league ───
  const leagueTrend = useMemo(() => {
    const byYear = {}
    seasonRecords.forEach((r) => {
      const year = seasonStartYear(r.season)
      if (!year) return
      const src = r.source || 'Unknown'
      const row = byYear[year] || (byYear[year] = { year })
      row[`${src}__pts`] = (row[`${src}__pts`] || 0) + (r.points ?? 0)
      row[`${src}__gp`] = (row[`${src}__gp`] || 0) + (r.gamesPlayed ?? 0)
    })
    return Object.values(byYear)
      .sort((a, b) => a.year - b.year)
      .map((row) => {
        const out = { year: row.year }
        Object.keys(row).forEach((k) => {
          if (!k.endsWith('__pts')) return
          const src = k.slice(0, -'__pts'.length)
          const gp = row[`${src}__gp`] || 0
          out[src] = gp ? Number((row[k] / gp).toFixed(2)) : null
          out[`${src}__gp`] = gp
          out[`${src}__pts`] = row[k]
        })
        return out
      })
  }, [seasonRecords])

  const leagueSources = useMemo(() => {
    const seen = new Set()
    seasonRecords.forEach((r) => { if (r.source) seen.add(r.source) })
    return Array.from(seen)
  }, [seasonRecords])

  // ── Game-level stats (from game history) ────────────────────────────────
  const sortedGames = useMemo(() => {
    return [...gameHistory].sort((a, b) => String(a.gameDate || '').localeCompare(String(b.gameDate || '')))
  }, [gameHistory])

  const seasonOptions = useMemo(() => {
    const map = {}
    availableSeasons.forEach((s) => {
      const year = Number(s.seasonYear)
      if (Number.isFinite(year) && year > 0) map[year] = year
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

  // ── Milestones ──────────────────────────────────────────────────────────
  const nextMilestone = (value, list) => {
    const next = (list || []).find((m) => value < m)
    return next ? { next, remaining: next - value } : null
  }
  const milestones = useMemo(() => ({
    points: nextMilestone(career.points, [50, 100, 250, 500]),
    goals: nextMilestone(career.goals, [50, 100, 250]),
    assists: nextMilestone(career.assists, [50, 100, 250]),
  }), [career])

  const gameHighlights = useMemo(() => {
    let bestPoints = 0
    let bestGoals = 0
    sortedGames.forEach((g) => {
      const p = g.points ?? 0
      const gl = g.goals ?? 0
      if (p > bestPoints) bestPoints = p
      if (gl > bestGoals) bestGoals = gl
    })
    return { bestPoints, bestGoals }
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

  const RankBar = ({ rank, size, pct }) => {
    const safeSize = Number(size) || 0
    const safeRank = Number(rank) || 0
    const width = safeSize ? Math.max(4, Math.round(((safeSize - safeRank + 1) / safeSize) * 100)) : 0
    return (
      <div className="min-w-[120px]">
        <div className="flex items-center justify-between text-xs">
          <span className="font-medium text-slate-700">
            {safeRank ? `#${safeRank}` : '—'}
            {safeSize ? ` / ${safeSize}` : ''}
          </span>
          <span className="text-slate-400">{pct ? `${pct}%` : ''}</span>
        </div>
        <div className="mt-1 h-1.5 bg-slate-100 rounded-full overflow-hidden">
          <div className="h-full bg-indigo-500 rounded-full" style={{ width: `${width}%` }} />
        </div>
      </div>
    )
  }

  if (initialLoading) {
    return (
      <div className="flex items-center justify-center py-16 text-slate-500">
        <Loader2 className="w-5 h-5 animate-spin mr-2" />
        Loading statistics...
      </div>
    )
  }

  if (gamesError && gameHistory.length === 0) {
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

      {/* ── This season: both cards follow the selector below ─────────── */}
      <div className="flex flex-col sm:flex-row sm:items-end sm:justify-between gap-2 pt-2">
        <div>
          <h2 className="text-xs font-semibold uppercase tracking-wide text-slate-500">This season</h2>
          <p className="text-xs text-slate-400">Both charts below follow the season you pick</p>
        </div>
        <label className="flex items-center gap-2 text-sm text-slate-600">
          <span className="text-xs font-semibold uppercase tracking-wide text-slate-500">Season</span>
          <select
            value={selectedSeason}
            onChange={(e) => setSelectedSeason(e.target.value)}
            className="text-sm border border-slate-300 rounded-md px-3 py-1.5 bg-white"
            aria-label="Season filter"
          >
            {seasonOptions.length === 0 && <option value="">All seasons</option>}
            {seasonOptions.map((y) => (
              <option key={y} value={y}>
                {y}-{y + 1}
              </option>
            ))}
          </select>
        </label>
      </div>

      <div className="grid grid-cols-1 lg:grid-cols-2 gap-4">
        {/* Game-by-game */}
        <Card>
          <CardHeader>
            <CardTitle>Game-by-Game</CardTitle>
            <CardDescription>
              Goals and assists in each game
              {loadingGames && <span className="ml-2 text-slate-400">loading…</span>}
            </CardDescription>
          </CardHeader>
          <CardContent>
            {perGameChart.length === 0 ? (
              <p className="text-slate-500 text-sm text-center py-8">No game-by-game data yet.</p>
            ) : (
              <div className="h-56 sm:h-64">
                <ResponsiveContainer width="100%" height="100%">
                  <BarChart data={perGameChart} margin={{ top: 8, right: 8, left: -20, bottom: 12 }}>
                    <CartesianGrid strokeDasharray="3 3" stroke="#e2e8f0" />
                    <XAxis dataKey="idx" tick={{ fontSize: 11 }} interval="preserveStartEnd" label={{ value: 'Game', position: 'insideBottom', offset: -6, fontSize: 11 }} />
                    <YAxis tick={{ fontSize: 11 }} allowDecimals={false} label={{ value: 'Points', angle: -90, position: 'insideLeft', fontSize: 11 }} />
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

        {/* Best opponents */}
        <Card>
          <CardHeader>
            <CardTitle>Best Opponents</CardTitle>
            <CardDescription>Most points scored against one team</CardDescription>
          </CardHeader>
          <CardContent>
            {topOpponents.length === 0 ? (
              <p className="text-slate-500 text-sm text-center py-8">No games in this season yet.</p>
            ) : (
              <div className="h-56 sm:h-64">
                <ResponsiveContainer width="100%" height="100%">
                  <BarChart data={topOpponents} layout="vertical" margin={{ top: 4, right: 20, left: 8, bottom: 0 }}>
                    <CartesianGrid strokeDasharray="3 3" stroke="#e2e8f0" />
                    <XAxis type="number" tick={{ fontSize: 12 }} allowDecimals={false} />
                    <YAxis type="category" dataKey="opponent" tick={{ fontSize: 11 }} width={110} />
                    <Tooltip />
                    <Bar dataKey="points" name="Points" fill="#34d399" radius={[0, 4, 4, 0]} maxBarSize={26} />
                  </BarChart>
                </ResponsiveContainer>
              </div>
            )}
          </CardContent>
        </Card>
      </div>

      {/* ── Career: aggregates across every season ────────────────────── */}
      <div className="pt-2">
        <h2 className="text-xs font-semibold uppercase tracking-wide text-slate-500">Career</h2>
        <p className="text-xs text-slate-400">All seasons combined — not affected by the season selector</p>
      </div>

      {/* Milestones & streaks */}
      <Card>
        <CardHeader>
          <CardTitle>Milestones & Streaks</CardTitle>
          <CardDescription>Career milestones and hot streaks</CardDescription>
        </CardHeader>
        <CardContent>
          <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
            <div className="border border-slate-200 rounded-lg p-3">
              <div className="text-xs text-slate-500">Next milestone</div>
              {milestones.points ? (
                <div className="mt-1 text-sm">
                  <b className="text-indigo-600">{milestones.points.remaining} pts</b>
                  <span className="text-slate-600"> to {milestones.points.next}</span>
                </div>
              ) : (
                <div className="mt-1 text-sm text-slate-400">—</div>
              )}
              {milestones.goals ? (
                <div className="text-sm text-slate-600">{milestones.goals.remaining} goals to {milestones.goals.next}</div>
              ) : null}
              {milestones.assists ? (
                <div className="text-sm text-slate-600">{milestones.assists.remaining} assists to {milestones.assists.next}</div>
              ) : null}
            </div>
            <div className="border border-slate-200 rounded-lg p-3">
              <div className="text-xs text-slate-500">Best game</div>
              <div className="mt-1 text-2xl font-bold text-slate-900">{gameHighlights.bestPoints} pts</div>
              <div className="text-[11px] text-slate-400">{gameHighlights.bestGoals} goals</div>
            </div>
          </div>
        </CardContent>
      </Card>

      {/* Trends */}
      <div className="grid grid-cols-1 lg:grid-cols-2 gap-4">
        {seasonTrends.length > 0 && (
          <Card>
            <CardHeader>
              <CardTitle>Season-over-Season</CardTitle>
              <CardDescription>Goals, assists, and points per season (all leagues combined)</CardDescription>
            </CardHeader>
            <CardContent>
              <div className="h-56 sm:h-64">
                <ResponsiveContainer width="100%" height="100%">
                  <BarChart data={seasonTrends} margin={{ top: 8, right: 8, left: -12, bottom: 0 }}>
                    <CartesianGrid strokeDasharray="3 3" stroke="#e2e8f0" />
                    <XAxis dataKey="label" tick={{ fontSize: 11 }} interval="preserveStartEnd" />
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

        {/* Points per game by league */}
        <Card>
          <CardHeader>
            <CardTitle>Points per Game</CardTitle>
            <CardDescription>Season average (points ÷ games), one bar per league</CardDescription>
          </CardHeader>
          <CardContent>
            {leagueTrend.length === 0 ? (
              <p className="text-sm text-slate-500 py-10 text-center">No season data yet.</p>
            ) : (
              <div className="h-56 sm:h-64">
                <ResponsiveContainer width="100%" height="100%">
                  <BarChart
                    data={leagueTrend}
                    margin={{ top: 4, right: 16, left: 0, bottom: 0 }}
                    barGap={2}
                    barCategoryGap="22%"
                  >
                    <CartesianGrid strokeDasharray="3 3" stroke="#e2e8f0" />
                    <XAxis dataKey="year" tick={{ fontSize: 12 }} tickMargin={6} />
                    <YAxis
                      tick={{ fontSize: 12 }}
                      width={38}
                      domain={[0, 'auto']}
                      tickFormatter={(v) => Number(v).toFixed(1)}
                    />
                    <Tooltip
                      cursor={{ fill: '#f1f5f9' }}
                      content={({ active, payload, label }) => {
                        if (!active || !payload || !payload.length) return null
                        return (
                          <div className="bg-white border border-slate-200 rounded shadow-sm px-3 py-2 text-xs">
                            <div className="font-semibold text-slate-700 mb-1">
                              Season {label}–{Number(label) + 1}
                            </div>
                            {payload.map((p) => (
                              <div key={p.dataKey} className="flex items-center gap-2 whitespace-nowrap">
                                <span className="w-2 h-2 rounded-full shrink-0" style={{ background: p.fill || p.color }} />
                                <span className="text-slate-600">{SOURCE_LABELS[p.dataKey] || p.dataKey}</span>
                                <span className="font-medium">{Number(p.value).toFixed(2)} P/GP</span>
                                <span className="text-slate-400">
                                  ({p.payload[`${p.dataKey}__gp`]} GP · {p.payload[`${p.dataKey}__pts`]} P)
                                </span>
                              </div>
                            ))}
                          </div>
                        )
                      }}
                    />
                    <Legend wrapperStyle={{ fontSize: 12 }} formatter={(name) => SOURCE_LABELS[name] || name} />
                    {leagueSources.map((src) => (
                      <Bar
                        key={src}
                        dataKey={src}
                        name={src}
                        fill={LEAGUE_COLORS[src] || LEAGUE_COLORS.Unknown}
                        maxBarSize={22}
                        radius={[3, 3, 0, 0]}
                      />
                    ))}
                  </BarChart>
                </ResponsiveContainer>
              </div>
            )}
            <p className="text-xs text-slate-500 mt-2">Scoring rate each season, split by league.</p>
          </CardContent>
        </Card>
      </div>

      {/* Team / league rankings */}
      <Card>
        <CardHeader>
          <CardTitle>Team & League Rankings</CardTitle>
          <CardDescription>Where you rank by points among teammates and the league each season</CardDescription>
        </CardHeader>
        <CardContent>
          {loadingRankings ? (
            <div className="flex items-center justify-center py-6 text-slate-500">
              <Loader2 className="w-4 h-4 animate-spin mr-2" /> Loading rankings...
            </div>
          ) : rankingsError ? (
            <p className="text-sm text-red-700 py-4">{rankingsError}</p>
          ) : rankings.length === 0 ? (
            <p className="text-sm text-slate-500 text-center py-6">No ranking data yet.</p>
          ) : (
            <>
            {/* Mobile cards */}
            <div className="sm:hidden space-y-3">
              {rankings.map((r, i) => (
                <div key={i} className="border border-slate-200 rounded-lg p-3">
                  <div className="flex items-center justify-between gap-2">
                    <div className="font-medium text-slate-900 text-sm truncate">{r.team || '—'}</div>
                    <span className="text-xs text-slate-500 whitespace-nowrap">{SOURCE_LABELS[r.source] || r.source} · {r.games ?? '—'} GP</span>
                  </div>
                  <div className="text-xs text-slate-500 mt-0.5">{r.season} · {r.points ?? '—'} PTS</div>
                  <div className="mt-2 space-y-1.5">
                    <button
                      onClick={() => openTeam(r)}
                      className="block w-full text-left rounded hover:bg-indigo-50 p-0.5 -m-0.5 transition-colors"
                      title="View team roster"
                    >
                      <div className="text-[11px] text-slate-400 mb-0.5">Team rank</div>
                      <RankBar rank={r.teamRank} size={r.teamSize} pct={r.teamPercentile} />
                    </button>
                    <div>
                      <div className="text-[11px] text-slate-400 mb-0.5">League rank</div>
                      <RankBar rank={r.leagueRank} size={r.leagueSize} pct={r.leaguePercentile} />
                    </div>
                  </div>
                </div>
              ))}
              <p className="text-[11px] text-slate-400">
                Percentile = % of players you outrank (higher is better). Ties share the same rank.
              </p>
            </div>
            {/* Desktop table */}
            <div className="hidden sm:block overflow-x-auto">
              <table className="w-full text-sm">
                <thead>
                  <tr className="text-slate-500 border-b border-slate-200">
                    <th className="text-left py-2">Season</th>
                    <th className="text-left">League</th>
                    <th className="text-left">Team</th>
                    <th className="text-right">GP</th>
                    <th className="text-right">PTS</th>
                    <th className="text-left">Team Rank</th>
                    <th className="text-left">League Rank</th>
                  </tr>
                </thead>
                <tbody>
                  {rankings.map((r, i) => (
                    <tr key={i} className="border-b border-slate-100 align-top">
                      <td className="py-2 whitespace-nowrap font-medium">{r.season}</td>
                      <td className="py-2">{SOURCE_LABELS[r.source] || r.source}</td>
                      <td className="py-2">{r.team || '—'}</td>
                      <td className="py-2 text-right">{r.games ?? '—'}</td>
                      <td className="py-2 text-right font-medium">{r.points ?? '—'}</td>
                      <td className="py-2">
                        <button
                          onClick={() => openTeam(r)}
                          className="block w-full text-left rounded hover:bg-indigo-50 p-0.5 -m-0.5 transition-colors"
                          title="View team roster"
                        >
                          <RankBar rank={r.teamRank} size={r.teamSize} pct={r.teamPercentile} />
                        </button>
                      </td>
                      <td className="py-2">
                        <RankBar rank={r.leagueRank} size={r.leagueSize} pct={r.leaguePercentile} />
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
              <p className="text-[11px] text-slate-400 mt-2">
                Percentile = % of players you outrank (higher is better). Ties share the same rank.
              </p>
            </div>
            </>
          )}
        </CardContent>
      </Card>


      {/* Team roster modal */}
      {teamModal && (
        <div className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-black/40" onClick={closeTeam}>
          <div
            className="bg-white rounded-xl shadow-xl w-full max-w-lg max-h-[80vh] flex flex-col"
            onClick={(e) => e.stopPropagation()}
          >
            <div className="flex items-center justify-between px-5 py-4 border-b border-slate-200">
              <div>
                <h3 className="text-lg font-semibold text-slate-900">{teamModal.team}</h3>
                <p className="text-xs text-slate-500">
                  {SOURCE_LABELS[teamModal.source] || teamModal.source} · {teamModal.season} — sorted by points
                </p>
              </div>
              <button onClick={closeTeam} className="text-slate-400 hover:text-slate-600 text-xl leading-none" aria-label="Close">
                ×
              </button>
            </div>
            <div className="overflow-y-auto p-4">
              {teamLoading ? (
                <div className="flex items-center justify-center py-8 text-slate-500">
                  <Loader2 className="w-4 h-4 animate-spin mr-2" /> Loading team...
                </div>
              ) : teamError ? (
                <p className="text-sm text-red-700 py-4">{teamError}</p>
              ) : (
                <table className="w-full text-sm">
                  <thead>
                    <tr className="text-slate-500 border-b border-slate-200">
                      <th className="text-left py-1.5">#</th>
                      <th className="text-left">Player</th>
                      <th className="text-right">GP</th>
                      <th className="text-right">G</th>
                      <th className="text-right">A</th>
                      <th className="text-right">PTS</th>
                    </tr>
                  </thead>
                  <tbody>
                    {teamPlayers.map((p) => {
                      const isMe = String(p.player_id) === String(teamModal.playerId)
                      return (
                        <tr
                          key={p.player_id}
                          className={`border-b border-slate-100 ${isMe ? 'bg-indigo-50 font-semibold' : ''}`}
                        >
                          <td className={`py-1.5 pr-2 ${isMe ? 'text-indigo-700' : 'text-slate-500'}`}>{p.rank}</td>
                          <td className="py-1.5">
                            {p.name}
                            {p.is_goalie ? <span className="ml-2 text-[10px] px-1.5 py-0.5 rounded bg-slate-200 text-slate-600">G</span> : null}
                            {isMe ? <span className="ml-2 text-[10px] px-1.5 py-0.5 rounded bg-indigo-600 text-white">You</span> : null}
                          </td>
                          <td className="py-1.5 text-right">{p.games ?? '—'}</td>
                          <td className="py-1.5 text-right">{p.goals ?? '—'}</td>
                          <td className="py-1.5 text-right">{p.assists ?? '—'}</td>
                          <td className="py-1.5 text-right font-medium">{p.points ?? '—'}</td>
                        </tr>
                      )
                    })}
                  </tbody>
                </table>
              )}
            </div>
          </div>
        </div>
      )}
    </div>
  )
}
