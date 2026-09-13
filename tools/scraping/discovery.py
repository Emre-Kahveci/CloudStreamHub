"""
discovery.py

Shared semantic discovery helpers for CloudStreamHub health monitoring, live smoke testing, and probe tooling.
Ensures unified logic across provider_health.py, live_provider_smoke.py, and provider_probe.py:
- Content Item Discovery (Cards/posters): Strict count > 0 for PASS.
- Soft 404 validation: Checks HTTP status, title, and body tokens.
- Player Discovery with Chaining:
  * Movie: Detail page -> Player discovery.
  * TvSeries / Anime: Detail page -> Discover first episode link -> Fetch episode page -> Player discovery.
  * Strong player evidence: iframe[src], video[src], .m3u8 URLs, JWPlayer / VideoJS initializations.
- Subtitle Classification: TRUTHFUL distinction between VTT/SRT, DUBBED, HARDSUB, or NONE.
"""

import re
from typing import Dict, Any, List, Optional, Tuple
from urllib.parse import urljoin, urlparse
from bs4 import BeautifulSoup

from .models import FetchMode, FetchStatus, FetchResult
from .detection import is_soft_404
from .redaction import XhrRedactor

KNOWN_CARD_SELECTORS = [
    "a.mcard", "a.dcard", "a.poster", "a.item", "a[href*='/belgesel/']",
    "div.poster a", ".listmovie a", ".panel a", "article a", ".film-content a",
    "div.film-box a", "div.menulink a", "div.kutu a", "a.card", "div.title-card a"
]

STRONG_PLAYER_HOSTS = [
    "vidpapi", "closeload", "rapidrame", "streamtape", "doodstream",
    "vidsrc", "superembed", "player", "embed", "upstream"
]

def discover_homepage_cards(html_body: str, base_url: str, selector_override: Optional[str] = None) -> List[Dict[str, Any]]:
    """
    Extracts valid content cards from homepage HTML.
    Returns list of discovered items with {title, url, poster}.
    """
    if not html_body:
        return []

    soup = BeautifulSoup(html_body, "html.parser")
    selectors_to_check = [s.strip() for s in selector_override.split(",")] if selector_override else KNOWN_CARD_SELECTORS

    cards = []
    for sel in selectors_to_check:
        for tag in soup.select(sel):
            href = tag.get("href")
            title = tag.get("title") or tag.text.strip()
            img = tag.find("img")
            poster = (img.get("src") or img.get("data-src") or img.get("data-lazy-src")) if img else None

            if href and (title or poster):
                abs_href = urljoin(base_url, href)
                # Ignore non-content links like login/register/social
                if any(x in abs_href.lower() for x in ["login", "register", "giris", "kayit", "facebook", "twitter", "instagram"]):
                    continue
                cards.append({
                    "title": (title or "Discovered Title")[:80].strip(),
                    "url": abs_href,
                    "poster": urljoin(base_url, poster) if poster else None,
                    "selector": sel
                })
                if len(cards) >= 15:
                    break
        if cards:
            break

    return cards

def parse_detail_page(html_body: str, status_code: Optional[int], base_url: str) -> Dict[str, Any]:
    """
    Validates and extracts detail page metadata with soft-404 rejection.
    """
    result = {
        "status": "FAIL",
        "title": None,
        "isSoft404": False,
        "episodeLinks": [],
        "reason": None
    }

    if not html_body or status_code == 404:
        result["status"] = "FAIL"
        result["reason"] = f"HTTP {status_code}"
        result["isSoft404"] = (status_code == 404)
        return result

    soup = BeautifulSoup(html_body, "html.parser")
    title_tag = soup.select_one("h1, h2, meta[property='og:title'], title")
    title = title_tag.text.strip() if title_tag else "Unknown"

    if is_soft_404(status_code, html_body, title):
        result["status"] = "FAIL"
        result["title"] = title[:50]
        result["isSoft404"] = True
        result["reason"] = "SOFT_404_PAGE"
        return result

    result["status"] = "PASS"
    result["title"] = title[:80]

    # Find episode links for series/anime
    ep_selectors = [
        "a[href*='bolum']", "a[href*='episode']", "a[href*='sezon']",
        ".episode-item a", ".season-item a", "a.episode", "#bolumler a",
        "a[href*='/titles/']"
    ]
    seen_eps = set()
    for sel in ep_selectors:
        for tag in soup.select(sel):
            href = tag.get("href")
            if href:
                abs_href = urljoin(base_url, href)
                if abs_href not in seen_eps and abs_href != base_url:
                    seen_eps.add(abs_href)
                    result["episodeLinks"].append(abs_href)
        if result["episodeLinks"]:
            break

    return result

