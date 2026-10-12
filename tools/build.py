#!/usr/bin/env python3
"""Build helper for the Structure Reset addon.

Runs the Gradle wrapper of this workspace with a JDK 17 launcher, because
ForgeGradle 6 / Forge 1.20.1 cannot run on the JDK that may be first on PATH.

Usage:
    python tools/build.py                 # gradlew.bat build
    python tools/build.py clean
    python tools/build.py build --offline
    python tools/build.py test --info     # extra tokens are forwarded verbatim

The exit code of this script is the exit code of Gradle.
"""

from __future__ import annotations

import argparse
import os
import re
import subprocess
import sys
from pathlib import Path

try:  # keep console output from exploding on legacy code pages
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    sys.stderr.reconfigure(encoding="utf-8", errors="replace")
except Exception:  # pragma: no cover
    pass

SCRIPT = Path(__file__).resolve()
WORKSPACE = SCRIPT.parent.parent
FALLBACK_JDK17 = Path(r"C:\Program Files\Java\jdk-17")

JDK_SEARCH_ROOTS = (
    Path(r"C:\Program Files\Java"),
    Path(r"C:\Program Files\Eclipse Adoptium"),
    Path(r"C:\Program Files\Microsoft"),
    Path(r"C:\Program Files\Zulu"),
    Path(r"C:\Program Files\Amazon Corretto"),
    Path(r"C:\Program Files\BellSoft"),
)


def _version_of(home: Path) -> str | None:
    """Return the java version string of a JDK home, or None."""
    release = home / "release"
    if release.is_file():
        try:
            text = release.read_text(encoding="utf-8", errors="replace")
        except OSError:
            text = ""
        m = re.search(r'JAVA_VERSION\s*=\s*"([^"]+)"', text)
        if m:
            return m.group(1)
    for name in ("javac.exe", "javac"):
        exe = home / "bin" / name
        if exe.is_file():
            try:
                proc = subprocess.run(
                    [str(exe), "-version"],
                    capture_output=True,
                    text=True,
                    timeout=120,
                )
            except Exception:
                continue
            out = (proc.stdout or "") + (proc.stderr or "")
            m = re.search(r"javac\s+([0-9][0-9A-Za-z_.\-]*)", out)
            if m:
                return m.group(1)
    return None


def is_jdk17(home: Path | None) -> bool:
    if home is None:
        return False
    home = Path(home)
    if not home.is_dir():
        return False
    version = _version_of(home)
    return bool(version) and version.split(".")[0] == "17"


def find_jdk17() -> Path | None:
    """JDK 17 that points at JAVA_HOME first, then the well known install path."""
    java_home = os.environ.get("JAVA_HOME")
    if java_home and is_jdk17(Path(java_home)):
        return Path(java_home)
    if is_jdk17(FALLBACK_JDK17):
        return FALLBACK_JDK17
    # last resort: look for a jdk-17* install in the usual vendor folders
    for root in JDK_SEARCH_ROOTS:
        if not root.is_dir():
            continue
        for candidate in sorted(root.glob("jdk-17*")) + sorted(root.glob("*17*")):
            if is_jdk17(candidate):
                return candidate
    return None


def gradle_command(task: str, extra: list) -> list:
    """A command list that works on Windows whether or not the path has spaces."""
    return ["cmd.exe", "/c", "gradlew.bat", task, *extra]


def main(argv: list) -> int:
    parser = argparse.ArgumentParser(description="Run the Gradle wrapper with JDK 17.")
    parser.add_argument("task", nargs="?", default="build", help="Gradle task (default: build)")
    parser.add_argument("--offline", action="store_true", help="Pass --offline to Gradle")
    parser.add_argument("--workspace", default=None, help="Project root (defaults to this script's parent)")
    args, extra = parser.parse_known_args(argv)

    workspace = Path(args.workspace).resolve() if args.workspace else WORKSPACE
    gradlew = workspace / "gradlew.bat"
    if not gradlew.is_file():
        print(f"ERROR: Gradle wrapper not found: {gradlew}", file=sys.stderr)
        return 1

    jdk = find_jdk17()
    if jdk is None:
        print(
            "ERROR: no JDK 17 found. Set JAVA_HOME to a JDK 17 or install one at "
            f"{FALLBACK_JDK17}",
            file=sys.stderr,
        )
        return 1

    gradle_args = [args.task]
    if args.offline:
        gradle_args.append("--offline")
    gradle_args.extend(extra)

    env = os.environ.copy()
    env["JAVA_HOME"] = str(jdk)
    env["PATH"] = str(jdk / "bin") + os.pathsep + env.get("PATH", "")

    print(f"[build.py] workspace = {workspace}")
    print(f"[build.py] JAVA_HOME = {jdk} (java {_version_of(jdk)})")
    print(f"[build.py] command   = gradlew.bat {' '.join(gradle_args)}")
    sys.stdout.flush()

    cmd = gradle_command(args.task, gradle_args[1:])
    try:
        proc = subprocess.Popen(
            cmd,
            cwd=str(workspace),
            env=env,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            text=True,
            encoding="utf-8",
            errors="replace",
            bufsize=1,
        )
    except OSError as exc:
        print(f"ERROR: could not start Gradle: {exc}", file=sys.stderr)
        return 1

    try:
        assert proc.stdout is not None
        for line in proc.stdout:
            sys.stdout.write(line)
            sys.stdout.flush()
        proc.wait()
    except KeyboardInterrupt:
        proc.kill()
        print("[build.py] interrupted", file=sys.stderr)
        return 130

    print(f"[build.py] gradle exit code = {proc.returncode}")
    return proc.returncode if proc.returncode is not None else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
