import pytest
from tools.scraping.redaction import XhrRedactor

def test_sanitize_headers():
    headers = {
        "Authorization": "Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.doNotLeakThisSignature12345",
        "Cookie": "session_id=abcdef123456; token=secret",
        "User-Agent": "Mozilla/5.0",
        "X-Custom-Token": "secret-value",
        "Content-Type": "application/json"
    }
    sanitized = XhrRedactor.sanitize_headers(headers)
    assert sanitized["Authorization"] == "<REDACTED>"
    assert sanitized["Cookie"] == "<REDACTED>"
    assert sanitized["X-Custom-Token"] == "<REDACTED>"
    assert sanitized["User-Agent"] == "Mozilla/5.0"
    assert sanitized["Content-Type"] == "application/json"

def test_sanitize_media_url():
    url = "https://cdn.example.com/live/stream.m3u8?token=xyz123&expires=1700000000"
    sanitized = XhrRedactor.sanitize_url(url)
    assert "token" not in sanitized
    assert "expires" not in sanitized
    assert "<REDACTED_QUERY>" in sanitized
    assert sanitized.startswith("https://cdn.example.com/live/stream.m3u8?")

def test_sanitize_query_params():
    url = "https://api.example.com/titles?search=naruto&auth=secret123&token=tok456"
    sanitized = XhrRedactor.sanitize_url(url)
    assert "naruto" in sanitized
    assert "secret123" not in sanitized
    assert "tok456" not in sanitized
    assert "auth=%3CREDACTED%3E" in sanitized or "auth=<REDACTED>" in sanitized

def test_sanitize_body():
    body = '{"username": "testuser", "password": "supersecretpassword", "jwt": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.doNotLeakThisSignature12345"}'
    sanitized = XhrRedactor.sanitize_body(body)
    assert "supersecretpassword" not in sanitized
    assert "<REDACTED>" in sanitized
    assert "<REDACTED_JWT>" in sanitized
