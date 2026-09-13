"""
fetch.py

3-Tier ProviderFetcher abstraction implementing HTTP -> DYNAMIC -> STEALTH strategy.
Integrated with Scrapling (Fetcher, DynamicFetcher, StealthyFetcher), redirect safety checks,
XHR interception, and security redaction.
"""

import os
import time
import json
import logging
from typing import Optional, Set, List, Dict, Any, Callable
from urllib.parse import urlparse

from .models import FetchMode, FetchStatus, FetchResult, CapturedXhr
from .detection import (
    is_cloudflare_challenge,
    is_bot_blocked,
    verify_redirect_safety,
    verify_content_markers
)
from .redaction import XhrRedactor

logger = logging.getLogger("ProviderFetcher")

class ProviderFetcher:
    """
    Central fetch engine for CloudStreamHub monitoring and research.
    
    Tiers:
    - HTTP (Tier 1): High speed, curl_cffi with browser impersonation via Scrapling Fetcher.
    - DYNAMIC (Tier 2): Headless Playwright browser via Scrapling DynamicFetcher for JS hydration.
    - STEALTH (Tier 3): Anti-detect Playwright browser via Scrapling StealthyFetcher for Cloudflare/WAF bypass.
    """

    def __init__(
        self,
        allowed_hosts: Optional[Set[str]] = None,
        canonical: Optional[str] = None,
        timeout: int = 15,
        user_agent: Optional[str] = None
    ):
        self.allowed_hosts = allowed_hosts or set()
        self.canonical = canonical
        self.timeout = timeout
        self.user_agent = user_agent

        # Session cache for reuse
        self._http_session = None
        self._dynamic_session = None
        self._stealth_session = None

    def close(self):
        """Releases active browser sessions and network resources."""
        if self._dynamic_session:
            try:
                self._dynamic_session.close()
            except Exception:
                pass
            self._dynamic_session = None

        if self._stealth_session:
            try:
                self._stealth_session.close()
            except Exception:
                pass
            self._stealth_session = None

        if self._http_session:
            try:
                self._http_session.close()
            except Exception:
                pass
            self._http_session = None

    def __enter__(self):
        return self

    def __exit__(self, exc_type, exc_val, exc_tb):
        self.close()

    def fetch(
        self,
        url: str,
        preferred_mode: FetchMode = FetchMode.HTTP,
        allow_dynamic_fallback: bool = True,
        allow_stealth_fallback: bool = True,
        capture_xhr: bool = False,
        expected_markers: Optional[List[str]] = None,
        method: str = "GET",
        data: Optional[Any] = None,
        headers: Optional[Dict[str, str]] = None
    ) -> FetchResult:
        """
        Executes request following preferred mode and fallback rules.
        """
        # Mode execution order determination
        modes_to_try = [preferred_mode]
        if preferred_mode == FetchMode.HTTP:
            if allow_dynamic_fallback:
                modes_to_try.append(FetchMode.DYNAMIC)
            if allow_stealth_fallback and FetchMode.STEALTH not in modes_to_try:
                modes_to_try.append(FetchMode.STEALTH)
        elif preferred_mode == FetchMode.DYNAMIC:
            if allow_stealth_fallback:
                modes_to_try.append(FetchMode.STEALTH)

        last_result = None

        for mode in modes_to_try:
            res = self._fetch_single(
                url=url,
                mode=mode,
                capture_xhr=capture_xhr,
                method=method,
                data=data,
                headers=headers
            )
            last_result = res

            # If untrusted redirect or network error, do not retry other modes
            if res.status == FetchStatus.UNTRUSTED_REDIRECT:
                return res

            # Check if markers match if specified
            if res.status == FetchStatus.SUCCESS and expected_markers:
                if not verify_content_markers(res.body, expected_markers):
                    # Content marker missing might indicate a soft-block or JS-rendered shell
                    if mode != FetchMode.STEALTH and (allow_dynamic_fallback or allow_stealth_fallback):
                        continue

            # If success, return immediately
            if res.status == FetchStatus.SUCCESS and not res.cloudflare and not res.blocked:
                return res

            # If Cloudflare detected, dynamic alone won't solve it -> continue to stealth if allowed
            if res.cloudflare and mode != FetchMode.STEALTH and allow_stealth_fallback:
                continue

            # If blocked (403/WAF), try stealth
            if res.blocked and mode != FetchMode.STEALTH and allow_stealth_fallback:
                continue

        return last_result if last_result else FetchResult(
            requestedUrl=url,
            finalUrl=url,
            statusCode=None,
            fetchMode=preferred_mode,
            status=FetchStatus.NETWORK_ERROR,
            error="No fetch attempts succeeded"
        )

    def _fetch_single(
        self,
        url: str,
        mode: FetchMode,
        capture_xhr: bool = False,
        method: str = "GET",
        data: Optional[Any] = None,
        headers: Optional[Dict[str, str]] = None
    ) -> FetchResult:
        t0 = time.time()
        captured_list: List[Dict[str, Any]] = []

        try:
            if mode == FetchMode.HTTP:
                return self._fetch_http(url, t0, method=method, data=data, headers=headers)
            elif mode == FetchMode.DYNAMIC:
                return self._fetch_browser(url, t0, stealth=False, capture_xhr=capture_xhr, headers=headers)
            elif mode == FetchMode.STEALTH:
                return self._fetch_browser(url, t0, stealth=True, capture_xhr=capture_xhr, headers=headers)
            else:
                raise ValueError(f"Unknown fetch mode: {mode}")
        except Exception as e:
            elapsed = int((time.time() - t0) * 1000)
            err_str = str(e)
            status = FetchStatus.NETWORK_ERROR
            if "timeout" in err_str.lower():
                status = FetchStatus.TIMEOUT
            return FetchResult(
                requestedUrl=url,
                finalUrl=url,
                statusCode=None,
                fetchMode=mode,
                status=status,
                elapsedMs=elapsed,
                error=err_str
            )

    def _fetch_http(
        self,
        url: str,
        t0: float,
        method: str = "GET",
        data: Optional[Any] = None,
        headers: Optional[Dict[str, str]] = None
    ) -> FetchResult:
        from scrapling import Fetcher

        # Execute HTTP GET/POST with browser impersonation
        req_headers = headers or {}
        if method.upper() == "POST":
            resp = Fetcher.post(url, data=data, headers=req_headers, timeout=self.timeout)
        else:
            resp = Fetcher.get(url, headers=req_headers, timeout=self.timeout)

        elapsed = int((time.time() - t0) * 1000)
        final_url = getattr(resp, "url", url)
        status_code = getattr(resp, "status", None)
        
        # In Scrapling Response, body contains raw bytes and html_content contains parsed HTML string
        if hasattr(resp, "html_content") and resp.html_content:
            body = resp.html_content
        elif hasattr(resp, "body") and isinstance(resp.body, bytes):
            body = resp.body.decode("utf-8", errors="ignore")
        else:
            body = str(getattr(resp, "text", "") or "")

        resp_headers = getattr(resp, "headers", {}) or {}

        # 1. Verify redirect safety
        safe, candidate_host = verify_redirect_safety(final_url, self.allowed_hosts, self.canonical)
        if not safe and candidate_host:
            return FetchResult(
                requestedUrl=url,
                finalUrl=final_url,
                statusCode=status_code,
                fetchMode=FetchMode.HTTP,
                status=FetchStatus.UNTRUSTED_REDIRECT,
                elapsedMs=elapsed,
                candidateHost=candidate_host,
                error=f"Redirected to untrusted host: {candidate_host}"
            )

        # 2. Check Cloudflare Challenge
        is_cf = is_cloudflare_challenge(status_code, body, resp_headers)
        if is_cf:
            return FetchResult(
                requestedUrl=url,
                finalUrl=final_url,
                statusCode=status_code,
                fetchMode=FetchMode.HTTP,
                status=FetchStatus.CLOUDFLARE,
                cloudflare=True,
                blocked=True,
                elapsedMs=elapsed,
                body=body
            )

        # 3. Check General Anti-Bot Block
        is_blocked = is_bot_blocked(status_code, body)
        if is_blocked:
            return FetchResult(
                requestedUrl=url,
                finalUrl=final_url,
                statusCode=status_code,
                fetchMode=FetchMode.HTTP,
                status=FetchStatus.BLOCKED,
                blocked=True,
                elapsedMs=elapsed,
                body=body
            )

        # 4. Standard HTTP status check
        fetch_status = FetchStatus.SUCCESS if (status_code and 200 <= status_code < 400) else FetchStatus.NETWORK_ERROR

        return FetchResult(
            requestedUrl=url,
            finalUrl=final_url,
            statusCode=status_code,
            fetchMode=FetchMode.HTTP,
            status=fetch_status,
            elapsedMs=elapsed,
            body=body
        )

    def _fetch_browser(
        self,
        url: str,
        t0: float,
        stealth: bool = False,
        capture_xhr: bool = False,
        headers: Optional[Dict[str, str]] = None
    ) -> FetchResult:
        from scrapling import DynamicFetcher, StealthyFetcher

        fetcher_cls = StealthyFetcher if stealth else DynamicFetcher
        mode = FetchMode.STEALTH if stealth else FetchMode.DYNAMIC

        captured_raw: List[Dict[str, Any]] = []

        def page_setup_hook(page):
            if headers:
                try:
                    page.set_extra_http_headers(headers)
                except Exception:
                    pass

            if capture_xhr:
                def on_request(req):
                    try:
                        res_type = req.resource_type
                        if res_type in ("xhr", "fetch", "websocket", "media", "script"):
                            captured_raw.append({
                                "url": req.url,
                                "method": req.method,
                                "resourceType": res_type,
                                "headers": req.headers,
                                "postData": req.post_data
                            })
                    except Exception:
                        pass

                def on_response(resp):
                    try:
                        # Correlate response status and headers with captured request
                        resp_url = resp.url
                        for item in reversed(captured_raw):
                            if item["url"] == resp_url:
                                item["status"] = resp.status
                                item["responseHeaders"] = resp.headers
                                break
                    except Exception:
                        pass

                page.on("request", on_request)
                page.on("response", on_response)

        # Execute browser fetch
        fetch_kwargs = {
            "page_setup": page_setup_hook,
            "timeout": self.timeout * 1000
        }

        resp = fetcher_cls.fetch(url, **fetch_kwargs)
        elapsed = int((time.time() - t0) * 1000)

        final_url = getattr(resp, "url", url)
        status_code = getattr(resp, "status", 200)

        if hasattr(resp, "html_content") and resp.html_content:
            body = resp.html_content
        elif hasattr(resp, "body") and isinstance(resp.body, bytes):
            body = resp.body.decode("utf-8", errors="ignore")
        else:
            body = str(getattr(resp, "text", "") or "")

        resp_headers = getattr(resp, "headers", {}) or {}

        # Sanitize captured XHRs
        sanitized_xhrs = [XhrRedactor.sanitize_captured_xhr(x) for x in captured_raw]

        # 1. Verify redirect safety
        safe, candidate_host = verify_redirect_safety(final_url, self.allowed_hosts, self.canonical)
        if not safe and candidate_host:
            return FetchResult(
                requestedUrl=url,
                finalUrl=final_url,
                statusCode=status_code,
                fetchMode=mode,
                status=FetchStatus.UNTRUSTED_REDIRECT,
                browserUsed=True,
                elapsedMs=elapsed,
                candidateHost=candidate_host,
                capturedXhr=sanitized_xhrs,
                error=f"Redirected to untrusted host: {candidate_host}"
            )

        # 2. Check Cloudflare
        is_cf = is_cloudflare_challenge(status_code, body, resp_headers)
        if is_cf:
            return FetchResult(
                requestedUrl=url,
                finalUrl=final_url,
                statusCode=status_code,
                fetchMode=mode,
                status=FetchStatus.CLOUDFLARE,
                cloudflare=True,
                blocked=True,
                browserUsed=True,
                elapsedMs=elapsed,
                body=body,
                capturedXhr=sanitized_xhrs
            )

        # 3. Check General Anti-Bot Block
        is_blocked = is_bot_blocked(status_code, body)
        if is_blocked:
            return FetchResult(
                requestedUrl=url,
                finalUrl=final_url,
                statusCode=status_code,
                fetchMode=mode,
                status=FetchStatus.BLOCKED,
                blocked=True,
                browserUsed=True,
                elapsedMs=elapsed,
                body=body,
                capturedXhr=sanitized_xhrs
            )

        fetch_status = FetchStatus.SUCCESS if (status_code and 200 <= status_code < 400) else FetchStatus.NETWORK_ERROR

        return FetchResult(
            requestedUrl=url,
            finalUrl=final_url,
            statusCode=status_code,
            fetchMode=mode,
            status=fetch_status,
            browserUsed=True,
            elapsedMs=elapsed,
            body=body,
            capturedXhr=sanitized_xhrs
        )
