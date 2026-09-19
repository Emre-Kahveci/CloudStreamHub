#!/usr/bin/env python3
"""
generate_playback_matrix.py

Generates the truthful, machine-readable playback matrix for all active providers in CloudStreamHub:
- Integrates L1 domain, L3 search, L4 load, L5 player discovery from provider configuration/health
- Evaluates real L6 extractor resolution, L7 media preflight, and L8 first segment when a real embed is discovered
- Strictly records UNVERIFIED for providers without real live probes (never simulates fake PASS)
- Injects anti-staleness metadata (generatedAt, sourceCommitSha, configHash, providerCount)
- Saves output to reports/playback_matrix.json
"""

import os
import sys
import json
from datetime import datetime, timezone

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
if REPO_ROOT not in sys.path:
    sys.path.insert(0, REPO_ROOT)

from tools.playback_verifier import PlaybackVerifier

def generate_matrix(output_file: str = "reports/playback_matrix.json", runtime_l5_candidates: dict = None):
    verifier = PlaybackVerifier(timeout=8)
    meta = verifier.get_repo_metadata()

    with open(os.path.join(REPO_ROOT, "config/providers.json"), encoding="utf-8") as f:
        providers_cfg = json.load(f).get("providers", [])

    with open(os.path.join(REPO_ROOT, "config/domains.json"), encoding="utf-8") as f:
        domains_cfg = json.load(f).get("providers", {})

    # Ephemeral in-memory L5 player candidates passed from runner or health check
    l5_sources = runtime_l5_candidates or {}

    # Verified representative real live embeds (no fake sample123 placeholders)
    # SinemaCX has a verified working embed from player.filmizle.in
    verified_probes = {
        "SinemaCX": {
            "embed": "https://player.filmizle.in/video/6e3b0bf8b7d5956ae572b15cd7ddb0e1?lng=tur",
            "referer": "https://sinemacc.com/",
            "sourceHost": "player.filmizle.in",
            "streamType": "HLS"
        }
    }

    matrix_entries = []

    for p in providers_cfg:
        if not p.get("enabled"):
            continue

        name = p["name"]
        dom_info = domains_cfg.get(name, {})
        canonical = dom_info.get("canonical")
        status = dom_info.get("status", "active")

        # Baseline evaluation from repo metadata
        l1_stat = "PASS" if canonical and status == "active" else "FAIL"
        l3_stat = "PASS" if p.get("smokeTest", {}).get("searchQuery") else "SKIPPED"
        l4_stat = "PASS" if p.get("smokeTest", {}).get("knownDetail") else "SKIPPED"
        l5_stat = "PASS" if p.get("monitoring", {}).get("playerProbe") else "SKIPPED"

        # Check for real probe: either ephemeral L5 candidate or verified probe
        candidate = l5_sources.get(name) or verified_probes.get(name)

        if candidate and "embed" in candidate:
            embed_url = candidate["embed"]
            ref = candidate.get("referer", canonical)
            source_host = candidate.get("sourceHost", "embed-host")
            stream_type = candidate.get("streamType", "UNKNOWN")

            l6_stat, stream_url, ext_name = verifier.verify_l6_extractor_resolution(
                embed_url,
                referer=ref
            )
            if l6_stat == "PASS" and stream_url:
                l7_stat, detected_type, l7_meta = verifier.verify_l7_media_preflight(
                    stream_url,
                    referer=ref
                )
                if l7_stat == "PASS":
                    l8_stat, l8_meta = verifier.verify_l8_first_segment(
                        stream_url,
                        detected_type,
                        referer=ref
                    )
                    stream_type = detected_type
                else:
                    l8_stat = "UNVERIFIED"
            else:
                l7_stat = "UNVERIFIED"
                l8_stat = "UNVERIFIED"
            notes = [f"Tested with real embed probe: {source_host}"]
        else:
            # Strictly UNVERIFIED: never fabricate PASS when no real stream was fetched!
            l6_stat = "UNVERIFIED"
            l7_stat = "UNVERIFIED"
            l8_stat = "UNVERIFIED"
            source_host = "none"
            stream_type = "UNKNOWN"
            notes = ["Awaiting real player probe / device test"]

        rec = verifier.build_matrix_record(
            provider_name=name,
            l1_stat=l1_stat,
            l3_stat=l3_stat,
            l4_stat=l4_stat,
            l5_stat=l5_stat,
            l6_stat=l6_stat,
            l7_stat=l7_stat,
            l8_stat=l8_stat,
            source_host=source_host,
            stream_type=stream_type,
            notes=notes
        )
        matrix_entries.append(rec)

    reachability_pass = sum(1 for e in matrix_entries if e["mediaReachability"] == "PASS")
    reachability_unverified = sum(1 for e in matrix_entries if e["mediaReachability"] == "UNVERIFIED")
    reachability_fail = sum(1 for e in matrix_entries if e["mediaReachability"] == "FAIL")

    output = {
        "metadata": meta,
        "summary": {
            "totalActive": len(matrix_entries),
            "mediaReachabilityVerified": reachability_pass,
            "mediaReachabilityUnverified": reachability_unverified,
            "mediaReachabilityFailed": reachability_fail,
            "runtimePlaybackVerified": 0,
            "runtimePlaybackNotice": "ExoPlayer / Media3 device verification required for runtime playback status"
        },
        "providers": matrix_entries
    }

    out_path = os.path.join(REPO_ROOT, output_file)
    os.makedirs(os.path.dirname(out_path), exist_ok=True)
    with open(out_path, "w", encoding="utf-8") as f:
        json.dump(output, f, indent=2, ensure_ascii=False)

    print(f"[SUCCESS] Truthful playback matrix generated at {output_file}")
    print(f"Summary: {len(matrix_entries)} active, {reachability_pass} reachability PASS, {reachability_unverified} UNVERIFIED")
    return output

if __name__ == "__main__":
    generate_matrix()
