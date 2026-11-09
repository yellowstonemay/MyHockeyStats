import React, { useEffect, useState } from 'react'
import { integrationsApi } from '../lib/integrationsApi'
import { Button } from './Button'
import { Loader2, RefreshCw, X } from 'lucide-react'

function fmt(ts) {
  if (!ts) return '—'
  const d = new Date(ts)
  return isNaN(d.getTime()) ? String(ts) : d.toLocaleString()
}

/**
 * The transcript a deep-dive stored for one source link.
 *
 * Keeping it in the database (player_source_links.last_deep_dive_log) means the
 * admin can read what the scrape actually did without shell access — the status
 * badge alone said SUCCESS while AYHL had started serving a 404 page.
 */
export default function DeepDiveLogDialog({ target, onClose }) {
  const [data, setData] = useState(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')

  const load = async () => {
    if (!target?.source || !target?.sourcePlayerId || target.missingId) return
    setLoading(true)
    setError('')
    try {
      const resp = await integrationsApi.fetchDeepDiveLog(target.source, target.sourcePlayerId)
      setData(resp)
    } catch (err) {
      setError(err.message || 'Failed to load the deep-dive log')
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    if (target?.missingId) {
      setLoading(false)
      return
    }
    load()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [target?.source, target?.sourcePlayerId, target?.missingId])

  if (!target) return null

  const failed = data?.last_deep_dive_status === 'FAILED'

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-slate-900/40 p-4">
      <div className="w-full max-w-3xl rounded-lg bg-white shadow-xl">
        <div className="flex items-start justify-between gap-3 border-b border-slate-200 px-5 py-3">
          <div>
            <h3 className="font-semibold text-slate-900">
              Last deep-dive log · {target.source} {target.sourcePlayerId}
            </h3>
            <p className="text-xs text-slate-500">
              {target.label ? `${target.label} · ` : ''}
              {data ? `recorded ${fmt(data.last_deep_dive_at)}` : 'loading…'}
            </p>
          </div>
          <div className="flex items-center gap-2">
            <Button size="sm" variant="outline" onClick={load} disabled={loading} title="Reload">
              <RefreshCw className={`w-4 h-4 ${loading ? 'animate-spin' : ''}`} />
            </Button>
            <Button size="sm" variant="outline" onClick={onClose} title="Close">
              <X className="w-4 h-4" />
            </Button>
          </div>
        </div>

        <div className="px-5 py-3">
          {error && <p className="text-sm text-red-700 mb-2">{error}</p>}
          {data && (
            <div className="flex flex-wrap items-center gap-2 mb-2 text-xs">
              <span
                className={`px-2 py-0.5 rounded-full font-medium ${
                  failed ? 'bg-red-100 text-red-700' : 'bg-emerald-100 text-emerald-700'
                }`}
              >
                {data.last_deep_dive_status || 'UNKNOWN'}
              </span>
              <span className="text-slate-500">link {data.link_state || '—'}</span>
              {data.last_deep_dive_error && (
                <span className="text-red-700 break-all">{data.last_deep_dive_error}</span>
              )}
            </div>
          )}

          {loading ? (
            <div className="flex items-center justify-center py-10 text-slate-500 text-sm">
              <Loader2 className="w-4 h-4 animate-spin mr-2" /> Loading transcript…
            </div>
          ) : target.missingId ? (
            <p className="text-sm text-slate-600 py-6">
              This account row has no source player id on its last deep-dive, so there is no
              transcript to show. Refresh the player to record one.
            </p>
          ) : (
            <pre className="max-h-[55vh] overflow-auto whitespace-pre-wrap rounded-md bg-slate-900 px-3 py-2 text-[12px] leading-relaxed text-slate-100">
{data?.last_deep_dive_log || '(no transcript)'}
            </pre>
          )}
        </div>
      </div>
    </div>
  )
}
