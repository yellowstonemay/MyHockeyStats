import React, { useState, useEffect } from 'react'
import { useAuth } from '../lib/AuthContext'
import { integrationsApi } from '../lib/integrationsApi'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '../components/Card'
import { Button } from '../components/Button'
import { AlertCircle, Loader2, RefreshCw } from 'lucide-react'

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
              <CardDescription>Registration, last visit, and deep-dive freshness</CardDescription>
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
                    </tr>
                  </thead>
                  <tbody>
                    {players.map((p) => {
                      const st = status(p)
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
                        </tr>
                      )
                    })}
                  </tbody>
                </table>
              </div>
            </CardContent>
          </Card>
        )}
      </div>
    </div>
  )
}
