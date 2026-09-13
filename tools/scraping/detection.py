"""
detection.py

Anti-bot, Cloudflare challenge, redirect security, and content marker detection.
"""

from typing import Set, Tuple, Optional, List, Dict
from urllib.parse import urlparse

CLOUDFLARE_INDICATORS = [
    "cloudflare",
    "cf-ray",
    "cf-chl-bypass",
    "just a moment...",
    "attention required! | cloudflare",
    "challenge-running",
    "turnstile",
    "cf-mitigated",
    "_cf_chl"
]

BLOCKED_STATUS_CODES = {403, 429, 503}

def is_cloudflare_challenge(status: Optional[int], body: str, headers: Optional[Dict[str, str]] = None) -> bool:
    """Detects whether a response is an active Cloudflare Turnstile, Managed Challenge, or interstitial."""
    if not status:
        return False

    body_lower = body.lower() if body else ""
    hdr_str = " ".join(f"{k}:{v}" for k, v in (headers or {}).items()).lower()

    has_cf_marker = any(ind in body_lower or ind in hdr_str for ind in CLOUDFLARE_INDICATORS)

    if status in (403, 503, 429) and has_cf_marker:
        return True

    # Sometimes 200 is returned for the interstitial HTML page itself
    if status == 200 and ("just a moment..." in body_lower or "challenge-running" in body_lower):
        return True

    return False

def is_bot_blocked(status: Optional[int], body: str) -> bool:
    """Detects general anti-bot blocking (WAF, 403 Forbidden, rate limit)."""
    if status in BLOCKED_STATUS_CODES:
        return True
    body_lower = body.lower() if body else ""
    if any(phrase in body_lower for phrase in ("access denied", "waf", "bot detected", "ddos protection by")):
        return True
    return False

def verify_redirect_safety(
    final_url: str,
    allowed_hosts: Set[str],
    canonical: Optional[str] = None
) -> Tuple[bool, Optional[str]]:
    """
    Ensures that a request's final redirected URL remains within allowed destination hosts.
    Returns (is_allowed, candidate_host).
    """
    if not final_url:
        return False, None

    parsed = urlparse(final_url)
    host = parsed.netloc.lower().split(":")[0]

    # Normalize allowed hosts (lower and stripped)
    normalized_allowed = {h.lower().strip() for h in allowed_hosts if h}

    if canonical:
        can_host = urlparse(canonical).netloc.lower().split(":")[0]
        if can_host:
            normalized_allowed.add(can_host)

    # If no allowlist is configured, redirect safety is permissive
    if not normalized_allowed:
        return True, None

    if host in normalized_allowed:
        return True, None

    # Check for subdomains
    for allowed in normalized_allowed:
        if host.endswith("." + allowed):
            return True, None

    return False, host

def verify_content_markers(body: str, expected_markers: List[str]) -> bool:
    """Verifies that at least one of the expected content markers is present in the HTML body."""
    if not expected_markers:
        return True
    if not body:
        return False
    body_lower = body.lower()
    return any(marker.lower() in body_lower for marker in expected_markers)
