#!/usr/bin/env python3
"""
playback_verifier.py

Extended Playback Verification Engine for CloudStreamHub providers:
- L6: Extractor Resolution (Tests if an embed/iframe URL resolves to a media stream URL)
- L7: Media Preflight (Validates Content-Type, HTTP status, and container/manifest magic signatures)
- L8: First Segment Verification (For HLS/DASH, fetches variant and first chunk/segment to ensure readability)
- Playback Matrix Generation: Generates machine-readable reports/playback_matrix.json
- Anti-Staleness Metadata: Injects commit SHA, config hash, generated timestamp, and version metadata
"""

import os
import sys
import re
import ssl
import json
import time
import hashlib
import subprocess
import urllib.request
import urllib.parse
from datetime import datetime, timezone
from typing import Dict, Any, List, Optional, Tuple

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
if REPO_ROOT not in sys.path:
    sys.path.insert(0, REPO_ROOT)

DEFAULT_TIMEOUT = 10
USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

class PlaybackVerifier:
    def __init__(self, timeout: int = DEFAULT_TIMEOUT):
        self.timeout = timeout
        self.ssl_ctx = ssl.create_default_context()

    def get_repo_metadata(self) -> Dict[str, Any]:
        """Collects truthful Git commit, config hash, and version metadata."""
        sha = "unknown"
        try:
            p = subprocess.run(["git", "rev-parse", "HEAD"], cwd=REPO_ROOT, capture_output=True, text=True)
            if p.returncode == 0:
                sha = p.stdout.strip()
        except Exception:
            pass

        # Hash providers and domains configuration
        h = hashlib.sha256()
        for cfg in ["config/providers.json", "config/domains.json"]:
            path = os.path.join(REPO_ROOT, cfg)
            if os.path.isfile(path):
                with open(path, "rb") as f:
                    h.update(f.read())
        config_hash = h.hexdigest()[:16]

        with open(os.path.join(REPO_ROOT, "config/providers.json"), encoding="utf-8") as f:
            provs = json.load(f).get("providers", [])
        active_count = sum(1 for p in provs if p.get("enabled"))

        return {
            "generatedAt": datetime.now(timezone.utc).isoformat(),
            "sourceCommitSha": sha,
            "configHash": config_hash,
            "providerCount": active_count,
            "cloudStreamVersion": "v4.8.0"
        }

    def verify_l6_extractor_resolution(self, embed_url: str, referer: Optional[str] = None) -> Tuple[str, Optional[str], Optional[str]]:
        """
        L6: Resolves an embed/iframe URL to a candidate stream URL.
        Returns (status, stream_url, extractor_name).
        """
        if not embed_url or not embed_url.startswith("http"):
            return "FAIL", None, None

        headers = {"User-Agent": USER_AGENT, "Referer": referer or embed_url}

        # 1. Direct stream link
        if any(embed_url.endswith(ext) or ext in embed_url for ext in [".m3u8", ".mp4", ".mkv"]):
            return "PASS", embed_url, "Direct"

        # 2. FilmizleIn / FirePlayer
        if "filmizle.in" in embed_url:
            m = re.search(r'/video/([a-zA-Z0-9_-]+)', embed_url)
            if m:
                vid = m.group(1)
                api_url = f"https://player.filmizle.in/player/index.php?data={vid}&do=getVideo"
                data = urllib.parse.urlencode({"hash": vid, "r": referer or "https://sinemacc.com/", "s": ""}).encode("utf-8")
                req = urllib.request.Request(api_url, data=data, headers={
                    "User-Agent": USER_AGENT,
                    "X-Requested-With": "XMLHttpRequest",
                    "Referer": embed_url
                })
                try:
                    with urllib.request.urlopen(req, timeout=self.timeout, context=self.ssl_ctx) as resp:
                        js = json.loads(resp.read().decode("utf-8", errors="ignore"))
                        stream = js.get("securedLink") or js.get("videoSource")
                        if stream:
                            return "PASS", stream.replace("\\/", "/"), "FilmizleIn"
                except Exception:
                    pass

        # 3. Peacemaker
        if "peacemakerst.com" in embed_url:
            m = re.search(r'/video/([a-zA-Z0-9_-]+)', embed_url)
            if m:
                vid = m.group(1)
                api_url = f"https://peacemakerst.com/tv/video/{vid}?do=getVideo"
                data = urllib.parse.urlencode({"hash": vid, "r": referer or "https://www.dizimom.diy/", "s": ""}).encode("utf-8")
                req = urllib.request.Request(api_url, data=data, headers={
                    "User-Agent": USER_AGENT,
                    "X-Requested-With": "XMLHttpRequest",
                    "Referer": embed_url
                })
                try:
                    with urllib.request.urlopen(req, timeout=self.timeout, context=self.ssl_ctx) as resp:
                        js = json.loads(resp.read().decode("utf-8", errors="ignore"))
                        sources = js.get("videoSources", [])
                        if sources and sources[0].get("file"):
                            return "PASS", sources[0]["file"], "Peacemaker"
                except Exception:
                    pass

        # 4. Vidmoly
        if "vidmoly" in embed_url:
            full_url = embed_url.replace("/w/", "/embed-") if "/w/" in embed_url else embed_url
            req = urllib.request.Request(full_url, headers=headers)
            try:
                with urllib.request.urlopen(req, timeout=self.timeout, context=self.ssl_ctx) as resp:
                    html = resp.read().decode("utf-8", errors="ignore")
                    m3u8 = re.search(r'file:\s*["\']([^"\']+\.m3u8[^"\']*)["\']', html)
                    if m3u8:
                        return "PASS", m3u8.group(1), "Vidmoly"
            except Exception:
                pass

        # 5. CloseLoad
        if "closeload" in embed_url:
            req = urllib.request.Request(embed_url, headers=headers)
            try:
                with urllib.request.urlopen(req, timeout=self.timeout, context=self.ssl_ctx) as resp:
                    html = resp.read().decode("utf-8", errors="ignore")
                    m = re.search(r'file:\s*["\']([^"\']+)["\']', html) or re.search(r'<source[^>]+src=["\']([^"\']+)["\']', html)
                    if m:
                        return "PASS", m.group(1), "CloseLoad"
            except Exception:
                pass

        return "UNSUPPORTED_HOST", None, None

    def verify_l7_media_preflight(self, stream_url: str, referer: Optional[str] = None) -> Tuple[str, str, Dict[str, Any]]:
        """
        L7: Validates Content-Type, HTTP status, and container magic bytes.
        Prevents ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED (3003) by rejecting HTML/JSON.
        Returns (status, stream_type, metadata_dict).
        """
        if not stream_url or not stream_url.startswith("http"):
            return "FAIL", "UNKNOWN", {"reason": "INVALID_URL"}

        headers = {
            "User-Agent": USER_AGENT,
            "Range": "bytes=0-1024"
        }
        if referer:
            headers["Referer"] = referer

        req = urllib.request.Request(stream_url, headers=headers)
        try:
            with urllib.request.urlopen(req, timeout=self.timeout, context=self.ssl_ctx) as resp:
                status_code = resp.status
                ct = resp.headers.get("Content-Type", "")
                bytes_sample = resp.read(512)
                text_sample = bytes_sample.decode("utf-8", errors="ignore").lower().strip()

                # Rejection of HTML and JSON errors (3003 root cause prevention)
                if "text/html" in ct or text_sample.startswith("<!doctype html") or text_sample.startswith("<html") or "security error" in text_sample:
                    return "FAIL", "HTML_ERROR_PAGE", {
                        "statusCode": status_code,
                        "contentType": ct,
                        "reason": "HTML_PAGE_REJECTED_3003_PREVENTION"
                    }

                # HLS detection
                if text_sample.startswith("#extm3u") or "application/vnd.apple.mpegurl" in ct or "application/x-mpegurl" in ct:
                    return "PASS", "HLS", {
                        "statusCode": status_code,
                        "contentType": ct,
                        "magicHeader": "#EXTM3U"
                    }

                # MP4 container detection (ftyp box)
                if len(bytes_sample) >= 8 and (b"ftyp" in bytes_sample[4:8] or b"moov" in bytes_sample[4:8]):
                    return "PASS", "PROGRESSIVE_MP4", {
                        "statusCode": status_code,
                        "contentType": ct,
                        "magicHeader": "ftyp"
                    }

                # Matroska / WebM EBML
                if bytes_sample.startswith(b"\x1a\x45\xdf\xa3"):
                    return "PASS", "PROGRESSIVE_MKV", {
                        "statusCode": status_code,
                        "contentType": ct,
                        "magicHeader": "EBML"
                    }

                # Generic 200 with video content type
                if "video/" in ct and status_code in (200, 206):
                    return "PASS", "VIDEO", {
                        "statusCode": status_code,
                        "contentType": ct
                    }

                return "FAIL", "UNKNOWN_CONTAINER", {
                    "statusCode": status_code,
                    "contentType": ct,
                    "reason": "UNRECOGNIZED_CONTAINER_SIGNATURE"
                }
        except urllib.error.HTTPError as e:
            return "FAIL", "HTTP_ERROR", {"statusCode": e.code, "reason": str(e)}
        except Exception as e:
            return "FAIL", "NETWORK_ERROR", {"reason": str(e)}

    def verify_l8_first_segment(self, stream_url: str, stream_type: str, referer: Optional[str] = None) -> Tuple[str, Dict[str, Any]]:
        """
        L8: Verifies that the first actual media segment/chunk can be fetched and is non-empty.
        """
        headers = {"User-Agent": USER_AGENT}
        if referer:
            headers["Referer"] = referer

        if stream_type != "HLS":
            # For progressive files, fetch first 4KB
            headers["Range"] = "bytes=0-4096"
            req = urllib.request.Request(stream_url, headers=headers)
            try:
                with urllib.request.urlopen(req, timeout=self.timeout, context=self.ssl_ctx) as resp:
                    chunk = resp.read(2048)
                    if len(chunk) > 0:
                        return "PASS", {"bytesFetched": len(chunk)}
                    return "FAIL", {"reason": "EMPTY_FIRST_CHUNK"}
            except Exception as e:
                return "FAIL", {"reason": str(e)}

        # For HLS: Fetch playlist, find first variant or segment
        req = urllib.request.Request(stream_url, headers=headers)
        try:
            with urllib.request.urlopen(req, timeout=self.timeout, context=self.ssl_ctx) as resp:
                playlist = resp.read().decode("utf-8", errors="ignore")

            lines = [l.strip() for l in playlist.splitlines() if l.strip() and not l.startswith("#")]
            if not lines:
                return "FAIL", {"reason": "NO_SEGMENTS_OR_VARIANTS_IN_PLAYLIST"}

            first_target = urllib.parse.urljoin(stream_url, lines[0])

            # If first target is a variant playlist, fetch media playlist
            if first_target.endswith(".m3u8") or ".m3u8" in first_target:
                v_req = urllib.request.Request(first_target, headers=headers)
                with urllib.request.urlopen(v_req, timeout=self.timeout, context=self.ssl_ctx) as v_resp:
                    v_playlist = v_resp.read().decode("utf-8", errors="ignore")
                v_lines = [l.strip() for l in v_playlist.splitlines() if l.strip() and not l.startswith("#")]
                if not v_lines:
                    return "FAIL", {"reason": "NO_MEDIA_SEGMENTS_IN_VARIANT"}
                first_target = urllib.parse.urljoin(first_target, v_lines[0])

            # Now fetch the first segment
            headers["Range"] = "bytes=0-2048"
            seg_req = urllib.request.Request(first_target, headers=headers)
            with urllib.request.urlopen(seg_req, timeout=self.timeout, context=self.ssl_ctx) as seg_resp:
                seg_bytes = seg_resp.read(1024)
                if len(seg_bytes) > 0:
                    return "PASS", {"segmentUrl": first_target[:60] + "...", "bytes": len(seg_bytes)}
                return "FAIL", {"reason": "EMPTY_SEGMENT_RESPONSE"}
        except Exception as e:
            return "FAIL", {"reason": str(e)}

    def build_matrix_record(
        self,
        provider_name: str,
        l1_stat: str,
        l3_stat: str,
        l4_stat: str,
        l5_stat: str,
        l6_stat: str,
        l7_stat: str,
        l8_stat: str,
        source_host: Optional[str] = None,
        stream_type: Optional[str] = None,
        notes: Optional[List[str]] = None
    ) -> Dict[str, Any]:
        """Creates a standardized provider playback matrix entry."""
        return {
            "provider": provider_name,
            "domain": l1_stat,
            "search": l3_stat,
            "load": l4_stat,
            "playerDiscovery": l5_stat,
            "extractorResolution": l6_stat,
            "mediaPreflight": l7_stat,
            "firstSegment": l8_stat,
            "runtimePlayback": "PLAYABLE" if (l7_stat == "PASS" and l8_stat == "PASS") else "UNVERIFIED",
            "sourceHost": source_host or "none",
            "streamType": stream_type or "UNKNOWN",
            "notes": notes or []
        }