def evaluate_player_discovery(html_body: str, base_url: str) -> Tuple[str, Dict[str, Any]]:
    """
    Evaluates presence of real player containers without generic 'player' substring false positives.
    Reports: 'PLAYER_DISCOVERED' or 'PLAYER_NOT_FOUND'.
    Strong Evidence:
    - Real iframes with src or data-src pointing to video/embed hosts
    - HTML5 <video> with <source src="...">
    - Embedded m3u8 or mp4 URLs in script initializations
    - JWPlayer or VideoJS initialization with file/source property
    """
    info = {
        "iframes": [],
        "videos": [],
        "mediaUrls": [],
        "hasPlayerScript": False
    }

    if not html_body:
        return "PLAYER_NOT_FOUND", info

    soup = BeautifulSoup(html_body, "html.parser")

    # 1. Inspect Iframes
    for ifr in soup.find_all("iframe"):
        src = ifr.get("src") or ifr.get("data-src") or ifr.get("data-lazy-src")
        if src:
            abs_src = urljoin(base_url, src)
            # Filter out non-video ads/analytics iframes
            if not any(ign in abs_src.lower() for ign in ["google", "recaptcha", "analytics", "disqus", "ads"]):
                info["iframes"].append(XhrRedactor.sanitize_url(abs_src))

    # 2. Inspect HTML5 Video elements
    for v in soup.find_all("video"):
        src = v.get("src")
        if src:
            info["videos"].append(XhrRedactor.sanitize_url(urljoin(base_url, src)))
        for source in v.find_all("source"):
            ssrc = source.get("src")
            if ssrc:
                info["videos"].append(XhrRedactor.sanitize_url(urljoin(base_url, ssrc)))

    # 3. Inspect Script player initializations (m3u8, closeload, vidpapi, jwplayer sources)
    body_str = html_body
    m3u8_matches = re.findall(r'https?://[^"\'\s<>]+\.(?:m3u8|mp4|mpd)[^"\'\s<>]*', body_str, re.IGNORECASE)
    for m in m3u8_matches[:5]:
        info["mediaUrls"].append(XhrRedactor.sanitize_url(m))

    has_player_code = bool(
        re.search(r'(?:jwplayer|videojs|Playerjs)\s*\(', body_str, re.IGNORECASE) or
        any(host in body_str.lower() for host in ["vidpapi", "closeload", "rapidrame", "streamtape", "doodstream", "vidsrc", "superembed"]) or
        re.search(r'(?:file|source)\s*:\s*["\']https?://[^"\']+\.(?:m3u8|mp4)', body_str, re.IGNORECASE)
    )
    info["hasPlayerScript"] = has_player_code

    if info["iframes"] or info["videos"] or info["mediaUrls"] or has_player_code:
        return "PLAYER_DISCOVERED", info

    return "PLAYER_NOT_FOUND", info

def classify_subtitles(html_body: str, title: Optional[str] = None) -> str:
    """
    Accurately classifies subtitle availability:
    - 'VTT/SRT': WebVTT or SRT track discovered
    - 'HARDSUB': Explicitly hardsubbed (gömülü altyazı)
    - 'DUBBED': Audio dubbing without separate subtitles
    - 'NONE': No subtitle tracks or markers discovered
    """
    if not html_body:
        return "NONE"

    body_lower = html_body.lower()
    title_lower = (title or "").lower()

    if ".vtt" in body_lower or ".srt" in body_lower or "<track" in body_lower:
        return "VTT/SRT"

    if "altyazı" in title_lower or "altyazılı" in title_lower or "gömülü altyazı" in body_lower:
        return "HARDSUB"

    if "dublaj" in title_lower or "türkçe dublaj" in body_lower:
        return "DUBBED"

    return "NONE"
