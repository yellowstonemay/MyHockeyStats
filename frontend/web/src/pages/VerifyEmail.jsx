import React from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '../components/Card'
import { Trophy } from 'lucide-react'

const STATES = {
  ok: {
    title: 'Email confirmed',
    description: 'Your account is ready',
    body: 'Thanks — your email address is confirmed. You can sign in now.',
    tone: 'bg-green-100 border-green-400 text-green-700',
  },
  expired: {
    title: 'Link expired',
    description: 'That link is no longer valid',
    body: 'Confirmation links expire after a while. Sign in and we can send you a fresh one.',
    tone: 'bg-amber-100 border-amber-400 text-amber-800',
  },
  invalid: {
    title: 'Link not valid',
    description: 'We could not confirm this link',
    body: 'This confirmation link is invalid or has already been used. If your email is still unconfirmed, sign in to request a new link.',
    tone: 'bg-red-100 border-red-400 text-red-700',
  },
}

/**
 * Landing page for the link in the confirmation email. The backend consumes the
 * token and redirects here with ?status=ok|expired|invalid.
 */
export default function VerifyEmail() {
  const [searchParams] = useSearchParams()
  const status = searchParams.get('status') || 'invalid'
  const state = STATES[status] || STATES.invalid

  return (
    <div className="min-h-screen bg-gradient-to-b from-primary-50 to-slate-50 flex items-center justify-center py-12 px-4">
      <Card className="w-full max-w-md shadow-lg">
        <CardHeader className="text-center">
          <div className="flex justify-center mb-4">
            <Trophy className="w-10 h-10 text-primary-600" />
          </div>
          <CardTitle>{state.title}</CardTitle>
          <CardDescription>{state.description}</CardDescription>
        </CardHeader>
        <CardContent className="space-y-4">
          <div className={`p-3 border rounded-md text-sm ${state.tone}`}>{state.body}</div>
          <Link
            to="/signin"
            className="block text-center w-full text-primary-600 hover:underline font-medium"
          >
            Continue to sign in
          </Link>
        </CardContent>
      </Card>
    </div>
  )
}
