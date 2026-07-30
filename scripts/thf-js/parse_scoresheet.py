#!/usr/bin/env python3
"""parse_scoresheet.py

Parse a THF/AHF game scoresheet PDF and produce a structured JSON file with:
    - visitor_roster:  { jersey_no: {name, goals, assists, points, pims}, ... }
    - home_roster:     { jersey_no: {name, goals, assists, points, pims}, ... }

Usage:
    # Single file (JSON written next to PDF)
    python parse_scoresheet.py data/scoresheet/thf/2025/43061.pdf

    # Multiple files / glob
    python parse_scoresheet.py data/scoresheet/thf/2025/*.pdf
    python parse_scoresheet.py data/scoresheet/ahf/2025/*.pdf

    # Specify output directory
    python parse_scoresheet.py data/scoresheet/thf/2025/*.pdf --out-dir data/scoresheet/thf/2025/json

    # Print parsed JSON to stdout instead of writing a file
    python parse_scoresheet.py 43061.pdf --stdout

Requirements:
    pip install pdfplumber
"""

import sys
import json
import re
import glob
import argparse
from pathlib import Path

try:
    import pdfplumber
except ImportError:
    sys.exit("pdfplumber not installed.  Run: pip install pdfplumber")

# ── Column x-range constants (standard scoresheet template) ──────────────────
# Derived from the PDF word-position analysis.  All values are in PDF points.
# Visitor roster (left edge)
VIS_JERSEY_X = (15, 43)
VIS_POS_X    = (43, 63)
VIS_NAME_X   = (63, 148)

# Visitor scoring columns (centre-left)
VS_NO_X   = (145, 162)
VS_PER_X  = (186, 213)
VS_TIME_X = (213, 244)
VS_G_X    = (244, 265)   # goal scorer jersey
VS_A1_X   = (265, 286)   # assist 1 jersey
VS_A2_X   = (286, 308)   # assist 2 jersey

# Home scoring columns (centre-right)
HS_NO_X   = (305, 323)
HS_PER_X  = (347, 371)
HS_TIME_X = (371, 406)
HS_G_X    = (404, 427)   # goal scorer jersey
HS_A1_X   = (427, 448)   # assist 1 jersey
HS_A2_X   = (448, 465)   # assist 2 jersey

# Home roster (right edge)
HOME_JERSEY_X = (460, 485)
HOME_POS_X    = (483, 505)
HOME_NAME_X   = (502, 600)

# Penalty table columns
AWAY_PEN_PLAYER_X = (58, 75)
AWAY_PEN_MIN_X    = (205, 223)
HOME_PEN_PLAYER_X = (342, 360)
HOME_PEN_MIN_X    = (488, 506)

Y_TOL = 4   # point tolerance when matching words on the same line


# ── Low-level helpers ─────────────────────────────────────────────────────────

def _in_x(word, x_range):
    return x_range[0] <= word["x0"] < x_range[1]


def _in_y(word, y_min, y_max):
    return y_min <= word["top"] <= y_max


def _same_line(w1_top, w2_top):
    return abs(w1_top - w2_top) <= Y_TOL


def _is_number(text):
    return bool(re.match(r"^\d+$", text.strip()))


def _parse_numeric(text):
    """Parse integer or decimal numeric text (e.g., 2, 1.5)."""
    raw = str(text).strip()
    if not re.match(r"^\d+(?:\.\d+)?$", raw):
        return None
    try:
        return float(raw)
    except ValueError:
        return None


def _words_in_band(words, x_range, y_min=None, y_max=None):
    """Return words whose x0 falls inside x_range and (optionally) y range."""
    result = []
    for w in words:
        if not _in_x(w, x_range):
            continue
        if y_min is not None and w["top"] < y_min:
            continue
        if y_max is not None and w["top"] > y_max:
            continue
        result.append(w)
    return result


def _words_at_y(words, y_target, x_range=None):
    """All words on approximately the same line, optionally filtered by x."""
    result = []
    for w in words:
        if not _same_line(w["top"], y_target):
            continue
        if x_range and not _in_x(w, x_range):
            continue
        result.append(w)
    return sorted(result, key=lambda x: x["x0"])


def _find_anchor_y(words, text, x_range=None):
    """Return the top-y of the first word matching *text* (case-insensitive)."""
    for w in words:
        if w["text"].strip().lower() == text.lower():
            if x_range and not _in_x(w, x_range):
                continue
            return w["top"]
    return None


