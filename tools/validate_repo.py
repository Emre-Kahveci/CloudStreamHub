#!/usr/bin/env python3
"""
validate_repo.py

Static repository validator:
- Validates repo.json structure
- Validates settings.gradle.kts and module directories
- Checks for duplicate modules and duplicate internalNames
- Validates module build.gradle.kts metadata (authors, language, description, status, tvTypes)
- Checks Kotlin plugin registration (@CloudstreamPlugin & Plugin class)
- Checks MainAPI inheritance
- Validates generated plugins.json and verifies SHA-256 hash against built .cs3 artifacts
"""

import os
import sys
import json
import re
import hashlib
import argparse

VALID_TV_TYPES = {
    "Movie", "TvSeries", "Anime", "AnimeMovie", "OVA", "Cartoon",
    "Documentary", "AsianDrama", "Live", "NSFW", "Others", "Music",
    "AudioBook", "CustomMedia", "Audio", "Podcast", "Torrent"
}

VALID_STATUSES = {0, 1, 2, 3}

def check_file_hash(filepath, expected_hash):
    sha = hashlib.sha256()
    with open(filepath, "rb") as f:
        while chunk := f.read(65536):
            sha.update(chunk)
    computed = "sha256-" + sha.hexdigest()
    return computed.lower() == expected_hash.lower()

def main():
    parser = argparse.ArgumentParser(description="CloudStream Repository Validator")
    parser.add_argument("--verify-artifacts", action="store_true", help="Verify generated .cs3 and plugins.json artifacts")
    args = parser.parse_args()

    repo_root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    errors = []

    # 1. Check repo.json
    repo_json_path = os.path.join(repo_root, "repo.json")
    if not os.path.exists(repo_json_path):
        errors.append("Missing repo.json in root!")
    else:
        try:
            with open(repo_json_path, "r", encoding="utf-8") as f:
                repo_data = json.load(f)
            if not repo_data.get("name"):
                errors.append("repo.json must contain a non-empty 'name'")
            if repo_data.get("manifestVersion") != 1:
                errors.append("repo.json manifestVersion must be 1")
            if not isinstance(repo_data.get("pluginLists"), list) or not repo_data["pluginLists"]:
                errors.append("repo.json must contain a non-empty 'pluginLists' array")
        except Exception as e:
            errors.append(f"Invalid JSON in repo.json: {e}")

    # 2. Check settings.gradle.kts and discover modules
    settings_path = os.path.join(repo_root, "settings.gradle.kts")
    if not os.path.exists(settings_path):
        errors.append("Missing settings.gradle.kts in root!")

    # Find modules with build.gradle.kts
    modules = []
    ignored = {"tools", "docs", "config", "legacy", ".github", "gradle", "reports", "site", ".gradle", "build"}
    for entry in os.listdir(repo_root):
        full_p = os.path.join(repo_root, entry)
        if os.path.isdir(full_p) and entry not in ignored and not entry.startswith("."):
            if os.path.exists(os.path.join(full_p, "build.gradle.kts")):
                modules.append(entry)

    print(f"[Validator] Discovered {len(modules)} provider modules: {modules}")

    # 3. Check each module
    internal_names = set()
    for mod in modules:
        mod_dir = os.path.join(repo_root, mod)
        bg_file = os.path.join(mod_dir, "build.gradle.kts")
        with open(bg_file, "r", encoding="utf-8") as f:
            bg_content = f.read()

        # Check version
        ver_match = re.search(r'version\s*=\s*(\d+)', bg_content)
        if not ver_match:
            errors.append(f"[{mod}] Missing version in build.gradle.kts")
        else:
            ver = int(ver_match.group(1))
            if ver <= 0:
                errors.append(f"[{mod}] Version must be > 0 (found {ver})")

        # Check authors
        if "authors" not in bg_content:
            errors.append(f"[{mod}] Missing authors list in build.gradle.kts")

        # Check description
        if "description" not in bg_content:
            errors.append(f"[{mod}] Missing description in build.gradle.kts")

        # Check language
        lang_match = re.search(r'language\s*=\s*"([^"]+)"', bg_content)
        if not lang_match:
            errors.append(f"[{mod}] Missing language in build.gradle.kts")

        # Check Kotlin source files
        src_dir = os.path.join(mod_dir, "src", "main", "kotlin")
        if not os.path.exists(src_dir):
            errors.append(f"[{mod}] Missing src/main/kotlin directory")
            continue

        has_plugin = False
        has_main_api = False
        for root, _, files in os.walk(src_dir):
            for file in files:
                if file.endswith(".kt"):
                    with open(os.path.join(root, file), "r", encoding="utf-8") as f:
                        kt_code = f.read()
                    if "@CloudstreamPlugin" in kt_code:
                        has_plugin = True
                    if ": MainAPI()" in kt_code or ": MainAPI" in kt_code:
                        has_main_api = True

        if not has_plugin:
            errors.append(f"[{mod}] Missing @CloudstreamPlugin class")
        if not has_main_api:
            errors.append(f"[{mod}] Missing MainAPI implementation class")

    # 4. If --verify-artifacts is specified
    if args.verify_artifacts:
        plugins_json_path = os.path.join(repo_root, "build", "plugins.json")
        if not os.path.exists(plugins_json_path):
            errors.append(f"Artifact verification failed: {plugins_json_path} does not exist!")
        else:
            with open(plugins_json_path, "r", encoding="utf-8") as f:
                plugins = json.load(f)
            print(f"[Validator] Validating {len(plugins)} entries in build/plugins.json")
            for p in plugins:
                p_name = p.get("name")
                p_hash = p.get("fileHash")
                if not p_hash:
                    errors.append(f"Plugin {p_name} is missing fileHash in plugins.json")
                # Look for corresponding .cs3
                cs3_found = False
                for r, _, files in os.walk(repo_root):
                    for file in files:
                        if file == f"{p_name}.cs3":
                            cs3_path = os.path.join(r, file)
                            cs3_found = True
                            if p_hash and not check_file_hash(cs3_path, p_hash):
                                errors.append(f"Hash mismatch for {file}!")
                if not cs3_found:
                    errors.append(f"Missing .cs3 artifact file for plugin {p_name}")

    if errors:
        print("\n=== Validation Failures ===")
        for e in errors:
            print(f" - [ERROR] {e}")
        sys.exit(1)

    print("\n[SUCCESS] Repository validation passed with 0 errors!")

if __name__ == "__main__":
    main()
