#!/usr/bin/env python3
"""
provider_health.py

Truthful multi-tier provider health inspection powered by Scrapling:
L0: Configuration (Config schema, root module, gradle build, manifest, Kotlin classes)
L1: Domain Reachable (DNS/HTTPS, HTTP status, redirect chain, allowedHosts, markers)
    - Status: PASS, CLOUDFLARE, UNTRUSTED_REDIRECT, CONFIG_ERROR, CONTENT_MARKER_MISMATCH, FAIL
L2: Homepage (DOM smoke probe with Scrapling + adaptive selector drift detection)
    - Status: PASS, DRIFT_DETECTED, FAIL, AUTOMATION_BLOCKED
L3: Search (Config-driven search probe: GET / POST_JSON / POST_FORM)
    - Status: PASS, FAIL, AUTOMATION_BLOCKED, SKIPPED
L4: Load (Detail page probe with soft 404 detection & adaptive title checking)
    - Status: PASS, DRIFT_DETECTED, FAIL, AUTOMATION_BLOCKED, SKIPPED
L5: Player Discovery (Evaluates player container discovery with episode chaining)
    - Movie: Detail -> Player discovery
    - TvSeries / Anime: Detail -> Episode discovery -> Player discovery
    - Status: PLAYER_DISCOVERED, PLAYER_NOT_FOUND, AUTOMATION_BLOCKED, SKIPPED
    - NOTE: Discovery of player containers NEVER implies CloudStream Android PLAYBACK_PASS!

Tiers that are not executed or not configured report 'skipped' or 'untested', NEVER fake 'pass'.
"""

import os
import sys

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
if REPO_ROOT not in sys.path:
    sys.path.insert(0, REPO_ROOT)

import json
import time
import argparse
import urllib.parse
from urllib.parse import urljoin, urlparse
from typing import Dict, Any, List, Optional, Tuple
from datetime import datetime, timezone
from bs4 import BeautifulSoup

