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

import io
import sys

MAX_ERROR_LEN = 500
# The transcript is what the admin page shows when a status is clicked, so keep
# enough of it to see the source's own messages without bloating the row.
MAX_LOG_LEN = 8000


class _Capture(io.TextIOBase):
    """stdout tee that can hand back everything printed since the last mark.

    Deep-dive scripts print their progress (and the helpers they call print too),
    so capturing at the stream level gives the admin page the real transcript
    without threading a logger through every function.
    """

    def __init__(self, stream) -> None:
        self._stream = stream
        self._chunks: list[str] = []
        self._mark = 0

    def write(self, text: str) -> int:  # type: ignore[override]
        self._stream.write(text)
        self._chunks.append(text)
        if len(self._chunks) > 20_000:
            # Keep memory bounded on long --all-seasons runs; the mark is reset
            # so a slice can only lose lines printed long before it.
            self._chunks = self._chunks[10_000:]
            self._mark = 0
        return len(text)

    def flush(self) -> None:  # type: ignore[override]
        self._stream.flush()

    def mark(self) -> None:
        self._mark = len(self._chunks)

    def since_mark(self, limit: int = MAX_LOG_LEN) -> str:
        text = "".join(self._chunks[self._mark:])
        return text if len(text) <= limit else "…" + text[-limit:]


def install_capture():
    """Tee sys.stdout and return the sink (call once, at the start of main)."""
    sink = _Capture(sys.stdout)
    sys.stdout = sink
    return sink


def record_outcome(conn, source: str, source_player_id: str, ok: bool,
                   error: object = None, log_text: str | None = None) -> None:
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
                          last_deep_dive_error  = %s,
                          last_deep_dive_log    = COALESCE(%s, last_deep_dive_log)
                    WHERE source = %s AND source_player_id = %s
                      AND link_state = 'CONFIRMED'""",
                ("SUCCESS" if ok else "FAILED", detail,
                 (log_text[:MAX_LOG_LEN] if log_text else None), source, str(source_player_id)),
            )
        conn.commit()
    except Exception as e:  # noqa: BLE001
        print(f"  ! could not record deep-dive outcome for {source}:{source_player_id}: {e}")
        try:
            conn.rollback()
        except Exception:  # noqa: BLE001
            pass
