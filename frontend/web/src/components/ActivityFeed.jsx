import React, { useEffect, useState } from 'react'
import { integrationsApi } from '../lib/integrationsApi'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from './Card'
import { Loader2, Pencil, RotateCcw, X } from 'lucide-react'
import { leagueBadgeOfGame, sourceMeta } from '../lib/leagues'

/** Stable key for one game: the (source, seasonYear, gameId) triple. */
function gameKey(g) {
  return `${g.source}:${g.seasonYear}:${g.gameId}`
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

// "HH:MM" (24h, MHR only) -> "5:20 PM" for display.
function formatTime(value) {
  if (!value) return ''
  const m = String(value).match(/^(\d{2}):(\d{2})$/)
  if (!m) return ''
  const hour = Number(m[1])
  const suffix = hour >= 12 ? 'PM' : 'AM'
  const h12 = hour % 12 === 0 ? 12 : hour % 12
  return `${h12}:${m[2]} ${suffix}`
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

// MHR tags every row: a League Game is played inside the division's league,
// while Non-League / Tournament games are the team's extras. It is the only
// source that publishes both, so only its rows carry this chip.
const MHR_GAME_TYPES = {
  'League Game': 'League',
  'Non-League Game': 'Non-League',
  'Tournament Game': 'Tournament',
}

function gameTypeLabel(gameType) {
  const value = String(gameType ?? '').trim()
  if (!value) return ''
  return MHR_GAME_TYPES[value] || value.replace(/\s*game$/i, '')
}

const COPY = {
  mine: {
    description: "Latest games for the player you're viewing",
    empty: 'No recent games yet. Stats refresh with the daily deep-dive.',
  },
  following: {
    description: 'Latest games for the players you follow',
    empty: 'No recent games for the players you follow yet.',
  },
  all: {
    description: 'Latest games for you and the players you follow',
    empty: 'No recent games yet. Stats refresh with the daily deep-dive.',
  },
}

export default function ActivityFeed({ playerId, scope = 'all', limit = 10, canEdit = false, onSaved }) {
  const [entries, setEntries] = useState([])
  const [asOf, setAsOf] = useState(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')

  // Same inline override Game History writes: G/A/PIM for one game.
  const [editingKey, setEditingKey] = useState(null)
  const [values, setValues] = useState({ goals: 0, assists: 0, pim: 0 })
  const [saving, setSaving] = useState(false)
  const [feedback, setFeedback] = useState('')

  const copy = COPY[scope] || COPY.all

  const load = async () => {
    setLoading(true)
    setError('')
    try {
      const resp = await integrationsApi.fetchActivity(limit, playerId, scope === 'all' ? undefined : scope)
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
  }, [playerId, scope, limit])

  const startEdit = (g) => {
    setFeedback('')
    setEditingKey(gameKey(g))
    setValues({ goals: g.goals ?? 0, assists: g.assists ?? 0, pim: g.pim ?? 0 })
  }

  const cancelEdit = () => {
    setEditingKey(null)
    setFeedback('')
  }

  // Drop the manual entry so the scraped value shows again — same as Game History.
  const resetStats = async (g) => {
    setSaving(true)
    setFeedback('')
    try {
      const resp = await integrationsApi.resetGameStats(playerId, {
        source: g.source,
        seasonYear: g.seasonYear,
        gameId: g.gameId,
      })
      setEditingKey(null)
      setFeedback(resp.message || 'Manual stats cleared.')
      await load()
      if (onSaved) onSaved()
    } catch (err) {
      setFeedback(err.message || 'Failed to clear the manual stats')
    } finally {
      setSaving(false)
    }
  }

  const saveEdit = async (g) => {
    setSaving(true)
    setFeedback('')
    try {
      await integrationsApi.saveGameStats(playerId, {
        source: g.source,
        seasonYear: g.seasonYear,
        gameId: g.gameId,
        goals: Number(values.goals ?? 0),
        assists: Number(values.assists ?? 0),
        pim: Number(values.pim ?? 0),
      })
      setEditingKey(null)
      setFeedback('Saved — these numbers now override the scraped ones.')
      await load()
      if (onSaved) onSaved()
    } catch (err) {
      setFeedback(err.message || 'Failed to save the game stats')
    } finally {
      setSaving(false)
    }
  }

  return (
    <Card>
      <CardHeader className="flex flex-col sm:flex-row sm:items-start sm:justify-between gap-1">
        <div>
          <CardTitle>Recent Activity</CardTitle>
          <CardDescription>{copy.description}</CardDescription>
        </div>
        <div className="text-xs text-slate-400 whitespace-nowrap">
          {asOf ? `Updated ${timeAgo(asOf)}` : 'Refreshed daily at 9:00 AM'}
        </div>
      </CardHeader>
      <CardContent>
        {error && <p className="text-sm text-red-700 mb-3">{error}</p>}
        {feedback && <p className="text-xs text-slate-500 mb-2">{feedback}</p>}
        {loading ? (
          <div className="flex items-center justify-center py-8 text-slate-500">
            <Loader2 className="w-4 h-4 animate-spin mr-2" /> Loading activity...
          </div>
        ) : entries.length === 0 ? (
          <p className="text-sm text-slate-500 text-center py-6">{copy.empty}</p>
        ) : (
          <ul className="divide-y divide-slate-100">
            {entries.map((g, i) => {
              // A game from a team schedule (MHR) is badged with the league that
              // runs it when the game itself is league play.
              const meta = sourceMeta(leagueBadgeOfGame(g))
              const h = highlightOf(g)
              const key = gameKey(g)
              // Only your own player's games can be overridden, and only when the
              // link grants edit rights (owners/admins/editors).
              const editable = canEdit && (scope === 'mine' || g.isMe) && g.gameId != null && g.seasonYear != null
              const editing = editingKey === key
              return (
                <li key={key} className="py-2.5">
                  <div className="flex items-center gap-3">
                  <span className={`w-2 h-2 rounded-full shrink-0 ${h.dot}`} />
                  <div className="min-w-0 flex-1">
                    <div className="flex items-center gap-2 min-w-0">
                      <span className={`text-[11px] font-semibold px-1.5 py-0.5 rounded-full shrink-0 ${meta.color}`}>
                        {meta.label}
                      </span>
                      {g.source === 'MHR' && gameTypeLabel(g.gameType) && (
                        <span className="text-[10px] font-medium px-1.5 py-0.5 rounded-full bg-slate-100 text-slate-600 shrink-0">
                          {gameTypeLabel(g.gameType)}
                        </span>
                      )}
                      {/* The "mine" feed (Overview) is already scoped to the player you
                          are viewing, so naming every row "You" says nothing. */}
                      {scope !== 'mine' && (
                        <>
                          <span className={`font-medium text-sm truncate ${g.isMe ? 'text-indigo-700' : 'text-slate-900'}`}>
                            {g.isMe ? 'You' : g.playerName}
                          </span>
                          {g.isMe && (
                            <span className="text-[10px] uppercase tracking-wide text-indigo-400 font-semibold shrink-0">you</span>
                          )}
                        </>
                      )}
                    </div>
                    <div className="text-xs text-slate-500 truncate">
                      {g.date || '—'}{formatTime(g.time) ? ` ${formatTime(g.time)}` : ''} · {g.opponent || g.teamFor || 'game'}
                    </div>
                  </div>
                  <div className="text-right shrink-0">
                    <div className={`text-xs font-semibold px-2 py-0.5 rounded-full ${h.color}`}>
                      {editing
                        ? `${Number(values.goals ?? 0)}G-${Number(values.assists ?? 0)}A (${Number(values.goals ?? 0) + Number(values.assists ?? 0)}P)`
                        : g.points != null
                          ? `${g.goals ?? 0}G-${g.assists ?? 0}A (${g.points}P)`
                          : '—'}
                    </div>
                    <div className="text-[10px] text-slate-400 mt-0.5 flex items-center justify-end gap-1">
                      {g.statsEdited && !editing && (
                        <>
                          <span className="px-1 py-0.5 rounded bg-amber-100 text-amber-800 border border-amber-200">
                            manual
                          </span>
                          <button
                            type="button"
                            onClick={() => resetStats(g)}
                            disabled={saving}
                            className="text-slate-400 hover:text-slate-700"
                            title="Use the scraped value again"
                            aria-label="Clear manual stats"
                          >
                            <RotateCcw className="w-3 h-3" />
                          </button>
                        </>
                      )}
                      {editable ? (
                        <button
                          type="button"
                          onClick={() => (editing ? cancelEdit() : startEdit(g))}
                          className="text-slate-400 hover:text-indigo-600"
                          title={editing ? 'Cancel' : 'Edit goals, assists and PIM'}
                          aria-label={editing ? 'Cancel edit' : 'Edit this game'}
                        >
                          {editing ? <X className="w-3 h-3" /> : <Pencil className="w-3 h-3" />}
                        </button>
                      ) : null}
                      <span>{h.label}</span>
                    </div>
                  </div>
                  </div>
                  {editing && (
                    <div className="mt-2 flex flex-wrap items-end gap-2 rounded-lg border border-slate-200 bg-slate-50 p-2">
                      <label className="text-[11px] text-slate-500">
                        <span className="block uppercase tracking-wide">G</span>
                        <input
                          type="number"
                          min="0"
                          value={values.goals}
                          onChange={(e) => setValues((v) => ({ ...v, goals: e.target.value }))}
                          className="mt-0.5 w-16 text-sm border border-slate-300 rounded px-2 py-1"
                        />
                      </label>
                      <label className="text-[11px] text-slate-500">
                        <span className="block uppercase tracking-wide">A</span>
                        <input
                          type="number"
                          min="0"
                          value={values.assists}
                          onChange={(e) => setValues((v) => ({ ...v, assists: e.target.value }))}
                          className="mt-0.5 w-16 text-sm border border-slate-300 rounded px-2 py-1"
                        />
                      </label>
                      <label className="text-[11px] text-slate-500">
                        <span className="block uppercase tracking-wide">PIM</span>
                        <input
                          type="number"
                          min="0"
                          value={values.pim}
                          onChange={(e) => setValues((v) => ({ ...v, pim: e.target.value }))}
                          className="mt-0.5 w-16 text-sm border border-slate-300 rounded px-2 py-1"
                        />
                      </label>
                      <button
                        type="button"
                        onClick={() => saveEdit(g)}
                        disabled={saving}
                        className="px-3 py-1.5 rounded-md bg-indigo-600 text-white text-xs font-medium hover:bg-indigo-700 disabled:opacity-60"
                      >
                        {saving ? 'Saving…' : 'Save'}
                      </button>
                      <button
                        type="button"
                        onClick={cancelEdit}
                        disabled={saving}
                        className="px-3 py-1.5 rounded-md border border-slate-300 text-xs font-medium text-slate-600 hover:bg-slate-100 disabled:opacity-60"
                      >
                        Cancel
                      </button>
                    </div>
                  )}
                </li>
              )
            })}
          </ul>
        )}
      </CardContent>
    </Card>
  )
}
