import React, { useCallback, useEffect, useRef, useState } from 'react'
import { integrationsApi } from '../lib/integrationsApi'
import { Button } from './Button'
import { CheckCircle2, Clock, Loader2, RefreshCw, X, XCircle } from 'lucide-react'

const ACTIVE_STATUSES = new Set(['PENDING', 'RUNNING'])
const POLL_MS = 5000

function fmtTime(ts) {
  if (!ts) return null
  const d = new Date(ts)
  return isNaN(d.getTime()) ? String(ts) : d.toLocaleTimeString()
}

function fmt(ts) {
  return fmtTime(ts) || '—'
}

function badge(status) {
  if (status === 'PENDING') return { label: 'Queued', cls: 'bg-amber-100 text-amber-700', Icon: Clock, spin: false }
  if (status === 'RUNNING') return { label: 'Running', cls: 'bg-sky-100 text-sky-700', Icon: Loader2, spin: true }
  if (status === 'COMPLETED') return { label: 'Success', cls: 'bg-emerald-100 text-emerald-700', Icon: CheckCircle2, spin: false }
  return { label: 'Failed', cls: 'bg-red-100 text-red-700', Icon: XCircle, spin: false }
}

/**
 * Modal that shows what a "Deep-dive" click enqueued and how far it has got.
 *
 * A click only writes PENDING rows to deep_dive_requests; the Mac mini poller
 * (cron every 5 minutes) claims them and runs the all-seasons scrapers. So the
 * dialog polls the queue every 5s while anything is PENDING/RUNNING and stops
 * on its own once every request has a final status.
 */
export default function DeepDiveStatusDialog({ userId, label, message, onClose, onFinished }) {
  const [requests, setRequests] = useState([])
  const [error, setError] = useState('')
  const [loading, setLoading] = useState(true)
  const [nonce, setNonce] = useState(0)

  const finishedRef = useRef(false)
  const onFinishedRef = useRef(onFinished)
  useEffect(() => {
    onFinishedRef.current = onFinished
  })

  const load = useCallback(async () => {
    try {
      const resp = await integrationsApi.fetchDeepDiveRequests(userId)
      setRequests(resp.requests || [])
      setError('')
    } catch (err) {
      setError(err.message || 'Failed to load deep-dive status')
    } finally {
      setLoading(false)
    }
  }, [userId])

  useEffect(() => {
    setLoading(true)
    load()
  }, [load, nonce])

  const active = requests.filter((r) => ACTIVE_STATUSES.has(r.status)).length
  const done = requests.length - active

  useEffect(() => {
    if (loading) return
    if (active === 0) {
      if (!finishedRef.current) {
        finishedRef.current = true
        onFinishedRef.current?.()
      }
      return
    }
    finishedRef.current = false
    const timer = setTimeout(() => setNonce((n) => n + 1), POLL_MS)
    return () => clearTimeout(timer)
  }, [active, loading, nonce])

  useEffect(() => {
    const onKey = (e) => {
      if (e.key === 'Escape') onClose()
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [onClose])

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center p-4">
      <div className="absolute inset-0 bg-slate-900/40" onClick={onClose} />
      <div
        role="dialog"
        aria-modal="true"
        aria-label="Deep-dive status"
        className="relative w-full max-w-lg max-h-[85vh] overflow-y-auto rounded-lg bg-white shadow-xl border border-slate-200"
      >
        <div className="flex items-start justify-between gap-3 p-4 border-b border-slate-200">
          <div>
            <h2 className="text-lg font-semibold text-slate-900">
              Deep-dive status{label ? ` — ${label}` : ''}
            </h2>
            <p className="text-xs text-slate-500 mt-0.5">
              {active > 0
                ? `In progress: ${done} of ${requests.length} finished.`
                : requests.length > 0
                  ? `All ${requests.length} request(s) finished.`
                  : 'No deep-dive requests yet.'}
            </p>
          </div>
          <button
            type="button"
            onClick={onClose}
            className="text-slate-400 hover:text-slate-700"
            aria-label="Close"
          >
            <X className="w-5 h-5" />
          </button>
        </div>

        <div className="p-4 space-y-3">
          {message ? (
            <div className="p-3 rounded-md bg-sky-50 border border-sky-200 text-sky-800 text-sm">{message}</div>
          ) : null}

          {error ? (
            <div className="p-3 rounded-md bg-red-100 border border-red-400 text-red-700 text-sm">{error}</div>
          ) : null}

          {loading && requests.length === 0 ? (
            <div className="flex items-center justify-center py-8 text-slate-600 text-sm">
              <Loader2 className="w-4 h-4 animate-spin mr-2" />
              Loading queue...
            </div>
          ) : requests.length === 0 ? (
            <p className="text-sm text-slate-500 py-2">Nothing has been queued for this login yet.</p>
          ) : (
            <ul className="divide-y divide-slate-200 border border-slate-200 rounded-md">
              {requests.map((r) => {
                const b = badge(r.status)
                return (
                  <li key={r.id} className="p-3">
                    <div className="flex items-center justify-between gap-2">
                      <span className="font-medium text-slate-800 text-sm">{r.player_name || `Player #${r.player_id}`}</span>
                      <span className={`inline-flex items-center gap-1 text-xs px-2 py-1 rounded-full ${b.cls}`}>
                        <b.Icon className={`w-3.5 h-3.5${b.spin ? ' animate-spin' : ''}`} />
                        {b.label}
                      </span>
                    </div>
                    <div className="mt-1 text-xs text-slate-500 flex flex-wrap gap-x-3">
                      <span>Queued {fmt(r.requested_at)}</span>
                      {r.started_at ? <span>Started {fmt(r.started_at)}</span> : null}
                      {r.completed_at ? <span>Finished {fmt(r.completed_at)}</span> : null}
                      {r.sources ? <span>Sources: {String(r.sources).split(',').filter(Boolean).join(', ')}</span> : null}
                    </div>
                    {r.status === 'PENDING' ? (
                      <p className="mt-1 text-xs text-amber-700">
                        Waiting for the Mac mini poller (runs every 5 minutes).
                      </p>
                    ) : null}
                    {r.error ? <p className="mt-1 text-xs text-red-700 break-words">{r.error}</p> : null}
                  </li>
                )
              })}
            </ul>
          )}

          <p className="text-xs text-slate-500">
            One click enqueues a full all-seasons refresh for every player on this login. The Mac mini poller
            picks requests up on the next 5-minute tick, then the scrape itself takes a minute or two.
          </p>
        </div>

        <div className="flex items-center justify-end gap-2 p-4 border-t border-slate-200">
          <Button size="sm" variant="outline" onClick={() => setNonce((n) => n + 1)} disabled={loading}>
            {loading ? <Loader2 className="w-3.5 h-3.5 animate-spin mr-1" /> : <RefreshCw className="w-3.5 h-3.5 mr-1" />}
            Refresh
          </Button>
          <Button size="sm" onClick={onClose}>Close</Button>
        </div>
      </div>
    </div>
  )
}
