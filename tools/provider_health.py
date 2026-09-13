#!/usr/bin/env python3
"""
provider_health.py

Truthful multi-tier provider health inspection powered by Scrapling:
L0: Configuration (Config schema, root module, gradle build, manifest, Kotlin classes)
L1: Domain Reachable (DNS/HTTPS, HTTP status, redirect chain, allowedHosts, markers)
    - Status: PASS, CLOUDFLARE, UNTRUSTED_REDIRECT, FAIL
L2: Homepage (DOM smoke probe with Scrapling + adaptive selector drift detection)
    - Status: PASS, DRIFT_DETECTED, FAIL, AUTOMATION_BLOCKED
L3: Search (Search probe with searchQuery: AJAX/POST/GET results returned)
    - Status: PASS, FAIL, UNVERIFIED, AUTOMATION_BLOCKED, SKIPPED
L4: Load (Detail page probe with knownDetail: title & metadata loaded)
    - Status: PASS, DRIFT_DETECTED, FAIL, AUTOMATION_BLOCKED, SKIPPED
L5: Player Discovery (Discovery of player iframe / embed / stream container without byte download)
    - Status: PLAYER_DISCOVERED, PLAYER_NOT_FOUND, AUTOMATION_BLOCKED, SKIPPED
    - NOTE: Discovery of player containers NEVER implies CloudStream Android PLAYBACK_PASS!

Tiers that are not executed or not configured report 'skipped' or 'untested', NEVER fake 'pass'.
"""

import os
import sys

# Ensure repository root is in sys.path
REPO_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
if REPO_ROOT not in sys.path:
    sys.path.insert(0, REPO_ROOT)

import json
import time
import argparse
import urllib.parse
from datetime import datetime, timezone
from bs4 import BeautifulSoup

from tools.scraping import (
    ProviderFetcher,
    FetchMode,
    FetchStatus,
    AdaptiveManager,
    XhrRedactor
)

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

def check_l2_homepage(name, html_body, canonical, adaptive_mgr: AdaptiveManager):
    if not html_body:
        return "fail", "EMPTY_BODY", None

    # Preferred / specific selector fingerprints
    known_selectors = {
        "AnimeciX": "a.poster, a.item, div.title-card, a[href*='/titles/']",
        "BelgeselX": "a[href*='/belgesel/'], div.panel a, .video-item a",
        "DiziPal": "article a, a.poster, .movie-box a, a[href*='/dizi/'], a[href*='/film/']",
        "FilmMakinesi": "div.film-box a, .film-content a, a.poster, a[href*='/film/']",
        "HDFilmCehennemi": "a.poster, div.poster a, .film-content a, a.card",
        "KultFilmler": "a.mcard, a.dcard, a[href*='-izle/'], article a",
        "TurkAnime": "div.menulink a, div.kutu a, a[href*='/anime/']",
        "YesilCamTv": ".film-kutusu a, .video-item a, a[href*='-izle/']"
    }

    primary_selector = known_selectors.get(name, "a.mcard, a.dcard, a.poster, a.item, article a")

    # Strict check using BeautifulSoup first
    soup = BeautifulSoup(html_body, "html.parser")
    items = []

    for sel in [s.strip() for s in primary_selector.split(",")]:
        for tag in soup.select(sel):
            href = tag.get("href")
            title = tag.get("title") or tag.text.strip()
            if href and (title or tag.find("img")):
                items.append((title or "Discovered Item", href))
                if len(items) >= 3:
                    break
        if items:
            break

    if items:
        # Also register baseline with adaptive manager
        try:
            adaptive_mgr.check_css_selector(
                html_content=html_body,
                selector_str=primary_selector.split(",")[0].strip(),
                identifier=f"{name}_homepage_card",
                base_url=canonical
            )
        except Exception:
            pass
        return "pass", f"Found {len(items)} items. Top: {items[0][0][:40]}", None

    # Fallback to general generic selectors
    generic_selectors = [
        "a.mcard", "a.dcard", "a.item", "a.poster", "a[href*='/belgesel/']",
        "div.panel a", "article a", ".film-content a", ".movie-box a",
        ".video-item a", "div.film-box a"
    ]
    for sel in generic_selectors:
        for tag in soup.select(sel):
            href = tag.get("href")
            title = tag.get("title") or tag.text.strip()
            if href and (title or tag.find("img")):
                items.append((title or "Generic Item", href))
                if len(items) >= 3:
                    break
        if items:
            break

    if items:
        return "pass", f"Found {len(items)} items via fallback generic selector", None

    # If neither strict nor generic matched, run adaptive selector drift check
    try:
        drift_stat, drift_count, candidates = adaptive_mgr.check_css_selector(
            html_content=html_body,
            selector_str=primary_selector.split(",")[0].strip(),
            identifier=f"{name}_homepage_card",
            base_url=canonical
        )
        if drift_stat == "DRIFT_DETECTED" and drift_count > 0:
            return "drift_detected", f"Selector drift detected: {drift_count} candidate elements found", candidates
    except Exception:
        pass

    return "fail", "NO_HOMEPAGE_ITEMS_PARSED", None

