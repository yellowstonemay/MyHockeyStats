#!/usr/bin/env python3
"""Record how the last deep-dive of each player went, for the admin page.

`player_source_links.last_verified_at` is refreshed by identity_link.py on every
run, so it only says when a link was last *seen* — a source whose scrape fails
every day still looks freshly verified. The deep-dive scripts call
record_outcome() once per player instead, so the admin page can show the result
of the most recent attempt rather than a timestamp that never goes stale.

Rows are keyed on (source, source_player_id) — the same key identity_link.py
and the scrapers already work with — so callers never need a profile id.
"""
from __future__ import annotations

MAX_ERROR_LEN = 500


def record_outcome(conn, source: str, source_player_id: str, ok: bool,
                   error: object = None) -> None:
    """Stamp this player's link row with the outcome of one deep-dive attempt.

    Best-effort: bookkeeping must never take a scrape down with it, so failures
    here are reported and swallowed.
    """
    if conn is None:
        return

    detail = None if ok else (str(error) if error else "unknown error")
    if detail is not None and len(detail) > MAX_ERROR_LEN:
        detail = detail[:MAX_ERROR_LEN - 1] + "\u2026"

    # A failed scrape can leave the connection inside an aborted transaction;
    # discard that partial work so the UPDATE below is accepted.
    if not ok:
        try:
            conn.rollback()
        except Exception:  # noqa: BLE001
            pass

    try:
        with conn.cursor() as cur:
            cur.execute(
                """UPDATE player_source_links
                      SET last_deep_dive_at     = NOW(),
                          last_deep_dive_status = %s,
                          last_deep_dive_error  = %s
                    WHERE source = %s AND source_player_id = %s
                      AND link_state = 'CONFIRMED'""",
                ("SUCCESS" if ok else "FAILED", detail, source, str(source_player_id)),
            )
        conn.commit()
    except Exception as e:  # noqa: BLE001
        print(f"  ! could not record deep-dive outcome for {source}:{source_player_id}: {e}")
        try:
            conn.rollback()
        except Exception:  # noqa: BLE001
            pass
