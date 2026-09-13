from unittest.mock import MagicMock
import pytest
from tools.scraping.models import FetchMode, FetchStatus, FetchResult
from tools.scraping.fetch import ProviderFetcher

class MockResponse:
    def __init__(self, status=200, url="https://target.test/home", body="<html><body>Content</body></html>", headers=None):
        self.status = status
        self.url = url
        self.html_content = body
        self.body = body.encode("utf-8")
        self.headers = headers or {}

def test_fetch_http_success_no_browser_fallback(monkeypatch):
    """Verifies that HTTP success returns immediately and never touches browser tiers."""
    fetcher = ProviderFetcher(allowed_hosts={"target.test"}, canonical="https://target.test", timeout=10)
    
    mock_get = MagicMock(return_value=MockResponse(status=200, url="https://target.test/home", body="<html>Valid Content</html>"))
    monkeypatch.setattr(fetcher, "_fetch_http", lambda url, t0, **kwargs: FetchResult(
        requestedUrl=url,
        finalUrl="https://target.test/home",
        statusCode=200,
        fetchMode=FetchMode.HTTP,
        status=FetchStatus.SUCCESS,
        body="<html>Valid Content</html>"
    ))
    mock_browser = MagicMock()
    monkeypatch.setattr(fetcher, "_fetch_browser", mock_browser)

    res = fetcher.fetch("https://target.test/home")
    assert res.status == FetchStatus.SUCCESS
    assert res.fetchMode == FetchMode.HTTP
    assert mock_browser.call_count == 0

def test_cloudflare_escalates_directly_to_stealth(monkeypatch):
    """
    Verifies that when HTTP returns a Cloudflare challenge, fetcher escalates
    DIRECTLY to STEALTH mode (skipping DYNAMIC) and requests solve_cloudflare=True.
    """
    fetcher = ProviderFetcher(allowed_hosts={"target.test"}, canonical="https://target.test", timeout=10)

    # 1. HTTP returns Cloudflare challenge
    monkeypatch.setattr(fetcher, "_fetch_http", lambda url, t0, **kwargs: FetchResult(
        requestedUrl=url,
        finalUrl=url,
        statusCode=403,
        fetchMode=FetchMode.HTTP,
        status=FetchStatus.CLOUDFLARE,
        cloudflare=True,
        blocked=True,
        body="<html><title>Just a moment...</title></html>"
    ))

    browser_calls = []
    def mock_fetch_browser(url, t0, stealth=False, capture_xhr=False, headers=None):
        browser_calls.append({"stealth": stealth, "capture_xhr": capture_xhr})
        return FetchResult(
            requestedUrl=url,
            finalUrl=url,
            statusCode=200,
            fetchMode=FetchMode.STEALTH if stealth else FetchMode.DYNAMIC,
            status=FetchStatus.SUCCESS,
            body="<html>Solved content</html>",
            browserUsed=True
        )

    monkeypatch.setattr(fetcher, "_fetch_browser", mock_fetch_browser)

    res = fetcher.fetch("https://target.test/home", allow_dynamic_fallback=True, allow_stealth_fallback=True)
    
    assert res.status == FetchStatus.SUCCESS
    assert res.fetchMode == FetchMode.STEALTH
    # Verify directly escalated to STEALTH (1 browser call total, stealth=True)
    assert len(browser_calls) == 1
    assert browser_calls[0]["stealth"] is True

def test_content_marker_missing_after_all_tiers_fails_closed(monkeypatch):
    """Verifies that if expected markers are missing across all tiers, CONTENT_MARKER_MISMATCH is returned."""
    fetcher = ProviderFetcher(allowed_hosts={"target.test"}, canonical="https://target.test", timeout=10)

    monkeypatch.setattr(fetcher, "_fetch_http", lambda url, t0, **kwargs: FetchResult(
        requestedUrl=url,
        finalUrl=url,
        statusCode=200,
        fetchMode=FetchMode.HTTP,
        status=FetchStatus.SUCCESS,
        body="<html>Parked Domain Page</html>"
    ))
    monkeypatch.setattr(fetcher, "_fetch_browser", lambda url, t0, stealth=False, **kwargs: FetchResult(
        requestedUrl=url,
        finalUrl=url,
        statusCode=200,
        fetchMode=FetchMode.STEALTH if stealth else FetchMode.DYNAMIC,
        status=FetchStatus.SUCCESS,
        body="<html>Parked Domain Page</html>"
    ))

    res = fetcher.fetch(
        "https://target.test/home",
        expected_markers=["LegitimateProviderMarker"],
        allow_dynamic_fallback=True,
        allow_stealth_fallback=True
    )
    assert res.status == FetchStatus.CONTENT_MARKER_MISMATCH
    assert "Expected markers missing" in res.error

def test_capture_xhr_forces_browser_pass(monkeypatch):
    """Verifies that when capture_xhr=True, HTTP tier is bypassed in favor of browser execution."""
    fetcher = ProviderFetcher(allowed_hosts={"target.test"}, canonical="https://target.test", timeout=10)

    mock_http = MagicMock()
    monkeypatch.setattr(fetcher, "_fetch_http", mock_http)

    mock_browser = MagicMock(return_value=FetchResult(
        requestedUrl="https://target.test",
        finalUrl="https://target.test",
        statusCode=200,
        fetchMode=FetchMode.DYNAMIC,
        status=FetchStatus.SUCCESS,
        browserUsed=True,
        capturedXhr=[{"url": "https://api.test/data", "method": "GET", "resourceType": "xhr"}]
    ))
    monkeypatch.setattr(fetcher, "_fetch_browser", mock_browser)

    res = fetcher.fetch("https://target.test", preferred_mode=FetchMode.HTTP, capture_xhr=True)
    assert res.browserUsed is True
    assert mock_http.call_count == 0
    assert mock_browser.call_count == 1
    assert len(res.capturedXhr) == 1

def test_session_lifecycle_cleanup():
    """Verifies that session managers are properly exited and cleaned up on close and context exit."""
    fetcher = ProviderFetcher(timeout=10)
    
    mock_http_mgr = MagicMock()
    mock_http_client = MagicMock()
    mock_dyn_mgr = MagicMock()
    mock_dyn_client = MagicMock()
    mock_stealth_mgr = MagicMock()
    mock_stealth_client = MagicMock()

    fetcher._http_session_manager = mock_http_mgr
    fetcher._http_client = mock_http_client
    fetcher._dynamic_session_manager = mock_dyn_mgr
    fetcher._dynamic_client = mock_dyn_client
    fetcher._stealth_session_manager = mock_stealth_mgr
    fetcher._stealth_client = mock_stealth_client

    fetcher.close()

    assert mock_http_mgr.__exit__.call_count == 1
    assert mock_dyn_mgr.__exit__.call_count == 1
    assert mock_stealth_mgr.__exit__.call_count == 1
    assert fetcher._http_client is None
    assert fetcher._dynamic_client is None
    assert fetcher._stealth_client is None