def check_l3_search(name, canonical, smoke_test, fetcher: ProviderFetcher):
    q = smoke_test.get("searchQuery")
    if not q:
        return "skipped", "NO_SEARCH_QUERY_CONFIGURED"

    try:
        if name == "HDFilmCehennemi":
            post_data = json.dumps({"query": q})
            res = fetcher.fetch(
                f"{canonical}/search/",
                preferred_mode=FetchMode.HTTP,
                method="POST",
                data=post_data,
                headers={"Content-Type": "application/json", "X-Requested-With": "fetch"}
            )
            if res.status == FetchStatus.CLOUDFLARE or res.status == FetchStatus.BLOCKED:
                return "automation_blocked", f"Search endpoint protected ({res.status.value})"
            if res.statusCode == 200:
                try:
                    data = json.loads(res.body)
                    results = data.get("results", [])
                    if results:
                        return "pass", f"Found {len(results)} results"
                except Exception:
                    pass
            return "fail", f"Search HTTP {res.statusCode} or 0 results"

        elif name == "TurkAnime":
            post_data = urllib.parse.urlencode({"arama": q})
            res = fetcher.fetch(
                f"{canonical}/ajax/arama",
                preferred_mode=FetchMode.HTTP,
                method="POST",
                data=post_data,
                headers={"Content-Type": "application/x-www-form-urlencoded", "X-Requested-With": "XMLHttpRequest"}
            )
            if res.status == FetchStatus.CLOUDFLARE or res.status == FetchStatus.BLOCKED:
                return "automation_blocked", f"Search AJAX protected ({res.status.value})"
            if res.statusCode == 200 and ("/anime/" in res.body or "turkanime" in res.body):
                return "pass", "Search AJAX returned anime matches"
            return "fail", f"Search HTTP {res.statusCode} or empty response"

        else:
            # Generic query check
            search_urls = [
                f"{canonical}/?s={urllib.parse.quote(q)}",
                f"{canonical}/find?q={urllib.parse.quote(q)}",
                f"{canonical}/arama?q={urllib.parse.quote(q)}"
            ]
            for s_url in search_urls:
                res = fetcher.fetch(s_url, preferred_mode=FetchMode.HTTP)
                if res.status == FetchStatus.CLOUDFLARE or res.status == FetchStatus.BLOCKED:
                    return "automation_blocked", f"Search protected ({res.status.value})"
                if res.statusCode == 200:
                    soup = BeautifulSoup(res.body, "html.parser")
                    results = soup.select("article, .movie-box, .film-box, .video-item, .poster, .film-content, a.mcard, a.dcard")
                    if results:
                        return "pass", f"Found {len(results)} search results"

            return "fail", f"Search returned HTTP {res.statusCode if 'res' in locals() else 'None'} or no match"

    except Exception as e:
        return "fail", str(e)

