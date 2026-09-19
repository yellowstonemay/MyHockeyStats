import React, { useState } from 'react'
import { useNavigate, Link } from 'react-router-dom'
import { useAuth } from '../lib/AuthContext'
import { Button } from '../components/Button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '../components/Card'
import { Input } from '../components/Input'
import SocialSignInButtons from '../components/SocialSignInButtons'
import { api } from '../lib/utils'
import { Trophy } from 'lucide-react'

export default function SignIn() {
  const navigate = useNavigate()
  const { login } = useAuth()
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [error, setError] = useState('')
  const [loading, setLoading] = useState(false)
  // Set when the server says the address has not been confirmed yet.
  const [needsVerification, setNeedsVerification] = useState(false)
  const [resent, setResent] = useState(false)
  const [resending, setResending] = useState(false)

  const handleSignIn = async (e) => {
    e.preventDefault()
    setError('')
    setResent(false)

    if (!email || !password) {
      setError('Please fill in all fields')
      return
    }

    setLoading(true)
    try {
      const response = await api.login(email, password)
      login(response.accessToken || response.token, {
        email,
        isAdmin: !!response.isAdmin,
        hasPlayer: !!response.hasPlayer,
      })
      navigate(response.hasPlayer === false ? '/players' : '/dashboard')
    } catch (err) {
      setNeedsVerification(err.code === 'EMAIL_NOT_VERIFIED')
      setError(err.message || 'Failed to sign in')
    } finally {
      setLoading(false)
    }
  }

  const handleResend = async () => {
    setResending(true)
    setResent(false)
    try {
      await api.resendVerification(email)
      setResent(true)
    } catch (err) {
      setError(err.message || 'Could not resend the email')
    } finally {
      setResending(false)
    }
  }

  return (
    <div className="min-h-screen bg-gradient-to-b from-primary-50 to-slate-50 flex items-center justify-center py-12 px-4">
      <Card className="w-full max-w-md shadow-lg">
        <CardHeader className="text-center">
          <div className="flex justify-center mb-4">
            <Trophy className="w-10 h-10 text-primary-600" />
          </div>
          <CardTitle>Sign In</CardTitle>
          <CardDescription>Welcome back to MyHockeyStats</CardDescription>
        </CardHeader>
        <CardContent className="space-y-6">
          <form onSubmit={handleSignIn} className="space-y-4">
            {error && (
              <div className="p-3 bg-red-100 border border-red-400 text-red-700 rounded-md text-sm">
                {error}
              </div>
            )}

            {needsVerification && (
              <div className="space-y-2">
                {resent && (
                  <div className="p-3 bg-green-100 border border-green-400 text-green-700 rounded-md text-sm">
                    If that address has an unconfirmed account, a new link is on its way.
                  </div>
                )}
                <Button
                  type="button"
                  variant="outline"
                  className="w-full"
                  disabled={resending}
                  onClick={handleResend}
                >
                  {resending ? 'Sending…' : 'Resend confirmation email'}
                </Button>
              </div>
            )}

            <div>
              <label className="label">Email</label>
              <Input
                type="email"
                placeholder="your@email.com"
                value={email}
                onChange={(e) => setEmail(e.target.value)}
                disabled={loading}
              />
            </div>

            <div>
              <div className="flex items-center justify-between">
                <label className="label">Password</label>
                <Link
                  to="/forgot-password"
                  className="text-xs text-primary-600 hover:underline mb-1"
                >
                  Forgot password?
                </Link>
              </div>
              <Input
                type="password"
                placeholder="••••••••"
                value={password}
                onChange={(e) => setPassword(e.target.value)}
                disabled={loading}
              />
            </div>

            <Button
              type="submit"
              className="w-full"
              disabled={loading}
            >
              {loading ? 'Signing in...' : 'Sign In'}
            </Button>
          </form>

          {/* Divider + social sign-in sit AFTER the form: "or" separates the
              two ways to sign in. */}
          <SocialSignInButtons disabled={loading} />

          <p className="text-center text-sm text-slate-600">
            Don't have an account?{' '}
            <Link to="/signup" className="text-primary-600 hover:underline font-medium">
              Create one
            </Link>
          </p>
        </CardContent>
      </Card>
    </div>
  )
}
