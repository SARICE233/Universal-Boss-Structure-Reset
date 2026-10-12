#!/usr/bin/env python3
"""Shared JDK 17 discovery helpers for the offline toolchain.

The workspace is validated on a machine whose "java" on PATH is a newer JDK
(25) while Forge 1.20.1 must be parsed and built with 17, so every offline tool
resolves the compiler and the runtime the same way:

1. an explicit override (STRUCTURERESET_JAVAC / STRUCTURERESET_JAVA),
2. JAVA_HOME when it points at a JDK 17,
3. the well known install path C:/Program Files/Java/jdk-17,
4. a jdk-17* directory under the usual vendor install folders,
5. whatever javac / java PATH offers (last resort only).

Windows paths are written with forward slashes on purpose: pathlib accepts them
and this file then needs no escape sequences.  Nothing here downloads anything.
"""

from __future__ import annotations

import os
import re
import shutil
import subprocess
import sys
from pathlib import Path

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    sys.stderr.reconfigure(encoding="utf-8", errors="replace")
except Exception:  # pragma: no cover
    pass

FALLBACK_JDK17 = Path("C:/Program Files/Java/jdk-17")

JDK_SEARCH_ROOTS = (
    Path("C:/Program Files/Java"),
    Path("C:/Program Files/Eclipse Adoptium"),
    Path("C:/Program Files/Microsoft"),
    Path("C:/Program Files/Zulu"),
    Path("C:/Program Files/Amazon Corretto"),
    Path("C:/Program Files/BellSoft"),
    Path("C:/Program Files/Temurin"),
)

_EXE_SUFFIX = ".exe" if os.name == "nt" else ""


def _exe(name):
    return name + _EXE_SUFFIX


def java_release_version(home):
    """Return the version string recorded by a JDK home, or None."""
    home = Path(home)
    if not home.is_dir():
        return None
    release = home / "release"
    if release.is_file():
        try:
            text = release.read_text(encoding="utf-8", errors="replace")
        except OSError:
            text = ""
        match = re.search(r'JAVA_VERSION[ \t]*=[ \t]*"([^"]+)"', text)
        if match:
            return match.group(1)
    javac_path = home / "bin" / _exe("javac")
    if javac_path.is_file():
        try:
            proc = subprocess.run([str(javac_path), "-version"], capture_output=True,
                                  text=True, timeout=120)
        except Exception:
            return None
        text = (proc.stdout or "") + (proc.stderr or "")
        match = re.search(r"javac[ \t]+([0-9][0-9A-Za-z_.-]*)", text)
        if match:
            return match.group(1)
    return None


def is_jdk17(home):
    """True when a directory is a JDK whose major version is 17."""
    if home is None:
        return False
    home = Path(home)
    if not home.is_dir():
        return False
    version = java_release_version(home)
    return bool(version) and version.split(".")[0] == "17"


def find_jdk17():
    """Absolute JDK 17 home, or None when this machine has none."""
    env_home = os.environ.get("JAVA_HOME")
    if env_home and is_jdk17(Path(env_home)):
        return Path(env_home)
    if is_jdk17(FALLBACK_JDK17):
        return FALLBACK_JDK17
    for root in JDK_SEARCH_ROOTS:
        if not root.is_dir():
            continue
        candidates = sorted(root.glob("jdk-17*")) + sorted(root.glob("*17*"))
        for candidate in candidates:
            if is_jdk17(candidate):
                return candidate
    return None


def javac(jdk=None):
    """Path of the javac to use, preferring JDK 17."""
    override = os.environ.get("STRUCTURERESET_JAVAC")
    if override and Path(override).is_file():
        return override
    if jdk is None:
        jdk = find_jdk17()
    if jdk is not None:
        candidate = Path(jdk) / "bin" / _exe("javac")
        if candidate.is_file():
            return str(candidate)
    found = shutil.which("javac")
    return found if found else "javac"


def java(jdk=None):
    """Path of the java runtime to use, preferring JDK 17."""
    override = os.environ.get("STRUCTURERESET_JAVA")
    if override and Path(override).is_file():
        return override
    if jdk is None:
        jdk = find_jdk17()
    if jdk is not None:
        candidate = Path(jdk) / "bin" / _exe("java")
        if candidate.is_file():
            return str(candidate)
    found = shutil.which("java")
    return found if found else "java"


def tool_version(executable):
    """One line reporting what a compiler or runtime says for -version."""
    try:
        proc = subprocess.run([str(executable), "-version"], capture_output=True,
                              text=True, timeout=120)
    except Exception as exc:
        return "unavailable (%s)" % exc
    text = ((proc.stdout or "") + (proc.stderr or "")).strip()
    return text.replace("\r", " ").replace("\n", " ").strip() or "unknown"


def describe(jdk=None):
    """Human readable description of the toolchain a script is about to use."""
    if jdk is None:
        jdk = find_jdk17()
    label = str(jdk) if jdk is not None else "PATH fallback (no JDK 17 found)"
    return ("JDK 17 home: %s; javac: %s (%s)"
            % (label, javac(jdk), tool_version(javac(jdk))))
