import React, { useState, useEffect } from 'react'
import { integrationsApi } from '../../lib/integrationsApi'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '../Card'
import { Button } from '../Button'
import { AlertCircle, Loader2, RefreshCw, Download, FileText, X } from 'lucide-react'

export default function SeasonsTab() {
  const [games, setGames] = useState([])
  const [availableSeasons, setAvailableSeasons] = useState([])
  const [selectedSeason, setSelectedSeason] = useState('')
  const [selectedGame, setSelectedGame] = useState(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')

  useEffect(() => {
    loadGameHistory()
  }, [])

  const loadGameHistory = async (seasonYear = '') => {
    setLoading(true)
    setError('')
    try {
      const response = await integrationsApi.fetchMyGameHistory(seasonYear)
      setGames(response.games || [])
      setAvailableSeasons(response.availableSeasons || [])
      setSelectedSeason(response.selectedSeasonYear ?? '')
    } catch (err) {
      setError(err.message || 'Failed to load game history')
    } finally {
      setLoading(false)
    }
  }

  const handleSeasonChange = async (e) => {
    const season = e.target.value ? Number(e.target.value) : ''
    setSelectedSeason(season)
    await loadGameHistory(season)
  }

  const handleRetry = () => {
    loadGameHistory(selectedSeason)
  }

  const selectedSeasonLabel = availableSeasons.find(
    (season) => season.seasonYear === selectedSeason
  )?.seasonLabel || 'Selected Season'

  const downloadCsv = () => {
    if (!games.length) return

    const headers = [
      'Date', 'Source', 'Game ID', 'Game Type', 'League',
      'Team For', 'Team Against', 'Goals', 'Assists', 'Points', 'PIM'
    ]

    const escapeCsv = (value) => {
      const text = value == null ? '' : String(value)
      if (text.includes(',') || text.includes('"') || text.includes('\n')) {
        return `"${text.replace(/"/g, '""')}"`
      }
      return text
    }

    const rows = games.map((g) => [
      g.gameDate || '',
      g.source || '',
      g.gameId || '',
      g.gameType || '',
      g.league || '',
      g.teamFor || '',
      g.teamAgainst || '',
      g.goals ?? 0,
      g.assists ?? 0,
      g.points ?? 0,
      g.pim ?? 0,
    ])

    const csvContent = [
      headers.join(','),
      ...rows.map((row) => row.map(escapeCsv).join(',')),
    ].join('\n')

    const blob = new Blob([csvContent], { type: 'text/csv;charset=utf-8;' })
    const url = URL.createObjectURL(blob)
    const link = document.createElement('a')
    link.href = url
    link.download = `game-history-${selectedSeason || 'season'}.csv`
    document.body.appendChild(link)
    link.click()
    document.body.removeChild(link)
    URL.revokeObjectURL(url)
  }

  const downloadPdf = () => {
    if (!games.length) return

    const tableRows = games.map((g) => `
      <tr>
        <td>${g.gameDate || ''}</td>
        <td>${g.source || ''}</td>
        <td>${g.gameId || ''}</td>
        <td>${g.teamFor || ''}</td>
        <td>${g.teamAgainst || ''}</td>
        <td style="text-align:right;">${g.goals ?? 0}</td>
        <td style="text-align:right;">${g.assists ?? 0}</td>
        <td style="text-align:right;">${g.points ?? 0}</td>
        <td style="text-align:right;">${g.pim ?? 0}</td>
      </tr>
    `).join('')

    const popup = window.open('', '_blank')
    if (!popup) return

    popup.document.write(`
      <html>
        <head>
          <title>Game History ${selectedSeasonLabel}</title>
          <style>
            body { font-family: Arial, sans-serif; margin: 24px; color: #0f172a; }
            h1 { margin: 0 0 8px; font-size: 20px; }
            p { margin: 0 0 16px; color: #475569; }
            table { width: 100%; border-collapse: collapse; font-size: 12px; }
            th, td { border: 1px solid #cbd5e1; padding: 6px; }
            th { background: #f1f5f9; text-align: left; }
          </style>
        </head>
        <body>
          <h1>Game History</h1>
          <p>${selectedSeasonLabel}</p>
          <table>
            <thead>
              <tr>
                <th>Date</th>
                <th>Source</th>
                <th>Game ID</th>
                <th>Team</th>
                <th>Opponent</th>
                <th>G</th>
                <th>A</th>
                <th>P</th>
                <th>PIM</th>
              </tr>
            </thead>
            <tbody>${tableRows}</tbody>
          </table>
        </body>
      </html>
    `)
    popup.document.close()
    popup.focus()
    popup.print()
  }

  const totals = games.reduce(
    (acc, g) => ({
      games: acc.games + 1,
      goals: acc.goals + (g.goals ?? 0),
      assists: acc.assists + (g.assists ?? 0),
      points: acc.points + (g.points ?? 0),
    }),
    { games: 0, goals: 0, assists: 0, points: 0 }
  )

  return (
    <Card>
      <CardHeader>
        <CardTitle>Game History & Career Statistics</CardTitle>
        <CardDescription>
          View per-game history across AYHL, THF, AHF, and NJHS for the selected season
        </CardDescription>
      </CardHeader>
      <CardContent>
        {error && (
          <div className="p-4 bg-red-100 border border-red-400 text-red-700 rounded-md mb-6">
            <div className="flex items-center justify-between">
              <div className="flex items-center gap-2">
                <AlertCircle className="w-5 h-5" />
                <span>{error}</span>
              </div>
              <Button
                onClick={handleRetry}
                variant="outline"
                size="sm"
                disabled={loading}
              >
                <RefreshCw className="w-4 h-4 mr-2" />
                Retry
              </Button>
            </div>
          </div>
        )}

        {loading && (
          <div className="flex items-center justify-center py-12">
            <Loader2 className="w-6 h-6 animate-spin text-primary-600">
              <span className="ml-2">Loading game history...</span>
            </Loader2>
          </div>
        )}

        {!loading && availableSeasons.length > 0 && (
          <div className="mb-6 flex flex-col md:flex-row md:items-end gap-3">
            <div className="flex-1">
              <label className="label">Season</label>
              <select
                value={selectedSeason}
                onChange={handleSeasonChange}
                className="input appearance-none"
              >
                {availableSeasons.map((season) => (
                  <option key={season.seasonYear} value={season.seasonYear}>
                    {season.seasonLabel}
                  </option>
                ))}
              </select>
            </div>
            <div className="flex gap-2">
              <Button variant="outline" onClick={downloadCsv} disabled={!games.length}>
                <Download className="w-4 h-4 mr-2" />
                Export CSV
              </Button>
              <Button variant="outline" onClick={downloadPdf} disabled={!games.length}>
                <FileText className="w-4 h-4 mr-2" />
                Export PDF
              </Button>
            </div>
          </div>
        )}

        {!loading && games.length === 0 && !error && (
          <div className="text-center py-8 text-slate-500">
            <p>No game records found for this season.</p>
            <p className="text-sm mt-1">Game history will appear once per-game data exists for your profile name.</p>
          </div>
        )}

        {!loading && games.length > 0 && (
          <>
            <div className="grid grid-cols-2 md:grid-cols-4 gap-3 mb-6">
              <div className="p-3 rounded-lg bg-slate-100">
                <p className="text-xs text-slate-600">Games</p>
                <p className="text-lg font-semibold">{totals.games}</p>
              </div>
              <div className="p-3 rounded-lg bg-slate-100">
                <p className="text-xs text-slate-600">Goals</p>
                <p className="text-lg font-semibold">{totals.goals}</p>
              </div>
              <div className="p-3 rounded-lg bg-slate-100">
                <p className="text-xs text-slate-600">Assists</p>
                <p className="text-lg font-semibold">{totals.assists}</p>
              </div>
              <div className="p-3 rounded-lg bg-primary-50 border border-primary-100">
                <p className="text-xs text-primary-700">Points</p>
                <p className="text-lg font-semibold text-primary-700">{totals.points}</p>
              </div>
            </div>

            <div className="overflow-x-auto border border-slate-200 rounded-lg">
              <table className="w-full text-sm">
                <thead className="bg-slate-100">
                  <tr>
                    <th className="text-left px-3 py-2">Date</th>
                    <th className="text-left px-3 py-2">Source</th>
                    <th className="text-left px-3 py-2">Game ID</th>
                    <th className="text-left px-3 py-2">Team</th>
                    <th className="text-left px-3 py-2">Opponent</th>
                    <th className="text-right px-3 py-2">G</th>
                    <th className="text-right px-3 py-2">A</th>
                    <th className="text-right px-3 py-2">P</th>
                    <th className="text-right px-3 py-2">PIM</th>
                  </tr>
                </thead>
                <tbody>
                  {games.map((game, idx) => (
                    <tr
                      key={`${game.source}-${game.gameId}-${idx}`}
                      className="border-t border-slate-200 hover:bg-slate-50 cursor-pointer"
                      onClick={() => setSelectedGame(game)}
                    >
                      <td className="px-3 py-2">{game.gameDate || '—'}</td>
                      <td className="px-3 py-2 font-medium">{game.source}</td>
                      <td className="px-3 py-2">{game.gameId || '—'}</td>
                      <td className="px-3 py-2">{game.teamFor || '—'}</td>
                      <td className="px-3 py-2">{game.teamAgainst || '—'}</td>
                      <td className="px-3 py-2 text-right">{game.goals ?? 0}</td>
                      <td className="px-3 py-2 text-right">{game.assists ?? 0}</td>
                      <td className="px-3 py-2 text-right font-semibold text-primary-700">{game.points ?? 0}</td>
                      <td className="px-3 py-2 text-right">{game.pim ?? 0}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>

            {selectedGame && (
              <div className="fixed inset-0 z-50 bg-black/40 flex items-center justify-center p-4">
                <div className="bg-white rounded-lg w-full max-w-2xl shadow-xl border border-slate-200">
                  <div className="flex items-center justify-between border-b border-slate-200 p-4">
                    <h3 className="text-lg font-semibold text-slate-900">Game Details</h3>
                    <button
                      type="button"
                      onClick={() => setSelectedGame(null)}
                      className="text-slate-500 hover:text-slate-800"
                    >
                      <X className="w-5 h-5" />
                    </button>
                  </div>
                  <div className="p-4 grid grid-cols-2 gap-4 text-sm">
                    <div><p className="text-slate-500">Date</p><p className="font-medium">{selectedGame.gameDate || '—'}</p></div>
                    <div><p className="text-slate-500">Source</p><p className="font-medium">{selectedGame.source || '—'}</p></div>
                    <div><p className="text-slate-500">Game ID</p><p className="font-medium">{selectedGame.gameId || '—'}</p></div>
                    <div><p className="text-slate-500">Game Type</p><p className="font-medium">{selectedGame.gameType || '—'}</p></div>
                    <div><p className="text-slate-500">League</p><p className="font-medium">{selectedGame.league || '—'}</p></div>
                    <div><p className="text-slate-500">Season</p><p className="font-medium">{selectedGame.seasonLabel || '—'}</p></div>
                    <div><p className="text-slate-500">Team</p><p className="font-medium">{selectedGame.teamFor || '—'}</p></div>
                    <div><p className="text-slate-500">Opponent</p><p className="font-medium">{selectedGame.teamAgainst || '—'}</p></div>
                    <div><p className="text-slate-500">Goals</p><p className="font-medium">{selectedGame.goals ?? 0}</p></div>
                    <div><p className="text-slate-500">Assists</p><p className="font-medium">{selectedGame.assists ?? 0}</p></div>
                    <div><p className="text-slate-500">Points</p><p className="font-medium text-primary-700">{selectedGame.points ?? 0}</p></div>
                    <div><p className="text-slate-500">PIM</p><p className="font-medium">{selectedGame.pim ?? 0}</p></div>
                  </div>
                  <div className="border-t border-slate-200 p-4 flex justify-end">
                    <Button variant="outline" onClick={() => setSelectedGame(null)}>
                      Close
                    </Button>
                  </div>
                </div>
              </div>
            )}
          </>
        )}
      </CardContent>
    </Card>
  )
}
