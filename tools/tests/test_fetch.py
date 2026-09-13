import pytest
from tools.scraping.models import FetchMode, FetchStatus
from tools.scraping.fetch import ProviderFetcher

def test_provider_fetcher_http_mock():
    # Test fetch against a reliable public endpoint
    with ProviderFetcher(timeout=10) as fetcher:
        res = fetcher.fetch(
            "https://example.com",
            preferred_mode=FetchMode.HTTP,
            allow_dynamic_fallback=False,
            allow_stealth_fallback=False
        )
        assert res.statusCode == 200
        assert res.fetchMode == FetchMode.HTTP
        assert res.status == FetchStatus.SUCCESS
        assert len(res.body) > 0

def test_provider_fetcher_untrusted_redirect():
    # Attempting to fetch example.com with allowed_hosts pointing strictly to another domain
    with ProviderFetcher(allowed_hosts={"onlyallowed.org"}, canonical="https://onlyallowed.org", timeout=10) as fetcher:
        res = fetcher.fetch(
            "https://example.com",
            preferred_mode=FetchMode.HTTP,
            allow_dynamic_fallback=False
        )
        assert res.status == FetchStatus.UNTRUSTED_REDIRECT
        assert res.candidateHost == "example.com"