# ── Section parsers ───────────────────────────────────────────────────────────

def _parse_header(words):
    """Extract game_id (from filename), date, time, rink, game_number."""
    header = [w for w in words if w["top"] < 40]
    ordered = sorted(header, key=lambda x: x["x0"])

    game_date = game_time = am_pm = rink_str = game_no = None
    rink_parts = []
    after_rink = False
    after_time = False

    for w in ordered:
        t = w["text"]
        if re.match(r"\d{2}-\d{2}-\d{2}$", t):
            game_date = t
            after_time = False
        elif re.match(r"\d{2}:\d{2}$", t) and game_date:
            game_time = t
            after_time = True
        elif t in ("AM", "PM") and after_time:
            game_time = f"{game_time} {t}"
            after_time = False
        elif t == "Rink:":
            after_rink = True
        elif t == "Game":
            after_rink = False
        elif t == "#:":
            pass
        elif re.match(r"^\d{4,}$", t) and not game_no:
            # likely game number (5+ digits) – capture it
            game_no = t
        elif after_rink:
            rink_parts.append(t)

    return {
        "game_date": game_date,
        "game_time": game_time,
        "rink": " ".join(rink_parts),
        "game_number": game_no,
    }


def _parse_team_names(words):
    """Read 'Visitor Team: XXX  Home Team: YYY' row (y ≈ 50–70)."""
    row = sorted([w for w in words if 48 < w["top"] < 72], key=lambda x: x["x0"])

    visitor_parts = []
    home_parts = []

    for w in row:
        t = w["text"]
        x = w["x0"]
        # Skip label tokens
        if t in ("Visitor", "Home", "Team:"):
            continue
        if x > 100 and x < 340:
            visitor_parts.append(t)
        elif x > 415:
            home_parts.append(t)

    return " ".join(visitor_parts), " ".join(home_parts)


def _find_section_y_bounds(words):
    """
    Locate the y boundaries for the combined roster/scoring section.

    Returns (scoring_header_y, section_end_y) where:
      scoring_header_y  = y of the column-header row ("No S/P Per Time G A A …")
      section_end_y     = y of "Goaltender Records" heading (marks end of
                          the roster+scoring area).
    """
    # The visitor-side "S/P" header is the most reliable anchor
    scoring_header_y = None
    for w in words:
        if w["text"] == "S/P" and VS_NO_X[0] <= w["x0"] <= VS_A2_X[1]:
            scoring_header_y = w["top"]
            break
    if scoring_header_y is None:
        scoring_header_y = 125.0   # fallback

    goaltender_y = _find_anchor_y(words, "Goaltender")
    if goaltender_y is None:
        goaltender_y = 310.0       # fallback

    return scoring_header_y, goaltender_y


def _find_penalty_y_bounds(words):
    """
    Locate vertical bounds for penalties rows.

    Returns (penalties_start_y, penalties_end_y).
    """
    penalty_header_y = None
    for w in words:
        if w["text"].strip().lower() == "penalties" and w["x0"] < 260:
            penalty_header_y = w["top"]
            break

    if penalty_header_y is None:
        penalty_header_y = 540.0

    footer_y = None
    for w in words:
        # Bottom footer starts with "Game #..."
        if w["text"].strip().lower() == "game" and w["top"] > penalty_header_y:
            footer_y = w["top"]
            break

    if footer_y is None:
        footer_y = 780.0

    return penalty_header_y + 8, footer_y


def _parse_roster(words, jersey_x, name_x, y_min, y_max):
    """
    Build { jersey_no: player_name } for one side of the scoresheet.
    Iterates over jersey-number words and collects the name words on the same line.
    """
    jersey_words = _words_in_band(words, jersey_x, y_min, y_max)
    roster = {}

    for jw in jersey_words:
        if not _is_number(jw["text"]):
            continue
        jersey_no = jw["text"].strip()

        # Collect all name tokens on the same line, within the name x-range
        name_tokens = _words_at_y(words, jw["top"], x_range=name_x)
        name = " ".join(t["text"] for t in name_tokens).strip()

        if jersey_no and name:
            # If duplicate jersey numbers appear (e.g., roster re-listed), keep first
            roster.setdefault(jersey_no, name)

    return roster


