import React from 'react'

export default function SourceConflictPanel({ games = [] }) {
  const conflicted = games.filter((game) => game.hasConflict)

  if (!conflicted.length) {
    return null
  }

  return (
    <div className="rounded-xl border border-amber-300 bg-amber-50 p-4 space-y-3">
      <h3 className="text-lg font-semibold text-amber-900">Conflicts Detected</h3>
      {conflicted.map((game) => (
        <div key={game.logicalGameKey} className="rounded-lg border border-amber-200 bg-white p-3">
          <div className="font-medium text-slate-900">{game.date} vs {game.opponent}</div>
          {game.conflicts?.map((conflict) => (
            <div key={`${game.logicalGameKey}-${conflict.field}`} className="text-sm text-slate-700 mt-1">
              {conflict.field}: {Object.entries(conflict.values || {}).map(([source, value]) => `${source}=${value}`).join(', ')}
            </div>
          ))}
        </div>
      ))}
    </div>
  )
}
