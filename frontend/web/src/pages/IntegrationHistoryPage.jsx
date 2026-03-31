import React, { useEffect, useMemo, useState } from 'react'
import { integrationsApi } from '../lib/integrationsApi'
import MatchCandidateSelector from '../components/integrations/MatchCandidateSelector'
import SourceConflictPanel from '../components/integrations/SourceConflictPanel'
import IntegrationStatusBanner from '../components/integrations/IntegrationStatusBanner'

export default function IntegrationHistoryPage() {
  const [statusData, setStatusData] = useState(null)
  const [history, setHistory] = useState(null)
  const [selectedBySource, setSelectedBySource] = useState({})
  const [selectedSeason, setSelectedSeason] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')

  const candidatesBySource = useMemo(() => {
    const grouped = {}
    for (const candidate of statusData?.candidates || []) {
      if (!grouped[candidate.source]) {
        grouped[candidate.source] = []
      }
      grouped[candidate.source].push(candidate)
    }
    return grouped
  }, [statusData])

  const missingSelectionSources = useMemo(() => {
    const missing = []
    for (const [source, candidates] of Object.entries(candidatesBySource)) {
      if (candidates.length > 1 && !selectedBySource[source]) {
        missing.push(source)
      }
    }
    return missing
  }, [candidatesBySource, selectedBySource])

  useEffect(() => {
    const load = async () => {
      setBusy(true)
      setError('')
      try {
        const status = await integrationsApi.getMatchStatus()
        setStatusData(status)
        if (status.status === 'AMBIGUOUS_SELECTION_REQUIRED') {
          const defaults = {}
          for (const candidate of status.candidates || []) {
            const source = candidate.source
            if (!source) {
              continue
            }
            if (!(source in defaults)) {
              defaults[source] = candidate.candidateId
            }
          }
          setSelectedBySource(defaults)
        }

        if (status.status === 'LINKED') {
          const historyResponse = await integrationsApi.getHistory()
          setHistory(historyResponse)
          setSelectedSeason(historyResponse.season || '')
        }
      } catch (err) {
        setError(err.message || 'Failed to load integration data')
      } finally {
        setBusy(false)
      }
    }

    load()
  }, [])

  const handleSeasonChange = async (event) => {
    const season = event.target.value
    setSelectedSeason(season)
    setBusy(true)
    setError('')
    try {
      const historyResponse = await integrationsApi.getHistory(season)
      setHistory(historyResponse)
    } catch (err) {
      setError(err.message || 'Failed to load season history')
    } finally {
      setBusy(false)
    }
  }

  const handleSelect = (source, candidateId) => {
    setSelectedBySource((prev) => ({
      ...prev,
      [source]: candidateId,
    }))
  }

  const handleConfirm = async () => {
    setBusy(true)
    setError('')
    try {
      const selections = Object.entries(candidatesBySource)
        .map(([source, candidates]) => {
          if (!candidates.length) {
            return null
          }
          const candidateId = candidates.length === 1
            ? candidates[0].candidateId
            : selectedBySource[source]
          if (!candidateId) {
            return null
          }
          return { source, candidateId }
        })
        .filter(Boolean)

      if (!selections.length) {
        setError('Please select at least one candidate.')
        return
      }

      await integrationsApi.confirmMatches(selections)
      const refreshedStatus = await integrationsApi.getMatchStatus()
      const refreshedHistory = await integrationsApi.getHistory(selectedSeason || undefined)
      setStatusData(refreshedStatus)
      setHistory(refreshedHistory)
      setSelectedSeason(refreshedHistory.season || selectedSeason)
    } catch (err) {
      setError(err.message || 'Failed to confirm candidate')
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="min-h-screen bg-slate-50">
      <div className="container py-8 space-y-6">
        <div>
          <h1 className="text-3xl font-bold text-slate-900">Integrated History</h1>
          <p className="text-slate-600">Linked records from THF, AYHL, and AHF.</p>
        </div>

        {error && (
          <div className="rounded-lg border border-rose-300 bg-rose-50 px-4 py-3 text-rose-800">{error}</div>
        )}

        <IntegrationStatusBanner status={statusData?.status} missingSources={history?.missingSources || []} />

        {statusData?.status === 'AMBIGUOUS_SELECTION_REQUIRED' && (
          <MatchCandidateSelector
            candidates={statusData.candidates}
            selectedBySource={selectedBySource}
            missingSources={missingSelectionSources}
            onSelect={handleSelect}
            onConfirm={handleConfirm}
            loading={busy}
          />
        )}

        {busy && <p className="text-slate-600">Loading integration data...</p>}

        {history && (
          <div className="space-y-4">
            <div className="rounded-xl border border-slate-200 bg-white p-4">
              <div className="flex flex-col gap-3 md:flex-row md:items-end md:justify-between">
                <div>
                  <h2 className="text-xl font-semibold text-slate-900">Season {history.season}</h2>
                  <p className="text-sm text-slate-600">Sources: {(history.sources || []).join(', ')}</p>
                </div>
                {(history.availableSeasons || []).length > 0 && (
                  <label className="text-sm text-slate-700">
                    <span className="mr-2 font-medium">Season</span>
                    <select
                      value={selectedSeason}
                      onChange={handleSeasonChange}
                      className="rounded-md border border-slate-300 bg-white px-3 py-2 text-sm text-slate-900"
                    >
                      {(history.availableSeasons || []).map((seasonOption) => (
                        <option key={seasonOption} value={seasonOption}>{seasonOption}</option>
                      ))}
                    </select>
                  </label>
                )}
              </div>
            </div>

            <SourceConflictPanel games={history.games || []} />

            <div className="rounded-xl border border-slate-200 bg-white p-4">
              <h3 className="text-lg font-semibold text-slate-900 mb-3">Player Record</h3>
              {(history.teamHistory || []).length === 0 ? (
                <p className="text-sm text-slate-600">No matching records were found yet.</p>
              ) : (
                <div className="overflow-x-auto">
                  <table className="min-w-full text-sm text-slate-700">
                    <thead>
                      <tr className="border-b border-slate-200 text-left text-slate-500">
                        <th className="py-2 pr-4">Source</th>
                        <th className="py-2 pr-4">Club</th>
                        <th className="py-2 pr-4">Team</th>
                        <th className="py-2 pr-4">Jersey</th>
                        <th className="py-2 pr-4">GP</th>
                        <th className="py-2 pr-4">G</th>
                        <th className="py-2 pr-4">A</th>
                        <th className="py-2 pr-4">P</th>
                        <th className="py-2 pr-4">Pen</th>
                        <th className="py-2 pr-4">PIM</th>
                      </tr>
                    </thead>
                    <tbody>
                      {(history.teamHistory || []).map((team) => (
                        <tr key={`${team.source}-${team.sourcePlayerId}-${team.team}`} className="border-b border-slate-100">
                          <td className="py-2 pr-4">{team.source}</td>
                          <td className="py-2 pr-4">{team.club}</td>
                          <td className="py-2 pr-4">{team.team}</td>
                          <td className="py-2 pr-4">{team.jerseyNumber || '-'}</td>
                          <td className="py-2 pr-4">{team.gamesPlayed ?? '-'}</td>
                          <td className="py-2 pr-4">{team.goals ?? '-'}</td>
                          <td className="py-2 pr-4">{team.assists ?? '-'}</td>
                          <td className="py-2 pr-4">{team.points ?? '-'}</td>
                          <td className="py-2 pr-4">{team.penalties ?? '-'}</td>
                          <td className="py-2 pr-4">{team.pim ?? '-'}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              )}
            </div>
          </div>
        )}
      </div>
    </div>
  )
}
