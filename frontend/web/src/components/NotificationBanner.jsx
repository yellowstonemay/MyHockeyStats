import React, { useState, useEffect, useCallback } from 'react'
import { useAuth } from '../lib/AuthContext'
import { integrationsApi } from '../lib/integrationsApi'
import { Loader2, X } from 'lucide-react'

/**
 * Global banner that shows any ACTIVE notification for the signed-in user
 * (e.g. "stats are being retrieved" while their deep-dive runs). Polls every
 * 30s so it disappears automatically once the poller resolves the notification.
 */
export default function NotificationBanner() {
  const { user, token } = useAuth()
  const [notifications, setNotifications] = useState([])

  const load = useCallback(async () => {
    if (!user || !token) {
      setNotifications([])
      return
    }
    try {
      const data = await integrationsApi.fetchNotifications()
      setNotifications(data.notifications || [])
    } catch {
      // Ignore transient errors; banner just won't update this cycle
    }
  }, [user, token])

  useEffect(() => {
    load()
    const interval = setInterval(load, 30000)
    return () => clearInterval(interval)
  }, [load])

  const dismiss = async (id) => {
    try {
      await integrationsApi.dismissNotification(id)
    } catch {
      // ignore
    }
    setNotifications((prev) => prev.filter((n) => n.id !== id))
  }

  if (!notifications.length) return null

  const n = notifications[0]
  return (
    <div className="w-full bg-indigo-600 text-white text-sm">
      <div className="container mx-auto px-4 py-2.5 flex items-center justify-between gap-3">
        <div className="flex items-center gap-2 min-w-0">
          <Loader2 className="w-4 h-4 animate-spin shrink-0" />
          <span className="truncate">{n.message || n.title || 'Your stats are being retrieved…'}</span>
        </div>
        <button
          onClick={() => dismiss(n.id)}
          className="shrink-0 opacity-80 hover:opacity-100 transition-opacity"
          aria-label="Dismiss"
        >
          <X className="w-4 h-4" />
        </button>
      </div>
    </div>
  )
}
