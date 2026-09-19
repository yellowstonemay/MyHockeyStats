import React, { useState } from 'react'
import { Link } from 'react-router-dom'
import { Button } from '../components/Button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '../components/Card'
import { Input } from '../components/Input'
import { api } from '../lib/utils'
import { Trophy } from 'lucide-react'

/**
 * "Forgot password" — asks for an email and sends a reset link.
 *
 * The response is always the same, whether or not an account exists, so this
 * page cannot be used to discover which addresses are registered.
 */
export default function ForgotPassword() {
  const [email, setEmail] = useState('')
  const [sent, setSent] = useState(false)
  const [error, setError] = useState('')
  const [loading, setLoading] = useState(false)

  const handleSubmit = async (e) => {
    e.preventDefault()
    setError('')

    if (!email) {
      setError('Please enter your email address')
      return
    }

    setLoading(true)
    try {
      await api.forgotPassword(email)
      setSent(true)
    } catch (err) {
      setError(err.message || 'Could not send the reset email')
    } finally {
      setLoading(false)
    }
  }

  return (
    <div className="min-h-screen bg-gradient-to-b from-primary-50 to-slate-50 flex items-center justify-center py-12 px-4">
      <Card className="w-full max-w-md shadow-lg">
        <CardHeader className="text-center">
          <div className="flex justify-center mb-4">
            <Trophy className="w-10 h-10 text-primary-600" />
          </div>
          <CardTitle>{sent ? 'Check your email' : 'Reset your password'}</CardTitle>
          <CardDescription>
            {sent
              ? 'We may have sent you a reset link'
              : "Enter your email and we'll send you a link to choose a new password"}
          </CardDescription>
        </CardHeader>

        <CardContent className="space-y-6">
          {sent ? (
            <>
              <p className="text-sm text-slate-600">
                If <strong>{email}</strong> has an account, we've sent a link to reset its password.
                The link expires in an hour and can only be used once.
              </p>
              <p className="text-sm text-slate-500">
                Nothing arrived? Check your spam folder, or try again in a minute.
              </p>
              <p className="text-center text-sm text-slate-600">
                <Link to="/signin" className="text-primary-600 hover:underline font-medium">
                  Back to sign in
                </Link>
              </p>
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
                  <label className="label">Email</label>
                  <Input
                    type="email"
                    placeholder="your@email.com"
                    value={email}
                    onChange={(e) => setEmail(e.target.value)}
                    disabled={loading}
                  />
                </div>

                <Button type="submit" className="w-full" disabled={loading}>
                  {loading ? 'Sending…' : 'Send reset link'}
                </Button>
              </form>

              <p className="text-center text-sm text-slate-600">
                Remembered it?{' '}
                <Link to="/signin" className="text-primary-600 hover:underline font-medium">
                  Sign in
                </Link>
              </p>
            </>
          )}
        </CardContent>
      </Card>
    </div>
  )
}
