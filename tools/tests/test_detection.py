import pytest
from tools.scraping.detection import (
    is_cloudflare_challenge,
    is_bot_blocked,
    verify_redirect_safety,
    verify_content_markers
)

def test_is_cloudflare_challenge():
    cf_body = "<html><title>Just a moment...</title><body><div id='challenge-running'>Checking your browser...</div></body></html>"
    assert is_cloudflare_challenge(403, cf_body) is True
    assert is_cloudflare_challenge(200, cf_body) is True
    assert is_cloudflare_challenge(200, "<html><head><title>Welcome</title></head></html>") is False

def test_is_bot_blocked():
    assert is_bot_blocked(403, "Access Denied") is True
    assert is_bot_blocked(429, "Too Many Requests") is True
    assert is_bot_blocked(200, "Normal Page") is False

def test_verify_redirect_safety():
    allowed = {"example.com", "www.example.com", "cdn.example.com"}
    safe, cand = verify_redirect_safety("https://example.com/home", allowed, "https://example.com")
    assert safe is True
    assert cand is None

    safe, cand = verify_redirect_safety("https://sub.example.com/test", allowed, "https://example.com")
    assert safe is True

    safe, cand = verify_redirect_safety("https://malicious-redirect.com/login", allowed, "https://example.com")
    assert safe is False
    assert cand == "malicious-redirect.com"

def test_verify_content_markers():
    body = "<html><body><h1>Yeşilçam Klasikleri</h1></body></html>"
    assert verify_content_markers(body, ["Yeşilçam", "yesilcam"]) is True
    assert verify_content_markers(body, ["AnimeciX", "anime"]) is False
