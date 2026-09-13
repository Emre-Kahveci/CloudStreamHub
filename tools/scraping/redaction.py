"""
redaction.py

Security and privacy redaction layer for captured XHRs, requests, headers,
query parameters, tokens, and media URLs.
"""

import re
from typing import Any, Dict, List, Optional
from urllib.parse import urlparse, parse_qs, urlencode, urlunparse

SENSITIVE_HEADER_KEYS = {
    "authorization",
    "cookie",
    "set-cookie",
    "proxy-authorization",
    "x-auth-token",
    "x-api-key",
    "cf-access-token",
    "token"
}

SENSITIVE_PARAM_PATTERNS = [
    re.compile(r"^(token|auth|sig|signature|key|expires|secret|pass|passwd|password|jwt)$", re.IGNORECASE),
    re.compile(r".*(token|signature|secret|auth).*", re.IGNORECASE)
]

JWT_REGEX = re.compile(r"eyJ[a-zA-Z0-9_-]{10,}\.eyJ[a-zA-Z0-9_-]{10,}\.[a-zA-Z0-9_-]{10,}")
BEARER_REGEX = re.compile(r"Bearer\s+[a-zA-Z0-9_\-\.=]+", re.IGNORECASE)

class XhrRedactor:
    """Sanitizes sensitive security headers, parameters, tokens and ephemeral media URLs."""

    @staticmethod
    def sanitize_header_value(key: str, value: str) -> str:
        key_lower = key.lower().strip()
        if key_lower in SENSITIVE_HEADER_KEYS or any(s in key_lower for s in ("auth", "token", "cookie", "key")):
            return "<REDACTED>"
        # Also redact if value itself looks like a Bearer token or JWT
        if JWT_REGEX.search(value) or BEARER_REGEX.search(value):
            return "<REDACTED_TOKEN>"
        return value

    @staticmethod
    def sanitize_headers(headers: Dict[str, str]) -> Dict[str, str]:
        if not headers:
            return {}
        sanitized = {}
        for k, v in headers.items():
            sanitized[k] = XhrRedactor.sanitize_header_value(k, str(v))
        return sanitized

    @staticmethod
    def sanitize_url(url: str) -> str:
        if not url:
            return ""
        try:
            parsed = urlparse(url)
            # Check for media streams with query strings (e.g. .m3u8, .mpd, .ts)
            path_lower = parsed.path.lower()
            if any(path_lower.endswith(ext) for ext in (".m3u8", ".mpd", ".ts", ".mp4", ".m4s")) and parsed.query:
                # Retain scheme, host, path but redact query parameters
                return urlunparse((parsed.scheme, parsed.netloc, parsed.path, parsed.params, "<REDACTED_QUERY>", parsed.fragment))

            if not parsed.query:
                return url

            query_dict = parse_qs(parsed.query, keep_blank_values=True)
            new_query = {}
            for param, values in query_dict.items():
                if any(pat.match(param) for pat in SENSITIVE_PARAM_PATTERNS):
                    new_query[param] = ["<REDACTED>"]
                else:
                    new_query[param] = [
                        "<REDACTED_TOKEN>" if (JWT_REGEX.search(val) or len(val) > 128) else val
                        for val in values
                    ]

            redacted_query = urlencode(new_query, doseq=True)
            return urlunparse((parsed.scheme, parsed.netloc, parsed.path, parsed.params, redacted_query, parsed.fragment))
        except Exception:
            return url

    @staticmethod
    def sanitize_body(body: Optional[str]) -> Optional[str]:
        if not body:
            return body
        # Redact JWTs and Bearer tokens in raw string payloads
        sanitized = JWT_REGEX.sub("<REDACTED_JWT>", body)
        sanitized = BEARER_REGEX.sub("Bearer <REDACTED>", sanitized)
        # Redact passwords/keys in JSON-like fields
        sanitized = re.sub(
            r'("(?:password|secret|token|apiKey|key|auth)"\s*:\s*)"[^"]+"',
            r'\1"<REDACTED>"',
            sanitized,
            flags=re.IGNORECASE
        )
        return sanitized

    @staticmethod
    def sanitize_captured_xhr(xhr_item: Dict[str, Any]) -> Dict[str, Any]:
        """Deep sanitization of a captured network request/response record."""
        sanitized = dict(xhr_item)
        if "url" in sanitized:
            sanitized["url"] = XhrRedactor.sanitize_url(sanitized["url"])
        if "headers" in sanitized and isinstance(sanitized["headers"], dict):
            sanitized["headers"] = XhrRedactor.sanitize_headers(sanitized["headers"])
        if "responseHeaders" in sanitized and isinstance(sanitized["responseHeaders"], dict):
            sanitized["responseHeaders"] = XhrRedactor.sanitize_headers(sanitized["responseHeaders"])
        if "postData" in sanitized and sanitized["postData"]:
            sanitized["postData"] = XhrRedactor.sanitize_body(str(sanitized["postData"]))
        return sanitized
