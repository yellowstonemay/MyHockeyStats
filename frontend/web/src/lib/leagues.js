// Which league a scraped game belongs to, and how to label it.
//
// Every component that lists games (Overview, Recent Activity, My Leagues) shows
// a league badge, so the mapping and its rules live here instead of being copied
// into each one.

// Display order + badge colour for the leagues we track.
export const SOURCE_META = {
  AYHL: { label: 'AYHL', color: 'bg-indigo-100 text-indigo-700' },
  THF: { label: 'THF', color: 'bg-sky-100 text-sky-700' },
  AHF: { label: 'AHF', color: 'bg-emerald-100 text-emerald-700' },
  NJHS: { label: 'NJ HS', color: 'bg-amber-100 text-amber-700' },
  MHR: { label: 'MHR', note: 'non-league & tournaments', color: 'bg-rose-100 text-rose-700' },
}

const SOURCE_ORDER = ['AYHL', 'THF', 'AHF', 'NJHS', 'MHR']

export function sourceMeta(source) {
  return SOURCE_META[source] || { label: source, color: 'bg-slate-100 text-slate-600' }
}

export function sourceOrder(source) {
  const i = SOURCE_ORDER.indexOf(source)
  return i === -1 ? SOURCE_ORDER.length : i
}

// MHR is the only source that tracks whole TEAM schedules, and it stores the
// governing league as a prefix of the division ("AYHL 11U AAA", "THF 11U",
// "AYHL 15U AAA Premier"). That names the league a row belongs to — a New Jersey
// Colonials 11U AAA game is an AYHL game. A division that names no league we
// track keeps the source key, so the row stays in the MHR bucket.
const MHR_LEAGUE_PREFIXES = [
  { key: 'AYHL', pattern: /^ayhl\b/i },
  { key: 'THF', pattern: /^thf\b/i },
  { key: 'AHF', pattern: /^ahf\b/i },
  { key: 'NJHS', pattern: /^njhs\b|^nj hs\b/i },
]

export function leagueOfGame(game) {
  const source = game.source || 'Unknown'
  if (source !== 'MHR') return source
  const leagueText = String(game.league ?? '').trim()
  const match = MHR_LEAGUE_PREFIXES.find((entry) => entry.pattern.test(leagueText))
  return match ? match.key : source
}

// MHR marks every row: a League Game is played inside the division's league (the
// one named in the division text), while Non-League / Tournament games are the
// team's extras outside it. It is the only source that publishes both.
export function isLeaguePlay(gameType) {
  const text = String(gameType ?? '')
  return /league/i.test(text) && !/non[-\s]?league/i.test(text)
}

// The league a single game should be badged with. Only MHR mixes league and
// non-league games in one table, so only its rows are split by the game marker;
// AYHL/THF/AHF/NJHS list nothing but league play — and their rows often carry no
// game_type at all — so they keep their own badge.
export function leagueBadgeOfGame(game) {
  const source = game.source || 'Unknown'
  if (source !== 'MHR') return source
  const league = leagueOfGame(game)
  return league !== 'MHR' && isLeaguePlay(game.gameType) ? league : 'MHR'
}

// A career row reconciles games/goals/assists/points; when every one of them is
// zero the league simply published nothing, so the stats are unknown rather than
// a real 0 (a skeleton row for a season that has really started).
export function careerRowHasStats(record) {
  return (
    (record.gamesPlayed ?? 0) > 0 ||
    (record.goals ?? 0) > 0 ||
    (record.assists ?? 0) > 0 ||
    (record.points ?? 0) > 0
  )
}
