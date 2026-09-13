#!/usr/bin/env python3
"""
manage_health_issues.py

Automates GitHub Issue creation, deduplication, and closing for provider failures and domain changes:
- Checks reports/provider-health.json and reports/domain-health.json
- Interacts with GitHub REST API using $GITHUB_TOKEN and $GITHUB_REPOSITORY
- Dedup: Appends comment if an open issue already exists for that provider/tier
- Recovery: Closes existing open issues when provider returns to healthy state
- Domain Watch: Opens an issue if untrusted redirect / candidate domain detected
"""

import os
import sys
import json
import urllib.request
import urllib.error
from datetime import datetime, timezone

GITHUB_TOKEN = os.environ.get("GITHUB_TOKEN")
GITHUB_REPOSITORY = os.environ.get("GITHUB_REPOSITORY")
SERVER_URL = os.environ.get("GITHUB_SERVER_URL", "https://github.com")
RUN_ID = os.environ.get("GITHUB_RUN_ID", "")

def github_api(endpoint, method="GET", data=None):
    if not GITHUB_TOKEN or not GITHUB_REPOSITORY:
        return None
    url = f"https://api.github.com/repos/{GITHUB_REPOSITORY}{endpoint}"
    headers = {
        "Authorization": f"token {GITHUB_TOKEN}",
        "Accept": "application/vnd.github.v3+json",
        "User-Agent": "CloudStreamHub-Health-Bot"
    }
    encoded_data = json.dumps(data).encode("utf-8") if data else None
    req = urllib.request.Request(url, data=encoded_data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=15) as resp:
            return json.loads(resp.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        print(f"[API Error] {method} {url} -> HTTP {e.code}: {e.read().decode('utf-8', errors='ignore')}", file=sys.stderr)
        return None
    except Exception as e:
        print(f"[API Error] {method} {url} -> {e}", file=sys.stderr)
        return None

def get_open_issues():
    issues = github_api("/issues?state=open&per_page=100")
    return issues or []

def create_issue(title, body, labels):
    print(f"[Issue] Creating issue: '{title}' with labels: {labels}")
    data = {"title": title, "body": body, "labels": labels}
    return github_api("/issues", method="POST", data=data)

def add_comment(issue_number, comment):
    print(f"[Issue] Adding comment to #{issue_number}")
    data = {"body": comment}
    return github_api(f"/issues/{issue_number}/comments", method="POST", data=data)

def close_issue(issue_number, reason="Provider health checks passed"):
    print(f"[Issue] Closing issue #{issue_number}: {reason}")
    add_comment(issue_number, f"### Resolution Update\n\n{reason}\n\nAuto-closing issue at {datetime.now(timezone.utc).isoformat()}.")
    data = {"state": "closed"}
    return github_api(f"/issues/{issue_number}", method="PATCH", data=data)

def main():
    if not GITHUB_TOKEN or not GITHUB_REPOSITORY:
        print("[Issue Manager] GITHUB_TOKEN or GITHUB_REPOSITORY not set. Running in dry-run mode.")
        return

    repo_root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    prov_file = os.path.join(repo_root, "reports", "provider-health.json")
    dom_file = os.path.join(repo_root, "reports", "domain-health.json")

    prov_reports = []
    if os.path.exists(prov_file):
        with open(prov_file, "r", encoding="utf-8") as f:
            prov_reports = json.load(f)

    dom_reports = []
    if os.path.exists(dom_file):
        with open(dom_file, "r", encoding="utf-8") as f:
            dom_reports = json.load(f)

    workflow_url = f"{SERVER_URL}/{GITHUB_REPOSITORY}/actions/runs/{RUN_ID}" if RUN_ID else "N/A"
    open_issues = get_open_issues()

    # 1. Process Domain Health Issues
    for d in dom_reports:
        name = d.get("provider")
        status = d.get("status")
        candidate = d.get("candidateHost")
        labels = ["domain-watch", "automated", name]

        # Check if domain changed or untrusted redirect
        if status == "untrusted_redirect" or candidate:
            existing = [i for i in open_issues if "domain-watch" in [lbl["name"] for lbl in i.get("labels", [])] and name in [lbl["name"] for lbl in i.get("labels", [])]]
            body = f"""## [Domain Watch] Possible Domain Change Detected\n\n- **Provider:** {name}\n- **Checked At:** {datetime.now(timezone.utc).isoformat()}\n- **Canonical Domain:** {d.get('canonical')}\n- **Final URL:** {d.get('finalUrl')}\n- **Candidate Host:** {candidate}\n- **HTTPS Valid:** {d.get('httpsValid')}\n- **Marker Found:** {d.get('markerFound')}\n- **Redirect Chain:** `{d.get('redirectChain')}`\n- **Workflow Run:** {workflow_url}\n\n*Manual review required before updating config/domains.json.*"""
            if not existing:
                create_issue(f"[Domain Watch] {name} possible domain change", body, labels)
            else:
                add_comment(existing[0]["number"], f"Automated check update: Status is still `{status}` with candidate `{candidate}`.\nRun: {workflow_url}")

    # 2. Process Provider Health Issues
    for p in prov_reports:
        name = p.get("provider")
        overall = p.get("overallStatus")
        tiers = p.get("tierResults", {})
        labels = ["provider-health", "automated", name]
        existing = [i for i in open_issues if "provider-health" in [lbl["name"] for lbl in i.get("labels", [])] and name in [lbl["name"] for lbl in i.get("labels", [])]]

        # If provider has failed, drifted, or encountered untrusted redirect:
        is_untrusted = tiers.get("L1_domain") == "untrusted_redirect" or p.get("overallStatus") == "critical"
        is_critical_fail = tiers.get("L0_config") == "fail" or (tiers.get("L1_domain") and tiers["L1_domain"].startswith("fail"))
        is_drift = any(v == "drift_detected" for v in tiers.values())
        is_blocked = any(v in ("cloudflare_challenge", "automation_blocked") for v in tiers.values())

        if is_untrusted:
            candidate = p.get("candidateHost")
            body = f"""## [Provider Health] Untrusted Redirect Detected\n\n- **Provider:** {name}\n- **Checked At:** {p.get('checkedAt')}\n- **Canonical Domain:** {p.get('canonical')}\n- **Final URL:** {p.get('finalUrl')}\n- **Candidate Host:** {candidate}\n- **Tier Results:** `{tiers}`\n- **Workflow Run:** {workflow_url}\n\n*Urgent review required: The domain redirected outside allowed destination hosts.*"""
            untrusted_existing = [i for i in existing if "untrusted-redirect" in [lbl["name"] for lbl in i.get("labels", [])]]
            if not untrusted_existing:
                create_issue(f"[Security Alert] {name} — untrusted redirect to {candidate}", body, labels + ["untrusted-redirect", "security"])
            else:
                add_comment(untrusted_existing[0]["number"], f"Automated health update: Still redirecting to untrusted host `{candidate}`.\nRun: {workflow_url}")

        elif is_critical_fail:
            failed_tier = "L0_config" if tiers.get("L0_config") == "fail" else "L1_domain"
            err_msg = p.get("diagnostic", {}).get(failed_tier[:2], "Health check failure")
            body = f"""## [Provider Health] Critical Failure Detected\n\n- **Provider:** {name}\n- **Checked At:** {p.get('checkedAt')}\n- **Failed Tier:** {failed_tier}\n- **Canonical Domain:** {p.get('canonical')}\n- **Final URL:** {p.get('finalUrl')}\n- **Diagnostic:** {err_msg}\n- **Tier Results:** `{tiers}`\n- **Workflow Run:** {workflow_url}\n\n*Automated issue opened by Provider Health Monitor.*"""
            if not existing:
                create_issue(f"[Provider Health] {name} — {failed_tier} regression", body, labels + ["critical"])
            else:
                add_comment(existing[0]["number"], f"Automated health update: {failed_tier} still failing. Diagnostic: {err_msg}\nRun: {workflow_url}")

        elif is_drift:
            drift_tiers = [k for k, v in tiers.items() if v == "drift_detected"]
            candidates = p.get("diagnostic", {}).get("L2_candidates", [])
            body = f"""## [Selector Drift] Upstream DOM Mutation Detected\n\n- **Provider:** {name}\n- **Checked At:** {p.get('checkedAt')}\n- **Drifted Tier(s):** {drift_tiers}\n- **Canonical Domain:** {p.get('canonical')}\n- **Candidate Elements Found:**\n```json\n{json.dumps(candidates, indent=2)}\n```\n- **Workflow Run:** {workflow_url}\n\n*Note: Adaptive search located candidate elements. Provider Kotlin selectors may need updating.*"""
            drift_existing = [i for i in existing if "selector-drift" in [lbl["name"] for lbl in i.get("labels", [])]]
            if not drift_existing:
                create_issue(f"[Selector Drift] {name} — upstream DOM mutation detected", body, labels + ["selector-drift"])
            else:
                add_comment(drift_existing[0]["number"], f"Automated drift update: Candidate elements still active.\nRun: {workflow_url}")

        elif overall == "healthy" and existing:
            # Recovery detected!
            close_issue(existing[0]["number"], f"Provider {name} has passed health checks and is now healthy.")

if __name__ == "__main__":
    main()
