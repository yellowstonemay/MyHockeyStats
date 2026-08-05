import React, { useState, useEffect } from 'react'
import { useAuth } from '../lib/AuthContext'
import { integrationsApi } from '../lib/integrationsApi'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '../components/Card'
import { Button } from '../components/Button'
import { AlertCircle, Loader2, RefreshCw, Radar, Inbox } from 'lucide-react'

function fmt(ts) {
  if (!ts) return '—'
  const d = new Date(ts)
  if (isNaN(d.getTime())) return ts
  return d.toLocaleString()
}

function daysSince(ts) {
  if (!ts) return null
  const d = new Date(ts)
  if (isNaN(d.getTime())) return null
  return (Date.now() - d.getTime()) / 86400000
}

export default function AdminPlayersPage() {
  const { user } = useAuth()
  const [players, setPlayers] = useState([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [isAdmin, setIsAdmin] = useState(!!user?.isAdmin)
  const [triggeringId, setTriggeringId] = useState(null)
  const [actionMsg, setActionMsg] = useState(null)

  const [messages, setMessages] = useState([])
  const [messagesLoading, setMessagesLoading] = useState(false)
  const [messagesError, setMessagesError] = useState('')
  const [resolvingId, setResolvingId] = useState(null)

  const loadMessages = async () => {
    setMessagesLoading(true)
    setMessagesError('')
    try {
      const resp = await integrationsApi.fetchAdminMessages()
      setMessages(resp.messages || [])
    } catch (err) {
      setMessagesError(err.message || 'Failed to load messages')
    } finally {
      setMessagesLoading(false)
    }
  }

  useEffect(() => {
    if (isAdmin) loadMessages()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [isAdmin])

  const resolveMsg = async (id) => {
    setResolvingId(id)
    try {
      await integrationsApi.resolveAdminMessage(id)
      await loadMessages()
    } catch (err) {
      setMessagesError(err.message || 'Failed to resolve message')
    } finally {
      setResolvingId(null)
    }
  }

  useEffect(() => {
    setIsAdmin(!!user?.isAdmin)
  }, [user])

  const load = async () => {
    setLoading(true)
    setError('')
    try {
      const resp = await integrationsApi.fetchAdminPlayers()
      setPlayers(resp.players || [])
    } catch (err) {
      setError(err.message || 'Failed to load players')
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    if (isAdmin) load()
  }, [isAdmin])

  const triggerDeepDive = async (p) => {
    setTriggeringId(p.id)
    setActionMsg(null)
    try {
      const resp = await integrationsApi.triggerDeepDive(p.id)
      setActionMsg({ kind: 'ok', text: `${p.user_name || p.email}: ${resp.message}` })
    } catch (err) {
      setActionMsg({ kind: 'err', text: `${p.user_name || p.email}: ${err.message || 'Failed to enqueue'}` })
    } finally {
      setTriggeringId(null)
    }
  }

  const staleThresholdDays = 2
  const status = (p) => {
    if (!p.link_count || Number(p.link_count) === 0) return { label: 'No links', cls: 'bg-slate-100 text-slate-600' }
    const since = daysSince(p.last_deep_dive_at)
    if (since == null || since > staleThresholdDays) {
      return { label: 'Stale', cls: 'bg-amber-100 text-amber-700' }
    }
    return { label: 'Current', cls: 'bg-emerald-100 text-emerald-700' }
  }

  return (
    <div className="min-h-screen bg-slate-50">
      <div className="container py-8">
        <div className="flex items-center justify-between mb-6">
          <div>
            <h1 className="text-2xl font-bold text-slate-900">Admin — Registered Players</h1>
            <p className="text-slate-600 text-sm">Ordered by registration time (newest first).</p>
          </div>
          <Button variant="outline" onClick={load} disabled={loading}>
            <RefreshCw className="w-4 h-4 mr-2" />
            Refresh
          </Button>
        </div>

        {!isAdmin && !loading && (
          <Card>
            <CardContent>
              <div className="flex items-center gap-2 text-red-700">
                <AlertCircle className="w-5 h-5" />
                <span>Admin access required. Sign in with an admin account.</span>
              </div>
            </CardContent>
          </Card>
        )}

        {isAdmin && error && (
          <div className="p-4 bg-red-100 border border-red-400 text-red-700 rounded-md mb-4">
            {error}
          </div>
        )}

        {isAdmin && actionMsg && (
          <div className={`p-3 border rounded-md mb-4 text-sm ${actionMsg.kind === 'ok' ? 'bg-emerald-50 border-emerald-300 text-emerald-700' : 'bg-red-100 border-red-400 text-red-700'}`}>
            {actionMsg.text}
          </div>
        )}

        {isAdmin && loading && (
          <div className="flex items-center justify-center py-10 text-slate-600">
            <Loader2 className="w-5 h-5 animate-spin mr-2" />
            <span>Loading players...</span>
          </div>
        )}

        {isAdmin && !loading && !error && (
          <Card>
            <CardHeader>
              <CardTitle>Players ({players.length})</CardTitle>
              <CardDescription>Registration, last visit, and deep-dive freshness. The <strong>Full deep-dive</strong> button enqueues an all-seasons backfill for that player (picked up by the Mac mini poller).</CardDescription>
            </CardHeader>
            <CardContent>
              <div className="overflow-x-auto">
                <table className="w-full text-sm border border-slate-200 rounded-lg overflow-hidden">
                  <thead className="bg-slate-100 text-slate-700">
                    <tr>
                      <th className="px-3 py-2 text-left">Registered</th>
                      <th className="px-3 py-2 text-left">Email</th>
                      <th className="px-3 py-2 text-left">Name</th>
                      <th className="px-3 py-2 text-left">Profile</th>
                      <th className="px-3 py-2 text-left">Links</th>
                      <th className="px-3 py-2 text-left">Last visit</th>
                      <th className="px-3 py-2 text-left">Last deep-dive</th>
                      <th className="px-3 py-2 text-left">Status</th>
                      <th className="px-3 py-2 text-left">Actions</th>
                    </tr>
                  </thead>
                  <tbody>
                    {players.map((p) => {
                      const st = status(p)
                      const hasLinks = Number(p.link_count) > 0
                      return (
                        <tr key={p.id} className="border-t border-slate-200 hover:bg-slate-50">
                          <td className="px-3 py-2 whitespace-nowrap">{fmt(p.created_at)}</td>
                          <td className="px-3 py-2 font-medium">{p.email}</td>
                          <td className="px-3 py-2">{p.user_name || '—'}{p.is_admin ? <span className="ml-1 text-[10px] px-1.5 py-0.5 rounded bg-indigo-100 text-indigo-700">admin</span> : null}</td>
                          <td className="px-3 py-2">{p.profile_name || '—'}</td>
                          <td className="px-3 py-2">
                            <span className="text-slate-500">{(p.sources || '').split(',').filter(Boolean).join(', ') || '—'}</span>
                            <span className="ml-1 text-xs text-slate-400">({p.link_count ?? 0})</span>
                          </td>
                          <td className="px-3 py-2 whitespace-nowrap">{fmt(p.last_login_at)}</td>
                          <td className="px-3 py-2 whitespace-nowrap">{fmt(p.last_deep_dive_at)}</td>
                          <td className="px-3 py-2">
                            <span className={`text-xs px-2 py-1 rounded-full ${st.cls}`}>{st.label}</span>
                          </td>
                          <td className="px-3 py-2">
                            <Button
                              size="sm"
                              variant="outline"
                              disabled={!hasLinks || triggeringId === p.id}
                              onClick={() => triggerDeepDive(p)}
                              title={hasLinks ? 'Enqueue full all-seasons deep-dive' : 'Player has no identity links'}
                            >
                              {triggeringId === p.id ? <Loader2 className="w-3.5 h-3.5 animate-spin mr-1" /> : <Radar className="w-3.5 h-3.5 mr-1" />}
                              Deep-dive
                            </Button>
                          </td>
                        </tr>
                      )
                    })}
                  </tbody>
                </table>
              </div>
            </CardContent>
          </Card>
        )}

        {isAdmin && (
          <Card className="mt-6">
            <CardHeader>
              <CardTitle>
                <span className="inline-flex items-center gap-2">
                  <Inbox className="w-4 h-4" />
                  Support Messages ({messages.filter((m) => m.status === 'NEW').length} new)
                </span>
              </CardTitle>
              <CardDescription>Reports from users about incorrect data, missing seasons, etc.</CardDescription>
            </CardHeader>
            <CardContent>
              {messagesLoading ? (
                <div className="flex items-center justify-center py-6 text-slate-500">
                  <Loader2 className="w-4 h-4 animate-spin mr-2" /> Loading messages...
                </div>
              ) : messagesError ? (
                <p className="text-sm text-red-700 py-4">{messagesError}</p>
              ) : messages.length === 0 ? (
                <p className="text-sm text-slate-500 text-center py-6">No messages yet.</p>
              ) : (
                <div className="space-y-3">
                  {messages.map((m) => (
                    <div key={m.id} className={`border rounded-lg p-3 ${m.status === 'NEW' ? 'border-amber-300 bg-amber-50/50' : 'border-slate-200'}`}>
                      <div className="flex items-center justify-between gap-2">
                        <div className="flex items-center gap-2 min-w-0">
                          <span className={`text-[11px] font-semibold px-2 py-0.5 rounded-full ${m.status === 'NEW' ? 'bg-amber-100 text-amber-700' : 'bg-slate-100 text-slate-500'}`}>
                            {m.status === 'NEW' ? 'New' : 'Resolved'}
                          </span>
                          <span className="text-xs text-slate-500 truncate">
                            {m.email}{m.userName ? ` (${m.userName})` : ''}
                          </span>
                        </div>
                        <span className="text-[11px] text-slate-400 whitespace-nowrap">{fmt(m.createdAt)}</span>
                      </div>
                      <div className="mt-1 text-xs font-medium text-slate-700">{m.category}</div>
                      <p className="mt-1 text-sm text-slate-700 whitespace-pre-wrap">{m.message}</p>
                      {m.status === 'NEW' && (
                        <div className="mt-2">
                          <Button size="sm" variant="outline" onClick={() => resolveMsg(m.id)} disabled={resolvingId === m.id}>
                            {resolvingId === m.id ? <Loader2 className="w-3.5 h-3.5 animate-spin mr-1" /> : null}
                            Mark resolved
                          </Button>
                        </div>
                      )}
                    </div>
                  ))}
                </div>
              )}
            </CardContent>
          </Card>
        )}
      </div>
    </div>
  )
}