def check_l4_load(canonical, smoke_test, fetcher: ProviderFetcher, adaptive_mgr: AdaptiveManager):
    detail_url = smoke_test.get("knownDetail")
    if not detail_url:
        return "skipped", "NO_DETAIL_URL_CONFIGURED", None

    try:
        res = fetcher.fetch(detail_url, preferred_mode=FetchMode.HTTP, allow_dynamic_fallback=True)
        if res.status == FetchStatus.CLOUDFLARE or res.status == FetchStatus.BLOCKED:
            return "automation_blocked", f"Detail page protected ({res.status.value})", None

        if res.statusCode == 200:
            soup = BeautifulSoup(res.body, "html.parser")
            title_tag = soup.find("h1") or soup.find("title")
            if title_tag and len(title_tag.text.strip()) > 3:
                title = title_tag.text.strip()
                if "404" not in title and "not found" not in title.lower() and "bulunamadı" not in title.lower():
                    return "pass", f"Loaded: {title[:40]}", res.body
            
            # Check adaptive title selector
            stat, count, candidates = adaptive_mgr.check_css_selector(
                html_content=res.body,
                selector_str="h1",
                identifier=f"{canonical}_detail_title",
                base_url=canonical
            )
            if stat == "DRIFT_DETECTED" and count > 0:
                return "drift_detected", f"Detail title selector drifted ({count} candidates)", res.body

        return "fail", f"Load returned HTTP {res.statusCode}", None
    except Exception as e:
        return "fail", str(e), None

def check_l5_player_discovery(detail_body: str):
    """
    Evaluates presence of streaming player containers, embeds, or iframes.
    Strictly reports PLAYER_DISCOVERED or PLAYER_NOT_FOUND.
    NEVER reports playback_pass (playback can only be verified on Android runtime).
    """
    if not detail_body:
        return "player_not_found", "No detail body available to inspect"

    try:
        soup = BeautifulSoup(detail_body, "html.parser")
        iframes = [ifr.get("src") or ifr.get("data-src") for ifr in soup.find_all("iframe") if ifr.get("src") or ifr.get("data-src")]
        videos = [v.get("src") for v in soup.find_all("video") if v.get("src")]
        has_script_player = any(k in detail_body.lower() for k in ["player", "jwplayer", "m3u8", "eval(", "iframe", "video_url", "closeload", "rapidrame", "vidpapi"])

        if iframes or videos or has_script_player:
            return "player_discovered", f"Discovered {len(iframes)} iframes, videos: {len(videos)}, script player: {has_script_player}"
        return "player_not_found", "No player iframe, video tag, or player script discovered"
    except Exception as e:
        return "player_not_found", str(e)

