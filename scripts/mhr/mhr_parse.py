#!/usr/bin/env python3
"""Parsing helpers for myhockeyrankings.com (MHR) team pages.

MHR is a team-level source: ``team_info.php?y=<season>&t=<teamId>`` renders the
team's full schedule with results (no rosters and no per-player stats).  This
module turns that HTML into plain dicts so the scraping layer
(:mod:`mhr_client`) stays free of parsing logic and can be tested offline
against saved pages.

Marker legend from the MHR page (shown under the game list):

    *    league game
    **   tournament game
    ++   playoff game (league)      -> rendered as a double dagger
    ^    state tournament (provincials)
    ^^   district tournament
    +    national championship      -> rendered as a dagger

A game without a marker is a non-league (exhibition) game.
"""
from __future__ import annotations

import re
from datetime import date

from bs4 import BeautifulSoup

# Longest markers first: the page renders "**"/"^^" as single tokens.
MARKER_GAME_TYPES = [
    ("**", "Tournament Game"),
    ("^^", "District Tournament Game"),
    ("\u2021", "League Playoff Game"),    # double dagger
    ("\u2020", "National Championship"),  # dagger
    ("*", "League Game"),
    ("^", "State Tournament Game"),
]

NON_LEAGUE_GAME_TYPE = "Non-League Game"

LEAGUE_GAME_TYPES = {"League Game", "League Playoff Game"}

UNPLAYED_SCORE = 999

_MARKER_CHARS = "*^\u2020\u2021"
_RINK_RE = re.compile(r"rink-info")
_TEAM_RE = re.compile(r"team-info")
_GAME_RE = re.compile(r"game[-_]preview(?:\.php)?\?[^\"']*?g=(\d+)")
_TEAM_ID_RE = re.compile(r"team-info\?[^\"']*?t=(\d+)")
_DIVISION_RE = re.compile(r"division-info")
_SEASON_TOTALS_RE = re.compile(r"Season Totals for the (.+?)\s*$", re.MULTILINE)
_DATE_RE = re.compile(r"([A-Za-z]{3})\w*\s+(\d{1,2})")
_SCORE_RE = re.compile(r"(\d{1,3})\s*-\s*(\d{1,3})")

_MONTHS = {
    "jan": 1, "feb": 2, "mar": 3, "apr": 4, "may": 5, "jun": 6,
    "jul": 7, "aug": 8, "sep": 9, "oct": 10, "nov": 11, "dec": 12,
}


def season_label(season_year: int) -> str:
    return f"{season_year}-{season_year + 1} Season"


# -- team name helpers -------------------------------------------------------
# MHR team names look like "<club> <age>U <tier>" ("New Jersey Colonials 11U
# AAA").  The helpers below split a name into the parts the resolver compares
# (club / age group / tier) and are shared with the ingestion script's
# duplicate detection.

_AGE_TOKEN_RE = re.compile(r"\d{1,2}u")

# Tier words carry no club identity: "AAA" vs "AA" is the same club.
TIER_TOKENS = {
    "aaa", "aa", "a", "b", "c", "d", "premier", "elite", "national",
    "american", "major", "minor", "tier", "tier1", "tier2",
}

# Organization qualifiers that say nothing about which club it is.
GENERIC_CLUB_TOKENS = {
    "hockey", "club", "hc", "the", "youth", "selects", "academy", "hockeyclub",
    "association", "program",
}


def name_tokens(name: str) -> list[str]:
    return [tok for tok in re.split(r"[^a-z0-9]+", (name or "").lower()) if tok]


def age_group_tokens(name: str) -> set[str]:
    """Age group tokens in a name, e.g. ``{"11u"}`` for "... 11U AAA"."""
    return {tok for tok in name_tokens(name) if _AGE_TOKEN_RE.fullmatch(tok)}


def tier_tokens(name: str) -> set[str]:
    return {tok for tok in name_tokens(name) if tok in TIER_TOKENS}


def club_tokens(name: str) -> set[str]:
    """Distinctive club words: no age group, tier or generic qualifier."""
    return {
        tok for tok in name_tokens(name)
        if not _AGE_TOKEN_RE.fullmatch(tok)
        and tok not in TIER_TOKENS
        and tok not in GENERIC_CLUB_TOKENS
    }


def infer_season_date(season_year: int, month: int, day: int) -> date:
    """MHR game lists show "Sep 12" without a year.

    A season runs Aug -> Jul, so Aug-Dec belong to the season start year and
    Jan-Jul to the following calendar year.
    """
    year = season_year if month >= 8 else season_year + 1
    return date(year, month, day)


def game_type_for_marker(marker: str) -> str:
    if not marker:
        return NON_LEAGUE_GAME_TYPE
    for token, label in MARKER_GAME_TYPES:
        if marker.startswith(token):
            return label
    return NON_LEAGUE_GAME_TYPE


def is_league_game(game_type: str) -> bool:
    return game_type in LEAGUE_GAME_TYPES


def _classes(node) -> str:
    return " ".join(node.get("class") or [])


