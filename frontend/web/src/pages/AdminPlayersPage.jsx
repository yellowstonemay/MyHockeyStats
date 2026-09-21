import React, { useState, useEffect } from 'react'
import { useAuth } from '../lib/AuthContext'
import { integrationsApi } from '../lib/integrationsApi'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '../components/Card'
import { Button } from '../components/Button'
import DeepDiveStatusDialog from '../components/DeepDiveStatusDialog'
import { AlertCircle, Clock, Loader2, RefreshCw, Radar, Inbox } from 'lucide-react'

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
  const [ddDialog, setDdDialog] = useState(null)

  const [messages, setMessages] = useState([])
  const [messagesLoading, setMessagesLoading] = useState(false)
  const [messagesError, setMessagesError] = useState('')
  const [resolvingId, setResolvingId] = useState(null)

  const [profiles, setProfiles] = useState([])
  const [profilesError, setProfilesError] = useState('')
  const [ownerInputs, setOwnerInputs] = useState({})
  const [transferringId, setTransferringId] = useState(null)
  const [mergeTargets, setMergeTargets] = useState({})
  const [mergingId, setMergingId] = useState(null)

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

  const loadProfiles = async () => {
    setProfilesError('')
    try {
      const resp = await integrationsApi.fetchAdminProfiles()
      setProfiles(resp.profiles || [])
    } catch (err) {
      setProfilesError(err.message || 'Failed to load player profiles')
    }
  }

  useEffect(() => {
    if (isAdmin) load()
    if (isAdmin) loadProfiles()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [isAdmin])

  const transferOwner = async (profile) => {
    const email = (ownerInputs[profile.id] || '').trim()
    if (!email) return
    setTransferringId(profile.id)
    setActionMsg(null)
    try {
      const resp = await integrationsApi.setPlayerOwner(profile.id, { email })
      setActionMsg({ kind: 'ok', text: resp.message || `Owner of ${profile.profile_name} updated.` })
      setOwnerInputs((prev) => ({ ...prev, [profile.id]: '' }))
      await loadProfiles()
    } catch (err) {
      setActionMsg({ kind: 'err', text: err.message || 'Failed to transfer ownership' })
    } finally {
      setTransferringId(null)
    }
  }

  // Profiles that share a player name are the duplicates this merge is for
  const mergeCandidates = (profile) =>
    profiles.filter(
      (o) =>
        String(o.id) !== String(profile.id) &&
        (o.profile_name || '').trim().toLowerCase() ===
          (profile.profile_name || '').trim().toLowerCase()
    )

  const mergeProfile = async (profile) => {
    const targetId = mergeTargets[profile.id]
    if (!targetId) return
    const target = profiles.find((o) => String(o.id) === String(targetId))
    const ok = window.confirm(
      `Merge "${profile.profile_name} #${profile.id}" into "${target?.profile_name} #${targetId}"?\n\n` +
        'Seasons, games, manual G/A entries, source links and logins move to the surviving ' +
        `profile, then #${profile.id} is deleted. This cannot be undone.`
    )
    if (!ok) return
    setMergingId(profile.id)
    setActionMsg(null)
    try {
      const resp = await integrationsApi.mergePlayerProfile(profile.id, Number(targetId))
      setActionMsg({ kind: 'ok', text: resp.message || 'Profiles merged.' })
      setMergeTargets((prev) => ({ ...prev, [profile.id]: '' }))
      await loadProfiles()
      await load()
    } catch (err) {
      setActionMsg({ kind: 'err', text: err.message || 'Failed to merge profiles' })
    } finally {
      setMergingId(null)
    }
  }

  const triggerDeepDive = async (p) => {
    const who = p.user_name || p.email
    setTriggeringId(p.id)
    setActionMsg(null)
    try {
      const resp = await integrationsApi.triggerDeepDive(p.id)
      setActionMsg({ kind: 'ok', text: `${who}: ${resp.message}` })
      // Show the queue straight away — the run itself happens on the Mac mini
      // poller, so the table alone would look like nothing happened.
      setDdDialog({ userId: p.id, label: who, message: resp.message })
      await load()
    } catch (err) {
      const text = err.message || 'Failed to enqueue'
      setActionMsg({ kind: 'err', text: `${who}: ${text}` })
      setDdDialog({ userId: p.id, label: who, message: text })
    } finally {
      setTriggeringId(null)
    }
  }

  const openDeepDiveStatus = (p) => {
    setDdDialog({ userId: p.id, label: p.user_name || p.email, message: null })
  }

  const staleThresholdDays = 2
  const status = (p) => {
    if (!p.link_count || Number(p.link_count) === 0) return { label: 'No links', cls: 'bg-slate-100 text-slate-600' }
    if (p.last_deep_dive_status === 'FAILED') return { label: 'Failed', cls: 'bg-red-100 text-red-700' }
    const since = daysSince(p.last_deep_dive_at)
    if (since == null || since > staleThresholdDays) {
      return { label: 'Stale', cls: 'bg-amber-100 text-amber-700' }
    }
    return { label: 'Current', cls: 'bg-emerald-100 text-emerald-700' }
  }

  // How the most recent deep-dive of this player actually ended, not just when
  // it was attempted (a failing source keeps its link "verified" every run).
  const deepDive = (p) => {
    if (!p.last_deep_dive_at) {
      return { label: 'Never', cls: 'bg-slate-100 text-slate-500', when: null, title: 'No deep-dive recorded yet' }
    }
    const when = fmt(p.last_deep_dive_at)
    const src = p.last_deep_dive_source ? ` (${p.last_deep_dive_source})` : ''
    if (p.last_deep_dive_status === 'FAILED') {
      return {
        label: 'Failed',
        cls: 'bg-red-100 text-red-700',
        when,
        title: `Failed ${when}${src}: ${p.last_deep_dive_error || 'unknown error'}`,
      }
    }
    return { label: 'Success', cls: 'bg-emerald-100 text-emerald-700', when, title: `Succeeded ${when}${src}` }
  }

  // Is a deep-dive already queued/running for this login? The button then shows
  // the queue state and opens the status dialog instead of enqueuing a second
  // run (the backend refuses duplicates anyway).
  const requestState = (p) => {
    if (triggeringId === p.id) {
      return { active: false, label: 'Deep-dive', icon: <Loader2 className="w-3.5 h-3.5 animate-spin mr-1" /> }
    }
    if (p.deep_dive_request_status === 'RUNNING') {
      return { active: true, label: 'Running…', icon: <Loader2 className="w-3.5 h-3.5 animate-spin mr-1 text-sky-600" /> }
    }
    if (p.deep_dive_request_status === 'PENDING') {
      return { active: true, label: 'Queued…', icon: <Clock className="w-3.5 h-3.5 mr-1 text-amber-600" /> }
    }
    return { active: false, label: 'Deep-dive', icon: <Radar className="w-3.5 h-3.5 mr-1" /> }
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
              <CardDescription>Registration, last visit, and deep-dive freshness. <strong>Last deep-dive</strong> shows whether the most recent scrape of that player succeeded or failed (hover for the reason and the source). The <strong>Full deep-dive</strong> button enqueues an all-seasons backfill for that player (picked up by the Mac mini poller) and opens a status popup; while a refresh is queued or running the button reads <strong>Queued…</strong>/<strong>Running…</strong> and reopens that popup instead of enqueuing a second run.</CardDescription>
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
                      const dd = deepDive(p)
                      const ddr = requestState(p)
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
                          <td className="px-3 py-2 whitespace-nowrap">
                            <span className={`text-xs px-2 py-1 rounded-full ${dd.cls}`} title={dd.title}>{dd.label}</span>
                            {dd.when ? <span className="ml-2 text-xs text-slate-500">{dd.when}</span> : null}
                          </td>
                          <td className="px-3 py-2">
                            <span className={`text-xs px-2 py-1 rounded-full ${st.cls}`}>{st.label}</span>
                          </td>
                          <td className="px-3 py-2">
                            <Button
                              size="sm"
                              variant={ddr.active ? 'secondary' : 'outline'}
                              disabled={!hasLinks || triggeringId === p.id}
                              onClick={() => (ddr.active ? openDeepDiveStatus(p) : triggerDeepDive(p))}
                              title={
                                !hasLinks
                                  ? 'Player has no identity links'
                                  : ddr.active
                                    ? 'Already queued — click to see the status'
                                    : 'Enqueue full all-seasons deep-dive'
                              }
                            >
                              {ddr.icon}
                              {ddr.label}
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
              <CardTitle>Player ownership ({profiles.length})</CardTitle>
              <CardDescription>
                One owner account per player decides the profile, manual G/A entries and who else may
                edit. Duplicate rows usually mean the same kid was added twice. Transferring ownership
                attaches the account to the player automatically.
              </CardDescription>
            </CardHeader>
            <CardContent>
              {profilesError && (
                <div className="p-3 bg-red-100 border border-red-400 text-red-700 rounded-md mb-4 text-sm">
                  {profilesError}
                </div>
              )}
              <div className="overflow-x-auto">
                <table className="w-full text-sm border border-slate-200 rounded-lg overflow-hidden">
                  <thead className="bg-slate-100 text-slate-700">
                    <tr>
                      <th className="px-3 py-2 text-left">Player</th>
                      <th className="px-3 py-2 text-left">Born</th>
                      <th className="px-3 py-2 text-left">Owner</th>
                      <th className="px-3 py-2 text-left">Attached logins</th>
                      <th className="px-3 py-2 text-left">May edit</th>
                      <th className="px-3 py-2 text-left">Transfer to</th>
                      <th className="px-3 py-2 text-left">Merge duplicate into</th>
                    </tr>
                  </thead>
                  <tbody>
                    {profiles.map((p) => (
                      <tr key={p.id} className="border-t border-slate-200 hover:bg-slate-50">
                        <td className="px-3 py-2 font-medium">
                          {p.profile_name}
                          <span className="ml-1 text-xs text-slate-400">#{p.id}</span>
                        </td>
                        <td className="px-3 py-2 whitespace-nowrap">
                          {p.birthdate ? String(p.birthdate).slice(0, 10) : '—'}
                        </td>
                        <td className="px-3 py-2">
                          {p.owner_email || (
                            <span className="text-xs px-2 py-1 rounded-full bg-amber-100 text-amber-700">
                              unowned
                            </span>
                          )}
                        </td>
                        <td className="px-3 py-2 text-slate-600">{p.attached_logins || '—'}</td>
                        <td className="px-3 py-2 text-slate-600">{p.editors || '—'}</td>
                        <td className="px-3 py-2">
                          <div className="flex items-center gap-2">
                            <input
                              type="email"
                              placeholder="owner@example.com"
                              value={ownerInputs[p.id] || ''}
                              onChange={(e) =>
                                setOwnerInputs((prev) => ({ ...prev, [p.id]: e.target.value }))
                              }
                              className="border border-slate-300 rounded px-2 py-1 text-sm w-48"
                            />
                            <Button
                              size="sm"
                              variant="outline"
                              disabled={transferringId === p.id || !(ownerInputs[p.id] || '').trim()}
                              onClick={() => transferOwner(p)}
                            >
                              {transferringId === p.id ? 'Saving...' : 'Transfer'}
                            </Button>
                          </div>
                        </td>
                        <td className="px-3 py-2">
                          {mergeCandidates(p).length === 0 ? (
                            <span className="text-xs text-slate-400">—</span>
                          ) : (
                            <div className="flex items-center gap-2">
                              <select
                                value={mergeTargets[p.id] || ''}
                                onChange={(e) =>
                                  setMergeTargets((prev) => ({ ...prev, [p.id]: e.target.value }))
                                }
                                className="border border-slate-300 rounded px-2 py-1 text-sm"
                              >
                                <option value="">keep profile...</option>
                                {mergeCandidates(p).map((o) => (
                                  <option key={o.id} value={o.id}>
                                    #{o.id} {o.birthdate ? String(o.birthdate).slice(0, 10) : ''}{' '}
                                    {o.owner_email || 'unowned'}
                                  </option>
                                ))}
                              </select>
                              <Button
                                size="sm"
                                variant="outline"
                                disabled={mergingId === p.id || !mergeTargets[p.id]}
                                onClick={() => mergeProfile(p)}
                              >
                                {mergingId === p.id ? 'Merging...' : 'Merge'}
                              </Button>
                            </div>
                          )}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
              <p className="text-xs text-slate-500 mt-3">
                The email must belong to an existing account. Unowned profiles fall back to whoever
                created them. Merging is offered only between profiles that share the same player
                name; the row you pick in "keep profile" survives.
              </p>
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

      {isAdmin && ddDialog && (
        <DeepDiveStatusDialog
          userId={ddDialog.userId}
          label={ddDialog.label}
          message={ddDialog.message}
          onClose={() => {
            setDdDialog(null)
            load()
          }}
          onFinished={load}
        />
      )}
    </div>
  )
}