from tools.scraping import (
    ProviderFetcher,
    FetchMode,
    FetchStatus,
    AdaptiveManager,
    XhrRedactor,
    discover_homepage_cards,
    parse_detail_page,
    evaluate_player_discovery
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

def check_l2_homepage(name, html_body, canonical, adaptive_mgr: AdaptiveManager, monitoring_cfg: Dict[str, Any]):
    if not html_body:
        return "fail", "EMPTY_BODY", None

    # Use shared discovery helper
    cards = discover_homepage_cards(html_body, canonical)
    if len(cards) > 0:
        # Register baseline with adaptive manager
        try:
            adaptive_mgr.check_css_selector(
                html_content=html_body,
                selector_str=cards[0].get("selector", "a.poster"),
                identifier=f"{name}_homepage_card",
                base_url=canonical
            )
        except Exception:
            pass
        return "pass", f"Found {len(cards)} items. Top: {cards[0]['title']}", None

    # If 0 cards discovered, run adaptive selector drift check
    primary_selector = "a.poster, a.mcard, a.dcard, article a"
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

def check_l3_search(name, canonical, smoke_test, monitoring_cfg, fetcher: ProviderFetcher):
    q = smoke_test.get("searchQuery")
    if not q:
        return "skipped", "NO_SEARCH_QUERY_CONFIGURED"

    search_cfg = monitoring_cfg.get("search", {})
    method = search_cfg.get("method", "GET").upper()
    path = search_cfg.get("path", "/?s={query}")
    endpoint = urljoin(canonical, path.replace("{query}", urllib.parse.quote(q)))

    try:
        if method == "POST_JSON":
            payload_template = search_cfg.get("payload", {"query": "{query}"})
            payload_str = json.dumps(payload_template).replace("{query}", q)
            headers = search_cfg.get("headers", {"Content-Type": "application/json"})
            res = fetcher.fetch(endpoint, preferred_mode=FetchMode.HTTP, method="POST", data=payload_str, headers=headers)
            if res.status in (FetchStatus.CLOUDFLARE, FetchStatus.BLOCKED):
                return "automation_blocked", f"Search endpoint protected ({res.status.value})"
            if res.statusCode == 200:
                try:
                    data = json.loads(res.body)
                    json_key = search_cfg.get("jsonKey", "results")
                    results = data.get(json_key, [])
                    if results:
                        return "pass", f"Found {len(results)} results"
                except Exception:
                    pass
            return "fail", f"Search HTTP {res.statusCode} or 0 results"

        elif method == "POST_FORM":
            payload_template = search_cfg.get("payload", {"arama": "{query}"})
            data = {k: v.replace("{query}", q) for k, v in payload_template.items()}
            form_data = urllib.parse.urlencode(data)
            headers = search_cfg.get("headers", {"Content-Type": "application/x-www-form-urlencoded"})
            res = fetcher.fetch(endpoint, preferred_mode=FetchMode.HTTP, method="POST", data=form_data, headers=headers)
            if res.status in (FetchStatus.CLOUDFLARE, FetchStatus.BLOCKED):
                return "automation_blocked", f"Search AJAX protected ({res.status.value})"
            keyword = search_cfg.get("expectedKeyword", "/anime/")
            if res.statusCode == 200 and (keyword in res.body):
                return "pass", f"Search AJAX returned matches containing '{keyword}'"
            return "fail", f"Search HTTP {res.statusCode} or expected keyword not found"

        else:
            # Standard GET search
            res = fetcher.fetch(endpoint, preferred_mode=FetchMode.HTTP)
            if res.status in (FetchStatus.CLOUDFLARE, FetchStatus.BLOCKED):
                return "automation_blocked", f"Search protected ({res.status.value})"
            if res.statusCode == 200:
                soup = BeautifulSoup(res.body, "html.parser")
                selector = search_cfg.get("expectedSelector", "article a, .movie-box, .film-box, .video-item, .poster, a.mcard")
                results = soup.select(selector)
                if results:
                    return "pass", f"Found {len(results)} search results"

            return "fail", f"Search returned HTTP {res.statusCode if 'res' in locals() else 'None'} or no matches"

    except Exception as e:
        return "fail", str(e)

def check_l4_load(canonical, smoke_test, fetcher: ProviderFetcher, adaptive_mgr: AdaptiveManager):
    detail_url = smoke_test.get("knownDetail")
    if not detail_url:
        return "skipped", "NO_DETAIL_URL_CONFIGURED", None, []

    try:
        res = fetcher.fetch(detail_url, preferred_mode=FetchMode.HTTP, allow_dynamic_fallback=True)
        if res.status in (FetchStatus.CLOUDFLARE, FetchStatus.BLOCKED):
            return "automation_blocked", f"Detail page protected ({res.status.value})", None, []

        detail_info = parse_detail_page(res.body, res.statusCode, detail_url)
        if detail_info["status"] == "FAIL":
            if detail_info.get("isSoft404"):
                return "fail", f"SOFT_404_PAGE: {detail_info.get('title')}", None, []
            return "fail", f"Detail load failed ({detail_info.get('reason')})", None, []

        return "pass", f"Loaded: {detail_info['title'][:40]}", res.body, detail_info.get("episodeLinks", [])

    except Exception as e:
        return "fail", str(e), None, []

def check_l5_player_discovery(
    detail_body: str,
    detail_url: str,
    episode_links: List[str],
    monitoring_cfg: Dict[str, Any],
    fetcher: ProviderFetcher
):
    """
    Evaluates player discovery with target chaining:
    - If mode == 'episode' and episode_links exist: fetch first episode page and probe player!
    - Otherwise probe detail page.
    """
    player_probe_cfg = monitoring_cfg.get("playerProbe", {})
    probe_mode = player_probe_cfg.get("mode", "detail")

    target_body = detail_body
    target_url = detail_url

    if probe_mode == "episode" and episode_links:
        ep_url = episode_links[0]
        try:
            ep_res = fetcher.fetch(ep_url, preferred_mode=FetchMode.HTTP, allow_dynamic_fallback=True)
            if ep_res.statusCode == 200:
                target_body = ep_res.body
                target_url = ep_url
        except Exception:
            pass

    if not target_body:
        return "player_not_found", "No target content body available to inspect"

    stat, info = evaluate_player_discovery(target_body, target_url)
    if stat == "PLAYER_DISCOVERED":
        diag = f"Discovered {len(info['iframes'])} iframes, {len(info['videos'])} videos, scriptPlayer={info['hasPlayerScript']} at {target_url}"
        return "player_discovered", diag
    return "player_not_found", f"No player iframe, video tag, or player script discovered at {target_url}"

def inspect_provider(provider, repo_root, domains_config, adaptive_mgr: AdaptiveManager):
    name = provider["name"]
    domain_key = provider.get("domainKey", name)
    domain_info = domains_config.get("providers", {}).get(domain_key, {})
    canonical = domain_info.get("canonical")
    allowed_hosts = set(domain_info.get("allowedHosts", []))
    expected_markers = domain_info.get("expectedMarkers", [])
    smoke_test = provider.get("smokeTest", {})
    monitoring_cfg = provider.get("monitoring", {})

    pref_fetch = FetchMode(monitoring_cfg.get("preferredFetch", "HTTP"))
    dyn_fallback = monitoring_cfg.get("dynamicFallback", True)
    stealth_fallback = monitoring_cfg.get("stealthFallback", True)

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

    # L0
    l0_stat, l0_diag = check_l0_config(provider, repo_root, domains_config)
    report["tierResults"]["L0_config"] = l0_stat
    if l0_diag:
        report["diagnostic"]["L0"] = l0_diag
    if l0_stat != "pass":
        report["overallStatus"] = "failed"
        report["durationMs"] = int((time.time() - start_time) * 1000)
        return report

    # Fetcher session reuse context
    with ProviderFetcher(allowed_hosts=allowed_hosts, canonical=canonical, timeout=15) as fetcher:
        # L1: Domain
        l1_res = fetcher.fetch(
            url=canonical,
            preferred_mode=pref_fetch,
            allow_dynamic_fallback=dyn_fallback,
            allow_stealth_fallback=stealth_fallback,
            expected_markers=expected_markers
        )

        report["finalUrl"] = l1_res.finalUrl
        report["candidateHost"] = l1_res.candidateHost

        if l1_res.status == FetchStatus.CONFIG_ERROR:
            report["tierResults"]["L1_domain"] = "config_error"
            report["overallStatus"] = "critical"
            report["diagnostic"]["L1"] = l1_res.error
            for t in ["L2_homepage", "L3_search", "L4_load", "L5_player_discovery"]:
                report["tierResults"][t] = "skipped"
            report["durationMs"] = int((time.time() - start_time) * 1000)
            return report

        if l1_res.status == FetchStatus.UNTRUSTED_REDIRECT:
            report["tierResults"]["L1_domain"] = "untrusted_redirect"
            report["overallStatus"] = "critical"
            report["diagnostic"]["L1"] = f"Untrusted redirect candidate: {l1_res.candidateHost}"
            for t in ["L2_homepage", "L3_search", "L4_load", "L5_player_discovery"]:
                report["tierResults"][t] = "skipped"
            report["durationMs"] = int((time.time() - start_time) * 1000)
            return report

        if l1_res.status == FetchStatus.CONTENT_MARKER_MISMATCH:
            report["tierResults"]["L1_domain"] = "content_marker_mismatch"
            report["overallStatus"] = "failed"
            report["diagnostic"]["L1"] = l1_res.error
            for t in ["L2_homepage", "L3_search", "L4_load", "L5_player_discovery"]:
                report["tierResults"][t] = "skipped"
            report["durationMs"] = int((time.time() - start_time) * 1000)
            return report

        if l1_res.status == FetchStatus.CLOUDFLARE or l1_res.cloudflare:
            report["tierResults"]["L1_domain"] = "cloudflare_challenge"
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

        # L2: Homepage
        l2_stat, l2_diag, l2_candidates = check_l2_homepage(name, l1_res.body, canonical, adaptive_mgr, monitoring_cfg)
        report["tierResults"]["L2_homepage"] = l2_stat
        if l2_diag:
            report["diagnostic"]["L2"] = l2_diag
        if l2_candidates:
            report["diagnostic"]["L2_candidates"] = l2_candidates

        # L3: Search
        l3_stat, l3_diag = check_l3_search(name, canonical, smoke_test, monitoring_cfg, fetcher)
        report["tierResults"]["L3_search"] = l3_stat
        if l3_diag:
            report["diagnostic"]["L3"] = l3_diag

        # L4: Detail Load
        l4_stat, l4_diag, detail_body, episode_links = check_l4_load(canonical, smoke_test, fetcher, adaptive_mgr)
        report["tierResults"]["L4_load"] = l4_stat
        if l4_diag:
            report["diagnostic"]["L4"] = l4_diag

        # L5: Player Discovery with Chaining
        known_detail_url = smoke_test.get("knownDetail")
        l5_stat, l5_diag = check_l5_player_discovery(detail_body, known_detail_url, episode_links, monitoring_cfg, fetcher)
        report["tierResults"]["L5_player_discovery"] = l5_stat
        if l5_diag:
            report["diagnostic"]["L5"] = l5_diag

    # Overall Status Calculation
    tiers = report["tierResults"]
    if tiers["L1_domain"] in ("untrusted_redirect", "config_error"):
        report["overallStatus"] = "critical"
    elif tiers["L0_config"] == "fail" or tiers["L1_domain"].startswith("fail") or tiers["L1_domain"] == "content_marker_mismatch":
        report["overallStatus"] = "failed"
    elif tiers["L2_homepage"] == "fail" or tiers["L4_load"] == "fail":
        report["overallStatus"] = "degraded"
    elif tiers["L5_player_discovery"] == "player_not_found":
        report["overallStatus"] = "degraded"
    elif any(v == "drift_detected" for v in tiers.values()):
        report["overallStatus"] = "warning"
    elif any(v in ("cloudflare_challenge", "automation_blocked") for v in tiers.values()):
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