def _text(node) -> str:
    return re.sub(r"\s+", " ", node.get_text(" ", strip=True)).strip()


def _parse_date_text(season_year: int, text: str):
    match = _DATE_RE.search(text or "")
    if not match:
        return None
    month = _MONTHS.get(match.group(1).lower()[:3])
    if not month:
        return None
    return infer_season_date(season_year, month, int(match.group(2)))


def _id_from_href(node, pattern: re.Pattern):
    """Numeric id captured from ``node``'s href, or ``None``."""
    if node is None:
        return None
    match = pattern.search(node.get("href") or "")
    return match.group(1) if match else None


def _unique_game_id(game_id: str, seen: dict) -> str:
    """Suffix ``-2``, ``-3`` ... onto an id repeated within one page."""
    seen[game_id] = seen.get(game_id, 0) + 1
    count = seen[game_id]
    if count == 1:
        return game_id
    suffix = f"-{count}"
    return game_id[:64 - len(suffix)] + suffix


def _game_rows(scores_body, season_year: int):
    """Schedule rows: dated grid rows with a game link or an opponent link.

    Only unplayed games render a per-game link on current-season pages, so the
    opponent link is what identifies a played row.
    """
    rows = []
    for node in scores_body.find_all("div"):
        if "grid-cols-7" not in _classes(node):
            continue
        if not node.find("a", href=_GAME_RE) and not node.find("a", href=_TEAM_RE):
            continue
        cells = node.find_all("div", recursive=False)
        if _parse_date_text(season_year, _text(cells[0]) if cells else _text(node)) is None:
            continue
        rows.append(node)
    return rows


def _marker_for(row, opponent: str) -> str:
    span = row.select_one("span.game-marker")
    if span is not None:
        return span.get_text(strip=True)
    link = row.find("a", href=_TEAM_RE)
    if link is None:
        return ""
    cell = link.parent
    if cell is None:
        return ""
    text = " ".join(cell.stripped_strings)
    if opponent:
        text = text.split(opponent, 1)[-1]
    return "".join(ch for ch in text if ch in _MARKER_CHARS)


def parse_team_name(html: str, soup: BeautifulSoup | None = None) -> str | None:
    soup = soup or BeautifulSoup(html, "html.parser")
    node = soup.find(string=_SEASON_TOTALS_RE)
    if not node:
        return None
    return _text(node).split("Season Totals for the")[-1].strip()


def parse_division_name(soup: BeautifulSoup) -> str | None:
    link = soup.find("a", href=_DIVISION_RE)
    return _text(link) if link else None


def parse_team_page(html: str, season_year: int, team_id: str | int,
                    team_name: str | None = None) -> dict:
    """Parse a team schedule page into ``{team_name, division, games}``."""
    soup = BeautifulSoup(html, "html.parser")
    scores_body = soup.find(id="scores-body")
    if scores_body is None:
        raise ValueError("MHR team page has no scores section")

    resolved_name = team_name or parse_team_name(html, soup) or f"MHR team {team_id}"
    division = parse_division_name(soup)

    games = []
    seen_ids: dict = {}
    for row in _game_rows(scores_body, season_year):
        game_link = row.find("a", href=_GAME_RE)
        game_id = _id_from_href(game_link, _GAME_RE)

        cells = row.find_all("div", recursive=False)
        cell_text = _text(cells[0]) if cells else _text(row)
        game_date = _parse_date_text(season_year, cell_text)
        if game_date is None:
            continue

        opponent_link = row.find("a", href=_TEAM_RE)
        opponent = _text(opponent_link) if opponent_link else ""
        if game_id is None:
            opponent_team_id = _id_from_href(opponent_link, _TEAM_ID_RE)
            if not opponent_team_id:
                continue
            game_id = (f"syn-{season_year}-{game_date.month:02d}{game_date.day:02d}"
                       f"-t{opponent_team_id}")
        game_id = _unique_game_id(game_id, seen_ids)

        game_type = game_type_for_marker(_marker_for(row, opponent))

        result_node = row.find("strong")
        result = _text(result_node) if result_node else None
        if result not in ("W", "L", "T"):
            result = None

        score_text = _text(game_link) if game_link is not None else ""
        if not _SCORE_RE.search(score_text):
            score_text = (_text(cells[4]) if len(cells) > 4 else "") or _text(row)
        score_match = _SCORE_RE.search(score_text)
        score_for = score_against = UNPLAYED_SCORE
        if score_match:
            values = (int(score_match.group(1)), int(score_match.group(2)))
            if UNPLAYED_SCORE not in values:
                score_for, score_against = values

        rink_link = row.find("a", href=_RINK_RE)
        games.append({
            "game_id": game_id,
            "game_date": game_date,
            "game_type": game_type,
            "is_league": is_league_game(game_type),
            "played": score_for != UNPLAYED_SCORE,
            "result": result,
            "score_for": score_for,
            "score_against": score_against,
            "team_for": resolved_name,
            "team_against": opponent,
            "rink": _text(rink_link) if rink_link else None,
        })

    return {"team_name": resolved_name, "division": division, "games": games}
