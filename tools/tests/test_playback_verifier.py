import os
import sys
REPO_ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
if REPO_ROOT not in sys.path:
    sys.path.insert(0, REPO_ROOT)

import pytest
from tools.playback_verifier import PlaybackVerifier

def test_repo_metadata():
    verifier = PlaybackVerifier()
    meta = verifier.get_repo_metadata()
    assert "generatedAt" in meta
    assert "sourceCommitSha" in meta
    assert "configHash" in meta
    assert meta["providerCount"] == 30
    assert meta["cloudStreamVersion"] == "v4.8.0"

def test_l7_preflight_rejects_html_error():
    verifier = PlaybackVerifier()
    # Test rejection of HTML/error strings (preventing Media3 3003 error)
    stat, stream_type, meta = verifier.verify_l7_media_preflight("data:text/html,<!DOCTYPE html><html><body>Error</body></html>")
    assert stat == "FAIL"

def test_matrix_record_playable_when_l7_and_l8_pass():
    verifier = PlaybackVerifier()
    rec = verifier.build_matrix_record(
        provider_name="SinemaCX",
        l1_stat="PASS",
        l3_stat="PASS",
        l4_stat="PASS",
        l5_stat="PASS",
        l6_stat="PASS",
        l7_stat="PASS",
        l8_stat="PASS",
        source_host="player.filmizle.in",
        stream_type="HLS"
    )
    assert rec["provider"] == "SinemaCX"
    assert rec["runtimePlayback"] == "PLAYABLE"
    assert rec["streamType"] == "HLS"

def test_matrix_record_unverified_when_l7_fails():
    verifier = PlaybackVerifier()
    rec = verifier.build_matrix_record(
        provider_name="BrokenProvider",
        l1_stat="PASS",
        l3_stat="PASS",
        l4_stat="PASS",
        l5_stat="PASS",
        l6_stat="FAIL",
        l7_stat="FAIL",
        l8_stat="FAIL"
    )
    assert rec["runtimePlayback"] == "UNVERIFIED"
