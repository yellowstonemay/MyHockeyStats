import React, { useEffect, useState } from 'react'
import { integrationsApi } from '../lib/integrationsApi'
import ImportRunSummaryPanel from '../components/integrations/ImportRunSummaryPanel'

export default function IntegrationAdminPage() {
  const [runSummary, setRunSummary] = useState(null)
  const [latestDaily, setLatestDaily] = useState(null)
  const [error, setError] = useState('')
  const [loading, setLoading] = useState(false)

  const triggerImport = async () => {
    setLoading(true)
    setError('')
    try {
      const run = await integrationsApi.runImport(['THF', 'AYHL', 'AHF'])
      const summary = await integrationsApi.getImportRun(run.runId)
      setRunSummary(summary)
    } catch (err) {
      setError(err.message || 'Failed to run import')
    } finally {
      setLoading(false)
    }
  }

  const loadLatestDaily = async () => {
    setLoading(true)
    setError('')
    try {
      const summary = await integrationsApi.getLatestDailyRun()
      setLatestDaily(summary)
    } catch (err) {
      setError(err.message || 'Failed to load daily status')
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    loadLatestDaily()
  }, [])

  return (
    <div className="min-h-screen bg-slate-50">
      <div className="container py-8 space-y-6">
        <h1 className="text-3xl font-bold text-slate-900">Integration Admin</h1>
        <p className="text-slate-600">Manage manual imports and inspect daily run status.</p>

        {error && <div className="rounded-lg border border-rose-300 bg-rose-50 px-4 py-3 text-rose-800">{error}</div>}

        <div className="flex flex-wrap gap-3">
          <button type="button" onClick={triggerImport} disabled={loading} className="rounded-md bg-primary-600 px-4 py-2 text-white disabled:opacity-50">
            {loading ? 'Running...' : 'Run Manual Import'}
          </button>
          <button type="button" onClick={loadLatestDaily} disabled={loading} className="rounded-md border border-slate-300 bg-white px-4 py-2 text-slate-800 disabled:opacity-50">
            Load Daily Status
          </button>
        </div>

        <ImportRunSummaryPanel runSummary={runSummary} />

        {latestDaily && (
          <div className="rounded-xl border border-slate-200 bg-white p-4 space-y-2">
            <h3 className="text-lg font-semibold text-slate-900">Latest Daily By Source</h3>
            {(latestDaily.latestBySource || []).map((entry) => (
              <div key={entry.source} className="rounded-lg border border-slate-200 px-3 py-2 text-sm text-slate-700">
                <div className="font-medium text-slate-900">{entry.source}</div>
                <div>Status: {entry.status} {entry.endedAt ? `(${entry.endedAt})` : ''}</div>
                <div>Tracked players: {entry.trackedPlayers ?? 0}</div>
                <div>
                  Last run counts: processed={entry.processed ?? 0}, accepted={entry.accepted ?? 0},
                  rejected={entry.rejected ?? 0}, duplicates={entry.duplicateSkipped ?? 0}
                </div>
              </div>
            ))}
          </div>
        )}
      </div>
    </div>
  )
}
