import React, { useCallback, useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { integrationsApi } from '../lib/integrationsApi'
import { Button } from '../components/Button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '../components/Card'
import { Input } from '../components/Input'
import { ArrowLeft, Loader2, Lock, Pencil, RefreshCw, Search, Star, Trash2, UserPlus } from 'lucide-react'

const BIRTH_MONTH_YEAR_REGEX = /^(0[1-9]|1[0-2])\/\d{4}$/

const POSITIONS = [
  { value: '', label: 'Select a position' },
  { value: 'forward', label: 'Forward' },
  { value: 'defense', label: 'Defense' },
  { value: 'goaltender', label: 'Goaltender' },
]

// The relation picker is gone: "Me or my kid" is the manual Add-a-player
// form, and "Following" is its own search-and-add section (the follows table).
// These values still arrive on legacy profile links.
const MY_RELATIONS = ['SELF', 'PARENT', 'GUARDIAN']

/** A FAN link means the login only follows this player. */
function isFollowingPlayer(player) {
  return String(player?.relation || '').toUpperCase() === 'FAN'
}

/** Small pill used to mark a whole section as editable or view-only. */
function CardBadge({ icon: Icon, tone = 'slate', children }) {
  const tones = {
    slate: 'bg-slate-100 text-slate-600 border-slate-200',
    green: 'bg-green-100 text-green-800 border-green-200',
    indigo: 'bg-indigo-100 text-indigo-800 border-indigo-200',
  }
  return (
    <span
      className={`inline-flex items-center gap-1 px-2 py-0.5 rounded-full border text-xs font-medium ${tones[tone] || tones.slate}`}
    >
      {Icon && <Icon className="w-3 h-3" />}
      {children}
    </span>
  )
}

const emptyForm = { fullName: '', birthMonthYear: '', location: '', position: '', relation: 'PARENT' }

const SOURCE_META = {
  AYHL: { label: 'AYHL', color: 'bg-indigo-100 text-indigo-700' },
  THF: { label: 'THF', color: 'bg-sky-100 text-sky-700' },
  AHF: { label: 'AHF', color: 'bg-emerald-100 text-emerald-700' },
  NJHS: { label: 'NJ HS', color: 'bg-amber-100 text-amber-700' },
  EP: { label: 'Elite Prospects', color: 'bg-purple-100 text-purple-700' },
}

/**
 * Player management.
 *
 * One login can manage several players (siblings, multiple kids) and the same
 * player can be added to several logins (two parents, player + guardian).
 */
export default function Players() {
  const navigate = useNavigate()
  const [players, setPlayers] = useState([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [notice, setNotice] = useState('')
  const [busyId, setBusyId] = useState(null)

  const [addForm, setAddForm] = useState(emptyForm)
  const [adding, setAdding] = useState(false)
  const [showAddForm, setShowAddForm] = useState(false)

  // Following: search across AYHL / THF / AHF / NJ HS and add with no other input.
  const [query, setQuery] = useState('')
  const [searching, setSearching] = useState(false)
  const [candidates, setCandidates] = useState([])
  const [searched, setSearched] = useState(false)
  const [follows, setFollows] = useState([])
  const [maxFollows, setMaxFollows] = useState(20)
  const [followsLoading, setFollowsLoading] = useState(true)
  const [followBusyKey, setFollowBusyKey] = useState('')

  const [editingId, setEditingId] = useState(null)
  const [editForm, setEditForm] = useState(emptyForm)
  const [savingEdit, setSavingEdit] = useState(false)

  const load = useCallback(async () => {
    setLoading(true)
    setError('')
    try {
      const resp = await integrationsApi.fetchMyPlayers()
      setPlayers(resp.players || [])
    } catch (err) {
      setError(err.message || 'Failed to load your players')
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => {
    load()
  }, [load])

  const loadFollows = useCallback(async () => {
    setFollowsLoading(true)
    try {
      const resp = await integrationsApi.fetchFollows()
      setFollows(resp.follows || [])
      if (resp.maxFollows) setMaxFollows(resp.maxFollows)
    } catch (err) {
      setError(err.message || 'Failed to load who you follow')
    } finally {
      setFollowsLoading(false)
    }
  }, [])

  useEffect(() => {
    loadFollows()
  }, [loadFollows])

  const flash = (message) => {
    setNotice(message)
    setTimeout(() => setNotice(''), 4000)
  }

  const validate = (form) => {
    if (!form.fullName?.trim()) throw new Error('Player name is required')
    if (!form.birthMonthYear?.trim()) throw new Error('Birth month/year is required (MM/YYYY)')
    if (!BIRTH_MONTH_YEAR_REGEX.test(form.birthMonthYear)) {
      throw new Error('Birth month/year must be in MM/YYYY format (example: 05/2011)')
    }
  }

  const handleAdd = async (e) => {
    e.preventDefault()
    setError('')
    try {
      validate(addForm)
    } catch (err) {
      setError(err.message)
      return
    }
    setAdding(true)
    try {
      const resp = await integrationsApi.createPlayer(addForm)
      setAddForm(emptyForm)
      setShowAddForm(false)
      await load()
      flash(resp.message || 'Player added.')
    } catch (err) {
      setError(err.message || 'Failed to add player')
    } finally {
      setAdding(false)
    }
  }

  const handleSearch = async (e) => {
    e.preventDefault()
    const q = query.trim()
    if (q.length < 2) return
    setSearching(true)
    setSearched(true)
    setCandidates([])
    setError('')
    try {
      const resp = await integrationsApi.searchFollows(q)
      setCandidates(resp.candidates || [])
    } catch (err) {
      setError(err.message || 'Search failed')
    } finally {
      setSearching(false)
    }
  }

  const handleFollow = async (candidate) => {
    const key = `${candidate.source}:${candidate.sourcePlayerId}`
    setFollowBusyKey(key)
    setError('')
    try {
      const resp = await integrationsApi.addFollow({
        source: candidate.source,
        sourcePlayerId: candidate.sourcePlayerId,
        playerName: candidate.playerName,
      })
      flash(resp.message || 'Following')
      setCandidates([])
      setSearched(false)
      setQuery('')
      await loadFollows()
    } catch (err) {
      setError(err.message || 'Failed to follow')
    } finally {
      setFollowBusyKey('')
    }
  }

  const handleUnfollow = async (follow) => {
    const key = `${follow.source}:${follow.sourcePlayerId}`
    setFollowBusyKey(key)
    setError('')
    try {
      await integrationsApi.deleteFollow(follow.id)
      flash(`Unfollowed ${follow.playerName || follow.sourcePlayerId}`)
      await loadFollows()
    } catch (err) {
      setError(err.message || 'Failed to unfollow')
    } finally {
      setFollowBusyKey('')
    }
  }

  const followedKeys = new Set(follows.map((f) => `${f.source}:${f.sourcePlayerId}`))

  // Two categories, not one mixed list: players you manage (editable) and the
  // players you follow (read-only views of someone else's player). Followed
  // players also become selectable on the dashboard; here they stay in the
  // Following list, which is the only place they can be removed from.
  const myPlayers = players.filter((player) => !isFollowingPlayer(player))

  /**
   * Editing is allowed only for players this login manages. A followed player
   * is a read-only view no matter what permissions the login has: the server
   * lets admins edit anything, but "following" means look, don't touch.
   */
  const canManage = (player) => Boolean(player?.canEdit) && !isFollowingPlayer(player)

  const startEdit = (player) => {
    setEditingId(player.id)
    setEditForm({
      fullName: player.fullName || '',
      birthMonthYear: player.birthMonthYear || '',
      location: player.location || '',
      position: player.position || '',
      relation: 'PARENT',
    })
  }

  const handleSaveEdit = async (playerId) => {
    setError('')
    try {
      validate(editForm)
    } catch (err) {
      setError(err.message)
      return
    }
    setSavingEdit(true)
    try {
      await integrationsApi.updatePlayer(playerId, editForm)
      setEditingId(null)
      await load()
      flash('Player updated.')
    } catch (err) {
      setError(err.message || 'Failed to update player')
    } finally {
      setSavingEdit(false)
    }
  }

  const handleSetPrimary = async (playerId) => {
    setBusyId(playerId)
    setError('')
    try {
      await integrationsApi.setPrimaryPlayer(playerId)
      await load()
      flash('Default player updated.')
    } catch (err) {
      setError(err.message || 'Failed to set default player')
    } finally {
      setBusyId(null)
    }
  }

  const handleRefresh = async (playerId) => {
    setBusyId(playerId)
    setError('')
    try {
      const resp = await integrationsApi.requestPlayerDeepDive(playerId)
      flash(resp.message || 'Refreshing stats…')
    } catch (err) {
      setError(err.message || 'Failed to request a refresh')
    } finally {
      setBusyId(null)
    }
  }

  const handleUnlink = async (player) => {
    if (!window.confirm(`Remove ${player.fullName} from your account?`)) return
    setBusyId(player.id)
    setError('')
    try {
      await integrationsApi.unlinkPlayer(player.id)
      await load()
      flash('Player removed from your account.')
    } catch (err) {
      setError(err.message || 'Failed to remove player')
    } finally {
      setBusyId(null)
    }
  }

  return (
    <div className="min-h-screen bg-slate-50">
      <div className="container py-12 max-w-4xl">
        <div className="flex items-center mb-6">
          <button
            onClick={() => navigate('/dashboard')}
            className="flex items-center space-x-2 text-primary-600 hover:text-primary-700 transition-colors"
          >
            <ArrowLeft className="w-5 h-5" />
            <span>Back to Dashboard</span>
          </button>
        </div>

        <h1 className="text-2xl font-bold text-slate-900 mb-1">My Players</h1>
        <p className="text-slate-600 mb-6">
          Manage each player profile and follow their stats from one place. Several accounts
          can share the same player.
        </p>

        {notice && (
          <div className="p-3 bg-green-100 border border-green-400 text-green-700 rounded-md mb-4 text-sm">
            {notice}
          </div>
        )}
        {error && (
          <div className="p-3 bg-red-100 border border-red-400 text-red-700 rounded-md mb-4 text-sm">
            {error}
          </div>
        )}

        {/* ── Existing players ──────────────────────────────────────── */}
        <Card className="mb-6">
          <CardHeader>
            <CardTitle className="flex items-center gap-2">
              <Pencil className="w-5 h-5" /> Me or my kids
              <CardBadge icon={Pencil} tone="green">editable</CardBadge>
            </CardTitle>
            <CardDescription>
              The default player is shown on your dashboard. Each player has one owner account;
              other accounts attached to it are read-only until the owner grants them edit access.
            </CardDescription>
          </CardHeader>
          <CardContent>
            {loading ? (
              <p className="text-slate-500 text-sm">Loading…</p>
            ) : myPlayers.length === 0 ? (
              <p className="text-slate-500 text-sm">
                No players yet. Add one below and we&apos;ll pull in their stats.
              </p>
            ) : (
              <ul className="divide-y divide-slate-200">
                {myPlayers.map((player) => (
                  <li key={player.id} className="py-4">
                    {editingId === player.id ? (
                      <div className="space-y-3">
                        <div className="grid sm:grid-cols-2 gap-3">
                          <div>
                            <label className="label">Full Name</label>
                            <Input
                              value={editForm.fullName}
                              onChange={(e) => setEditForm({ ...editForm, fullName: e.target.value })}
                              disabled={savingEdit}
                            />
                          </div>
                          <div>
                            <label className="label">Birth Month/Year</label>
                            <Input
                              placeholder="MM/YYYY"
                              value={editForm.birthMonthYear}
                              onChange={(e) =>
                                setEditForm({ ...editForm, birthMonthYear: e.target.value })
                              }
                              disabled={savingEdit}
                            />
                          </div>
                          <div>
                            <label className="label">Location</label>
                            <Input
                              placeholder="City, State"
                              value={editForm.location}
                              onChange={(e) => setEditForm({ ...editForm, location: e.target.value })}
                              disabled={savingEdit}
                            />
                          </div>
                          <div>
                            <label className="label">Position</label>
                            <select
                              className="input appearance-none"
                              value={editForm.position}
                              onChange={(e) => setEditForm({ ...editForm, position: e.target.value })}
                              disabled={savingEdit}
                            >
                              {POSITIONS.map((p) => (
                                <option key={p.value} value={p.value}>
                                  {p.label}
                                </option>
                              ))}
                            </select>
                          </div>
                        </div>
                        <div className="flex gap-3">
                          <Button onClick={() => handleSaveEdit(player.id)} disabled={savingEdit}>
                            {savingEdit ? 'Saving…' : 'Save'}
                          </Button>
                          <Button variant="outline" onClick={() => setEditingId(null)}>
                            Cancel
                          </Button>
                        </div>
                      </div>
                    ) : (
                      <div className="flex flex-wrap items-center justify-between gap-3">
                        <div>
                          <div className="flex items-center gap-2">
                            <span className="font-medium text-slate-900">{player.fullName}</span>
                            {player.isPrimary && (
                              <span className="inline-flex items-center gap-1 px-2 py-0.5 rounded-full bg-primary-50 text-primary-700 text-xs font-medium">
                                <Star className="w-3 h-3" /> Default
                              </span>
                            )}
                          </div>
                          <p className="text-sm text-slate-500">
                            {player.birthMonthYear || '—'}
                            {player.position ? ` · ${player.position}` : ''}
                            {player.location ? ` · ${player.location}` : ''}
                          </p>
                          {/* Owner = whose account the profile belongs to.
                              "you can edit" = whether THIS login may change it
                              (owner, granted editor, or admin). They are
                              different questions: an admin can edit a player
                              somebody else owns. */}
                          <p className="text-xs text-slate-500 mt-1 flex flex-wrap items-center gap-2">
                            {isFollowingPlayer(player) && (
                              <span className="inline-flex items-center gap-1 px-1.5 py-0.5 rounded bg-indigo-100 text-indigo-800 border border-indigo-200">
                                Following
                              </span>
                            )}
                            <span>
                              Owner:{' '}
                              <span className="font-medium text-slate-700">
                                {player.ownerEmail || 'nobody (tracked player)'}
                              </span>
                            </span>
                            {isFollowingPlayer(player) ? (
                              <span className="px-1.5 py-0.5 rounded bg-slate-100 text-slate-600 border border-slate-200">
                                read-only
                              </span>
                            ) : player.canEdit ? (
                              <span className="px-1.5 py-0.5 rounded bg-green-100 text-green-800 border border-green-200">
                                you can edit
                              </span>
                            ) : (
                              <span className="px-1.5 py-0.5 rounded bg-slate-100 text-slate-600 border border-slate-200">
                                read-only
                              </span>
                            )}
                          </p>
                          {/* Several logins may manage the same kid — show who,
                              so nobody assumes the profile is theirs alone. */}
                          {Array.isArray(player.sharedWith) && player.sharedWith.length > 1 && (
                            <p className="text-xs text-slate-500 mt-1 flex flex-wrap items-center gap-1">
                              <span className="text-slate-400">Shared with:</span>
                              {player.sharedWith
                                .filter((login) => !login.isYou)
                                .map((login) => (
                                  <span
                                    key={login.email}
                                    className={`inline-flex items-center gap-1 px-1.5 py-0.5 rounded border ${
                                      login.isOwner
                                        ? 'bg-emerald-50 text-emerald-800 border-emerald-200'
                                        : login.canEdit
                                          ? 'bg-sky-50 text-sky-800 border-sky-200'
                                          : 'bg-slate-50 text-slate-600 border-slate-200'
                                    }`}
                                    title={`relation: ${login.relation || 'unknown'}`}
                                  >
                                    {login.email}
                                    <span className="opacity-70">
                                      · {login.isOwner ? 'owner' : login.canEdit ? 'can edit' : 'read-only'}
                                    </span>
                                  </span>
                                ))}
                            </p>
                          )}
                        </div>
                        <div className="flex flex-wrap gap-2">
                          {!player.isPrimary && (
                            <Button
                              variant="secondary"
                              size="sm"
                              disabled={busyId === player.id}
                              onClick={() => handleSetPrimary(player.id)}
                            >
                              <Star className="w-4 h-4 mr-1" /> Set default
                            </Button>
                          )}
                          <Button
                            variant="secondary"
                            size="sm"
                            disabled={!canManage(player)}
                            title={canManage(player) ? undefined : 'Only the owner can change this profile'}
                            onClick={() => startEdit(player)}
                          >
                            Edit
                          </Button>
                          <Button
                            variant="secondary"
                            size="sm"
                            disabled={busyId === player.id}
                            onClick={() => handleRefresh(player.id)}
                          >
                            <RefreshCw className="w-4 h-4 mr-1" /> Refresh stats
                          </Button>
                          <Button
                            variant="outline"
                            size="sm"
                            disabled={busyId === player.id}
                            onClick={() => handleUnlink(player)}
                          >
                            <Trash2 className="w-4 h-4 mr-1" /> Remove
                          </Button>
                        </div>
                      </div>
                    )}
                  </li>
                ))}
              </ul>
            )}

            {/* Add a player lives in this card too, so "Me or my kids" and
                "Following" each own their list plus the way to add to it. */}
            <div className="mt-5 border-t border-slate-200 pt-4">
              {!showAddForm ? (
                <Button variant="secondary" onClick={() => setShowAddForm(true)}>
                  <UserPlus className="w-4 h-4 mr-2" /> Add a player
                </Button>
              ) : (
                <form onSubmit={handleAdd} className="space-y-4">
                  <p className="text-sm text-slate-600">
                    Creates a profile you own, so you can edit stats and details — use this for
                    yourself or your kid. We&apos;ll look up their leagues and seasons automatically.
                  </p>
                  <div className="grid sm:grid-cols-2 gap-4">
                    <div>
                      <label className="label">Player Full Name</label>
                      <Input
                        placeholder="Ethan Cai"
                        value={addForm.fullName}
                        onChange={(e) => setAddForm({ ...addForm, fullName: e.target.value })}
                        disabled={adding}
                      />
                    </div>
                    <div>
                      <label className="label">Birth Month/Year</label>
                      <Input
                        placeholder="MM/YYYY (example: 05/2011)"
                        value={addForm.birthMonthYear}
                        onChange={(e) => setAddForm({ ...addForm, birthMonthYear: e.target.value })}
                        disabled={adding}
                      />
                    </div>
                    <div>
                      <label className="label">Location</label>
                      <Input
                        placeholder="City, State"
                        value={addForm.location}
                        onChange={(e) => setAddForm({ ...addForm, location: e.target.value })}
                        disabled={adding}
                      />
                    </div>
                    <div>
                      <label className="label">Position</label>
                      <select
                        className="input appearance-none"
                        value={addForm.position}
                        onChange={(e) => setAddForm({ ...addForm, position: e.target.value })}
                        disabled={adding}
                      >
                        {POSITIONS.map((p) => (
                          <option key={p.value} value={p.value}>
                            {p.label}
                          </option>
                        ))}
                      </select>
                    </div>
                  </div>
                  <div className="flex gap-2">
                    <Button type="submit" disabled={adding}>
                      {adding ? 'Adding…' : 'Add player'}
                    </Button>
                    <Button
                      type="button"
                      variant="outline"
                      disabled={adding}
                      onClick={() => {
                        setShowAddForm(false)
                        setAddForm(emptyForm)
                      }}
                    >
                      Cancel
                    </Button>
                  </div>
                </form>
              )}
            </div>
          </CardContent>
        </Card>

        {/* ── Follow a player — search and add, nothing else ────────────── */}
        <Card className="mb-6">
          <CardHeader>
            <CardTitle className="flex items-center gap-2">
              <UserPlus className="w-5 h-5" /> Following ({follows.length}/{maxFollows})
              <CardBadge icon={Lock} tone="indigo">read-only</CardBadge>
            </CardTitle>
            <CardDescription>
              Search by name across AYHL, THF, AHF and NJ high school hockey, then hit Follow —
              nothing else to fill in. Followed players are view-only and show up in your player
              selector.
            </CardDescription>
          </CardHeader>
          <CardContent>
            <form onSubmit={handleSearch} className="flex flex-col sm:flex-row gap-2">
              <Input
                type="text"
                placeholder="Player name (e.g. Bryan Yang)"
                value={query}
                onChange={(e) => setQuery(e.target.value)}
                className="flex-1"
              />
              <Button type="submit" variant="secondary" disabled={searching || query.trim().length < 2}>
                {searching ? (
                  <Loader2 className="w-4 h-4 animate-spin mr-2" />
                ) : (
                  <Search className="w-4 h-4 mr-2" />
                )}
                Search
              </Button>
            </form>

            {searched && !searching && candidates.length === 0 && (
              <p className="text-sm text-slate-500 mt-3">
                No AYHL, THF, AHF or NJ HS match for that name. Try a shorter name or a different
                spelling.
              </p>
            )}

            {candidates.length > 0 && (
              <div className="mt-3 divide-y divide-slate-100 border border-slate-200 rounded-lg">
                {candidates.map((c) => {
                  const meta = SOURCE_META[c.source] || {
                    label: c.source,
                    color: 'bg-slate-100 text-slate-600',
                  }
                  const key = `${c.source}:${c.sourcePlayerId}`
                  const already = followedKeys.has(key)
                  const atCap = !already && follows.length >= maxFollows
                  return (
                    <div key={key} className="flex items-center justify-between gap-3 px-3 py-2.5">
                      <div className="min-w-0">
                        <div className="flex items-center gap-2">
                          <span className={`text-xs font-semibold px-2 py-0.5 rounded-full ${meta.color}`}>
                            {meta.label}
                          </span>
                          <span className="font-medium text-slate-900 text-sm truncate">
                            {c.playerName}
                          </span>
                        </div>
                        <div className="text-xs text-slate-500 mt-0.5 truncate">
                          {c.teams || '—'} · {c.seasons} season{c.seasons === 1 ? '' : 's'}
                        </div>
                      </div>
                      <Button
                        size="sm"
                        variant={already ? 'outline' : 'default'}
                        disabled={already || atCap || followBusyKey === key}
                        onClick={() => handleFollow(c)}
                      >
                        {followBusyKey === key ? (
                          <Loader2 className="w-4 h-4 animate-spin mr-1.5" />
                        ) : (
                          <UserPlus className="w-4 h-4 mr-1.5" />
                        )}
                        {already ? 'Following' : 'Follow'}
                      </Button>
                    </div>
                  )
                })}
              </div>
            )}

            {follows.length >= maxFollows && (
              <p className="text-xs text-amber-600 mt-2">
                You&apos;ve reached the {maxFollows}-player limit. Unfollow someone to follow more.
              </p>
            )}

            {/* Who you follow */}
            <div className="mt-5 border-t border-slate-200 pt-4">
              <div className="flex items-center justify-end mb-2">
                <button
                  type="button"
                  onClick={loadFollows}
                  className="text-xs text-primary-600 hover:underline inline-flex items-center gap-1"
                >
                  <RefreshCw className="w-3 h-3" /> Refresh
                </button>
              </div>
              {followsLoading ? (
                <p className="text-sm text-slate-500">Loading…</p>
              ) : follows.length === 0 ? (
                <p className="text-sm text-slate-500">You&apos;re not following anyone yet.</p>
              ) : (
                <ul className="divide-y divide-slate-200 border border-slate-200 rounded-lg">
                  {follows.map((f) => {
                    const meta = SOURCE_META[f.source] || {
                      label: f.source,
                      color: 'bg-slate-100 text-slate-600',
                    }
                    const key = `${f.source}:${f.sourcePlayerId}`
                    return (
                      <li key={f.id} className="flex items-center justify-between gap-3 px-3 py-2.5">
                        <div className="min-w-0 flex items-center gap-2">
                          <span className={`text-xs font-semibold px-2 py-0.5 rounded-full ${meta.color}`}>
                            {meta.label}
                          </span>
                          <span className="font-medium text-slate-900 text-sm truncate">
                            {f.playerName || f.sourcePlayerId}
                          </span>
                        </div>
                        <Button
                          variant="outline"
                          size="sm"
                          disabled={followBusyKey === key}
                          onClick={() => handleUnfollow(f)}
                        >
                          <Trash2 className="w-4 h-4 mr-1" /> Unfollow
                        </Button>
                      </li>
                    )
                  })}
                </ul>
              )}
            </div>
          </CardContent>
        </Card>

      </div>
    </div>
  )
}
