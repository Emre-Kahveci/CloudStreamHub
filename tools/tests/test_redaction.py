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

def test_redacts_animecix_x_e_h_header():
    headers = {
        "x-e-h": "8f3b2a1c9e7d6f5a4b3c2d1e0f",
        "X-E-H": "CaseInsensitiveCheck123",
        "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64)"
    }
    sanitized = XhrRedactor.sanitize_headers(headers)
    assert sanitized["x-e-h"] == "<REDACTED>"
    assert sanitized["X-E-H"] == "<REDACTED>"
    assert sanitized["User-Agent"] == "Mozilla/5.0 (Windows NT 10.0; Win64; x64)"

def test_redacts_auth_like_custom_headers():
    headers = {
        "x-custom-token": "tok123",
        "x-client-auth": "auth_val",
        "x-api-key": "secret_key_val",
        "api-key": "key999",
        "x-service-key": "svc_key",
        "User-Agent": "Mozilla/5.0",
        "Accept": "text/html,application/xhtml+xml",
        "Content-Type": "application/json",
        "Origin": "https://animecix.tv",
        "Referer": "https://animecix.tv/",
        "Sec-Ch-Ua": '"Chromium";v="130"'
    }
    sanitized = XhrRedactor.sanitize_headers(headers)
    assert sanitized["x-custom-token"] == "<REDACTED>"
    assert sanitized["x-client-auth"] == "<REDACTED>"
    assert sanitized["x-api-key"] == "<REDACTED>"
    assert sanitized["api-key"] == "<REDACTED>"
    assert sanitized["x-service-key"] == "<REDACTED>"
    # Normal headers must be preserved
    assert sanitized["User-Agent"] == "Mozilla/5.0"
    assert sanitized["Accept"] == "text/html,application/xhtml+xml"
    assert sanitized["Content-Type"] == "application/json"
    assert sanitized["Origin"] == "https://animecix.tv"
    assert sanitized["Referer"] == "https://animecix.tv/"
    assert sanitized["Sec-Ch-Ua"] == '"Chromium";v="130"'

def test_redacts_token_like_body_fields():
    body = (
        '{\n'
        '  "username": "public_user",\n'
        '  "siteToken": "st_998877",\n'
        '  "accessToken": "at_112233",\n'
        '  "refreshToken": "rt_445566",\n'
        '  "csrfToken": "csrf_aabbcc",\n'
        '  "authToken": "auth_xxyyzz",\n'
        '  "apiToken": "apitok_778899",\n'
        '  "clientSecret": "sec_001122",\n'
        '  "apiKey": "ak_334455",\n'
        '  "password": "super_secret_pw",\n'
        '  "credential": "cred_val",\n'
        '  "key": "raw_key_val",\n'
        '  "title": "Naruto Shippuden",\n'
        '  "description": "A great anime"\n'
        '}'
    )
    sanitized = XhrRedactor.sanitize_body(body)
    assert '"username": "public_user"' in sanitized
    assert '"title": "Naruto Shippuden"' in sanitized
    assert '"description": "A great anime"' in sanitized
    assert '"siteToken": "<REDACTED>"' in sanitized
    assert '"accessToken": "<REDACTED>"' in sanitized
    assert '"refreshToken": "<REDACTED>"' in sanitized
    assert '"csrfToken": "<REDACTED>"' in sanitized
    assert '"authToken": "<REDACTED>"' in sanitized
    assert '"apiToken": "<REDACTED>"' in sanitized
    assert '"clientSecret": "<REDACTED>"' in sanitized
    assert '"apiKey": "<REDACTED>"' in sanitized
    assert '"password": "<REDACTED>"' in sanitized
    assert '"credential": "<REDACTED>"' in sanitized
    assert '"key": "<REDACTED>"' in sanitized

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

def test_sanitize_body_jwt_and_bearer():
    body = '{"username": "testuser", "password": "supersecretpassword", "jwt": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.doNotLeakThisSignature12345", "auth": "Bearer secret_bearer_token"}'
    sanitized = XhrRedactor.sanitize_body(body)
    assert "supersecretpassword" not in sanitized
    assert "secret_bearer_token" not in sanitized
    assert "<REDACTED>" in sanitized
    assert "<REDACTED_JWT>" in sanitized
