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
            <ul className="mt-3 list-disc pl-5 text-slate-700">
              {(history.teamHistory || []).map((team) => (
                <li key={`${team.source}-${team.team}`}>{team.source}: {team.club} - {team.team}</li>
              ))}
            </ul>
          </div>
        )}
      </div>
    </div>
  )
}
