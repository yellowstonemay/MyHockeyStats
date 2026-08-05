"""Guard for the NJ.com high school hockey deep-dive.

Only scrape/link players who are high-school age AND live in New Jersey, so a
registered user with the same name as an NJ HS player in another state/age
group does not get incorrectly linked.
"""
from __future__ import annotations

import re
from datetime import date

# High-school hockey in NJ runs grades 9-12 (freshman ~13-14 .. senior ~18-19).
MIN_AGE = 13
MAX_AGE = 19

_NJ_ZIP = re.compile(r"\b(07|08|09)\d{3}\b")


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


def is_new_jersey(location: str) -> bool:
    """Heuristic: the profile location must point at New Jersey."""
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
    return False


def qualifies_for_njhs(birthdate, location) -> tuple[bool, str]:
    """Return (ok, reason). ok=True means eligible for NJ HS deep-dive."""
    a = age(birthdate)
    if not is_high_school_age(birthdate):
        return False, f"age={a} (need {MIN_AGE}-{MAX_AGE})"
    if not is_new_jersey(location):
        return False, f"location={location!r} not NJ"
    return True, ""
