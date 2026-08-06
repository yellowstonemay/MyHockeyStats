import React, { useEffect, useState } from 'react'
import { reportApi } from '../lib/reportApi'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from './Card'
import { Button } from './Button'
import { Loader2, Download, Sparkles, RefreshCw, User } from 'lucide-react'

const SOURCE_BADGE = {
  AYHL: 'bg-indigo-100 text-indigo-700',
  THF: 'bg-sky-100 text-sky-700',
  AHF: 'bg-emerald-100 text-emerald-700',
  NJHS: 'bg-amber-100 text-amber-700',
}

const TONE_BADGE = {
  positive: 'bg-emerald-100 text-emerald-700',
  watch: 'bg-amber-100 text-amber-700',
  neutral: 'bg-slate-100 text-slate-600',
}

const TONE_LABEL = { positive: 'Positive', watch: 'Watch', neutral: 'Neutral' }

export default function ReportTab() {
  const [report, setReport] = useState(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [generating, setGenerating] = useState(false)
  const [genMsg, setGenMsg] = useState(null)

  const load = async () => {
    setLoading(true)
    setError('')
    try {
      const data = await reportApi.fetchMyReport()
      setReport(data)
    } catch (err) {
      setError(err.message || 'Failed to load report')
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    load()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  const generate = async () => {
    const season = report?.latestSeason
    if (season == null) return
    setGenerating(true)
    setGenMsg(null)
    try {
      await reportApi.generateInsights(season)
      setGenMsg({ kind: 'ok', text: 'AI season report generated.' })
      await load()
    } catch (err) {
      const weekly = err.status === 429 && /week/i.test(err.message)
      const cap = err.status === 429 && /budget|tomorrow/i.test(err.message)
      setGenMsg({
        kind: 'err',
        text: err.message || 'Failed to generate AI report',
        weekly,
        cap,
      })
    } finally {
      setGenerating(false)
    }
  }

  const downloadPdf = () => window.print()

  const profile = report?.profile || {}
  const ai = report?.ai || {}
  const insights = report?.insights || []
  const totals = report?.totals || {}
  const seasons = report?.seasons || []
  const byLeague = report?.byLeague || []
  const rankings = report?.rankings || []
  const recentGames = report?.recentGames || []

  return (
    <div id="report-print-area" className="space-y-6 print:space-y-4">
      {/* Header */}
      <Card>
        <CardHeader className="flex flex-col sm:flex-row sm:items-start sm:justify-between gap-3 print:flex-row">
          <div>
            <CardTitle className="flex items-center gap-2">
              <User className="w-5 h-5 text-slate-400" />
              {profile.fullName || 'Player Report'}
              <span className="text-[10px] font-bold px-1.5 py-0.5 rounded-full bg-purple-100 text-purple-700">
                ✨ AI
              </span>
            </CardTitle>
            <CardDescription className="mt-1">
              {[profile.age ? `${profile.age} yrs` : '', profile.position, profile.location]
                .filter(Boolean)
                .join(' · ') || 'Unified career report across AYHL, THF, AHF and NJ high school hockey'}
            </CardDescription>
          </div>
          <Button variant="outline" onClick={downloadPdf} className="no-print shrink-0">
            <Download className="w-4 h-4 mr-2" /> Download PDF
          </Button>
        </CardHeader>
      </Card>

      {error && <p className="text-sm text-red-700">{error}</p>}

      {loading ? (
        <div className="flex items-center justify-center py-16 text-slate-500">
          <Loader2 className="w-5 h-5 animate-spin mr-2" /> Building your report…
        </div>
      ) : (
        <>
          {/* Season Insights (feature 2) */}
          <Card className="print:break-inside-avoid">
            <CardHeader className="flex flex-col sm:flex-row sm:items-center sm:justify-between gap-2">
              <div className="flex items-center gap-2">
                <Sparkles className="w-5 h-5 text-purple-500" />
                <CardTitle>Season Insights</CardTitle>
              </div>
              {insights.length > 0 && (
                <div className="text-xs text-slate-400">
                  {report.insightSeason ? `Season ${report.insightSeason} · ` : ''}1 AI report per week
                </div>
              )}
            </CardHeader>
            <CardContent>
              {insights.length > 0 ? (
                <>
                  <div className="grid grid-cols-1 gap-3">
                    {insights.map((ins, i) => (
                      <div key={i} className="border border-slate-200 rounded-lg p-3 print:break-inside-avoid">
                        <div className="flex items-center gap-2">
                          <span className={`text-[11px] font-semibold px-2 py-0.5 rounded-full ${TONE_BADGE[ins.tone] || TONE_BADGE.neutral}`}>
                            {TONE_LABEL[ins.tone] || 'Neutral'}
                          </span>
                          <span className="font-medium text-slate-900 text-sm">{ins.title}</span>
                        </div>
                        <p className="text-sm text-slate-600 mt-1.5 leading-relaxed">{ins.body}</p>
                      </div>
                    ))}
                  </div>
                  {ai.configured && (
                    <Button size="sm" variant="outline" className="mt-3 no-print" onClick={generate} disabled={generating}>
                      {generating ? <Loader2 className="w-4 h-4 animate-spin mr-1.5" /> : <RefreshCw className="w-4 h-4 mr-1.5" />}
                      Regenerate
                    </Button>
                  )}
                </>
              ) : (
                <div className="text-sm text-slate-600">
                  <p className="mb-3">
                    Get an AI-written summary of {report.latestSeason ?? ''} — scoring trend, how you rank, what to work on.
                  </p>
                  {!ai.configured ? (
                    <p className="text-slate-400 text-xs">AI reports aren't configured yet. Check back soon.</p>
                  ) : (
                    <Button onClick={generate} disabled={generating} className="no-print">
                      {generating ? <Loader2 className="w-4 h-4 animate-spin mr-2" /> : <Sparkles className="w-4 h-4 mr-2" />}
                      {generating ? 'Writing your report…' : 'Generate AI season report'}
                    </Button>
                  )}
                  <p className="text-[11px] text-slate-400 mt-2">
                    Free to view · one AI report per user per week
                  </p>
                </div>
              )}
              {genMsg && (
                <div
                  className={`mt-3 p-3 border rounded-md text-sm ${
                    genMsg.kind === 'err'
                      ? 'bg-red-100 border-red-400 text-red-700'
                      : 'bg-emerald-50 border-emerald-300 text-emerald-700'
                  }`}
                >
                  {genMsg.text}
                </div>
              )}
            </CardContent>
          </Card>

          {/* Career totals */}
          <Card className="print:break-inside-avoid">
            <CardHeader>
              <CardTitle>Career Totals</CardTitle>
              <CardDescription>Combined across all leagues and seasons</CardDescription>
            </CardHeader>
            <CardContent>
              <div className="grid grid-cols-3 sm:grid-cols-5 gap-3">
                {[
                  ['GP', totals.games ?? 0],
                  ['G', totals.goals ?? 0],
                  ['A', totals.assists ?? 0],
                  ['PTS', totals.points ?? 0],
                  ['PIM', totals.pim ?? 0],
                ].map(([label, value]) => (
                  <div key={label} className="border border-slate-200 rounded-lg p-3 text-center">
                    <div className="text-2xl font-bold text-slate-900">{value}</div>
                    <div className="text-[11px] uppercase tracking-wide text-slate-400 font-semibold mt-0.5">{label}</div>
                  </div>
                ))}
              </div>
            </CardContent>
          </Card>

          {/* Season timeline */}
          <Card className="print:break-inside-avoid">
            <CardHeader>
              <CardTitle>Seasons</CardTitle>
              <CardDescription>Every league and team, grouped by season</CardDescription>
            </CardHeader>
            <CardContent>
              {seasons.length === 0 ? (
                <p className="text-sm text-slate-400">No season data yet.</p>
              ) : (
                <div className="space-y-5">
                  {seasons.map((s) => (
                    <div key={s.seasonYear}>
                      <div className="flex items-center justify-between">
                        <div className="font-semibold text-slate-900 text-sm">{s.seasonYear}–{s.seasonYear + 1}</div>
                        <div className="text-xs text-slate-500">
                          Combined: <b className="text-slate-900">{s.combined.points ?? 0} PTS</b> ({s.combined.goals ?? 0}G-{s.combined.assists ?? 0}A in {s.combined.games ?? 0} GP)
                        </div>
                      </div>
                      <table className="w-full text-xs mt-1.5">
                        <thead>
                          <tr className="text-slate-400">
                            <th className="text-left font-semibold py-1 pr-2">League</th>
                            <th className="text-left font-semibold py-1 pr-2">Team</th>
                            <th className="text-right font-semibold py-1">GP</th>
                            <th className="text-right font-semibold py-1">G</th>
                            <th className="text-right font-semibold py-1">A</th>
                            <th className="text-right font-semibold py-1">PTS</th>
                            <th className="text-right font-semibold py-1">PIM</th>
                          </tr>
                        </thead>
                        <tbody>
                          {s.rows.map((r, i) => (
                            <tr key={i} className="border-t border-slate-100">
                              <td className="py-1 pr-2">
                                <span className={`text-[11px] font-semibold px-1.5 py-0.5 rounded-full ${SOURCE_BADGE[r.source] || 'bg-slate-100 text-slate-600'}`}>
                                  {r.source}
                                </span>
                                <span className="ml-1.5 text-slate-600">{r.league || '—'}</span>
                              </td>
                              <td className="py-1 pr-2 text-slate-700 truncate">{r.team || '—'}</td>
                              <td className="py-1 text-slate-600 text-right">{r.games ?? '—'}</td>
                              <td className="py-1 text-slate-600 text-right">{r.goals ?? '—'}</td>
                              <td className="py-1 text-slate-600 text-right">{r.assists ?? '—'}</td>
                              <td className="py-1 text-slate-900 font-medium text-right">{r.points ?? '—'}</td>
                              <td className="py-1 text-slate-600 text-right">{r.pim ?? '—'}</td>
                            </tr>
                          ))}
                        </tbody>
                      </table>
                    </div>
                  ))}
                </div>
              )}
            </CardContent>
          </Card>

          {/* By league */}
          {byLeague.length > 0 && (
            <Card className="print:break-inside-avoid">
              <CardHeader>
                <CardTitle>By League</CardTitle>
              </CardHeader>
              <CardContent>
                <table className="w-full text-xs">
                  <thead>
                    <tr className="text-slate-400">
                      <th className="text-left font-semibold py-1 pr-2">League</th>
                      <th className="text-right font-semibold py-1">Seasons</th>
                      <th className="text-right font-semibold py-1">GP</th>
                      <th className="text-right font-semibold py-1">PTS</th>
                    </tr>
                  </thead>
                  <tbody>
                    {byLeague.map((l, i) => (
                      <tr key={i} className="border-t border-slate-100">
                        <td className="py-1 pr-2 text-slate-700">{l.league}</td>
                        <td className="py-1 text-slate-600 text-right">{l.seasons}</td>
                        <td className="py-1 text-slate-600 text-right">{l.games}</td>
                        <td className="py-1 text-slate-900 font-medium text-right">{l.points}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </CardContent>
            </Card>
          )}

          {/* Rankings */}
          {rankings.length > 0 && (
            <Card className="print:break-inside-avoid">
              <CardHeader>
                <CardTitle>Team & League Ranking</CardTitle>
                <CardDescription>Percentile within team and league by season</CardDescription>
              </CardHeader>
              <CardContent>
                <table className="w-full text-xs">
                  <thead>
                    <tr className="text-slate-400">
                      <th className="text-left font-semibold py-1 pr-2">Season</th>
                      <th className="text-left font-semibold py-1 pr-2">League</th>
                      <th className="text-right font-semibold py-1">Team</th>
                      <th className="text-right font-semibold py-1">League</th>
                    </tr>
                  </thead>
                  <tbody>
                    {rankings.map((r, i) => (
                      <tr key={i} className="border-t border-slate-100">
                        <td className="py-1 pr-2 text-slate-500 whitespace-nowrap">{r.season}</td>
                        <td className="py-1 pr-2 text-slate-700 truncate">{r.team || '—'}</td>
                        <td className="py-1 text-slate-600 text-right">#{r.teamRank}/{r.teamSize}</td>
                        <td className="py-1 text-slate-600 text-right">#{r.leagueRank}/{r.leagueSize} ({r.leaguePercentile}%)</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </CardContent>
            </Card>
          )}

          {/* Recent games */}
          {recentGames.length > 0 && (
            <Card className="print:break-inside-avoid">
              <CardHeader>
                <CardTitle>Recent Games</CardTitle>
                <CardDescription>Last {recentGames.length} games across all leagues</CardDescription>
              </CardHeader>
              <CardContent>
                <table className="w-full text-xs">
                  <tbody>
                    {recentGames.map((g, i) => (
                      <tr key={i} className="border-t border-slate-100">
                        <td className="py-1 pr-2 text-slate-500 whitespace-nowrap">{g.date || '—'}</td>
                        <td className="py-1 pr-2 text-slate-700 truncate">{g.opponent || '—'}</td>
                        <td className="py-1 text-slate-600 text-right whitespace-nowrap">
                          {g.goals != null ? `${g.goals}G-${g.assists}A (${g.points}P)` : '—'}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </CardContent>
            </Card>
          )}
        </>
      )}
    </div>
  )
}
