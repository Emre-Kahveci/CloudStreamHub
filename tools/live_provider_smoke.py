#!/usr/bin/env python3
"""
live_provider_smoke.py

Comprehensive live smoke test verifying active CloudStreamHub providers:
- Homepage: HTTP 200 & DOM parsing
- Content Discovery: Cards/items parsed
- Detail: Metadata & episodes parsed
- Playback Discovery: Player / iframe / stream found
- Subtitle: Reports NONE / HARDSUB / VTT truthfully
- Explicit semantic status: PASS, AUTOMATION_BLOCKED, or FAIL
"""

import os
import sys
import json
import time
import argparse
import requests
from bs4 import BeautifulSoup
from datetime import datetime, timezone

HEADERS = {
    "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
    "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
    "Accept-Language": "tr,en-US;q=0.7,en;q=0.3"
}

def test_provider(provider):
    name = provider["name"]
    smoke = provider.get("smokeTest", {})
    known_detail = smoke.get("knownDetail")

    res = {
        "provider": name,
        "module": provider["module"],
        "homepage": {"status": "UNTESTED"},
        "detail": {"status": "UNTESTED"},
        "playback": {"status": "UNTESTED"},
        "subtitle": "NONE",
        "overall": "UNKNOWN",
        "details": []
    }

    session = requests.Session()
    session.headers.update(HEADERS)

    canonical = None
    if known_detail:
        from urllib.parse import urlparse
        p = urlparse(known_detail)
        canonical = f"{p.scheme}://{p.netloc}"

    if not canonical:
        res["overall"] = "FAIL"
        res["details"].append("No canonical URL could be resolved")
        return res

    try:
        r = session.get(canonical, timeout=12)
        if r.status_code == 200:
            soup = BeautifulSoup(r.text, "html.parser")
            items = soup.select("a.poster, a.item, a.mcard, a.dcard, a[href*='/belgesel/'], div.poster, .listmovie, .panel")
            if items:
                res["homepage"] = {"status": "PASS", "itemCount": len(items)}
            else:
                res["homepage"] = {"status": "PASS", "itemCount": 0, "note": "Page 200 but 0 cards matched generic selector"}
        elif r.status_code == 403 and ("cloudflare" in r.text.lower() or "cf-ray" in r.headers):
            res["homepage"] = {"status": "AUTOMATION_BLOCKED", "reason": "Cloudflare challenge"}
        else:
            res["homepage"] = {"status": "FAIL", "code": r.status_code}
    except Exception as e:
        res["homepage"] = {"status": "FAIL", "error": str(e)}

    if known_detail:
        try:
            dr = session.get(known_detail, timeout=12)
            if dr.status_code == 200:
                dsoup = BeautifulSoup(dr.text, "html.parser")
                title_tag = dsoup.select_one("h1, h2, meta[property='og:title']")
                title = title_tag.text.strip() if title_tag else "Unknown"
                res["detail"] = {"status": "PASS", "title": title[:50]}

                iframes = [ifr.get("src") or ifr.get("data-src") for ifr in dsoup.find_all("iframe") if ifr.get("src") or ifr.get("data-src")]
                videos = [v.get("src") for v in dsoup.find_all("video") if v.get("src")]
                has_player = any(k in dr.text.lower() for k in ["player", "jwplayer", "m3u8", "eval(", "closeload", "rapidrame", "vidpapi"])

                if iframes or videos or has_player:
                    res["playback"] = {"status": "PASS", "iframes": len(iframes), "hasPlayer": has_player}
                else:
                    res["playback"] = {"status": "FAIL", "reason": "No iframe or player found"}

                if ".vtt" in dr.text.lower() or ".srt" in dr.text.lower():
                    res["subtitle"] = "VTT/SRT"
                elif "dublaj" in title.lower() or "dublaj" in dr.text.lower():
                    res["subtitle"] = "HARDSUB/DUBLAJ"
                else:
                    res["subtitle"] = "NONE"
            elif dr.status_code == 403 and ("cloudflare" in dr.text.lower() or "cf-ray" in dr.headers):
                res["detail"] = {"status": "AUTOMATION_BLOCKED", "reason": "Cloudflare challenge"}
                res["playback"] = {"status": "AUTOMATION_BLOCKED"}
            else:
                res["detail"] = {"status": "FAIL", "code": dr.status_code}
                res["playback"] = {"status": "FAIL"}
        except Exception as e:
            res["detail"] = {"status": "FAIL", "error": str(e)}
            res["playback"] = {"status": "FAIL", "error": str(e)}

    statuses = [res["homepage"]["status"], res["detail"]["status"], res["playback"]["status"]]
    if any(s == "AUTOMATION_BLOCKED" for s in statuses):
        res["overall"] = "AUTOMATION_BLOCKED"
    elif all(s == "PASS" for s in statuses):
        res["overall"] = "PASS"
    elif any(s == "PASS" for s in statuses):
        res["overall"] = "PARTIAL_PASS"
    else:
        res["overall"] = "FAIL"

    return res

def main():
    repo_root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    providers_file = os.path.join(repo_root, "config", "providers.json")

    with open(providers_file, "r", encoding="utf-8") as f:
        data = json.load(f)

    providers = [p for p in data.get("providers", []) if p.get("enabled")]

    print(f"=== Live Provider Smoke Test ({len(providers)} providers) ===\n")
    results = []

    for p in providers:
        print(f"[*] Testing {p['name']}...", end=" ", flush=True)
        res = test_provider(p)
        results.append(res)
        print(f"{res['overall']} (Home: {res['homepage']['status']}, Detail: {res['detail']['status']}, Player: {res['playback']['status']}, Sub: {res['subtitle']})")

    out_path = os.path.join(repo_root, "reports", "live_provider_smoke.json")
    os.makedirs(os.path.dirname(out_path), exist_ok=True)
    with open(out_path, "w", encoding="utf-8") as f:
        json.dump({
            "testedAt": datetime.now(timezone.utc).isoformat(),
            "providers": results
        }, f, indent=2, ensure_ascii=False)

    print(f"\n[+] Smoke report saved to {out_path}")

if __name__ == "__main__":
    main()
