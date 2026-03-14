import React, { useEffect, useMemo, useState } from 'react'
import { integrationsApi } from '../lib/integrationsApi'
import MatchCandidateSelector from '../components/integrations/MatchCandidateSelector'
import SourceConflictPanel from '../components/integrations/SourceConflictPanel'
import IntegrationStatusBanner from '../components/integrations/IntegrationStatusBanner'

export default function IntegrationHistoryPage() {
  const [statusData, setStatusData] = useState(null)
  const [history, setHistory] = useState(null)
  const [selectedCandidateId, setSelectedCandidateId] = useState(null)
  const [selectedSeason, setSelectedSeason] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')

  const selectedCandidate = useMemo(() => {
    return statusData?.candidates?.find((candidate) => candidate.candidateId === selectedCandidateId)
  }, [statusData, selectedCandidateId])

  useEffect(() => {
    const load = async () => {
      setBusy(true)
      setError('')
      try {
        const status = await integrationsApi.getMatchStatus()
        setStatusData(status)

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

  const handleConfirm = async () => {
    if (!selectedCandidate) {
      return
    }
    setBusy(true)
    setError('')
    try {
      await integrationsApi.confirmMatches([
        {
          source: selectedCandidate.source,
          candidateId: selectedCandidate.candidateId,
        },
      ])
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
          <p className="text-slate-600">Linked records from THF, AYHL, and GameSheet.</p>
        </div>

        {error && (
          <div className="rounded-lg border border-rose-300 bg-rose-50 px-4 py-3 text-rose-800">{error}</div>
        )}

        <IntegrationStatusBanner status={statusData?.status} missingSources={history?.missingSources || []} />

        {statusData?.status === 'AMBIGUOUS_SELECTION_REQUIRED' && (
          <MatchCandidateSelector
            candidates={statusData.candidates}
            selectedId={selectedCandidateId}
            onSelect={setSelectedCandidateId}
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
              <h3 className="text-lg font-semibold text-slate-900 mb-3">Team History</h3>
              <ul className="space-y-2 text-slate-700">
                {(history.teamHistory || []).map((team) => (
                  <li key={`${team.source}-${team.team}`}>{team.source}: {team.club} - {team.team}</li>
                ))}
              </ul>
            </div>
          </div>
        )}
      </div>
    </div>
  )
}
