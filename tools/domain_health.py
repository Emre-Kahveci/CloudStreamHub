#!/usr/bin/env python3
"""
domain_health.py

Performs health and security checks on provider domains:
- Follows HTTP redirects safely (max 5 hops)
- Verifies HTTPS protocol
- Checks destination host against allowedHosts allowlist in config/domains.json
- Verifies presence of expected content integrity markers
- Flags hijacked/parked/phishing domains
- Generates structured JSON report
"""

import os
import sys
import json
import argparse
import urllib.request
import urllib.error
import ssl
from urllib.parse import urlparse

MAX_HOPS = 5
USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

class NoRedirectHandler(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None

def check_domain(name, info):
    canonical = info.get("canonical")
    allowed_hosts = set(info.get("allowedHosts", []))
    expected_markers = info.get("expectedMarkers", [])

    result = {
        "provider": name,
        "canonical": canonical,
        "finalUrl": None,
        "httpsValid": False,
        "hostAllowed": False,
        "markerFound": False,
        "redirectChain": [],
        "status": "unknown",
        "error": None
    }

    if not canonical:
        result["status"] = "no_domain"
        return result

    current_url = canonical
    hops = 0
    ctx = ssl.create_default_context()

    try:
        while hops < MAX_HOPS:
            result["redirectChain"].append(current_url)
            req = urllib.request.Request(current_url, headers={"User-Agent": USER_AGENT})
            opener = urllib.request.build_opener(NoRedirectHandler)
            try:
                with opener.open(req, timeout=10) as response:
                    final_url = current_url
                    result["finalUrl"] = final_url
                    result["httpsValid"] = final_url.startswith("https://")
                    parsed_host = urlparse(final_url).netloc.lower().split(":")[0]
                    result["hostAllowed"] = parsed_host in allowed_hosts

                    # Read first 64KB for markers
                    body = response.read(65536).decode("utf-8", errors="ignore")
                    if expected_markers:
                        result["markerFound"] = any(m.lower() in body.lower() for m in expected_markers)
                    else:
                        result["markerFound"] = True

                    if result["httpsValid"] and result["hostAllowed"] and result["markerFound"]:
                        result["status"] = "healthy"
                    elif not result["hostAllowed"]:
                        result["status"] = "untrusted_redirect"
                    elif not result["markerFound"]:
                        result["status"] = "marker_missing_possible_compromise"
                    else:
                        result["status"] = "degraded"
                    return result
            except urllib.error.HTTPError as e:
                if e.code in (301, 302, 307, 308):
                    new_loc = e.headers.get("Location")
                    if not new_loc:
                        result["error"] = f"Redirect without Location header (code {e.code})"
                        result["status"] = "http_error"
                        return result
                    if new_loc.startswith("/"):
                        parsed_cur = urlparse(current_url)
                        new_loc = f"{parsed_cur.scheme}://{parsed_cur.netloc}{new_loc}"
                    current_url = new_loc
                    hops += 1
                else:
                    result["status"] = f"http_{e.code}"
                    result["error"] = f"HTTP error {e.code}"
                    return result
        result["status"] = "redirect_loop"
        return result
    except Exception as e:
        result["status"] = "network_error"
        result["error"] = str(e)
        return result

def main():
    parser = argparse.ArgumentParser(description="Domain Health and Security Checker")
    parser.add_argument("--report-path", default="reports/domain-health.json", help="Path to write JSON report")
    args = parser.parse_args()

    repo_root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    domains_file = os.path.join(repo_root, "config", "domains.json")
    if not os.path.exists(domains_file):
        print(f"Error: {domains_file} does not exist", file=sys.stderr)
        sys.exit(1)

    with open(domains_file, "r", encoding="utf-8") as f:
        domains_config = json.load(f)

    providers = domains_config.get("providers", {})
    reports = []
    print(f"[Domain Health] Checking {len(providers)} providers...")

    for name, info in providers.items():
        res = check_domain(name, info)
        reports.append(res)
        print(f" - {name}: {res['status']} (final: {res['finalUrl']})")

    os.makedirs(os.path.dirname(os.path.join(repo_root, args.report_path)), exist_ok=True)
    with open(os.path.join(repo_root, args.report_path), "w", encoding="utf-8") as f:
        json.dump(reports, f, indent=2, ensure_ascii=False)
    print(f"\n[Domain Health] Report saved to {args.report_path}")

if __name__ == "__main__":
    main()
