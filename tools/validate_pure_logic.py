#!/usr/bin/env python3
"""Validate that the com.structurereset.api package stays Minecraft free.

The api layer is the part of this mod that must compile against nothing but the
JDK and gson, which is what lets tools/run_tests.py exercise it offline.  This
checker fails when

* any .java file below src/main/java/com/structurereset/api imports
  net.minecraft.*, net.minecraftforge.* or dev.latvian.* (wildcards included);
* that directory holds fewer than 11 .java files, so an empty or half deleted
  api package can never pass silently.

Usage:
    python tools/validate_pure_logic.py     # exit 0 when the api layer is pure

Every path is derived from Path(__file__), nothing is hard coded.
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    sys.stderr.reconfigure(encoding="utf-8", errors="replace")
except Exception:  # pragma: no cover
    pass

SCRIPT = Path(__file__).resolve()
WORKSPACE = SCRIPT.parent.parent
API_DIR = WORKSPACE / "src" / "main" / "java" / "com" / "structurereset" / "api"

# The api layer must not shrink back to a stub: the T2 delivery is 11 classes.
MIN_SOURCES = 11

FORBIDDEN_ROOTS = ("net.minecraft", "net.minecraftforge", "dev.latvian")

IMPORT_RE = re.compile(
    r"^\s*import\s+(?:static\s+)?([A-Za-z_$][A-Za-z0-9_$]*(?:\.[A-Za-z0-9_$*]+)*)\s*;")


def forbidden_root(imported: str):
    """The forbidden package root an imported name belongs to, or None."""
    for root in FORBIDDEN_ROOTS:
        if imported == root or imported.startswith(root + "."):
            return root
    return None


def scan(path: Path):
    """Every forbidden import of one file as (line number, imported name, root)."""
    violations = []
    text = path.read_text(encoding="utf-8", errors="replace")
    for number, line in enumerate(text.splitlines(), 1):
        match = IMPORT_RE.match(line)
        if not match:
            continue
        imported = match.group(1)
        root = forbidden_root(imported)
        if root is not None:
            violations.append((number, imported, root))
    return violations


def main() -> int:
    if not API_DIR.is_dir():
        print("FAIL: validate-pure-logic: missing directory %s" % API_DIR)
        return 1

    sources = sorted(API_DIR.rglob("*.java"))
    violations = []
    for source in sources:
        for number, imported, root in scan(source):
            violations.append((source, number, imported, root))

    ok = True
    if violations:
        ok = False
        print("FAIL: validate-pure-logic: Minecraft/Forge/KubeJS imports found:")
        for source, number, imported, root in violations:
            print("  %s:%d: import %s (forbidden root '%s')"
                  % (source, number, imported, root))
    if len(sources) < MIN_SOURCES:
        ok = False
        print("FAIL: validate-pure-logic: found %d api source(s) below %s, expected at least %d"
              % (len(sources), API_DIR, MIN_SOURCES))
    if not ok:
        return 1

    print("OK: validate-pure-logic: %d api source(s), no net.minecraft / "
          "net.minecraftforge / dev.latvian import" % len(sources))
    return 0


if __name__ == "__main__":
    sys.exit(main())
