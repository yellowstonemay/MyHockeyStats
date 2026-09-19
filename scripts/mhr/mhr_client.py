#!/usr/bin/env python3
"""Playwright client for myhockeyrankings.com (MHR).

MHR sits behind Cloudflare and only tolerates **one JSON call per browser
context**: a second call in the same context gets a "Just a moment..."
challenge.  Navigation itself is cheap, so every call gets a fresh context
(with playwright-stealth applied) and a short pause between contexts:

    with MhrClient() as client:
        html = client.fetch_team_page(2026, 1205)
        hits = client.search_teams("New Jersey Colonials 11U")

Verified quirks (probed 2026-09):
* Plain ``fetch`` with **no custom headers** gets 200; adding ``Accept:
  application/json`` or ``X-Mhr-Token`` may trigger the challenge.  The
  in-page fetch therefore sends no headers at all.
* The team page is a SPA route: ``#scores-body`` is filled in after the initial
  HTML load, so the fetch waits for the schedule grid first (a team with no
  games yet just times out, which is tolerated).
* ``/services/search/teams?q=`` matches *all* query tokens against the team
  name, so "New Jersey Devils Youth 15U" finds nothing while "New Jersey
  Devils 15U" finds the team.
"""
from __future__ import annotations

import json
import re
import time
import urllib.parse

from playwright.sync_api import TimeoutError as PlaywrightTimeoutError
from playwright.sync_api import sync_playwright

try:  # optional: the probes rely on stealth, but the client works without it
    from playwright_stealth import Stealth
except ImportError:  # pragma: no cover
    Stealth = None

BASE = "https://myhockeyrankings.com"
TEAM_SEARCH_PATH = "/services/search/teams?q="
DESKTOP_UA = (
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
)
DEFAULT_DELAY_SECONDS = 4.0
CHALLENGE_MARKERS = ("Just a moment", "cf-browser-verification", "Attention Required")
_TEAM_ID_RE = re.compile(r"[?&]t=(\d+)")


class MhrError(RuntimeError):
    """Raised when MHR returns a Cloudflare challenge or unexpected payload."""


class MhrClient:
    def __init__(self, headless: bool = True, delay: float = DEFAULT_DELAY_SECONDS,
                 max_attempts: int = 3, render_timeout: float = 25.0):
        self.headless = headless
        self.delay = delay
        self.max_attempts = max_attempts
        self.render_timeout = render_timeout
        self._playwright = None
        self._browser = None

    # -- lifecycle ---------------------------------------------------------
    def __enter__(self) -> "MhrClient":
        self.start()
        return self

    def __exit__(self, *exc_info) -> None:
        self.close()

    def start(self) -> None:
        if self._browser is not None:
            return
        self._playwright = sync_playwright().start()
        self._browser = self._playwright.chromium.launch(
            headless=self.headless,
            args=["--disable-blink-features=AutomationControlled", "--no-sandbox"],
        )

    def close(self) -> None:
        if self._browser is not None:
            self._browser.close()
            self._browser = None
        if self._playwright is not None:
            self._playwright.stop()
            self._playwright = None

    # -- internals ---------------------------------------------------------
    def _open_page(self):
        context = self._browser.new_context(
            user_agent=DESKTOP_UA,
            locale="en-US",
            viewport={"width": 1440, "height": 900},
        )
        if Stealth is not None:
            Stealth().apply_stealth_sync(context)
        return context, context.new_page()

    @staticmethod
    def _is_challenge(html: str) -> bool:
        head = html[:4000]
        return any(marker in head for marker in CHALLENGE_MARKERS)

    def _wait_ready(self, page, timeout: float | None = None) -> None:
        """Cloudflare's interstitial swaps the title once it clears."""
        deadline = time.time() + (timeout or self.render_timeout)
        while time.time() < deadline:
            try:
                if "Just a moment" not in (page.title() or ""):
                    return
            except Exception:
                return
            time.sleep(0.5)

    def _load(self, url: str, wait_for: str | None = None, keep_open: bool = False):
        """Load ``url`` in a fresh context, retrying on Cloudflare challenges.

        Returns ``(html, context, page)``; ``context``/``page`` are only live
        when ``keep_open`` is set (the caller then owns closing the context).
        """
        self.start()
        last_error: Exception | None = None
        for attempt in range(1, self.max_attempts + 1):
            context = None
            try:
                context, page = self._open_page()
                page.goto(url, wait_until="domcontentloaded",
                          timeout=int(self.render_timeout * 1000))
                self._wait_ready(page)
                if wait_for:
                    try:
                        page.wait_for_selector(wait_for, timeout=int(self.render_timeout * 1000))
                    except PlaywrightTimeoutError:
                        pass  # e.g. a team with no scheduled games yet
                html = page.content()
                if self._is_challenge(html):
                    raise MhrError(f"Cloudflare challenge for {url}")
                if keep_open:
                    return html, context, page
                context.close()
                time.sleep(self.delay)
                return html, None, None
            except Exception as exc:  # noqa: BLE001 - retried below
                last_error = exc
                if context is not None:
                    try:
                        context.close()
                    except Exception:  # pragma: no cover - teardown best effort
                        pass
                time.sleep(self.delay)
            if attempt < self.max_attempts:
                time.sleep(self.delay * attempt)
        raise MhrError(f"failed to load {url}: {last_error}")

    # -- public API --------------------------------------------------------
    def fetch_team_page(self, season_year: int, team_id: str | int) -> str:
        """Return the rendered HTML of a team's schedule page."""
        url = f"{BASE}/team_info.php?y={season_year}&t={team_id}"
        html, _, _ = self._load(url, wait_for="#scores-body div.grid-cols-7")
        return html

    def search_teams(self, query: str) -> list[dict]:
        """Search MHR teams by name (the site's own typeahead API)."""
        _, context, page = self._load(BASE + "/", keep_open=True)
        try:
            path = TEAM_SEARCH_PATH + urllib.parse.quote(query)
            result = page.evaluate(
                """async (path) => {
                    const r = await fetch(path, {credentials: 'same-origin'});
                    return {status: r.status, body: await r.text()};
                }""",
                path,
            )
        finally:
            context.close()
        if result["status"] != 200:
            raise MhrError(f"team search failed ({result['status']}): {result['body'][:120]}")
        payload = json.loads(result["body"])
        if not isinstance(payload, list):
            raise MhrError("team search returned an unexpected payload")
        teams = []
        for item in payload:
            if not isinstance(item, dict) or not item.get("name") or item.get("kind") != "team":
                continue
            match = _TEAM_ID_RE.search(item.get("url") or "")
            team_id = str(item.get("nbr") or (match.group(1) if match else "") or "")
            if not team_id:
                continue
            teams.append({
                "team_id": team_id,
                "name": item.get("name"),
                "level": item.get("level"),
                "level_desc": item.get("level_desc"),
                "url": item.get("url"),
            })
        return teams
