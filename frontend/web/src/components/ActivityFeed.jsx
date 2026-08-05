import React, { useEffect, useState } from 'react'
import { integrationsApi } from '../lib/integrationsApi'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from './Card'
import { Loader2 } from 'lucide-react'

const SOURCE_META = {
  AYHL: { label: 'AYHL', color: 'bg-indigo-100 text-indigo-700' },
  THF: { label: 'THF', color: 'bg-sky-100 text-sky-700' },
  AHF: { label: 'AHF', color: 'bg-emerald-100 text-emerald-700' },
  NJHS: { label: 'NJ HS', color: 'bg-amber-100 text-amber-700' },
}

function timeAgo(iso) {
  if (!iso) return ''
  const then = new Date(iso)
  if (Number.isNaN(then.getTime())) return ''
  const mins = Math.floor((Date.now() - then.getTime()) / 60000)
  if (mins < 1) return 'just now'
  if (mins < 60) return `${mins}m ago`
  const hrs = Math.floor(mins / 60)
  if (hrs < 24) return `${hrs}h ago`
  return `${Math.floor(hrs / 24)}d ago`
}

function highlightOf(g) {
  const goals = g.goals ?? 0
  const points = g.points ?? 0
  if (goals >= 3) return { label: 'Hat trick!', color: 'bg-orange-100 text-orange-700', dot: 'bg-orange-500' }
  if (points >= 3) return { label: 'Big game', color: 'bg-amber-100 text-amber-700', dot: 'bg-amber-500' }
  if (points >= 2) return { label: 'Multi-point', color: 'bg-indigo-100 text-indigo-700', dot: 'bg-indigo-500' }
  if (points >= 1) return { label: 'On the board', color: 'bg-emerald-100 text-emerald-700', dot: 'bg-emerald-500' }
  return { label: 'Played', color: 'bg-slate-100 text-slate-500', dot: 'bg-slate-400' }
}

export default function ActivityFeed() {
  const [entries, setEntries] = useState([])
  const [asOf, setAsOf] = useState(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')

  const load = async () => {
    setLoading(true)
    setError('')
    try {
      const resp = await integrationsApi.fetchActivity(10)
      setEntries(resp.entries || [])
      setAsOf(resp.asOf || null)
    } catch (err) {
      setError(err.message || 'Failed to load activity')
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    load()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  return (
    <Card>
      <CardHeader className="flex flex-col sm:flex-row sm:items-start sm:justify-between gap-1">
        <div>
          <CardTitle>Recent Activity</CardTitle>
          <CardDescription>Latest games for you and the players you follow</CardDescription>
        </div>
        <div className="text-xs text-slate-400 whitespace-nowrap">
          {asOf ? `Updated ${timeAgo(asOf)}` : 'Refreshed daily at 9:00 AM'}
        </div>
      </CardHeader>
      <CardContent>
        {error && <p className="text-sm text-red-700 mb-3">{error}</p>}
        {loading ? (
          <div className="flex items-center justify-center py-8 text-slate-500">
            <Loader2 className="w-4 h-4 animate-spin mr-2" /> Loading activity...
          </div>
        ) : entries.length === 0 ? (
          <p className="text-sm text-slate-500 text-center py-6">
            No recent games yet. Stats refresh with the daily deep-dive.
          </p>
        ) : (
          <ul className="divide-y divide-slate-100">
            {entries.map((g, i) => {
              const meta = SOURCE_META[g.source] || { label: g.source, color: 'bg-slate-100 text-slate-600' }
              const h = highlightOf(g)
              return (
                <li key={i} className="py-2.5 flex items-center gap-3">
                  <span className={`w-2 h-2 rounded-full shrink-0 ${h.dot}`} />
                  <div className="min-w-0 flex-1">
                    <div className="flex items-center gap-2 min-w-0">
                      <span className={`text-[11px] font-semibold px-1.5 py-0.5 rounded-full shrink-0 ${meta.color}`}>
                        {meta.label}
                      </span>
                      <span className={`font-medium text-sm truncate ${g.isMe ? 'text-indigo-700' : 'text-slate-900'}`}>
                        {g.isMe ? 'You' : g.playerName}
                      </span>
                      {g.isMe && (
                        <span className="text-[10px] uppercase tracking-wide text-indigo-400 font-semibold shrink-0">you</span>
                      )}
                    </div>
                    <div className="text-xs text-slate-500 truncate">
                      {g.date || '—'} · {g.opponent || g.teamFor || 'game'}
                    </div>
                  </div>
                  <div className="text-right shrink-0">
                    <div className={`text-xs font-semibold px-2 py-0.5 rounded-full ${h.color}`}>
                      {g.points != null ? `${g.goals ?? 0}G-${g.assists ?? 0}A (${g.points}P)` : '—'}
                    </div>
                    <div className="text-[10px] text-slate-400 mt-0.5">{h.label}</div>
                  </div>
                </li>
              )
            })}
          </ul>
        )}
      </CardContent>
    </Card>
  )
}
