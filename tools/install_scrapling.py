#!/usr/bin/env python3
"""
install_scrapling.py

Installs Scrapling, its fetcher dependencies, and browser engines for
headless testing and anti-bot handling.
Usage:
    python tools/install_scrapling.py [--skip-browsers]
"""

import sys
import subprocess
import argparse

def run_command(cmd, desc):
    print(f"[*] {desc}: {' '.join(cmd)}")
    try:
        subprocess.check_call(cmd)
        print(f"[+] {desc} succeeded.")
        return True
    except subprocess.CalledProcessError as e:
        print(f"[-] {desc} failed with exit code {e.returncode}.", file=sys.stderr)
        return False

def main():
    parser = argparse.ArgumentParser(description="Install Scrapling and browser dependencies")
    parser.add_argument("--skip-browsers", action="store_true", help="Skip installing patchright/playwright browsers")
    args = parser.parse_args()

    py_executable = sys.executable

    # 1. Install pip requirements
    success = run_command(
        [py_executable, "-m", "pip", "install", "-r", "tools/requirements.txt"],
        "Installing Python dependencies"
    )
    if not success:
        sys.exit(1)

    # 2. Install patchright chromium browser
    if not args.skip_browsers:
        print("[*] Installing browser binaries for patchright...")
        run_command(
            [py_executable, "-m", "patchright", "install", "chromium"],
            "Installing Patchright Chromium"
        )
        run_command(
            [py_executable, "-m", "playwright", "install", "chromium"],
            "Installing Playwright Chromium (optional fallback)"
        )

    # 3. Verify Scrapling import
    print("[*] Verifying Scrapling installation...")
    try:
        import scrapling
        from scrapling import Fetcher, DynamicFetcher, StealthyFetcher
        print(f"[+] Scrapling {scrapling.__version__} is ready.")
    except Exception as e:
        print(f"[-] Scrapling verification failed: {e}", file=sys.stderr)
        sys.exit(1)

if __name__ == "__main__":
    main()