def _parse_scoring(words, g_x, a1_x, a2_x, y_min, y_max):
    """
    Return a list of goal events for one side:
      [ { "goal": "24", "assists": ["53", "4"] }, ... ]

    A goal event is anchored on a word in the G column.  Assists are read
    from A1 and A2 columns at the same y.
    """
    g_words = _words_in_band(words, g_x, y_min, y_max)
    goals = []

    for gw in sorted(g_words, key=lambda x: x["top"]):
        if not _is_number(gw["text"]):
            continue
        goal_no = gw["text"].strip()
        y = gw["top"]

        assists = []
        for ax in (a1_x, a2_x):
            a_words = [w for w in words
                       if _in_x(w, ax) and _same_line(w["top"], y) and _is_number(w["text"])]
            assists.extend(w["text"].strip() for w in a_words)

        goals.append({"goal": goal_no, "assists": assists})

    return goals


def _parse_penalty_minutes(words, player_x, min_x, y_min, y_max):
    """
    Parse penalty rows and return jersey -> summed penalty minutes.

    Uses the side-specific player and minutes columns from the penalties table.
    """
    totals = {}
    min_words = _words_in_band(words, min_x, y_min, y_max)

    for mw in min_words:
        minutes = _parse_numeric(mw["text"])
        if minutes is None or minutes <= 0:
            continue

        player_words = [
            w for w in words
            if _in_x(w, player_x) and _same_line(w["top"], mw["top"]) and _is_number(w["text"])
        ]
        if not player_words:
            continue

        # There should be one player number per row; if multiple are found, sum to each.
        for pw in player_words:
            jersey = pw["text"].strip()
            totals[jersey] = totals.get(jersey, 0.0) + minutes

    return totals


def _build_scoring_totals(scoring_events):
    """Build per-player goals/assists totals from scoring events."""
    totals = {}

    for event in scoring_events:
        goal_no = str(event.get("goal", "")).strip()
        if goal_no:
            entry = totals.setdefault(goal_no, {"goals": 0, "assists": 0})
            entry["goals"] += 1

        assists = [str(a).strip() for a in event.get("assists", []) if str(a).strip()]
        for assist_no in assists:
            entry = totals.setdefault(assist_no, {"goals": 0, "assists": 0})
            entry["assists"] += 1

    return totals


def _merge_roster_with_totals(roster_map, scoring_events, penalty_minutes):
    """
    Convert roster map into stat-enriched entries:
    { "24": {"name": "Player", "goals": 1, "assists": 0, "points": 1, "pims": 2}, ... }
    """
    totals = _build_scoring_totals(scoring_events)
    enriched = {}

    for jersey_no, player_name in roster_map.items():
        player_totals = totals.get(jersey_no, {"goals": 0, "assists": 0})
        goals = int(player_totals["goals"])
        assists = int(player_totals["assists"])
        pims_raw = float(penalty_minutes.get(jersey_no, 0.0))
        if abs(pims_raw - round(pims_raw)) < 1e-9:
            pims = int(round(pims_raw))
        else:
            pims = round(pims_raw, 2)
        enriched[jersey_no] = {
            "name": player_name,
            "goals": goals,
            "assists": assists,
            "points": goals + assists,
            "pims": pims,
        }

    return enriched


# ── Main parser ───────────────────────────────────────────────────────────────

