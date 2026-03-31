import React from 'react'

export default function MatchCandidateSelector({
  candidates = [],
  selectedBySource = {},
  onSelect,
  onConfirm,
  loading,
  missingSources = [],
}) {
  if (!candidates.length) {
    return null
  }

  const groupedBySource = candidates.reduce((acc, candidate) => {
    const key = candidate.source || 'UNKNOWN'
    if (!acc[key]) {
      acc[key] = []
    }
    acc[key].push(candidate)
    return acc
  }, {})

  return (
    <div className="rounded-xl border border-slate-200 bg-white p-4 space-y-4">
      <div>
        <h3 className="text-lg font-semibold text-slate-900">Confirm Your Player Record</h3>
        <p className="text-sm text-slate-600">Multiple matches were found. Select one candidate per source to link your account.</p>
        {missingSources.length > 0 && (
          <p className="mt-1 text-xs text-amber-700">Selection still required for: {missingSources.join(', ')}</p>
        )}
      </div>

      <div className="space-y-4">
        {Object.entries(groupedBySource).map(([source, sourceCandidates]) => (
          <div key={source} className="space-y-2">
            <h4 className="text-sm font-semibold text-slate-800">{source}</h4>
            {sourceCandidates.map((candidate) => (
              <label key={`${candidate.source}-${candidate.candidateId}-${candidate.seasonLabel}`} className="flex items-center justify-between rounded-lg border border-slate-200 px-3 py-2 cursor-pointer hover:bg-slate-50">
                <div>
                  <div className="font-medium text-slate-900">{candidate.displayName}</div>
                  <div className="text-sm text-slate-600">{candidate.source} | {candidate.seasonLabel} | score {candidate.score?.toFixed?.(2) ?? candidate.score}</div>
                </div>
                <input
                  type="radio"
                  name={`integration-candidate-${source}`}
                  checked={selectedBySource[source] === candidate.candidateId}
                  onChange={() => onSelect(source, candidate.candidateId)}
                />
              </label>
            ))}
          </div>
        ))}
      </div>

      <button
        type="button"
        disabled={loading || missingSources.length > 0}
        onClick={onConfirm}
        className="rounded-md bg-primary-600 px-4 py-2 text-white disabled:opacity-50"
      >
        {loading ? 'Linking...' : 'Confirm Selection'}
      </button>
    </div>
  )
}
