import React, { useCallback, useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { integrationsApi } from '../lib/integrationsApi'
import { Button } from '../components/Button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '../components/Card'
import { Input } from '../components/Input'
import { ArrowLeft, Plus, RefreshCw, Star, Trash2 } from 'lucide-react'

const BIRTH_MONTH_YEAR_REGEX = /^(0[1-9]|1[0-2])\/\d{4}$/

const POSITIONS = [
  { value: '', label: 'Select a position' },
  { value: 'forward', label: 'Forward' },
  { value: 'defense', label: 'Defense' },
  { value: 'goaltender', label: 'Goaltender' },
]

const RELATIONS = [
  { value: 'SELF', label: 'This is me' },
  { value: 'PARENT', label: 'My child' },
  { value: 'GUARDIAN', label: 'My player' },
  { value: 'FAN', label: 'Following' },
]

const emptyForm = { fullName: '', birthMonthYear: '', location: '', position: '', relation: 'SELF' }

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
      await load()
      flash(resp.message || 'Player added.')
    } catch (err) {
      setError(err.message || 'Failed to add player')
    } finally {
      setAdding(false)
    }
  }

  const startEdit = (player) => {
    setEditingId(player.id)
    setEditForm({
      fullName: player.fullName || '',
      birthMonthYear: player.birthMonthYear || '',
      location: player.location || '',
      position: player.position || '',
      relation: player.relation || 'PARENT',
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

        {/* ── Existing players ─────────────────────────────────────────── */}
        <Card className="mb-6">
          <CardHeader>
            <CardTitle>Your players</CardTitle>
            <CardDescription>
              The default player is shown on your dashboard. Each player has one owner account;
              other accounts attached to it are read-only until the owner grants them edit access.
            </CardDescription>
          </CardHeader>
          <CardContent>
            {loading ? (
              <p className="text-slate-500 text-sm">Loading…</p>
            ) : players.length === 0 ? (
              <p className="text-slate-500 text-sm">
                No players yet. Add one below and we'll pull in their stats.
              </p>
            ) : (
              <ul className="divide-y divide-slate-200">
                {players.map((player) => (
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
                          <p className="text-xs text-slate-500 mt-1">
                            Owner:{' '}
                            <span className="font-medium text-slate-700">
                              {player.ownerEmail || 'not set'}
                            </span>
                            {player.canEdit ? (
                              <span className="ml-2 px-1.5 py-0.5 rounded bg-green-100 text-green-800 border border-green-200">
                                you can edit
                              </span>
                            ) : (
                              <span className="ml-2 px-1.5 py-0.5 rounded bg-slate-100 text-slate-600 border border-slate-200">
                                read-only
                              </span>
                            )}
                          </p>
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
                            disabled={!player.canEdit}
                            title={player.canEdit ? undefined : 'Only the owner can change this profile'}
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
          </CardContent>
        </Card>

        {/* ── Add a new player ─────────────────────────────────────────── */}
        <Card className="mb-6">
          <CardHeader>
            <CardTitle className="flex items-center gap-2">
              <Plus className="w-5 h-5" /> Add a player
            </CardTitle>
            <CardDescription>
              We'll look up this player's leagues and seasons automatically.
            </CardDescription>
          </CardHeader>
          <CardContent>
            <form onSubmit={handleAdd} className="space-y-4">
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
                <div>
                  <label className="label">Who is this?</label>
                  <select
                    className="input appearance-none"
                    value={addForm.relation}
                    onChange={(e) => setAddForm({ ...addForm, relation: e.target.value })}
                    disabled={adding}
                  >
                    {RELATIONS.map((r) => (
                      <option key={r.value} value={r.value}>
                        {r.label}
                      </option>
                    ))}
                  </select>
                </div>
              </div>
              <Button type="submit" disabled={adding}>
                {adding ? 'Adding…' : 'Add player'}
              </Button>
            </form>
          </CardContent>
        </Card>

      </div>
    </div>
  )
}