def parse_scoresheet(pdf_path: Path) -> dict:
    """Parse a single scoresheet PDF.  Returns a dict ready for JSON output."""
    with pdfplumber.open(str(pdf_path)) as pdf:
        # All scoresheets fit on one page; use first page only
        page = pdf.pages[0]
        words = page.extract_words(x_tolerance=3, y_tolerance=3)

    # ── Section anchors
    scoring_header_y, section_end_y = _find_section_y_bounds(words)
    penalties_y_min, penalties_y_max = _find_penalty_y_bounds(words)
    roster_y_min  = 80
    # Some scorecards place the last 1-2 roster lines just below the
    # "Goaltender Records" heading, so allow a small buffer.
    roster_y_max  = section_end_y + 24
    # Scoring data rows start a few points below the column header
    scoring_y_min = scoring_header_y + 5
    scoring_y_max = section_end_y

    # ── Parse each piece
    header           = _parse_header(words)
    visitor_team, home_team = _parse_team_names(words)
    visitor_roster   = _parse_roster(words, VIS_JERSEY_X, VIS_NAME_X,
                                     roster_y_min, roster_y_max)
    home_roster      = _parse_roster(words, HOME_JERSEY_X, HOME_NAME_X,
                                     roster_y_min, roster_y_max)
    visitor_scoring  = _parse_scoring(words, VS_G_X, VS_A1_X, VS_A2_X,
                                      scoring_y_min, scoring_y_max)
    home_scoring     = _parse_scoring(words, HS_G_X, HS_A1_X, HS_A2_X,
                                      scoring_y_min, scoring_y_max)
    visitor_penalty_minutes = _parse_penalty_minutes(
        words,
        AWAY_PEN_PLAYER_X,
        AWAY_PEN_MIN_X,
        penalties_y_min,
        penalties_y_max,
    )
    home_penalty_minutes = _parse_penalty_minutes(
        words,
        HOME_PEN_PLAYER_X,
        HOME_PEN_MIN_X,
        penalties_y_min,
        penalties_y_max,
    )

    visitor_roster_with_stats = _merge_roster_with_totals(
        visitor_roster,
        visitor_scoring,
        visitor_penalty_minutes,
    )
    home_roster_with_stats = _merge_roster_with_totals(
        home_roster,
        home_scoring,
        home_penalty_minutes,
    )

    return {
        "game_id":        pdf_path.stem,
        "game_number":    header["game_number"],
        "game_date":      header["game_date"],
        "game_time":      header["game_time"],
        "rink":           header["rink"],
        "visitor_team":   visitor_team,
        "home_team":      home_team,
        "visitor_roster": visitor_roster_with_stats,
        "home_roster":    home_roster_with_stats,
    }


# ── CLI ───────────────────────────────────────────────────────────────────────

def main():
    ap = argparse.ArgumentParser(
        description="Parse THF/AHF scoresheet PDF(s) into JSON"
    )
    ap.add_argument(
        "inputs", nargs="+",
        help="PDF file path(s).  Glob patterns are expanded automatically."
    )
    ap.add_argument(
        "--out-dir", default=None,
        help="Directory to write JSON files (default: same directory as each PDF)."
    )
    ap.add_argument(
        "--stdout", action="store_true",
        help="Print JSON to stdout instead of writing files (single PDF only)."
    )
    args = ap.parse_args()

    # Expand globs
    pdf_paths = []
    for pat in args.inputs:
        expanded = sorted(glob.glob(pat))
        if expanded:
            pdf_paths.extend(Path(p) for p in expanded)
        else:
            pdf_paths.append(Path(pat))

    if args.stdout and len(pdf_paths) != 1:
        sys.exit("--stdout requires exactly one input PDF.")

    ok = 0
    errors = 0
    for pdf_path in pdf_paths:
        if not pdf_path.exists():
            print(f"WARN  not found: {pdf_path}", file=sys.stderr)
            errors += 1
            continue
        if pdf_path.suffix.lower() != ".pdf":
            continue

        try:
            data = parse_scoresheet(pdf_path)
        except Exception as exc:
            print(f"ERROR {pdf_path.name}: {exc}", file=sys.stderr)
            errors += 1
            continue

        if args.stdout:
            print(json.dumps(data, indent=2, ensure_ascii=False))
            return

        # Determine output path
        if args.out_dir:
            out_path = Path(args.out_dir) / f"{pdf_path.stem}.json"
        else:
            out_path = pdf_path.with_suffix(".json")

        out_path.parent.mkdir(parents=True, exist_ok=True)
        with open(out_path, "w", encoding="utf-8") as f:
            json.dump(data, f, indent=2, ensure_ascii=False)

        v_goals = sum(player["goals"] for player in data["visitor_roster"].values())
        h_goals = sum(player["goals"] for player in data["home_roster"].values())
        print(
            f"OK    {pdf_path.name}"
            f"  →  {out_path.name}"
            f"  [{data['visitor_team']} {v_goals}G"
            f"  vs  {data['home_team']} {h_goals}G"
            f"  | v-roster {len(data['visitor_roster'])}"
            f"  h-roster {len(data['home_roster'])}]"
        )
        ok += 1

    if len(pdf_paths) > 1:
        print(f"\nDone: {ok} parsed, {errors} error(s).")


if __name__ == "__main__":
    main()
