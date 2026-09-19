import React, { useState } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import { Button } from '../components/Button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '../components/Card'
import { Input } from '../components/Input'
import { api } from '../lib/utils'
import { Trophy } from 'lucide-react'

const MIN_LENGTH = 8

/**
 * Landing page for the link in the reset email.
 *
 * The backend validates the token and redirects here with ?token=… (or
 * ?status=invalid when the link is dead). The token is only consumed when the
 * new password is submitted.
 */
export default function ResetPassword() {
  const [searchParams] = useSearchParams()
  const [token] = useState(() => searchParams.get('token') || '')
  const [password, setPassword] = useState('')
  const [confirmPassword, setConfirmPassword] = useState('')
  const [error, setError] = useState('')
  const [done, setDone] = useState(false)
  const [loading, setLoading] = useState(false)

  const linkIsBad = !token

  const handleSubmit = async (e) => {
    e.preventDefault()
    setError('')

    if (!password || !confirmPassword) {
      setError('Please fill in both fields')
      return
    }
    if (password.length < MIN_LENGTH) {
      setError(`Password must be at least ${MIN_LENGTH} characters`)
      return
    }
    if (password !== confirmPassword) {
      setError('Passwords do not match')
      return
    }

    setLoading(true)
    try {
      await api.resetPassword(token, password)
      setDone(true)
    } catch (err) {
      setError(err.message || 'Could not reset your password')
    } finally {
      setLoading(false)
    }
  }

  const title = done ? 'Password changed' : linkIsBad ? 'Link not valid' : 'Choose a new password'

  return (
    <div className="min-h-screen bg-gradient-to-b from-primary-50 to-slate-50 flex items-center justify-center py-12 px-4">
      <Card className="w-full max-w-md shadow-lg">
        <CardHeader className="text-center">
          <div className="flex justify-center mb-4">
            <Trophy className="w-10 h-10 text-primary-600" />
          </div>
          <CardTitle>{title}</CardTitle>
          <CardDescription>
            {done
              ? 'You can sign in with your new password'
              : linkIsBad
                ? 'That reset link is not valid'
                : 'Pick something you will remember'}
          </CardDescription>
        </CardHeader>

        <CardContent className="space-y-6">
          {done ? (
            <>
              <div className="p-3 bg-green-100 border border-green-400 text-green-700 rounded-md text-sm">
                Your password has been changed.
              </div>
              <Link
                to="/signin"
                className="block text-center text-primary-600 hover:underline font-medium"
              >
                Continue to sign in
              </Link>
            </>
          ) : linkIsBad ? (
            <>
              <div className="p-3 bg-red-100 border border-red-400 text-red-700 rounded-md text-sm">
                This reset link is invalid, expired, or has already been used.
              </div>
              <Link
                to="/forgot-password"
                className="block text-center text-primary-600 hover:underline font-medium"
              >
                Request a new link
              </Link>
            </>
          ) : (
            <>
              <form onSubmit={handleSubmit} className="space-y-4">
                {error && (
                  <div className="p-3 bg-red-100 border border-red-400 text-red-700 rounded-md text-sm">
                    {error}
                  </div>
                )}

                <div>
                  <label className="label">New password</label>
                  <Input
                    type="password"
                    placeholder="••••••••"
                    value={password}
                    onChange={(e) => setPassword(e.target.value)}
                    disabled={loading}
                  />
                  <p className="text-xs text-slate-500 mt-1">
                    At least {MIN_LENGTH} characters
                  </p>
                </div>

                <div>
                  <label className="label">Confirm new password</label>
                  <Input
                    type="password"
                    placeholder="••••••••"
                    value={confirmPassword}
                    onChange={(e) => setConfirmPassword(e.target.value)}
                    disabled={loading}
                  />
                </div>

                <Button type="submit" className="w-full" disabled={loading}>
                  {loading ? 'Saving…' : 'Set new password'}
                </Button>
              </form>

              <p className="text-center text-sm text-slate-600">
                <Link to="/signin" className="text-primary-600 hover:underline font-medium">
                  Back to sign in
                </Link>
              </p>
            </>
          )}
        </CardContent>
      </Card>
    </div>
  )
}
