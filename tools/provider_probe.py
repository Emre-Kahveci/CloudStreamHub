#!/usr/bin/env python3
"""
provider_probe.py

CLI reverse-engineering and site diagnostics tool powered by Scrapling.
Allows engineers to probe live streaming sites, evaluate anti-bot barriers,
inspect card/detail/player selectors, capture sanitized XHR streams, and check
for selector drift.

Usage:
  python tools/provider_probe.py <url> [--mode auto|http|dynamic|stealth] [--capture-xhr] [--output <path>]
"""

import os
import sys

# Ensure repository root is on sys.path
REPO_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
if REPO_ROOT not in sys.path:
    sys.path.insert(0, REPO_ROOT)

import json
import argparse
import logging
from typing import Dict, Any, List
from urllib.parse import urljoin, urlparse

from bs4 import BeautifulSoup
from tools.scraping import (
    ProviderFetcher,
    FetchMode,
    FetchStatus,
    AdaptiveManager,
    XhrRedactor
)

logging.basicConfig(level=logging.INFO, format="[%(levelname)s] %(message)s")
logger = logging.getLogger("provider_probe")

def probe_page(
    url: str,
    mode: str = "auto",
    capture_xhr: bool = False,
    timeout: int = 20
) -> Dict[str, Any]:
    fetch_mode_map = {
        "auto": FetchMode.HTTP,
        "http": FetchMode.HTTP,
        "dynamic": FetchMode.DYNAMIC,
        "stealth": FetchMode.STEALTH
    }
    preferred = fetch_mode_map.get(mode.lower(), FetchMode.HTTP)
    allow_dyn = mode in ("auto", "dynamic", "stealth")
    allow_stealth = mode in ("auto", "stealth")

    logger.info(f"Probing {url} with preferred mode={preferred.value}, dynamic_fallback={allow_dyn}, stealth_fallback={allow_stealth}...")

    with ProviderFetcher(timeout=timeout) as fetcher:
        result = fetcher.fetch(
            url=url,
            preferred_mode=preferred,
            allow_dynamic_fallback=allow_dyn,
            allow_stealth_fallback=allow_stealth,
            capture_xhr=capture_xhr
        )

    probe_data: Dict[str, Any] = {
        "inputUrl": url,
        "finalUrl": result.finalUrl,
        "statusCode": result.statusCode,
        "fetchModeUsed": result.fetchMode.value,
        "status": result.status.value,
        "browserUsed": result.browserUsed,
        "elapsedMs": result.elapsedMs,
        "cloudflare": result.cloudflare,
        "blocked": result.blocked,
        "error": result.error,
        "cards": [],
        "detail": {},
        "iframes": [],
        "mediaCandidates": [],
        "subtitles": [],
        "capturedXhr": result.capturedXhr if capture_xhr else [],
        "capturedXhrCount": len(result.capturedXhr)
    }

    if not result.body:
        return probe_data

    # Parse DOM
    soup = BeautifulSoup(result.body, "html.parser")

    # 1. Cards / Posters Probe
    card_selectors = [
        "a.mcard", "a.dcard", "a.poster", "a.item", "a[href*='/belgesel/']",
        "div.poster a", "article a", ".film-content a", ".movie-box a",
        ".video-item a", ".film-kutusu a", "div.film-box a", "a.card"
    ]
    discovered_cards = []
    for sel in card_selectors:
        for tag in soup.select(sel):
            href = tag.get("href")
            title = tag.get("title") or tag.text.strip()
            img = tag.find("img")
            poster = (img.get("src") or img.get("data-src")) if img else None
            if href and (title or poster):
                abs_href = urljoin(result.finalUrl, href)
                discovered_cards.append({
                    "title": title[:60] if title else "Untitled",
                    "url": abs_href,
                    "poster": urljoin(result.finalUrl, poster) if poster else None,
                    "selectorMatched": sel
                })
                if len(discovered_cards) >= 10:
                    break
        if discovered_cards:
            break

    probe_data["cards"] = discovered_cards

    # 2. Detail Analysis
    title_tag = soup.find("h1") or soup.find("title")
    desc_tag = soup.find("meta", attrs={"name": "description"}) or soup.find("div", class_="description") or soup.find("p")
    episodes = soup.select("a[href*='bolum'], a[href*='episode'], .season-item, .episode-item")

    probe_data["detail"] = {
        "title": title_tag.text.strip() if title_tag else None,
        "description": (desc_tag.text.strip() if desc_tag else "")[:160],
        "episodeLinkCount": len(episodes)
    }

    # 3. Iframes / Embeds
    for ifr in soup.find_all("iframe"):
        src = ifr.get("src") or ifr.get("data-src") or ifr.get("data-lazy-src")
        if src:
            abs_src = urljoin(result.finalUrl, src)
            probe_data["iframes"].append(XhrRedactor.sanitize_url(abs_src))

    # 4. Media Stream Candidates (.m3u8, .mpd, mp4) & Subtitles (.vtt, .srt)
    body_str = result.body
    import re
    m3u8_matches = re.findall(r'https?://[^"\'\s<>]+\.(?:m3u8|mp4|mpd)[^"\'\s<>]*', body_str, re.IGNORECASE)
    for m in m3u8_matches[:5]:
        probe_data["mediaCandidates"].append(XhrRedactor.sanitize_url(m))

    vtt_matches = re.findall(r'https?://[^"\'\s<>]+\.(?:vtt|srt)[^"\'\s<>]*', body_str, re.IGNORECASE)
    for v in vtt_matches[:5]:
        probe_data["subtitles"].append(XhrRedactor.sanitize_url(v))

    return probe_data

