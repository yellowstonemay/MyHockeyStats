import React from 'react'

export default function MatchCandidateSelector({ candidates = [], selectedId, onSelect, onConfirm, loading }) {
  if (!candidates.length) {
    return null
  }

  return (
    <div className="rounded-xl border border-slate-200 bg-white p-4 space-y-4">
      <div>
        <h3 className="text-lg font-semibold text-slate-900">Confirm Your Player Record</h3>
        <p className="text-sm text-slate-600">Multiple matches were found. Select the correct candidate to link your account.</p>
      </div>

      <div className="space-y-2">
        {candidates.map((candidate) => (
          <label key={candidate.candidateId} className="flex items-center justify-between rounded-lg border border-slate-200 px-3 py-2 cursor-pointer hover:bg-slate-50">
            <div>
              <div className="font-medium text-slate-900">{candidate.displayName}</div>
              <div className="text-sm text-slate-600">{candidate.source} | {candidate.seasonLabel} | score {candidate.score?.toFixed?.(2) ?? candidate.score}</div>
            </div>
            <input
              type="radio"
              name="integration-candidate"
              checked={selectedId === candidate.candidateId}
              onChange={() => onSelect(candidate.candidateId)}
            />
          </label>
        ))}
      </div>

      <button
        type="button"
        disabled={!selectedId || loading}
        onClick={onConfirm}
        className="rounded-md bg-primary-600 px-4 py-2 text-white disabled:opacity-50"
      >
        {loading ? 'Linking...' : 'Confirm Selection'}
      </button>
    </div>
  )
}
