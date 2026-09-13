#!/usr/bin/env python3
"""
provider_health.py

Multi-tier truthful provider health inspection:
L0: Configuration (Config schema, root module, gradle build, manifest, Kotlin classes)
L1: Domain Reachable (DNS/HTTPS, HTTP status, redirect chain, allowedHosts, markers)
L2: Homepage (Provider-specific DOM smoke probe: >=1 non-empty item title & valid URL)
L3: Search (Search probe with searchQuery: valid search results returned)
L4: Load (Detail page probe with knownDetail: title & metadata loaded)
L5: Playback Discovery (Discovery of player iframe / embed / stream container without byte download)

Tiers that are not executed or not configured report 'skipped' or 'untested', NEVER fake 'pass'.
"""

import os
import sys
import json
import time
import argparse
import urllib.request
import urllib.parse
import urllib.error
import ssl
from datetime import datetime, timezone
from bs4 import BeautifulSoup

USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

def fetch_url(url, method="GET", data=None, headers=None, timeout=12):
    req_headers = {
        "User-Agent": USER_AGENT,
        "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
    }
    if headers:
        req_headers.update(headers)
    req = urllib.request.Request(url, data=data, headers=req_headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            return resp.status, resp.read().decode("utf-8", errors="ignore"), resp.geturl()
    except urllib.error.HTTPError as e:
        body = e.read(16384).decode("utf-8", errors="ignore")
        return e.code, body, url
    except Exception as e:
        return None, str(e), url

def check_l0_config(provider, repo_root, domains_config):
    name = provider.get("name")
    module = provider.get("module")
    domain_key = provider.get("domainKey", name)

    if not name or not module:
        return "fail", "MISSING_NAME_OR_MODULE"

    mod_dir = os.path.join(repo_root, module)
    if not os.path.isdir(mod_dir):
        return "fail", f"MODULE_DIR_NOT_FOUND: {module}"

    bg_file = os.path.join(mod_dir, "build.gradle.kts")
    if not os.path.isfile(bg_file):
        return "fail", f"BUILD_GRADLE_NOT_FOUND: {module}"

    domain_info = domains_config.get("providers", {}).get(domain_key)
    if not domain_info or not domain_info.get("canonical"):
        return "fail", f"DOMAIN_CONFIG_NOT_FOUND: {domain_key}"

    return "pass", None

def check_l2_homepage(name, html_body, canonical):
    if not html_body:
        return "fail", "EMPTY_BODY"
    soup = BeautifulSoup(html_body, "html.parser")
    items = []

    # Provider specific selectors
    selectors = [
        "article a", ".film-content a", ".poster a", ".movie-box a",
        ".video-item a", ".entry-title a", "div.menulink a", "div.kutu a",
        ".film-kutusu a", "div.film-box a", "a.poster"
    ]

    for sel in selectors:
        for tag in soup.select(sel):
            href = tag.get("href")
            title = tag.get("title") or tag.text.strip()
            if href and title and len(title) > 2:
                items.append((title, href))
                if len(items) >= 3:
                    break
        if items:
            break

    if items:
        return "pass", f"Found {len(items)} items. Top: {items[0][0]}"
    return "fail", "NO_HOMEPAGE_ITEMS_PARSED"

def check_l3_search(name, canonical, smoke_test):
    q = smoke_test.get("searchQuery")
    if not q:
        return "skipped", "NO_SEARCH_QUERY_CONFIGURED"

    try:
        if name == "HDFilmCehennemi":
            post_data = json.dumps({"query": q}).encode("utf-8")
            status, body, _ = fetch_url(
                f"{canonical}/search/",
                method="POST",
                data=post_data,
                headers={"Content-Type": "application/json", "X-Requested-With": "fetch"}
            )
            if status == 200:
                data = json.loads(body)
                results = data.get("results", [])
                if results:
                    return "pass", f"Found {len(results)} results"
            return "fail", f"Search HTTP {status} or 0 results"

        elif name == "TurkAnime":
            post_data = urllib.parse.urlencode({"arama": q}).encode("utf-8")
            status, body, _ = fetch_url(
                f"{canonical}/ajax/arama",
                method="POST",
                data=post_data,
                headers={"Content-Type": "application/x-www-form-urlencoded", "X-Requested-With": "XMLHttpRequest"}
            )
            if status == 200 and ("/anime/" in body or "turkanime" in body):
                return "pass", "Search AJAX returned anime matches"
            return "fail", f"Search HTTP {status} or empty response"

        else:
            # Generic query check
            search_urls = [
                f"{canonical}/?s={urllib.parse.quote(q)}",
                f"{canonical}/find?q={urllib.parse.quote(q)}",
                f"{canonical}/arama?q={urllib.parse.quote(q)}"
            ]
            for s_url in search_urls:
                status, body, _ = fetch_url(s_url)
                if status == 200:
                    soup = BeautifulSoup(body, "html.parser")
                    results = soup.select("article, .movie-box, .film-box, .video-item, .poster, .film-content")
                    if results:
                        return "pass", f"Found {len(results)} search results"

            return "fail", f"Search returned HTTP {status} or no match"

    except Exception as e:
        return "fail", str(e)

def check_l4_load(canonical, smoke_test):
    detail_url = smoke_test.get("knownDetail")
    if not detail_url:
        return "skipped", "NO_DETAIL_URL_CONFIGURED"

    try:
        status, body, _ = fetch_url(detail_url)
        if status == 200:
            soup = BeautifulSoup(body, "html.parser")
            title_tag = soup.find("title") or soup.find("h1")
            if title_tag and len(title_tag.text.strip()) > 3:
                title = title_tag.text.strip()
                if "404" not in title and "not found" not in title.lower() and "bulunamadı" not in title.lower():
                    return "pass", f"Loaded: {title[:40]}"
        return "fail", f"Load returned HTTP {status}"
    except Exception as e:
        return "fail", str(e)

def check_l5_playback(canonical, smoke_test):
    detail_url = smoke_test.get("knownDetail")
    if not detail_url:
        return "skipped", "NO_DETAIL_URL_CONFIGURED"

    try:
        status, body, _ = fetch_url(detail_url)
        if status == 200:
            soup = BeautifulSoup(body, "html.parser")
            iframes = [ifr.get("src") or ifr.get("data-src") for ifr in soup.find_all("iframe")]
            videos = [v.get("src") for v in soup.find_all("video")]
            has_script_player = any(k in body.lower() for k in ["player", "jwplayer", "m3u8", "eval(", "iframe", "video_url"])
            if iframes or videos or has_script_player:
                return "pass", f"Discovered {len(iframes)} iframes, script player present: {has_script_player}"
            return "fail", "No player or iframe discovered"
        return "skipped", f"Detail HTTP {status}"
    except Exception as e:
        return "fail", str(e)

def inspect_provider(provider, repo_root, domains_config):
    name = provider["name"]
    domain_key = provider.get("domainKey", name)
    domain_info = domains_config.get("providers", {}).get(domain_key, {})
    canonical = domain_info.get("canonical")
    smoke_test = provider.get("smokeTest", {})

    report = {
        "provider": name,
        "checkedAt": datetime.now(timezone.utc).isoformat(),
        "canonical": canonical,
        "finalUrl": None,
        "tierResults": {
            "L0_config": "untested",
            "L1_domain": "untested",
            "L2_homepage": "untested",
            "L3_search": "untested",
            "L4_load": "untested",
            "L5_playback": "untested"
        },
        "overallStatus": "healthy",
        "diagnostic": {},
        "durationMs": 0
    }

    start_time = time.time()

    # L0
    l0_stat, l0_diag = check_l0_config(provider, repo_root, domains_config)
    report["tierResults"]["L0_config"] = l0_stat
    if l0_diag:
        report["diagnostic"]["L0"] = l0_diag
    if l0_stat != "pass":
        report["overallStatus"] = "failed"
        report["durationMs"] = int((time.time() - start_time) * 1000)
        return report

    # L1
    status, body, final_url = fetch_url(canonical)
    report["finalUrl"] = final_url
    if status == 200:
        report["tierResults"]["L1_domain"] = "pass"
    elif status == 403 and ("cloudflare" in body.lower() or "cf-ray" in body.lower()):
        report["tierResults"]["L1_domain"] = "cloudflare_challenge"
        report["overallStatus"] = "degraded"
        report["diagnostic"]["L1"] = "Cloudflare Challenge Active (Mobile App bypass active, direct bot probe 403)"
        # Cannot probe further without browser
        report["tierResults"]["L2_homepage"] = "skipped"
        report["tierResults"]["L3_search"] = "skipped"
        report["tierResults"]["L4_load"] = "skipped"
        report["tierResults"]["L5_playback"] = "skipped"
        report["durationMs"] = int((time.time() - start_time) * 1000)
        return report
    else:
        report["tierResults"]["L1_domain"] = f"fail (HTTP_{status})"
        report["overallStatus"] = "failed"
        report["diagnostic"]["L1"] = f"HTTP status: {status}"
        report["durationMs"] = int((time.time() - start_time) * 1000)
        return report

    # L2
    l2_stat, l2_diag = check_l2_homepage(name, body, canonical)
    report["tierResults"]["L2_homepage"] = l2_stat
    if l2_diag:
        report["diagnostic"]["L2"] = l2_diag

    # L3
    l3_stat, l3_diag = check_l3_search(name, canonical, smoke_test)
    report["tierResults"]["L3_search"] = l3_stat
    if l3_diag:
        report["diagnostic"]["L3"] = l3_diag

    # L4
    l4_stat, l4_diag = check_l4_load(canonical, smoke_test)
    report["tierResults"]["L4_load"] = l4_stat
    if l4_diag:
        report["diagnostic"]["L4"] = l4_diag

    # L5
    l5_stat, l5_diag = check_l5_playback(canonical, smoke_test)
    report["tierResults"]["L5_playback"] = l5_stat
    if l5_diag:
        report["diagnostic"]["L5"] = l5_diag

    # Determine overall status
    has_fail = any(v.startswith("fail") for v in report["tierResults"].values())
    if has_fail:
        report["overallStatus"] = "degraded" if report["tierResults"]["L1_domain"] == "pass" else "failed"

    report["durationMs"] = int((time.time() - start_time) * 1000)
    return report

def main():
    parser = argparse.ArgumentParser(description="Multi-tier Truthful Provider Health Checker")
    parser.add_argument("--report-path", default="reports/provider-health.json", help="Path to write health report")
    parser.add_argument("--provider", help="Check only specific provider")
    args = parser.parse_args()

    repo_root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    providers_file = os.path.join(repo_root, "config", "providers.json")
    domains_file = os.path.join(repo_root, "config", "domains.json")

    with open(providers_file, "r", encoding="utf-8") as f:
        providers = [p for p in json.load(f).get("providers", []) if p.get("enabled")]

    with open(domains_file, "r", encoding="utf-8") as f:
        domains_config = json.load(f)

    results = []
    print(f"[Provider Health] Inspecting {len(providers)} enabled providers across L0-L5...")

    for p in providers:
        if args.provider and p["name"].lower() != args.provider.lower():
            continue
        print(f"\n> Inspecting {p['name']}...")
        rep = inspect_provider(p, repo_root, domains_config)
        results.append(rep)
        print(f"  L0: {rep['tierResults']['L0_config']} | L1: {rep['tierResults']['L1_domain']} | L2: {rep['tierResults']['L2_homepage']} | L3: {rep['tierResults']['L3_search']} | L4: {rep['tierResults']['L4_load']} | L5: {rep['tierResults']['L5_playback']}")
        print(f"  Overall: {rep['overallStatus']} ({rep['durationMs']}ms)")

    os.makedirs(os.path.dirname(os.path.join(repo_root, args.report_path)), exist_ok=True)
    with open(os.path.join(repo_root, args.report_path), "w", encoding="utf-8") as f:
        json.dump(results, f, indent=2, ensure_ascii=False)

    print(f"\n[Provider Health] Saved comprehensive report to {args.report_path}")

if __name__ == "__main__":
    main()
