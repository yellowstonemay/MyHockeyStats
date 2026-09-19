import React, { useState } from 'react'
import { useNavigate, Link } from 'react-router-dom'
import { useAuth } from '../lib/AuthContext'
import { Button } from '../components/Button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '../components/Card'
import { Input } from '../components/Input'
import SocialSignInButtons from '../components/SocialSignInButtons'
import { api } from '../lib/utils'
import { Trophy } from 'lucide-react'

export default function SignUp() {
  const navigate = useNavigate()
  const { login } = useAuth()
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [confirmPassword, setConfirmPassword] = useState('')
  const [error, setError] = useState('')
  const [loading, setLoading] = useState(false)
  // Set once the account is created and the confirmation email is on its way.
  const [sentTo, setSentTo] = useState('')
  const [resent, setResent] = useState(false)
  const [resending, setResending] = useState(false)

  const handleSignUp = async (e) => {
    e.preventDefault()
    setError('')

    if (!email || !password || !confirmPassword) {
      setError('Please fill in all fields')
      return
    }

    if (password !== confirmPassword) {
      setError('Passwords do not match')
      return
    }

    if (password.length < 8) {
      setError('Password must be at least 8 characters')
      return
    }

    setLoading(true)
    try {
      const response = await api.signup(email, password)
      if (response.verificationRequired) {
        // The address has to be confirmed before the account can sign in.
        setSentTo(email)
        return
      }
      // Verification disabled server-side — original behaviour.
      login(response.token, { email, hasPlayer: response.hasPlayer })
      navigate(response.hasPlayer ? '/dashboard' : '/players')
    } catch (err) {
      setError(err.message || 'Failed to create account')
    } finally {
      setLoading(false)
    }
  }

  const handleResend = async () => {
    setResending(true)
    setResent(false)
    try {
      await api.resendVerification(sentTo)
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
          <CardTitle>{sentTo ? 'Confirm your email' : 'Create Your Account'}</CardTitle>
          <CardDescription>
            {sentTo
              ? 'One more step before you can sign in'
              : 'Join MyHockeyStats and start tracking your performance'}
          </CardDescription>
        </CardHeader>

        {/* space-y-6 keeps the form, the "or" divider and the footer link evenly spaced. */}
        <CardContent className="space-y-6">
          {sentTo ? (
            <>
              <p className="text-sm text-slate-600">
                We sent a confirmation link to <strong>{sentTo}</strong>. Open it to activate your
                account, then sign in.
              </p>

              {resent && (
                <div className="p-3 bg-green-100 border border-green-400 text-green-700 rounded-md text-sm">
                  If that address has an unconfirmed account, a new link is on its way.
                </div>
              )}

              {error && (
                <div className="p-3 bg-red-100 border border-red-400 text-red-700 rounded-md text-sm">
                  {error}
                </div>
              )}

              <Button
                type="button"
                variant="outline"
                className="w-full"
                disabled={resending}
                onClick={handleResend}
              >
                {resending ? 'Sending…' : 'Resend the link'}
              </Button>

              <p className="text-center text-sm text-slate-600">
                Already confirmed?{' '}
                <Link to="/signin" className="text-primary-600 hover:underline font-medium">
                  Sign in
                </Link>
              </p>
            </>
          ) : (
            <>
              <form onSubmit={handleSignUp} className="space-y-4">
                {error && (
                  <div className="p-3 bg-red-100 border border-red-400 text-red-700 rounded-md text-sm">
                    {error}
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
                  <label className="label">Password</label>
                  <Input
                    type="password"
                    placeholder="••••••••"
                    value={password}
                    onChange={(e) => setPassword(e.target.value)}
                    disabled={loading}
                  />
                  <p className="text-xs text-slate-500 mt-1">At least 8 characters</p>
                </div>

                <div>
                  <label className="label">Confirm Password</label>
                  <Input
                    type="password"
                    placeholder="••••••••"
                    value={confirmPassword}
                    onChange={(e) => setConfirmPassword(e.target.value)}
                    disabled={loading}
                  />
                </div>

                <Button type="submit" className="w-full" disabled={loading}>
                  {loading ? 'Creating account...' : 'Create Account'}
                </Button>
              </form>

              {/* Divider + social sign-in sit AFTER the form: "or" separates the
                  two ways to create an account. */}
              <SocialSignInButtons disabled={loading} />

              <p className="text-center text-sm text-slate-600">
                Already have an account?{' '}
                <Link to="/signin" className="text-primary-600 hover:underline font-medium">
                  Sign In
                </Link>
              </p>
            </>
          )}
        </CardContent>
      </Card>
    </div>
  )
}
