import React, { useEffect, useMemo, useState } from 'react'
import { Button } from '../components/Button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '../components/Card'
import { AlertCircle, Loader2, RefreshCw } from 'lucide-react'
import { integrationsApi } from '../lib/integrationsApi'
import SeasonsTab from '../components/PlayerProfile/SeasonsTab'
import StatisticsTab from '../components/StatisticsTab'
import MyLeagues from '../components/MyLeagues'
import FollowingTab from '../components/FollowingTab'

export default function Dashboard() {
  const [activeTab, setActiveTab] = useState('overview')
  const [seasonRecords, setSeasonRecords] = useState([])
  const [seasonsLoading, setSeasonsLoading] = useState(false)
  const [seasonsError, setSeasonsError] = useState('')
  const [editingKey, setEditingKey] = useState(null)
  const [editValues, setEditValues] = useState({})
  const [saving, setSaving] = useState(false)

  const getSeasonStartYear = (seasonValue) => {
    if (seasonValue == null) return 0
    const seasonText = String(seasonValue).trim()
    const match = seasonText.match(/(\d{4})/)
    return match ? Number(match[1]) : 0
  }

  const sortedSeasonRecords = useMemo(() => {
    return [...seasonRecords].sort((a, b) => {
      const seasonA = getSeasonStartYear(a.season)
      const seasonB = getSeasonStartYear(b.season)
      if (seasonB !== seasonA) return seasonB - seasonA

      const teamA = (a.team || '').toLowerCase()
      const teamB = (b.team || '').toLowerCase()
      return teamA.localeCompare(teamB)
    })
  }, [seasonRecords])

  const careerTotals = useMemo(() => {
    return seasonRecords.reduce(
      (acc, r) => ({
        games: acc.games + (r.gamesPlayed ?? 0),
        goals: acc.goals + (r.goals ?? 0),
        assists: acc.assists + (r.assists ?? 0),
        points: acc.points + (r.points ?? 0),
      }),
      { games: 0, goals: 0, assists: 0, points: 0 }
    )
  }, [seasonRecords])

  const totalsByLeague = useMemo(() => {
    const map = {}
    seasonRecords.forEach((r) => {
      const league = r.source || 'Unknown'
      if (!map[league]) map[league] = { league, seasons: 0, games: 0, goals: 0, assists: 0, points: 0 }
      map[league].seasons += 1
      map[league].games += r.gamesPlayed ?? 0
      map[league].goals += r.goals ?? 0
      map[league].assists += r.assists ?? 0
      map[league].points += r.points ?? 0
    })
    return Object.values(map).sort((a, b) => b.games - a.games)
  }, [seasonRecords])

  const loadSeasons = async () => {
    setSeasonsLoading(true)
    setSeasonsError('')
    try {
      const response = await integrationsApi.fetchMySeasons('')
      setSeasonRecords(response.records || [])
    } catch (err) {
      setSeasonsError(err.message || 'Failed to load season data')
    } finally {
      setSeasonsLoading(false)
    }
  }

  const recordKey = (r) => `${r.source || 'src'}|${r.sourcePlayerId || ''}|${r.season || ''}`

  const startEdit = (record) => {
    setEditingKey(recordKey(record))
    setEditValues({
      gamesPlayed: record.gamesPlayed ?? 0,
      goals: record.goals ?? 0,
      assists: record.assists ?? 0,
      points: record.points ?? 0,
      pim: record.pim ?? 0,
    })
  }

  const cancelEdit = () => {
    setEditingKey(null)
    setEditValues({})
  }

  const handleEditChange = (field, value) => {
    setEditValues((prev) => ({ ...prev, [field]: value === '' ? '' : Number(value) }))
  }

  const saveEdit = async (record) => {
    setSaving(true)
    setSeasonsError('')
    try {
      await integrationsApi.updateMySeason(record.source, record.sourcePlayerId, record.season, {
        gamesPlayed: Number(editValues.gamesPlayed || 0),
        goals: Number(editValues.goals || 0),
        assists: Number(editValues.assists || 0),
        points: Number(editValues.points || 0),
        pim: Number(editValues.pim || 0),
      })
      cancelEdit()
      await loadSeasons()
    } catch (err) {
      setSeasonsError(err.message || 'Failed to update season')
    } finally {
      setSaving(false)
    }
  }

  useEffect(() => {
    if (seasonRecords.length === 0 && !seasonsLoading && !seasonsError) {
      loadSeasons()
    }
  }, [])

  useEffect(() => {
    if ((activeTab === 'seasons' || activeTab === 'stats') && seasonRecords.length === 0 && !seasonsLoading && !seasonsError) {
      loadSeasons()
    }
  }, [activeTab, seasonRecords.length, seasonsLoading, seasonsError])

  return (
    <div className="min-h-screen bg-slate-50">
      {/* Sidebar & Content */}
      <div className="container py-8">
        <div className="grid grid-cols-1 lg:grid-cols-4 gap-8">
          {/* Sidebar */}
          <div className="lg:col-span-1">
            <Card>
              <CardContent className="p-0">
                <nav className="flex flex-col">
                  {[
                    { id: 'overview', label: 'Overview', icon: '📊' },
                    { id: 'seasons', label: 'Seasons', icon: '🏒' },
                    { id: 'games', label: 'Game History', icon: '📝' },
                    { id: 'stats', label: 'Statistics', icon: '📈' },
                    { id: 'following', label: 'Following', icon: '👥' },
                    { id: 'export', label: 'Export', icon: '📄' },
                  ].map((item) => (
                    <button
                      key={item.id}
                      onClick={() => setActiveTab(item.id)}
                      className={`flex items-center space-x-3 px-4 py-3 border-b border-slate-200 last:border-b-0 text-left transition-colors ${
                        activeTab === item.id
                          ? 'bg-primary-50 text-primary-600 font-medium border-l-4 border-primary-600'
                          : 'text-slate-600 hover:bg-slate-50'
                      }`}
                    >
                      <span>{item.icon}</span>
                      <span>{item.label}</span>
                    </button>
                  ))}
                </nav>
              </CardContent>
            </Card>
          </div>

          {/* Main Content */}
          <div className="lg:col-span-3">
            {activeTab === 'overview' && (
              <div className="space-y-6">
                <Card>
                  <CardHeader>
                    <CardTitle>Welcome Back!</CardTitle>
                    <CardDescription>Here's your hockey stats overview</CardDescription>
                  </CardHeader>
                  <CardContent>
                    {seasonsLoading && (
                      <div className="flex items-center justify-center py-6 text-slate-500">
                        <Loader2 className="w-5 h-5 animate-spin mr-2" />
                        <span>Loading career stats…</span>
                      </div>
                    )}
                    {!seasonsLoading && (
                      <div className="grid grid-cols-2 sm:grid-cols-4 gap-4">
                        <div className="bg-gradient-to-br from-primary-50 to-primary-100 rounded-lg p-4">
                          <div className="text-3xl font-bold text-primary-600">{careerTotals.games}</div>
                          <p className="text-sm text-slate-600 mt-1">Total Games</p>
                        </div>
                        <div className="bg-gradient-to-br from-secondary-50 to-secondary-100 rounded-lg p-4">
                          <div className="text-3xl font-bold text-secondary-600">{careerTotals.goals}</div>
                          <p className="text-sm text-slate-600 mt-1">Total Goals</p>
                        </div>
                        <div className="bg-gradient-to-br from-amber-50 to-amber-100 rounded-lg p-4">
                          <div className="text-3xl font-bold text-amber-600">{careerTotals.assists}</div>
                          <p className="text-sm text-slate-600 mt-1">Total Assists</p>
                        </div>
                        <div className="bg-gradient-to-br from-green-50 to-green-100 rounded-lg p-4">
                          <div className="text-3xl font-bold text-green-600">{careerTotals.points}</div>
                          <p className="text-sm text-slate-600 mt-1">Total Points</p>
                        </div>
                      </div>
                    )}
                  </CardContent>
                </Card>

                <MyLeagues />

                <Card>
                  <CardHeader>
                    <CardTitle>Totals by League</CardTitle>
                    <CardDescription>Career statistics broken down by league</CardDescription>
                  </CardHeader>
                  <CardContent>
                    {seasonsLoading && (
                      <div className="flex items-center justify-center py-4 text-slate-500">
                        <Loader2 className="w-4 h-4 animate-spin mr-2" />
                        <span>Loading…</span>
                      </div>
                    )}
                    {!seasonsLoading && totalsByLeague.length === 0 && (
                      <p className="text-slate-500 text-sm text-center py-4">No league data available yet.</p>
                    )}
                    {!seasonsLoading && totalsByLeague.length > 0 && (
                      <div className="overflow-x-auto">
                        <table className="w-full text-sm border border-slate-200 rounded-lg overflow-hidden">
                          <thead className="bg-slate-100 text-slate-700">
                            <tr>
                              <th className="px-3 py-2 text-left">League</th>
                              <th className="px-3 py-2 text-right">Seasons</th>
                              <th className="px-3 py-2 text-right">GP</th>
                              <th className="px-3 py-2 text-right">G</th>
                              <th className="px-3 py-2 text-right">A</th>
                              <th className="px-3 py-2 text-right">PTS</th>
                            </tr>
                          </thead>
                          <tbody>
                            {totalsByLeague.map((row) => (
                              <tr key={row.league} className="border-t border-slate-200 hover:bg-slate-50">
                                <td className="px-3 py-2 font-medium">{row.league}</td>
                                <td className="px-3 py-2 text-right">{row.seasons}</td>
                                <td className="px-3 py-2 text-right">{row.games}</td>
                                <td className="px-3 py-2 text-right">{row.goals}</td>
                                <td className="px-3 py-2 text-right">{row.assists}</td>
                                <td className="px-3 py-2 text-right font-semibold text-primary-700">{row.points}</td>
                              </tr>
                            ))}
                          </tbody>
                          <tfoot className="bg-slate-50 text-slate-700 font-semibold border-t-2 border-slate-300">
                            <tr>
                              <td className="px-3 py-2">Total</td>
                              <td className="px-3 py-2 text-right">{totalsByLeague.reduce((s, r) => s + r.seasons, 0)}</td>
                              <td className="px-3 py-2 text-right">{careerTotals.games}</td>
                              <td className="px-3 py-2 text-right">{careerTotals.goals}</td>
                              <td className="px-3 py-2 text-right">{careerTotals.assists}</td>
                              <td className="px-3 py-2 text-right text-primary-700">{careerTotals.points}</td>
                            </tr>
                          </tfoot>
                        </table>
                      </div>
                    )}
                  </CardContent>
                </Card>

                <Card>
                  <CardHeader>
                    <CardTitle>Quick Actions</CardTitle>
                  </CardHeader>
                  <CardContent className="flex flex-col sm:flex-row gap-4">
                    <Button variant="primary" className="flex-1">
                      Add Season
                    </Button>
                    <Button variant="secondary" className="flex-1">
                      Record Game
                    </Button>
                    <Button variant="outline" className="flex-1">
                      Export Stats
                    </Button>
                  </CardContent>
                </Card>
              </div>
            )}

            {activeTab === 'seasons' && (
              <Card>
                <CardHeader>
                  <CardTitle>Your Seasons</CardTitle>
                  <CardDescription>One table view of your seasons, sorted from latest to oldest</CardDescription>
                </CardHeader>
                <CardContent>
                  {seasonsError && (
                    <div className="p-4 bg-red-100 border border-red-400 text-red-700 rounded-md mb-4 flex items-center justify-between gap-3">
                      <div className="flex items-center gap-2">
                        <AlertCircle className="w-5 h-5" />
                        <span>{seasonsError}</span>
                      </div>
                      <Button onClick={loadSeasons} variant="outline" size="sm" disabled={seasonsLoading}>
                        <RefreshCw className="w-4 h-4 mr-2" />
                        Retry
                      </Button>
                    </div>
                  )}

                  {seasonsLoading && (
                    <div className="flex items-center justify-center py-10 text-slate-600">
                      <Loader2 className="w-5 h-5 animate-spin mr-2" />
                      <span>Loading seasons...</span>
                    </div>
                  )}

                  {!seasonsLoading && !seasonsError && sortedSeasonRecords.length === 0 && (
                    <p className="text-slate-600 text-center py-8">No season data found yet.</p>
                  )}

                  {!seasonsLoading && !seasonsError && sortedSeasonRecords.length > 0 && (
                    <div className="overflow-x-auto">
                      <table className="w-full text-sm border border-slate-200 rounded-lg overflow-hidden">
                        <thead className="bg-slate-100 text-slate-700">
                          <tr>
                            <th className="px-3 py-2 text-left">Season</th>
                            <th className="px-3 py-2 text-left">League</th>
                            <th className="px-3 py-2 text-left">Club</th>
                            <th className="px-3 py-2 text-left">Team</th>
                            <th className="px-3 py-2 text-right">GP</th>
                            <th className="px-3 py-2 text-right">G</th>
                            <th className="px-3 py-2 text-right">A</th>
                            <th className="px-3 py-2 text-right">PTS</th>
                            <th className="px-3 py-2 text-right">PIM</th>
                            <th className="px-3 py-2 text-right">Actions</th>
                          </tr>
                        </thead>
                        <tbody>
                          {sortedSeasonRecords.map((record, idx) => {
                            const isEditing = editingKey === recordKey(record)
                            const numInput = (field) => (
                              <input
                                type="number"
                                min="0"
                                value={editValues[field] ?? 0}
                                onChange={(e) => handleEditChange(field, e.target.value)}
                                className="w-14 px-1 py-0.5 border border-slate-300 rounded text-right"
                              />
                            )
                            return (
                              <tr key={`${record.source || 'src'}-${record.sourcePlayerId || idx}-${record.season || 'season'}`} className="border-t border-slate-200 hover:bg-slate-50">
                                <td className="px-3 py-2 font-medium">{record.season || '—'}</td>
                                <td className="px-3 py-2">{record.source || '—'}</td>
                                <td className="px-3 py-2">{record.club || '—'}</td>
                                <td className="px-3 py-2">{record.team || '—'}</td>
                                <td className="px-3 py-2 text-right">{isEditing ? numInput('gamesPlayed') : (record.gamesPlayed ?? 0)}</td>
                                <td className="px-3 py-2 text-right">{isEditing ? numInput('goals') : (record.goals ?? 0)}</td>
                                <td className="px-3 py-2 text-right">{isEditing ? numInput('assists') : (record.assists ?? 0)}</td>
                                <td className="px-3 py-2 text-right font-semibold text-primary-700">{isEditing ? numInput('points') : (record.points ?? 0)}</td>
                                <td className="px-3 py-2 text-right">{isEditing ? numInput('pim') : (record.pim ?? 0)}</td>
                                <td className="px-3 py-2 text-right whitespace-nowrap">
                                  {isEditing ? (
                                    <span className="inline-flex gap-1">
                                      <Button size="sm" onClick={() => saveEdit(record)} disabled={saving}>
                                        {saving ? 'Saving...' : 'Save'}
                                      </Button>
                                      <Button size="sm" variant="outline" onClick={cancelEdit} disabled={saving}>
                                        Cancel
                                      </Button>
                                    </span>
                                  ) : (
                                    record.isUserModified ? (
                                      <Button size="sm" variant="outline" onClick={() => startEdit(record)}>
                                        Edit
                                      </Button>
                                    ) : null
                                  )}
                                </td>
                              </tr>
                            )
                          })}
                        </tbody>
                      </table>
                    </div>
                  )}
                </CardContent>
              </Card>
            )}

            {activeTab === 'games' && (
              <SeasonsTab />
            )}

            {activeTab === 'stats' && (
              <StatisticsTab seasonRecords={seasonRecords} />
            )}

            {activeTab === 'following' && (
              <FollowingTab />
            )}

            {activeTab === 'export' && (
              <Card>
                <CardHeader>
                  <CardTitle>Export Your Stats</CardTitle>
                  <CardDescription>Download your stats as PDFs to share</CardDescription>
                </CardHeader>
                <CardContent>
                  <p className="text-slate-600 mb-4">
                    Export your game history and season summaries in PDF format.
                  </p>
                  <Button disabled>
                    Export as PDF
                  </Button>
                </CardContent>
              </Card>
            )}
          </div>
        </div>
      </div>
    </div>
  )
}
