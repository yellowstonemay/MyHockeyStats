import React, { useState, useEffect } from 'react'
import { integrationsApi } from '../../lib/integrationsApi'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '../Card'
import { Button } from '../Button'
import { AlertCircle, Loader2, RefreshCw } from 'lucide-react'

export default function SeasonsTab() {
  const [records, setRecords] = useState([])
  const [selectedSeason, setSelectedSeason] = useState('')
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [ambiguity, setAmbiguity] = useState(null)

  useEffect(() => {
    loadSeasons()
  }, [])

  const loadSeasons = async () => {
    setLoading(true)
    setError('')
    try {
      const response = await integrationsApi.fetchMySeasons(selectedSeason)
      setRecords(response.records || [])
      setAmbiguity(response.hasAmbiguity ? response.ambiguityNote : null)
    } catch (err) {
      setError(err.message || 'Failed to load season data')
    } finally {
      setLoading(false)
    }
  }

  const handleSeasonChange = async (e) => {
    const season = e.target.value
    setSelectedSeason(season)
    setLoading(true)
    setError('')
    try {
      const response = await integrationsApi.fetchMySeasons(season)
      setRecords(response.records || [])
      setAmbiguity(response.hasAmbiguity ? response.ambiguityNote : null)
    } catch (err) {
      setError(err.message || 'Failed to load season data')
    } finally {
      setLoading(false)
    }
  }

  const handleRetry = () => {
    loadSeasons()
  }

  // Group records by season
  const recordsBySeason = {}
  records.forEach((record) => {
    const season = record.season
    if (!recordsBySeason[season]) {
      recordsBySeason[season] = []
    }
    recordsBySeason[season].push(record)
  })

  const seasons = Object.keys(recordsBySeason).sort((a, b) => b - a)

  return (
    <Card>
      <CardHeader>
        <CardTitle>Game History & Career Statistics</CardTitle>
        <CardDescription>
          View your complete game history and career statistics from all hockey leagues
        </CardDescription>
      </CardHeader>
      <CardContent>
        {ambiguity && (
          <div className="p-3 bg-yellow-100 border border-yellow-400 text-yellow-800 rounded-md mb-6 flex items-start gap-2 text-sm">
            <AlertCircle className="w-5 h-5 flex-shrink-0 mt-0.5" />
            <div>
              <strong>Note:</strong> {ambiguity}
            </div>
          </div>
        )}

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
              <span className="ml-2">Loading career data...</span>
            </Loader2>
          </div>
        )}

        {!loading && records.length === 0 && !error && (
          <div className="text-center py-8 text-slate-500">
            <p>No career records found for your profile.</p>
            <p className="text-sm mt-1">Your data will appear here once it matches hockey league records.</p>
          </div>
        )}

        {!loading && records.length > 0 && (
          <>
            {seasons.length > 1 && (
              <div className="mb-6">
                <label className="label">Filter by Season</label>
                <select
                  value={selectedSeason}
                  onChange={handleSeasonChange}
                  className="input appearance-none"
                >
                  <option value="">All Seasons</option>
                  {Array.from(seasons)
                    .sort((a, b) => b - a)
                    .map((season) => (
                      <option key={season} value={season}>
                        {season} Season
                      </option>
                    ))}
                </select>
              </div>
            )}

            <div className="space-y-6">
              {seasons
                .sort((a, b) => b - a)
                .map((season) => (
                  <div key={season}>
                    <h3 className="text-lg font-semibold mb-3">
                      {season} Season ({recordsBySeason[season].length} record
                      {recordsBySeason[season].length > 1 ? 's' : ''})
                    </h3>
                    <div className="space-y-3">
                      {recordsBySeason[season]
                        .sort((a, b) => {
                          // Sort by source for consistent display
                          return (a.source || '').localeCompare(b.source || '')
                        })
                        .map((record, idx) => (
                          <div
                            key={`${record.source}-${record.sourcePlayerId}-${idx}`}
                            className="border border-slate-200 rounded-lg p-4 hover:bg-slate-50 transition-colors"
                          >
                            <div className="grid grid-cols-2 md:grid-cols-4 gap-4">
                              <div>
                                <p className="text-xs text-slate-600 mb-1">League</p>
                                <p className="font-semibold text-sm">{record.source}</p>
                              </div>
                              <div>
                                <p className="text-xs text-slate-600 mb-1">Club</p>
                                <p className="text-sm">{record.club || '—'}</p>
                              </div>
                              <div>
                                <p className="text-xs text-slate-600 mb-1">Team</p>
                                <p className="text-sm">{record.team || '—'}</p>
                              </div>
                              <div>
                                <p className="text-xs text-slate-600 mb-1">Jersey #</p>
                                <p className="text-sm">{record.jerseyNumber || '—'}</p>
                              </div>

                              <div>
                                <p className="text-xs text-slate-600 mb-1">Games Played</p>
                                <p className="font-semibold text-sm">{record.gamesPlayed || 0}</p>
                              </div>
                              <div>
                                <p className="text-xs text-slate-600 mb-1">Goals</p>
                                <p className="font-semibold text-sm">{record.goals || 0}</p>
                              </div>
                              <div>
                                <p className="text-xs text-slate-600 mb-1">Assists</p>
                                <p className="font-semibold text-sm">{record.assists || 0}</p>
                              </div>
                              <div>
                                <p className="text-xs text-slate-600 mb-1">Points</p>
                                <p className="font-semibold text-sm text-primary-600">
                                  {record.points || 0}
                                </p>
                              </div>

                              <div>
                                <p className="text-xs text-slate-600 mb-1">Penalties</p>
                                <p className="text-sm">{record.penalties || 0}</p>
                              </div>
                              <div>
                                <p className="text-xs text-slate-600 mb-1">PIM</p>
                                <p className="text-sm">{record.pim || 0}</p>
                              </div>
                            </div>
                          </div>
                        ))}
                    </div>
                  </div>
                ))}
            </div>
          </>
        )}
      </CardContent>
    </Card>
  )
}
