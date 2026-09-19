import React, { useEffect, useState } from 'react'
import { api } from '../lib/utils'
import { Button } from './Button'

const PROVIDERS = [
  { id: 'google', label: 'Google' },
  { id: 'facebook', label: 'Facebook' },
]

/**
 * "Continue with Google / Facebook" buttons.
 *
 * A provider is only rendered when the backend reports it as configured, so the
 * UI never offers a sign-in that cannot complete. Clicking navigates the whole
 * page to the backend, which runs the OAuth handshake and then returns the
 * browser to /oauth/callback.
 */
export default function SocialSignInButtons({ disabled = false }) {
  const [providers, setProviders] = useState({})

  useEffect(() => {
    let cancelled = false
    api
      .fetchOAuthProviders()
      .then((resp) => {
        if (!cancelled) setProviders(resp.providers || {})
      })
      .catch(() => {
        /* social sign-in is optional — stay silent if it is unavailable */
      })
    return () => {
      cancelled = true
    }
  }, [])

  const available = PROVIDERS.filter((p) => providers[p.id]?.enabled)
  if (available.length === 0) return null

  return (
    <div className="space-y-3">
      <div className="flex items-center gap-3">
        <div className="h-px flex-1 bg-slate-200" />
        <span className="text-xs font-medium uppercase tracking-wide text-slate-400">or</span>
        <div className="h-px flex-1 bg-slate-200" />
      </div>

      {available.map((provider) => (
        <Button
          key={provider.id}
          type="button"
          variant="outline"
          className="w-full"
          disabled={disabled}
          onClick={() => {
            window.location.href = api.oauthStartUrl(provider.id)
          }}
        >
          Continue with {provider.label}
        </Button>
      ))}
    </div>
  )
}
