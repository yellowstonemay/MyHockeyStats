import React, { useEffect, useState } from 'react'
import { GraduationCap, Send, Loader2, ShieldAlert } from 'lucide-react'
import { useAuth } from '../lib/AuthContext'
import { schoolApi } from '../lib/schoolApi'

// Quick questions - the same ones the CLI/web assistant offers.
const CHIPS = [
  { label: 'Grades', q: "How are Ethan's grades right now?" },
  { label: 'Coming tests', q: 'What tests or quizzes are coming up?' },
  { label: 'Due soon', q: 'What is due in the next 7 days?' },
  { label: 'Needs attention', q: 'What needs attention? Anything missing or weak?' },
  { label: 'Grade movement', q: 'Which grades moved since the start of the term?' },
]

/**
 * Admin-only page (/ethan): chat over Ethan's school data.
 * The data itself (grades/assignments) comes from a private scrape and is served by a
 * local-only assistant process; this page is only reachable by an admin login and the
 * backend refuses the API calls for everyone else.
 */
export default function SchoolAssistantPage() {
  const { user } = useAuth()
  const isAdmin = !!user?.isAdmin

  const [status, setStatus] = useState(null)
  const [statusError, setStatusError] = useState(null)
  const [messages, setMessages] = useState([])
  const [question, setQuestion] = useState('')
  const [busy, setBusy] = useState(false)

  useEffect(() => {
    if (!isAdmin) return
    schoolApi
      .status()
      .then(setStatus)
      .catch((e) => setStatusError(e.message || String(e)))
  }, [isAdmin])

  const ask = async (text) => {
    const q = (text || '').trim()
    if (!q || busy) return
    setBusy(true)
    setQuestion('')
    const entry = { question: q, pending: true }
    setMessages((list) => [entry, ...list])
    try {
      const r = await schoolApi.ask(q)
      setMessages((list) =>
        list.map((m) =>
          m === entry
            ? {
                question: q,
                answer: r.answer || r.error || '(no answer)',
                cost: r.cost_usd,
                tokens: r.usage && r.usage.total_tokens,
                model: r.model,
                day: r.meta && r.meta.day,
                failed: !r.answer,
              }
            : m
        )
      )
      if (r.meta) {
        setStatus((s) => ({
          ...(s || {}),
          day: r.meta.day,
          age_days: r.meta.age_days,
          spent_today_usd: r.spent_today_usd,
          daily_cap_usd: r.daily_cap_usd,
        }))
      }
    } catch (e) {
      setMessages((list) =>
        list.map((m) =>
          m === entry ? { question: q, answer: 'Request failed: ' + (e.message || e), failed: true } : m
        )
      )
    } finally {
      setBusy(false)
    }
  }

  if (!isAdmin) {
    return (
      <div className="container py-12">
        <div className="mx-auto max-w-xl rounded-lg border border-amber-200 bg-amber-50 p-6 flex items-start space-x-3">
          <ShieldAlert className="w-6 h-6 text-amber-600 mt-0.5" />
          <div>
            <h1 className="text-lg font-semibold text-amber-900">Admin access required</h1>
            <p className="text-amber-800 mt-1">This page is private. Sign in with an admin account to use it.</p>
          </div>
        </div>
      </div>
    )
  }

  return (
    <div className="container py-8">
      <div className="mx-auto max-w-3xl">
        <div className="flex items-center space-x-3">
          <GraduationCap className="w-7 h-7 text-primary-600" />
          <div>
            <h1 className="text-2xl font-bold text-slate-900">Ethan — school assistant</h1>
            <p className="text-sm text-slate-500">
              {status
                ? `data as of ${status.day}${status.age_days ? ` (${status.age_days} day(s) old)` : ' (today)'}` +
                  (status.spent_today_usd !== undefined
                    ? ` · $${Number(status.spent_today_usd).toFixed(4)} today of $${Number(status.daily_cap_usd ?? 0.5).toFixed(2)}`
                    : '')
                : statusError
                ? `assistant unavailable: ${statusError}`
                : 'loading…'}
            </p>
          </div>
        </div>

        <div className="mt-5 flex flex-wrap gap-2">
          {CHIPS.map((chip) => (
            <button
              key={chip.label}
              onClick={() => ask(chip.q)}
              disabled={busy}
              className="rounded-full border border-slate-300 bg-white px-3 py-1.5 text-sm text-slate-700 hover:bg-slate-50 disabled:opacity-50"
            >
              {chip.label}
            </button>
          ))}
        </div>

        <form
          className="mt-4 flex gap-2"
          onSubmit={(e) => {
            e.preventDefault()
            ask(question)
          }}
        >
          <input
            type="text"
            value={question}
            onChange={(e) => setQuestion(e.target.value)}
            placeholder="Ask about a grade, assignment or test…"
            className="flex-1 rounded-lg border border-slate-300 px-3 py-2.5 text-base focus:border-primary-500 focus:outline-none"
          />
          <button
            type="submit"
            disabled={busy || !question.trim()}
            className="inline-flex items-center space-x-2 rounded-lg bg-primary-600 px-4 py-2.5 text-white disabled:opacity-50"
          >
            {busy ? <Loader2 className="w-5 h-5 animate-spin" /> : <Send className="w-5 h-5" />}
            <span>Ask</span>
          </button>
        </form>

        <div className="mt-5 space-y-3">
          {messages.length === 0 && (
            <p className="text-sm text-slate-500">
              Answers come only from the scraped portal data (grades, assignments, due dates) — the
              assistant is told never to invent a score. Data refreshes with the daily scrape.
            </p>
          )}
          {messages.map((m, i) => (
            <div key={i} className={`rounded-lg border p-4 ${m.failed ? 'border-red-200 bg-red-50' : 'border-slate-200 bg-white'}`}>
              <div className="font-semibold text-slate-800">{m.question}</div>
              <div className="mt-2 whitespace-pre-wrap text-slate-700">
                {m.pending ? (
                  <span className="inline-flex items-center space-x-2 text-slate-500">
                    <Loader2 className="w-4 h-4 animate-spin" />
                    <span>thinking…</span>
                  </span>
                ) : (
                  m.answer
                )}
              </div>
              {!m.pending && (m.cost !== undefined || m.tokens || m.day) && (
                <div className="mt-2 text-xs text-slate-400">
                  {[
                    m.cost !== undefined ? `$${Number(m.cost).toFixed(4)}` : null,
                    m.tokens ? `${m.tokens} tokens` : null,
                    m.day ? `data ${m.day}` : null,
                  ]
                    .filter(Boolean)
                    .join(' · ')}
                </div>
              )}
            </div>
          ))}
        </div>
      </div>
    </div>
  )
}
