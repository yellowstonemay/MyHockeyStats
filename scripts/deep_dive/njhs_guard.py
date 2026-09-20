"""Guard for the NJ.com high school hockey deep-dive.

Only scrape/link players who are high-school age AND live in New Jersey, so a
registered user with the same name as an NJ HS player in another state/age
group does not get incorrectly linked.

`player_profiles.location` is free text (users type a town, a club name, or
nothing at all), so a bare town such as "BASKING RIDGE" carries no state token.
NJ is therefore confirmed from any available evidence:

  1. An explicit "New Jersey" / "N.J." / "NJ" token or an NJ ZIP in a location
     string. The strings considered are the profile's own `location` plus any
     AYHL roster hometowns we hold for the player, which do carry the state
     ("Basking Ridge, NJ").
  2. A town name that our own AYHL hometown data places in NJ. This catches
     bare-town profile values. It can also match a same-named town in another
     state (e.g. Bridgewater, MA), which is acceptable for a heuristic gate.

The scrapers stay authoritative: this only decides whether a link is plausible.
"""
from __future__ import annotations

import re
from datetime import date

# High-school hockey in NJ runs grades 9-12 (freshman ~13-14 .. senior ~18-19).
MIN_AGE = 13
MAX_AGE = 19

_NJ_ZIP = re.compile(r"\b(07|08|09)\d{3}\b")
_NJ_STATE_WORDS = {"nj", "n.j", "new jersey"}
# Normalized (upper-case) town names: letters plus the usual separators.
_TOWN_SHAPE = re.compile(r"^[A-Z][A-Z .'\-]*$")


def age(birthdate) -> int | None:
    if not birthdate:
        return None
    today = date.today()
    return today.year - birthdate.year - (
        (today.month, today.day) < (birthdate.month, birthdate.day)
    )


def is_high_school_age(birthdate) -> bool:
    a = age(birthdate)
    return a is not None and MIN_AGE <= a <= MAX_AGE


def _norm_town(value) -> str:
    return re.sub(r"\s+", " ", str(value)).strip().upper()


def _town_of(hometown) -> str:
    """The town part of "Town, ST"."""
    return _norm_town(str(hometown).split(",")[0])


def _state_of(hometown) -> str:
    """The state part of "Town, ST"; '' when there is no comma at all."""
    parts = str(hometown).split(",")
    if len(parts) < 2:
        return ""
    return parts[-1].strip().strip(".").lower()


def _looks_like_town(town: str) -> bool:
    return len(town) >= 3 and bool(_TOWN_SHAPE.match(town))


def is_new_jersey(location: str, nj_towns=None) -> bool:
    """Heuristic: the location string points at New Jersey.

    `nj_towns` is an optional set of normalized town names known to be in NJ
    (see load_nj_towns) used to resolve bare town names.
    """
    if not location or not str(location).strip():
        return False
    loc = str(location).lower().strip()
    if "new jersey" in loc or "n.j." in loc:
        return True
    if _NJ_ZIP.search(str(location)):
        return True
    # "nj" as a standalone token (comma/space separated), so "njok" won't match
    for tok in re.split(r"[\s,;]+", loc):
        if tok.strip(".") == "nj":
            return True
    return bool(nj_towns) and _town_of(location) in nj_towns


def qualifies_for_njhs(birthdate, location, extra_locations=(),
                       nj_towns=None) -> tuple[bool, str]:
    """Return (ok, reason). ok=True means eligible for NJ HS deep-dive.

    `extra_locations` are further location strings known for this player, e.g.
    AYHL roster hometowns such as "Basking Ridge, NJ".
    """
    a = age(birthdate)
    if not is_high_school_age(birthdate):
        return False, f"age={a} (need {MIN_AGE}-{MAX_AGE})"
    candidates = [location, *[x for x in extra_locations if x]]
    for cand in candidates:
        if is_new_jersey(cand, nj_towns):
            return True, ""
    tried = ", ".join(repr(c) for c in candidates)
    return False, f"location not NJ (checked {tried})"


def load_nj_towns(conn) -> set:
    """Town names our AYHL hometown data places in NJ ("Basking Ridge, NJ")."""
    with conn.cursor() as cur:
        cur.execute("SELECT DISTINCT hometown FROM ayhl_roster "
                    "WHERE hometown IS NOT NULL")
        rows = cur.fetchall()
    towns = set()
    for (hometown,) in rows:
        if _state_of(hometown) not in _NJ_STATE_WORDS:
            continue
        town = _town_of(hometown)
        if _looks_like_town(town):
            towns.add(town)
    return towns


def load_hometowns(conn, player_ids) -> dict:
    """AYHL roster hometowns keyed by player_profiles.id."""
    ids = sorted({int(i) for i in player_ids})
    if not ids:
        return {}
    with conn.cursor() as cur:
        cur.execute(
            """SELECT DISTINCT psl.player_id, ar.hometown
               FROM player_source_links psl
               JOIN ayhl_roster ar
                 ON ar.player_id::text = psl.source_player_id::text
               WHERE psl.source = 'AYHL' AND psl.player_id = ANY(%s)
                 AND ar.hometown IS NOT NULL""",
            (ids,),
        )
        rows = cur.fetchall()
    out = {}
    for player_id, hometown in rows:
        out.setdefault(player_id, []).append(hometown)
    return out
