#!/usr/bin/env python3
"""Pure syntax check for the Java sources of the workspace.

Runs the JDK 17 javac resolved by the sibling tools/toolchain.py module (never
the newer JDK that may be first on PATH) in annotation-processing-only mode
with -XDrawDiagnostics so that the raw diagnostic keys can be inspected.  Only
parser/syntax diagnostics are treated as failures: without a Minecraft
classpath every symbol lookup fails, and those resolution errors are ignored
on purpose.

The checker has no shared mutable state at all: every javac run gets its own
private scratch directory from tools/toolchain.py, so any number of invocations
may overlap.  --selftest proves that by running two simultaneous invocations.

Usage:
    python tools/syntax_check.py            # check src/main/java, exit 0 when clean
    python tools/syntax_check.py --selftest # check the checker itself, including
                                            # two simultaneous invocations
"""

from __future__ import annotations

import argparse
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

SCRIPT = Path(__file__).resolve()
WORKSPACE = SCRIPT.parent.parent

# The shared resolver picks a JDK 17 (the machine may have a newer JDK first on
# PATH).  It lives next to this file; importing it keeps every offline tool on
# the same compiler.  The checker still works if the module is unavailable.
sys.path.insert(0, str(SCRIPT.parent))
try:
    import toolchain  # type: ignore
except Exception:  # pragma: no cover
    toolchain = None

# -------- diagnostics that indicate a broken *syntax* -------------------------
SYNTAX_KEYS = frozenset(
    {
        "compiler.err.expected",
        "compiler.err.expected2",
        "compiler.err.expected3",
        "compiler.err.expected4",
        "compiler.err.expected.module.or.open",
        "compiler.err.illegal.start.of.expr",
        "compiler.err.illegal.start.of.type",
        "compiler.err.illegal.start.of.stmt",
        "compiler.err.illegal.start.of",
        "compiler.err.class.public.should.be.in.file",
        "compiler.err.premature.eof",
        "compiler.err.dot.class.expected",
        "compiler.err.not.stmt",
        "compiler.err.empty.char.lit",
        "compiler.err.illegal.char",
        "compiler.err.orphaned",
        "compiler.err.else.without.if",
        "compiler.err.catch.without.try",
        "compiler.err.finally.without.try",
        "compiler.err.enum.constant.expected",
        "compiler.err.record.header.expected",
        "compiler.err.mod.not.allowed.here",
        "compiler.err.repeated.modifier",
        "compiler.err.varargs.and.old.array.syntax",
        "compiler.err.illegal.underscore",
        "compiler.err.illegal.unicode.esc",
        "compiler.err.invalid.hex.number",
        "compiler.err.invalid.binary.number",
        "compiler.err.invalid.underscores.in.number",
    }
)
SYNTAX_KEY_PREFIXES = ("compiler.err.unclosed.", "compiler.err.illegal.start.of.")

_DIAG_RE = re.compile(r":\d+:(?:\d+:)?\s*(?P<key>compiler\.[A-Za-z0-9_.]+)")


def is_syntax_key(key: str) -> bool:
    return key in SYNTAX_KEYS or key.startswith(SYNTAX_KEY_PREFIXES)


def collect_java_files(java_root: Path):
    if not java_root.is_dir():
        return []
    return sorted(java_root.rglob("*.java"))


def find_javac():
    """JDK 17 javac when one can be located, else whatever PATH offers."""
    if toolchain is not None:
        return toolchain.javac()
    return shutil.which("javac")


def run_javac(javac: str, files, out_dir: Path):
    """Run javac in parse/annotation-only mode; return (exit code, diagnostics)."""
    # -XDrawDiagnostics prints locale independent diagnostic keys; the -J flags
    # additionally force English, UTF-8 text so output stays readable on a
    # non English Windows console.
    base = [javac, "-J-Duser.language=en", "-J-Duser.country=US",
            "-J-Dfile.encoding=UTF-8", "-proc:only", "-XDrawDiagnostics",
            "-d", str(out_dir)]
    attempts = [
        base + ["--release", "17"] + [str(f) for f in files],
        base + ["-source", "17", "-target", "17"] + [str(f) for f in files],
    ]
    last = (1, "")
    for cmd in attempts:
        proc = subprocess.run(cmd, capture_output=True, text=True, encoding="utf-8", errors="replace")
        diag = (proc.stdout or "") + (proc.stderr or "")
        last = (proc.returncode, diag)
        if "invalid flag" not in diag and "invalid target release" not in diag:
            return last
    return last


def syntax_errors(diagnostics: str):
    """Filter javac diagnostics down to the syntax-class ones."""
    found = []
    for line in diagnostics.splitlines():
        match = _DIAG_RE.search(line)
        if match and is_syntax_key(match.group("key")):
            found.append(line.strip())
    return found


def scratch_dir(prefix):
    """Private scratch directory for one javac run (see tools/toolchain.py)."""
    if toolchain is None:  # pragma: no cover - toolchain.py ships beside this file
        raise RuntimeError("tools/toolchain.py is required to create a scratch directory")
    return toolchain.make_scratch_dir(prefix, WORKSPACE)