def main():
    parser = argparse.ArgumentParser(description="CloudStreamHub Provider Reverse Engineering Probe")
    parser.add_argument("url", help="Target URL to probe")
    parser.add_argument("--mode", default="auto", choices=["auto", "http", "dynamic", "stealth"], help="Fetch strategy tier")
    parser.add_argument("--capture-xhr", action="store_true", help="Intercept and sanitize background XHR/fetch requests")
    parser.add_argument("--timeout", type=int, default=20, help="Request timeout in seconds")
    parser.add_argument("--output", help="Optional path to write JSON report")
    args = parser.parse_args()

    probe_result = probe_page(
        url=args.url,
        mode=args.mode,
        capture_xhr=args.capture_xhr,
        timeout=args.timeout
    )

    print("\n" + "=" * 60)
    print(" PROVIDER PROBE RESULTS (Scrapling)")
    print("=" * 60)
    print(f" Input URL:        {probe_result['inputUrl']}")
    print(f" Final URL:        {probe_result['finalUrl']}")
    print(f" Status Code:      {probe_result['statusCode']}")
    print(f" Fetch Mode Used:  {probe_result['fetchModeUsed']} (Browser: {probe_result['browserUsed']})")
    print(f" Status:           {probe_result['status']}")
    print(f" Elapsed:          {probe_result['elapsedMs']} ms")
    print(f" Cloudflare:       {probe_result['cloudflare']}")
    print(f" Anti-Bot Blocked: {probe_result['blocked']}")
    if probe_result['error']:
        print(f" Error:            {probe_result['error']}")

    print("\n--- Content Discovery ---")
    print(f" Cards Found:      {len(probe_result['cards'])}")
    for i, c in enumerate(probe_result['cards'][:3]):
        print(f"  [{i+1}] {c['title']} -> {c['url']}")

    print(f" Detail Title:     {probe_result['detail'].get('title')}")
    print(f" Episodes Found:   {probe_result['detail'].get('episodeLinkCount')}")

    print("\n--- Stream & Player Discovery ---")
    print(f" Iframes Discovered:        {len(probe_result['iframes'])}")
    for ifr in probe_result['iframes']:
        print(f"  - {ifr}")
    print(f" Direct Media Streams:      {len(probe_result['mediaCandidates'])}")
    for m in probe_result['mediaCandidates']:
        print(f"  - {m}")
    print(f" Subtitles Discovered:      {len(probe_result['subtitles'])}")
    for s in probe_result['subtitles']:
        print(f"  - {s}")

    if args.capture_xhr:
        print(f"\n--- Captured Sanitized XHRs ({probe_result['capturedXhrCount']}) ---")
        for x in probe_result['capturedXhr'][:10]:
            print(f"  [{x.get('method', 'GET')}] {x.get('status', '???')} {x.get('resourceType', 'xhr')} -> {x.get('url')}")

    print("=" * 60)

    if args.output:
        import os
        os.makedirs(os.path.dirname(os.path.abspath(args.output)), exist_ok=True)
        with open(args.output, "w", encoding="utf-8") as f:
            json.dump(probe_result, f, indent=2, ensure_ascii=False)
        print(f"\nSaved structured JSON probe report to: {args.output}")

if __name__ == "__main__":
    main()
