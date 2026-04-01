import React from 'react'

export default function IntegrationStatusBanner({ status, missingSources = [] }) {
  if (!status) {
    return null
  }

  if (status === 'NO_MATCH') {
    return (
      <div className="rounded-lg border border-slate-300 bg-slate-50 px-4 py-3 text-slate-700">
        No matching records were found yet. Your account remains secure and unlinked until data is available.
      </div>
    )
  }

  if (missingSources.length > 0) {
    return (
      <div className="rounded-lg border border-sky-300 bg-sky-50 px-4 py-3 text-sky-900">
        Records are linked. Missing sources: {missingSources.join(', ')}.
      </div>
    )
  }

  return (
    <div className="rounded-lg border border-emerald-300 bg-emerald-50 px-4 py-3 text-emerald-900">
      Records are linked and synchronized across available sources.
    </div>
  )
}
