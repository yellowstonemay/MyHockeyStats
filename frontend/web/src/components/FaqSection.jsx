import React, { useState } from 'react'
import { ChevronDown, Info } from 'lucide-react'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from './Card'

/**
 * Frequently asked questions for the player features.
 *
 * The answers describe what the app actually does: "Add a player" creates a
 * profile you own (editable, counted as "me"), "Follow a player" only tracks
 * somebody else's profile read-only, and a wrong number in a public game can be
 * corrected per game without touching the source site.
 */
const FAQ_ITEMS = [
  {
    question: 'How to add a player',
    answer: (
      <>
        Open <strong>My Players</strong> and use <strong>Add a player</strong> inside the
        &ldquo;Me or my kids&rdquo; card. Enter the player&apos;s full name and birth
        month/year (<span className="font-mono text-xs">MM/YYYY</span>) — the birth month
        is what tells apart two kids with the same name.
        <br />
        <br />
        Adding creates a profile <strong>you own</strong>, so you can edit the details and
        correct stats. The app then looks up the player&apos;s leagues (AYHL, THF, AHF,
        NJHS, MHR) and seasons automatically and pulls the games in. If nothing is found
        on the first try, choose the matching entry from the source results instead of
        adding a second profile with the same name.
      </>
    )
  },
  {
    question: 'How to follow a player',
    answer: (
      <>
        Use the <strong>Follow a player</strong> card, type the name, and pick the right
        person from the results — the league and age group (for example
        &ldquo;10U&rdquo; vs &ldquo;16U&rdquo;) distinguish players who share a name.
        <br />
        <br />
        The followed player then appears in your list with a <strong>Following</strong>{' '}
        badge and can be selected on the Dashboard and Report pages like any other
        player. You can stop following at any time with <strong>Unfollow</strong>.
      </>
    )
  },
  {
    question: 'What is the difference between adding a player and following a player?',
    answer: (
      <>
        <strong>Add a player</strong> creates a profile you own — yourself or your kid. It
        is editable: you can change the details, fix a game&apos;s G/A/PIM, refresh the
        stats, and it counts towards &ldquo;me&rdquo; in the activity feed.
        <br />
        <br />
        <strong>Follow a player</strong> only keeps track of someone else (a teammate, a
        cousin, an opponent). Anything you follow is <strong>read-only</strong>: you cannot
        edit the profile or its stats, and your follows never change that player for other
        users. In short: add = your player, follow = watch someone else&apos;s.
      </>
    )
  },
  {
    question: 'How to modify a game’s points if you believe the public data is incorrect',
    answer: (
      <>
        Every game row has a pencil (<strong>edit</strong>) button — in{' '}
        <strong>Recent Activity</strong> on the Dashboard and in{' '}
        <strong>Game history</strong> on the Report tab. Set the goals, assists and
        penalty minutes you believe are right and save.
        <br />
        <br />
        Your values are stored as a manual override next to the scraped ones, marked with
        a small <strong>manual</strong> badge, and they win wherever the game is shown —
        including the season totals. Use the <strong>clear</strong> button next to the
        badge to drop the override and fall back to the public numbers. Only the owner of
        the profile can change a game (an admin can do it for any player); following
        someone does not give you editing rights.
      </>
    )
  }
]

export function FaqSection() {
  const [openIndex, setOpenIndex] = useState(0)

  return (
    <Card id="faq">
      <CardHeader>
        <CardTitle>FAQ</CardTitle>
        <CardDescription>Adding, following and correcting player data</CardDescription>
      </CardHeader>
      <CardContent>
        <ul className="divide-y divide-slate-200 border border-slate-200 rounded-lg overflow-hidden">
          {FAQ_ITEMS.map((item, index) => {
            const open = openIndex === index
            return (
              <li key={item.question}>
                <button
                  type="button"
                  onClick={() => setOpenIndex(open ? -1 : index)}
                  aria-expanded={open}
                  className="w-full flex items-start justify-between gap-3 px-4 py-3 text-left hover:bg-slate-50"
                >
                  <span className="font-medium text-slate-900 text-sm">
                    {index + 1}. {item.question}
                  </span>
                  <ChevronDown
                    className={`w-4 h-4 mt-0.5 shrink-0 text-slate-400 transition-transform ${open ? 'rotate-180' : ''}`}
                  />
                </button>
                {open && (
                  <div className="px-4 pb-4 text-sm text-slate-600 leading-relaxed">
                    {item.answer}
                  </div>
                )}
              </li>
            )
          })}
        </ul>

        <div className="mt-4 flex items-start gap-2 rounded-lg border border-amber-200 bg-amber-50 px-3 py-2.5">
          <Info className="w-4 h-4 mt-0.5 shrink-0 text-amber-600" />
          <p className="text-sm text-amber-900">
            MyHockeyStats is built on publicly available league data. That data is not
            always complete or correct, so we cannot guarantee 100% accuracy on the points
            (goals, assists, PIM) or the records shown — use the per-game edit above when
            you know better.
          </p>
        </div>
      </CardContent>
    </Card>
  )
}

export default FaqSection