def check_files(files):
    """Return (exit code, syntax error lines) for a list of .java files."""
    javac = find_javac()
    if javac is None:
        print("FAIL: syntax-check: no javac is available", file=sys.stderr)
        return 2, ["no javac is available"]
    out_dir = scratch_dir("structurereset-syntax")
    try:
        code, diag = run_javac(javac, files, out_dir)
    finally:
        shutil.rmtree(out_dir, ignore_errors=True)
    errors = syntax_errors(diag)
    return (1 if errors else 0), errors


def check_tree(java_root: Path) -> int:
    files = collect_java_files(java_root)
    if not files:
        print("syntax-check: no .java files under " + str(java_root))
        return 0
    if toolchain is not None:
        print("syntax-check: using " + toolchain.describe())
    code, errors = check_files(files)
    if errors:
        for line in errors:
            print("FAIL: syntax-check: " + line)
        print("syntax-check: %d syntax error(s) in %d file(s)" % (len(errors), len(files)))
        return 1
    print("syntax-check: %d file(s) parsed, no syntax errors" % len(files))
    return 0


# ------------------------------------------------------------------ selftest

_GOOD = """
package sample;

public class Good {
    private final int value = 1;

    public int compute(int input) {
        if (input > 0) {
            return input + value;
        }
        return -input;
    }
}
"""

_BAD = """
package sample;

public class Bad {
    void broken() {
        int x = ;
    }
}
"""

_RESOLUTION_ONLY = """
package sample;

import net.minecraft.world.item.Item;
import net.minecraftforge.fml.common.Mod;

@Mod("sample")
public class ResolutionOnly {
    private Item item;
    private Missing helper;

    public Unknown call(AlsoMissing other) {
        return other.value;
    }
}
"""


def _write(path: Path, text: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text, encoding="utf-8")


def selftest() -> int:
    tmp = scratch_dir("structurereset-syntax-selftest")
    ok = True
    try:
        good = tmp / "good" / "Good.java"
        bad = tmp / "bad" / "Bad.java"
        reso = tmp / "resolution" / "ResolutionOnly.java"
        _write(good, _GOOD)
        _write(bad, _BAD)
        _write(reso, _RESOLUTION_ONLY)

        code, errors = check_files([good])
        if code != 0 or errors:
            print("FAIL: selftest: valid sample reported syntax errors: %r" % (errors,))
            ok = False

        code, errors = check_files([bad])
        if code == 0 or not errors:
            print("FAIL: selftest: broken sample was not reported as a syntax error")
            ok = False

        code, errors = check_files([reso])
        if code != 0 or errors:
            print("FAIL: selftest: resolution-only sample must be ignored, got %r" % (errors,))
            ok = False

        if check_tree(tmp / "good") != 0:
            print("FAIL: selftest: check_tree() on a valid tree did not return 0")
            ok = False
        if check_tree(tmp / "bad") == 0:
            print("FAIL: selftest: check_tree() on a broken tree returned 0")
            ok = False
    finally:
        shutil.rmtree(tmp, ignore_errors=True)

    if not parallel_selftest():
        ok = False

    print("syntax-check selftest: " + ("OK" if ok else "FAILED"))
    return 0 if ok else 1


def parallel_selftest() -> bool:
    """Two simultaneous invocations must both succeed and agree.

    Nothing in this checker writes to a fixed path -- every javac run is given a
    private scratch directory -- so overlapping runs cannot disturb each other.
    This keeps that property tested rather than assumed.
    """
    tmp = scratch_dir("structurereset-syntax-parallel")
    ok = True
    try:
        good_dir = tmp / "good"
        bad_dir = tmp / "bad"
        _write(good_dir / "Good.java", _GOOD)
        _write(bad_dir / "Bad.java", _BAD)
        for label, java_root, expected in (("clean tree", good_dir, 0),
                                           ("broken tree", bad_dir, 1)):
            children = [subprocess.Popen(
                [sys.executable, str(SCRIPT), "--java-root", str(java_root)],
                stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                text=True, encoding="utf-8", errors="replace") for _ in range(2)]
            codes = []
            for child in children:
                child.communicate()
                codes.append(child.returncode)
            print("selftest: two simultaneous syntax checks of a %s pid=%s -> exit codes %s"
                  % (label, [child.pid for child in children], codes))
            if any(code != expected for code in codes):
                print("FAIL: selftest: simultaneous syntax checks disagreed on a %s" % label)
                ok = False
    finally:
        shutil.rmtree(tmp, ignore_errors=True)
    return ok


def main(argv) -> int:
    parser = argparse.ArgumentParser(description="Pure javac syntax check.")
    parser.add_argument("--selftest", action="store_true", help="check the checker itself")
    parser.add_argument("--workspace", default=None, help="Project root (defaults to this script's parent)")
    parser.add_argument("--java-root", default=None, help="Java source root to scan")
    args = parser.parse_args(argv)

    if args.selftest:
        return selftest()

    workspace = Path(args.workspace).resolve() if args.workspace else WORKSPACE
    java_root = Path(args.java_root).resolve() if args.java_root else workspace / "src" / "main" / "java"
    return check_tree(java_root)


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
