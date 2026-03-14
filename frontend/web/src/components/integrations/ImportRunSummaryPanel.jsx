import React from 'react'

export default function ImportRunSummaryPanel({ runSummary }) {
  if (!runSummary) {
    return null
  }

  return (
    <div className="rounded-xl border border-slate-200 bg-white p-4 space-y-3">
      <h3 className="text-lg font-semibold text-slate-900">Import Run Summary</h3>
      <div className="text-sm text-slate-600">Run ID: {runSummary.runId}</div>
      <div className="text-sm text-slate-600">Status: {runSummary.status}</div>
      <div className="text-sm text-slate-600">Started: {runSummary.startedAt}</div>
      <div className="space-y-2">
        {(runSummary.sourceSummaries || []).map((sourceSummary) => (
          <div key={sourceSummary.source} className="rounded-lg border border-slate-200 px-3 py-2 text-sm text-slate-700">
            {sourceSummary.source}: processed={sourceSummary.processed}, accepted={sourceSummary.accepted},
            rejected={sourceSummary.rejected}, duplicates={sourceSummary.duplicateSkipped}, status={sourceSummary.status}
          </div>
        ))}
      </div>
    </div>
  )
}