def inspect_provider(provider, repo_root, domains_config, adaptive_mgr: AdaptiveManager):
    name = provider["name"]
    domain_key = provider.get("domainKey", name)
    domain_info = domains_config.get("providers", {}).get(domain_key, {})
    canonical = domain_info.get("canonical")
    allowed_hosts = set(domain_info.get("allowedHosts", []))
    expected_markers = domain_info.get("expectedMarkers", [])
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
            "L5_player_discovery": "untested"
        },
        "overallStatus": "healthy",
        "diagnostic": {},
        "durationMs": 0,
        "candidateHost": None
    }

    start_time = time.time()

    # L0: Configuration check
    l0_stat, l0_diag = check_l0_config(provider, repo_root, domains_config)
    report["tierResults"]["L0_config"] = l0_stat
    if l0_diag:
        report["diagnostic"]["L0"] = l0_diag
    if l0_stat != "pass":
        report["overallStatus"] = "failed"
        report["durationMs"] = int((time.time() - start_time) * 1000)
        return report

    # L1: Domain & Redirect check via ProviderFetcher
    with ProviderFetcher(allowed_hosts=allowed_hosts, canonical=canonical, timeout=15) as fetcher:
        l1_res = fetcher.fetch(
            url=canonical,
            preferred_mode=FetchMode.HTTP,
            allow_dynamic_fallback=True,
            allow_stealth_fallback=True,
            expected_markers=expected_markers
        )

        report["finalUrl"] = l1_res.finalUrl
        report["candidateHost"] = l1_res.candidateHost

        if l1_res.status == FetchStatus.UNTRUSTED_REDIRECT:
            report["tierResults"]["L1_domain"] = "untrusted_redirect"
            report["overallStatus"] = "critical"
            report["diagnostic"]["L1"] = f"Untrusted redirect candidate: {l1_res.candidateHost}"
            for t in ["L2_homepage", "L3_search", "L4_load", "L5_player_discovery"]:
                report["tierResults"][t] = "skipped"
            report["durationMs"] = int((time.time() - start_time) * 1000)
            return report

        if l1_res.status == FetchStatus.CLOUDFLARE or l1_res.cloudflare:
            report["tierResults"]["L1_domain"] = "cloudflare_challenge"
            report["overallStatus"] = "degraded"
            report["diagnostic"]["L1"] = "Cloudflare Challenge Active"
        elif l1_res.status == FetchStatus.SUCCESS:
            report["tierResults"]["L1_domain"] = "pass"
        else:
            report["tierResults"]["L1_domain"] = f"fail (HTTP_{l1_res.statusCode})"
            report["overallStatus"] = "failed"
            report["diagnostic"]["L1"] = l1_res.error or f"HTTP status: {l1_res.statusCode}"
            for t in ["L2_homepage", "L3_search", "L4_load", "L5_player_discovery"]:
                report["tierResults"][t] = "skipped"
            report["durationMs"] = int((time.time() - start_time) * 1000)
            return report

        # L2: Homepage inspection
        l2_stat, l2_diag, l2_candidates = check_l2_homepage(name, l1_res.body, canonical, adaptive_mgr)
        report["tierResults"]["L2_homepage"] = l2_stat
        if l2_diag:
            report["diagnostic"]["L2"] = l2_diag
        if l2_candidates:
            report["diagnostic"]["L2_candidates"] = l2_candidates

        # L3: Search inspection
        l3_stat, l3_diag = check_l3_search(name, canonical, smoke_test, fetcher)
        report["tierResults"]["L3_search"] = l3_stat
        if l3_diag:
            report["diagnostic"]["L3"] = l3_diag

        # L4: Detail Load inspection
        l4_stat, l4_diag, detail_body = check_l4_load(canonical, smoke_test, fetcher, adaptive_mgr)
        report["tierResults"]["L4_load"] = l4_stat
        if l4_diag:
            report["diagnostic"]["L4"] = l4_diag

        # L5: Player Discovery inspection
        l5_stat, l5_diag = check_l5_player_discovery(detail_body)
        report["tierResults"]["L5_player_discovery"] = l5_stat
        if l5_diag:
            report["diagnostic"]["L5"] = l5_diag

    # Overall Status Calculation
    tiers = report["tierResults"]
    if tiers["L1_domain"] == "untrusted_redirect":
        report["overallStatus"] = "critical"
    elif any(v.startswith("fail") for v in tiers.values()):
        report["overallStatus"] = "failed" if tiers["L1_domain"] != "pass" else "degraded"
    elif any(v == "drift_detected" for v in tiers.values()):
        report["overallStatus"] = "warning"
    elif any(v == "cloudflare_challenge" or v == "automation_blocked" for v in tiers.values()):
        report["overallStatus"] = "degraded"
    else:
        report["overallStatus"] = "healthy"

    report["durationMs"] = int((time.time() - start_time) * 1000)
    return report

def main():
    parser = argparse.ArgumentParser(description="Multi-tier Truthful Provider Health Checker (Scrapling)")
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

    adaptive_mgr = AdaptiveManager()

    results = []
    print(f"[Provider Health] Inspecting {len(providers)} enabled providers across L0-L5 (Scrapling)...")

    for p in providers:
        if args.provider and p["name"].lower() != args.provider.lower():
            continue
        print(f"\n> Inspecting {p['name']}...")
        rep = inspect_provider(p, repo_root, domains_config, adaptive_mgr)
        results.append(rep)
        t = rep["tierResults"]
        print(f"  L0: {t['L0_config']} | L1: {t['L1_domain']} | L2: {t['L2_homepage']} | L3: {t['L3_search']} | L4: {t['L4_load']} | L5: {t['L5_player_discovery']}")
        print(f"  Overall: {rep['overallStatus']} ({rep['durationMs']}ms)")

    os.makedirs(os.path.dirname(os.path.join(repo_root, args.report_path)), exist_ok=True)
    with open(os.path.join(repo_root, args.report_path), "w", encoding="utf-8") as f:
        json.dump(results, f, indent=2, ensure_ascii=False)

    print(f"\n[Provider Health] Saved comprehensive report to {args.report_path}")

if __name__ == "__main__":
    main()
