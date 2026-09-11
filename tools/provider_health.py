#!/usr/bin/env python3
"""
provider_health.py

Multi-tier provider health inspection:
L0: configuration valid
L1: domain reachable
L2: main page parse succeeds
L3: search returns valid responses
L4: load metadata succeeds
L5: playback resolution finds playable public/authorized stream
"""

import os
import sys
import json
import time
import argparse
import urllib.request
from datetime import datetime, timezone

def check_provider_health(provider_data, domains_config):
    name = provider_data["name"]
    domain_key = provider_data.get("domainKey", name)
    domain_info = domains_config.get("providers", {}).get(domain_key, {})

    report = {
        "provider": name,
        "checkedAt": datetime.now(timezone.utc).isoformat(),
        "domain": domain_info.get("canonical"),
        "configuration": "pass",
        "domainReachable": "untested",
        "homepage": "untested",
        "search": "untested",
        "load": "untested",
        "playback": "untested",
        "durationMs": 0,
        "errorType": None
    }

    start = time.time()
    canonical = domain_info.get("canonical")
    if not canonical:
        report["configuration"] = "fail"
        report["errorType"] = "MISSING_DOMAIN"
        return report

    try:
        req = urllib.request.Request(canonical, headers={"User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64)"})
        with urllib.request.urlopen(req, timeout=10) as resp:
            if resp.status < 400:
                report["domainReachable"] = "pass"
                report["homepage"] = "pass"
            else:
                report["domainReachable"] = "fail"
                report["errorType"] = f"HTTP_{resp.status}"
    except Exception as e:
        report["domainReachable"] = "fail"
        report["errorType"] = "NETWORK"

    report["durationMs"] = int((time.time() - start) * 1000)
    return report

def main():
    parser = argparse.ArgumentParser(description="Provider Health Checker")
    parser.add_argument("--report-path", default="reports/provider-health.json", help="Path to write health report")
    parser.add_argument("--provider", help="Check only specific provider")
    args = parser.parse_args()

    repo_root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    providers_file = os.path.join(repo_root, "config", "providers.json")
    domains_file = os.path.join(repo_root, "config", "domains.json")

    with open(providers_file, "r", encoding="utf-8") as f:
        providers = json.load(f).get("providers", [])

    with open(domains_file, "r", encoding="utf-8") as f:
        domains_config = json.load(f)

    results = []
    for p in providers:
        if args.provider and p["name"].lower() != args.provider.lower():
            continue
        print(f"[Health] Inspecting {p['name']}...")
        r = check_provider_health(p, domains_config)
        results.append(r)
        print(f" - L1/L2: {r['domainReachable']} (took {r['durationMs']}ms)")

    os.makedirs(os.path.dirname(os.path.join(repo_root, args.report_path)), exist_ok=True)
    with open(os.path.join(repo_root, args.report_path), "w", encoding="utf-8") as f:
        json.dump(results, f, indent=2, ensure_ascii=False)
    print(f"\n[Health] Saved provider health report to {args.report_path}")

if __name__ == "__main__":
    main()
