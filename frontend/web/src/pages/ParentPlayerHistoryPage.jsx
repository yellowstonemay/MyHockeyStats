import React, { useState } from 'react'
import { integrationsApi } from '../lib/integrationsApi'

export default function ParentPlayerHistoryPage() {
  const [playerUserId, setPlayerUserId] = useState('')
  const [linkedPlayerUserId, setLinkedPlayerUserId] = useState('')
  const [history, setHistory] = useState(null)
  const [error, setError] = useState('')
  const [loading, setLoading] = useState(false)

  const loadHistory = async (event) => {
    event.preventDefault()
    setLoading(true)
    setError('')
    try {
      const response = await integrationsApi.getParentHistory(playerUserId, '2024-2025', linkedPlayerUserId)
      setHistory(response)
    } catch (err) {
      setError(err.message || 'Failed to load player history')
    } finally {
      setLoading(false)
    }
  }

  return (
    <div className="min-h-screen bg-slate-50">
      <div className="container py-8 space-y-6">
        <h1 className="text-3xl font-bold text-slate-900">Parent View: Player History</h1>

        <form onSubmit={loadHistory} className="rounded-xl border border-slate-200 bg-white p-4 space-y-3">
          <label className="block text-sm text-slate-700">
            Player User ID
            <input
              className="mt-1 block w-full rounded-md border border-slate-300 px-3 py-2"
              value={playerUserId}
              onChange={(event) => setPlayerUserId(event.target.value)}
              required
            />
          </label>

          <label className="block text-sm text-slate-700">
            Linked Player User ID (optional)
            <input
              className="mt-1 block w-full rounded-md border border-slate-300 px-3 py-2"
              value={linkedPlayerUserId}
              onChange={(event) => setLinkedPlayerUserId(event.target.value)}
            />
          </label>

          <button type="submit" disabled={loading} className="rounded-md bg-primary-600 px-4 py-2 text-white disabled:opacity-50">
            {loading ? 'Loading...' : 'Load History'}
          </button>
        </form>

        {error && <div className="rounded-lg border border-rose-300 bg-rose-50 px-4 py-3 text-rose-800">{error}</div>}

        {history && (
          <div className="rounded-xl border border-slate-200 bg-white p-4">
            <h2 className="text-xl font-semibold text-slate-900">Season {history.season}</h2>
            <p className="text-sm text-slate-600">Sources: {(history.sources || []).join(', ')}</p>
            {(history.teamHistory || []).length === 0 ? (
              <p className="mt-3 text-sm text-slate-600">No matching records were found yet.</p>
            ) : (
              <div className="mt-3 overflow-x-auto">
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
        )}
      </div>
    </div>
  )
}
