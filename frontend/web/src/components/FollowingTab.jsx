import React, { useEffect, useState } from 'react'
import { integrationsApi } from '../lib/integrationsApi'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from './Card'
import { Button } from './Button'
import { Input } from './Input'
import { Loader2, Search, UserPlus, Trash2, RefreshCw } from 'lucide-react'

const SOURCE_META = {
  AYHL: { label: 'AYHL', color: 'bg-indigo-100 text-indigo-700' },
  THF: { label: 'THF', color: 'bg-sky-100 text-sky-700' },
  AHF: { label: 'AHF', color: 'bg-emerald-100 text-emerald-700' },
  NJHS: { label: 'NJ HS', color: 'bg-amber-100 text-amber-700' },
}

export default function FollowingTab() {
  const [follows, setFollows] = useState([])
  const [maxFollows, setMaxFollows] = useState(20)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [msg, setMsg] = useState(null)

  const [query, setQuery] = useState('')
  const [searching, setSearching] = useState(false)
  const [candidates, setCandidates] = useState([])
  const [searched, setSearched] = useState(false)

  const load = async () => {
    setLoading(true)
    setError('')
    try {
      const resp = await integrationsApi.fetchFollows()
      setFollows(resp.follows || [])
      if (resp.maxFollows) setMaxFollows(resp.maxFollows)
    } catch (err) {
      setError(err.message || 'Failed to load follows')
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    load()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  const doSearch = async (e) => {
    if (e && e.preventDefault) e.preventDefault()
    const q = query.trim()
    if (q.length < 2) return
    setSearching(true)
    setSearched(true)
    setCandidates([])
    setMsg(null)
    try {
      const resp = await integrationsApi.searchFollows(q)
      setCandidates(resp.candidates || [])
    } catch (err) {
      setMsg({ kind: 'err', text: err.message || 'Search failed' })
    } finally {
      setSearching(false)
    }
  }

  const follow = async (cand) => {
    try {
      const resp = await integrationsApi.addFollow({
        source: cand.source,
        sourcePlayerId: cand.sourcePlayerId,
        playerName: cand.playerName,
      })
      setMsg({ kind: resp.created ? 'ok' : 'info', text: resp.message })
      setCandidates([])
      setSearched(false)
      setQuery('')
      await load()
    } catch (err) {
      setMsg({ kind: 'err', text: err.message || 'Failed to follow' })
    }
  }

  const unfollow = async (id, name) => {
    try {
      await integrationsApi.deleteFollow(id)
      setMsg({ kind: 'ok', text: `Unfollowed ${name}` })
      await load()
    } catch (err) {
      setMsg({ kind: 'err', text: err.message || 'Failed to unfollow' })
    }
  }

  const alreadyFollowed = new Set(follows.map((f) => `${f.source}:${f.sourcePlayerId}`))

  return (
    <div className="space-y-6">
      {/* Add by name */}
      <Card>
        <CardHeader>
          <CardTitle>Follow a player</CardTitle>
          <CardDescription>Search by name across AYHL, THF, AHF and NJ high school hockey</CardDescription>
        </CardHeader>
        <CardContent>
          <form onSubmit={doSearch} className="flex gap-2">
            <Input
              type="text"
              placeholder="Player name (e.g. Ethan Cai)"
              value={query}
              onChange={(e) => setQuery(e.target.value)}
              className="flex-1"
            />
            <Button type="submit" disabled={searching || query.trim().length < 2}>
              {searching ? <Loader2 className="w-4 h-4 animate-spin mr-2" /> : <Search className="w-4 h-4 mr-2" />}
              Search
            </Button>
          </form>

          {searched && !searching && candidates.length === 0 && (
            <p className="text-sm text-slate-500 mt-3">
              No matches found. Try a shorter name or a different spelling.
            </p>
          )}

          {candidates.length > 0 && (
            <div className="mt-3 divide-y divide-slate-100 border border-slate-200 rounded-lg">
              {candidates.map((c, i) => {
                const meta = SOURCE_META[c.source] || { label: c.source, color: 'bg-slate-100 text-slate-600' }
                const followed = alreadyFollowed.has(`${c.source}:${c.sourcePlayerId}`)
                const atCap = !followed && follows.length >= maxFollows
                return (
                  <div key={`${c.source}:${c.sourcePlayerId}`} className="flex items-center justify-between gap-3 px-3 py-2.5">
                    <div className="min-w-0">
                      <div className="flex items-center gap-2">
                        <span className={`text-xs font-semibold px-2 py-0.5 rounded-full ${meta.color}`}>{meta.label}</span>
                        <span className="font-medium text-slate-900 text-sm truncate">{c.playerName}</span>
                      </div>
                      <div className="text-xs text-slate-500 mt-0.5 truncate">
                        {c.teams || '—'} · {c.seasons} season{c.seasons === 1 ? '' : 's'}
                      </div>
                    </div>
                    <Button
                      size="sm"
                      variant={followed ? 'outline' : 'default'}
                      disabled={followed || atCap}
                      onClick={() => follow(c)}
                    >
                      <UserPlus className="w-4 h-4 mr-1.5" />
                      {followed ? 'Following' : 'Follow'}
                    </Button>
                  </div>
                )
              })}
              {follows.length >= maxFollows && (
                <p className="px-3 py-2 text-xs text-amber-600">
                  You've reached the {maxFollows}-player limit. Unfollow someone to follow more.
                </p>
              )}
            </div>
          )}
        </CardContent>
      </Card>

      {/* Followed list */}
      <Card>
        <CardHeader className="flex flex-col sm:flex-row sm:items-start sm:justify-between gap-2">
          <div>
            <CardTitle>Following ({follows.length}/{maxFollows})</CardTitle>
            <CardDescription>Recent performance for players you follow (refreshed by the daily deep-dive)</CardDescription>
          </div>
          <Button variant="outline" onClick={load}>
            <RefreshCw className="w-4 h-4 mr-2" /> Refresh
          </Button>
        </CardHeader>
        <CardContent>
          {msg && (
            <div
              className={`mb-4 p-3 border rounded-md text-sm ${
                msg.kind === 'err'
                  ? 'bg-red-100 border-red-400 text-red-700'
                  : msg.kind === 'info'
                    ? 'bg-sky-50 border-sky-300 text-sky-700'
                    : 'bg-emerald-50 border-emerald-300 text-emerald-700'
              }`}
            >
              {msg.text}
            </div>
          )}
          {error && <p className="text-sm text-red-700 mb-3">{error}</p>}
          {loading ? (
            <div className="flex items-center justify-center py-8 text-slate-500">
              <Loader2 className="w-4 h-4 animate-spin mr-2" /> Loading...
            </div>
          ) : follows.length === 0 ? (
            <p className="text-sm text-slate-500 text-center py-8">
              You're not following anyone yet. Search for a player above to get started.
            </p>
          ) : (
            <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
              {follows.map((f) => {
                const meta = SOURCE_META[f.source] || { label: f.source, color: 'bg-slate-100 text-slate-600' }
                const s = f.season || {}
                return (
                  <div key={f.id} className="border border-slate-200 rounded-lg p-4">
                    <div className="flex items-center justify-between gap-2">
                      <div className="flex items-center gap-2 min-w-0">
                        <span className={`text-xs font-semibold px-2 py-0.5 rounded-full ${meta.color}`}>{meta.label}</span>
                        <span className="font-medium text-slate-900 truncate">{f.playerName}</span>
                      </div>
                      <button
                        onClick={() => unfollow(f.id, f.playerName)}
                        className="text-slate-400 hover:text-red-600 transition-colors"
                        aria-label={`Unfollow ${f.playerName}`}
                        title="Unfollow"
                      >
                        <Trash2 className="w-4 h-4" />
                      </button>
                    </div>

                    {s.season ? (
                      <div className="mt-2 text-xs text-slate-600">
                        <div className="truncate">
                          {s.team ? `${s.team} · ` : ''}
                          {s.season}
                        </div>
                        <div className="mt-1 flex gap-4 text-slate-500">
                          <span>GP <b className="text-slate-900">{s.games ?? '—'}</b></span>
                          <span>G <b className="text-slate-900">{s.goals ?? '—'}</b></span>
                          <span>A <b className="text-slate-900">{s.assists ?? '—'}</b></span>
                          <span>PTS <b className="text-slate-900">{s.points ?? '—'}</b></span>
                          <span>PIM <b className="text-slate-900">{s.pim ?? '—'}</b></span>
                        </div>
                      </div>
                    ) : (
                      <p className="mt-2 text-xs text-slate-400">
                        No season stats yet — will appear after the next deep-dive.
                      </p>
                    )}

                    {f.recentGames && f.recentGames.length > 0 && (
                      <div className="mt-3">
                        <div className="text-[11px] uppercase tracking-wide text-slate-400 font-semibold">
                          Last {f.recentGames.length} games
                        </div>
                        <table className="w-full text-xs mt-1">
                          <tbody>
                            {f.recentGames.map((g, i) => (
                              <tr key={i} className="border-t border-slate-100">
                                <td className="py-1 pr-2 text-slate-500 whitespace-nowrap">{g.date || '—'}</td>
                                <td className="py-1 pr-2 text-slate-700 truncate">{g.opponent || '—'}</td>
                                <td className="py-1 text-slate-500 text-right whitespace-nowrap">
                                  {g.points != null ? `${g.goals ?? 0}G-${g.assists ?? 0}A (${g.points}P)` : '—'}
                                </td>
                              </tr>
                            ))}
                          </tbody>
                        </table>
                      </div>
                    )}
                  </div>
                )
              })}
            </div>
          )}
        </CardContent>
      </Card>
    </div>
  )
}
