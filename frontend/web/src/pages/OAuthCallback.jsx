import React, { useEffect, useRef, useState } from 'react'
import { useNavigate, useSearchParams } from 'react-router-dom'
import { useAuth } from '../lib/AuthContext'
import { api } from '../lib/utils'
import { Button } from '../components/Button'
import { Card, CardContent, CardHeader, CardTitle } from '../components/Card'
import { Input } from '../components/Input'
import { Trophy } from 'lucide-react'

/**
 * Landing page for the backend OAuth redirect. Three cases:
 *
 *   ?code=...  — normal sign-in: the one-time code is swapped for a JWT.
 *   ?link=...  — the provider email already belongs to an email/password
 *                account, so we ask for that account's password before
 *                attaching the social login. Nothing is linked silently.
 *   ?error=... — the provider or backend rejected the sign-in.
 */
export default function OAuthCallback() {
  const [searchParams] = useSearchParams()
  const navigate = useNavigate()
  const { login } = useAuth()
  const [error, setError] = useState('')
  const [linkCode] = useState(() => searchParams.get('link'))
  const [email] = useState(() => searchParams.get('email') || '')
  const [password, setPassword] = useState('')
  const [linking, setLinking] = useState(false)
  // React 18 StrictMode runs effects twice in dev; the handoff code is
  // single-use, so make sure we only redeem it once.
  const exchanged = useRef(false)

  useEffect(() => {
    if (linkCode || exchanged.current) return
    exchanged.current = true

    const oauthError = searchParams.get('error')
    if (oauthError) {
      setError(oauthError)
      return
    }

    const code = searchParams.get('code')
    if (!code) {
      setError('Missing sign-in code. Please try signing in again.')
      return
    }

    api
      .exchangeOAuthCode(code)
      .then((resp) => {
        login(resp.token, { email: resp.email, isAdmin: resp.isAdmin, hasPlayer: resp.hasPlayer })
        // New accounts have no player yet — send them straight to add one.
        navigate(resp.hasPlayer ? '/dashboard' : '/players', { replace: true })
      })
      .catch((err) => setError(err.message || 'Sign-in failed'))
  }, [navigate, searchParams, login, linkCode])

  const handleConfirmLink = async (e) => {
    e.preventDefault()
    if (!password) {
      setError('Please enter your password')
      return
    }
    setError('')
    setLinking(true)
    try {
      const resp = await api.completeOAuthLink(linkCode, password)
      login(resp.token, { email: resp.email, isAdmin: resp.isAdmin, hasPlayer: resp.hasPlayer })
      navigate(resp.hasPlayer ? '/dashboard' : '/players', { replace: true })
    } catch (err) {
      setError(err.message || 'Could not link your account')
    } finally {
      setLinking(false)
    }
  }

  return (
    <div className="min-h-screen bg-gradient-to-b from-primary-50 to-slate-50 flex items-center justify-center py-12 px-4">
      <Card className="w-full max-w-md shadow-lg">
        <CardHeader className="text-center">
          <div className="flex justify-center mb-4">
            <Trophy className="w-10 h-10 text-primary-600" />
          </div>
          <CardTitle>
            {linkCode ? 'Link your account' : error ? 'Sign-in failed' : 'Signing you in…'}
          </CardTitle>
        </CardHeader>
        <CardContent className="space-y-4">
          {error && (
            <div className="p-3 bg-red-100 border border-red-400 text-red-700 rounded-md text-sm">
              {error}
            </div>
          )}

          {linkCode ? (
            <>
              <p className="text-sm text-slate-600">
                An account already exists{email ? <> for <strong>{email}</strong></> : null}. Enter
                its password to link this social sign-in to it. From then on you can use either
                method.
              </p>
              <form onSubmit={handleConfirmLink} className="space-y-4">
                <div>
                  <label className="label">Password</label>
                  <Input
                    type="password"
                    placeholder="••••••••"
                    value={password}
                    onChange={(e) => setPassword(e.target.value)}
                    disabled={linking}
                  />
                </div>
                <Button type="submit" className="w-full" disabled={linking}>
                  {linking ? 'Linking…' : 'Link and continue'}
                </Button>
              </form>
              <p className="text-center text-sm text-slate-600">
                Not your account?{' '}
                <a href="/signin" className="text-primary-600 hover:underline font-medium">
                  Sign in instead
                </a>
              </p>
            </>
          ) : error ? (
            <div className="text-center">
              <a href="/signin" className="text-primary-600 hover:underline font-medium">
                Back to sign in
              </a>
            </div>
          ) : (
            <div className="flex items-center justify-center">
              <div className="animate-spin rounded-full h-10 w-10 border-b-2 border-primary-600" />
            </div>
          )}
        </CardContent>
      </Card>
    </div>
  )
}
