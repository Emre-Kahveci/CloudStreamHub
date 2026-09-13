"""
tools/scraping package

Exposes the unified Scrapling-based web reverse engineering and health monitoring suite.
"""

from .models import FetchMode, FetchStatus, FetchResult, CapturedXhr
from .detection import (
    is_cloudflare_challenge,
    is_bot_blocked,
    verify_redirect_safety,
    verify_content_markers,
    is_soft_404
)
from .redaction import XhrRedactor
from .adaptive import AdaptiveManager
from .fetch import ProviderFetcher
from .discovery import (
    discover_homepage_cards,
    parse_detail_page,
    evaluate_player_discovery,
    classify_subtitles
)

__all__ = [
    "FetchMode",
    "FetchStatus",
    "FetchResult",
    "CapturedXhr",
    "is_cloudflare_challenge",
    "is_bot_blocked",
    "verify_redirect_safety",
    "verify_content_markers",
    "is_soft_404",
    "XhrRedactor",
    "AdaptiveManager",
    "ProviderFetcher",
    "discover_homepage_cards",
    "parse_detail_page",
    "evaluate_player_discovery",
    "classify_subtitles"
]
