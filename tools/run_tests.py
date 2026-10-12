#!/usr/bin/env python3
"""Compile and *really* execute the JUnit 5 suite of this workspace.

This is the offline replacement for "gradlew test":

* javac (JDK 17) compiles every source under src/main/java/com/structurereset/api
  together with every source under src/test/java into build/test-classes;
* the JUnit Platform console standalone jar supplies the JUnit 5 API, engine and
  launcher.  It is cached under tools/lib and downloaded from repo1.maven.org
  with a browser User-Agent only when that cache is empty -- never unpacked into
  src/;
* the suite is launched from that jar and its XML report is parsed, so the counts
  printed below come from a real execution, not from a grep of the sources.

Usage:
    python tools/run_tests.py              # compile + run, exit 0 when green
    python tools/run_tests.py --selftest   # prove the runner detects failing suites

Machine readable result (always the last line):
    SUMMARY tests=<n> failures=<n> errors=<n>
"""

from __future__ import annotations

import argparse
import os
import re
import shutil
import subprocess
import sys
import tempfile
import urllib.request
import xml.etree.ElementTree as ET
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import toolchain  # noqa: E402  (sibling module of the offline toolchain)

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    sys.stderr.reconfigure(encoding="utf-8", errors="replace")
except Exception:  # pragma: no cover
    pass

SCRIPT = Path(__file__).resolve()
WORKSPACE = SCRIPT.parent.parent
LIB_DIR = SCRIPT.parent / "lib"

# JUnit Platform 1.10.2 pairs with the JUnit Jupiter 5.10.2 declared in build.gradle.
JUNIT_VERSION = "1.10.2"
JUNIT_JAR_NAME = "junit-platform-console-standalone-%s.jar" % JUNIT_VERSION
JUNIT_URL = ("https://repo1.maven.org/maven2/org/junit/platform/"
             "junit-platform-console-standalone/%s/%s" % (JUNIT_VERSION, JUNIT_JAR_NAME))
BROWSER_UA = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
              "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
JAVA_RELEASE = "17"


class SuiteResult:
    """Outcome of one compile + JUnit run."""

    def __init__(self, tests=0, failures=0, errors=0, exit_code=0, output="", note=""):
        self.tests = tests
        self.failures = failures
        self.errors = errors
        self.exit_code = exit_code
        self.output = output
        self.note = note

    def summary_line(self):
        return "SUMMARY tests=%d failures=%d errors=%d" % (self.tests, self.failures, self.errors)


# ------------------------------------------------------------- JUnit jar

def cached_junit():
    """A previously cached console standalone jar, or None."""
    override = os.environ.get("JUNIT_CONSOLE_JAR")
    if override and Path(override).is_file():
        return Path(override)
    if LIB_DIR.is_dir():
        exact = LIB_DIR / JUNIT_JAR_NAME
        if exact.is_file():
            return exact
        others = sorted(LIB_DIR.glob("junit-platform-console-standalone-*.jar"))
        if others:
            return others[-1]
    return None


def download_junit():
    """Fetch the console standalone jar into tools/lib using a browser UA."""
    LIB_DIR.mkdir(parents=True, exist_ok=True)
    target = LIB_DIR / JUNIT_JAR_NAME
    part = target.with_name(target.name + ".part")
    request = urllib.request.Request(
        JUNIT_URL,
        headers={"User-Agent": BROWSER_UA, "Accept": "*/*",
                 "Accept-Language": "en-US,en;q=0.9"},
    )
    print("run-tests: downloading %s" % JUNIT_URL)
    with urllib.request.urlopen(request, timeout=180) as response, open(part, "wb") as handle:
        shutil.copyfileobj(response, handle)
    if part.stat().st_size < 1024 * 100:
        part.unlink(missing_ok=True)
        raise RuntimeError("downloaded JUnit jar looks truncated")
    part.replace(target)
    return target


def ensure_junit(allow_download=True):
    """The console jar to use, downloading it when necessary."""
    found = cached_junit()
    if found is not None:
        return found
    if not allow_download:
        return None
    try:
        return download_junit()
    except Exception as exc:
        print("FAIL: run-tests: cannot obtain the JUnit console jar (%s)" % exc,
              file=sys.stderr)
        return None


# ---------------------------------------------------------------- javac

def collect_java(root):
    """All .java files below a directory, sorted."""
    root = Path(root)
    return sorted(root.rglob("*.java")) if root.is_dir() else []


def compile_sources(javac_path, sources, classpath, out_dir):
    """Compile sources into out_dir; return (exit code, diagnostics)."""
    out_dir = Path(out_dir)
    out_dir.mkdir(parents=True, exist_ok=True)
    # -J flags force English, UTF-8 diagnostics so javac output stays readable
    # regardless of the console code page of the machine running the check.
    command = [str(javac_path),
               "-J-Duser.language=en", "-J-Duser.country=US", "-J-Dfile.encoding=UTF-8",
               "-encoding", "UTF-8", "-proc:none", "--release", JAVA_RELEASE]
    if classpath:
        command += ["-classpath", str(classpath)]
    command += ["-d", str(out_dir)]
    command += [str(source) for source in sources]
    proc = subprocess.run(command, capture_output=True, text=True,
                          encoding="utf-8", errors="replace")
    return proc.returncode, (proc.stdout or "") + (proc.stderr or "")


# ------------------------------------------------------------- JUnit run

def run_console(java_path, junit_jar, classes_dir, reports_dir, workspace):
    """Launch the JUnit console launcher; return (exit code, output)."""
    command = [
        str(java_path),
        "-Dstructurereset.workspace=%s" % workspace,
        "-Dfile.encoding=UTF-8",
        "-jar", str(junit_jar),
        "execute",
        "--class-path=%s" % classes_dir,
        "--scan-class-path=%s" % classes_dir,
        "--include-classname=.*",
        "--details=summary",
        "--disable-ansi-colors",
        "--reports-dir=%s" % reports_dir,
        "--fail-if-no-tests",
    ]
    proc = subprocess.run(command, capture_output=True, text=True,
                          encoding="utf-8", errors="replace")
    return proc.returncode, (proc.stdout or "") + (proc.stderr or "")


def parse_reports(reports_dir):
    """Read the JUnit XML reports; None when no report was written."""
    totals = {"tests": 0, "failures": 0, "errors": 0}
    seen = False
    for path in sorted(Path(reports_dir).glob("TEST-*.xml")):
        try:
            root = ET.parse(path).getroot()
        except Exception:
            continue
        if root.tag != "testsuite":
            continue
        seen = True
        totals["tests"] += int(root.get("tests") or 0)
        totals["failures"] += int(root.get("failures") or 0)
        totals["errors"] += int(root.get("errors") or 0)
    return totals if seen else None


def parse_console(text):
    """Fallback parser for the bracket summary block of the console output."""
    def number(label):
        match = re.search(r"([0-9]+)[ \t]+tests?[ \t]+" + label, text)
        return int(match.group(1)) if match else 0

    return {"tests": number("found"), "failures": number("failed"), "errors": 0}


def execute(workspace, junit_jar, verbose=False):
    """Compile api + test sources and run them; returns a SuiteResult."""
    workspace = Path(workspace).resolve()
    api_root = workspace / "src" / "main" / "java" / "com" / "structurereset" / "api"
    test_root = workspace / "src" / "test" / "java"

    test_sources = collect_java(test_root)
    if not test_sources:
        return SuiteResult(note="no test sources under %s" % test_root)

    jdk = toolchain.find_jdk17()
    javac_path = toolchain.javac(jdk)
    java_path = toolchain.java(jdk)
    api_sources = collect_java(api_root)
    sources = api_sources + test_sources

    classes_dir = workspace / "build" / "test-classes"
    reports_dir = workspace / "build" / "test-reports"
    for directory in (classes_dir, reports_dir):
        shutil.rmtree(directory, ignore_errors=True)

    if verbose:
        print("run-tests: " + toolchain.describe(jdk))
        print("run-tests: junit  = %s" % junit_jar)
    print("run-tests: compiling %d api source(s) + %d test source(s)"
          % (len(api_sources), len(test_sources)))

    code, output = compile_sources(javac_path, sources, junit_jar, classes_dir)
    if code != 0:
        print(output.rstrip())
        return SuiteResult(exit_code=1, output=output, note="javac failed")

    code, output = run_console(java_path, junit_jar, classes_dir, reports_dir, workspace)
    counts = parse_reports(reports_dir) or parse_console(output)
    tests = counts["tests"]
    failures = counts["failures"]
    errors = counts["errors"]
    # A source tree with tests that execute nothing is a failure, never a pass.
    drained = tests == 0
    exit_code = 0 if (code == 0 and failures == 0 and errors == 0 and not drained) else 1
    note = "junit console exit=%d" % code
    if drained:
        note += " (no test was executed)"
    return SuiteResult(tests=tests, failures=failures, errors=errors,
                       exit_code=exit_code, output=output, note=note)


# -------------------------------------------------------------- selftest

PASSING_TEST = """package sample;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class PassingTest {

    @Test
    void addsNumbers() {
        assertEquals(4, 2 + 2);
    }

    @Test
    void joinsStrings() {
        assertEquals("ab", "a" + "b");
    }
}
"""

FAILING_TEST = """package sample;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class FailingTest {

    @Test
    void assertsSomethingFalse() {
        assertEquals(5, 2 + 2);
    }
}
"""

ERROR_TEST = """package sample;

import org.junit.jupiter.api.Test;

class ErrorTest {

    @Test
    void throwsUnexpectedly() {
        throw new IllegalStateException("boom");
    }
}
"""


def _write(path, text):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text, encoding="utf-8")
    return path


def selftest():
    """Prove that the runner reports green suites as passes and red ones as failures."""
    junit_jar = ensure_junit()
    if junit_jar is None:
        print("FAIL: selftest: the JUnit console jar is not available")
        return 1

    tmp = Path(tempfile.mkdtemp(prefix="structurereset-runtests-selftest-"))
    ok = True
    try:
        green = tmp / "green"
        _write(green / "src" / "test" / "java" / "sample" / "PassingTest.java", PASSING_TEST)
        result = execute(green, junit_jar)
        print("selftest: green suite       -> %s (%s)" % (result.summary_line(), result.note))
        if result.exit_code != 0 or result.tests < 2 or result.failures or result.errors:
            print("FAIL: selftest: the passing suite was not reported as green")
            ok = False

        red = tmp / "red"
        _write(red / "src" / "test" / "java" / "sample" / "FailingTest.java", FAILING_TEST)
        result = execute(red, junit_jar)
        print("selftest: failing assertion -> %s (%s)" % (result.summary_line(), result.note))
        if result.exit_code == 0 or result.failures < 1:
            print("FAIL: selftest: a failing assertion did not produce a non-zero result")
            ok = False

        error = tmp / "error"
        _write(error / "src" / "test" / "java" / "sample" / "ErrorTest.java", ERROR_TEST)
        result = execute(error, junit_jar)
        print("selftest: thrown exception  -> %s (%s)" % (result.summary_line(), result.note))
        if result.exit_code == 0 or (result.errors + result.failures) < 1:
            print("FAIL: selftest: an exception inside a test was not reported")
            ok = False

        empty = tmp / "empty"
        (empty / "src" / "main" / "java").mkdir(parents=True, exist_ok=True)
        result = execute(empty, junit_jar)
        print("selftest: no test sources   -> %s (%s)" % (result.summary_line(), result.note))
        if result.exit_code != 0 or result.tests != 0:
            print("FAIL: selftest: an empty test tree must report tests=0 and exit 0")
            ok = False
    finally:
        shutil.rmtree(tmp, ignore_errors=True)

    print("run-tests selftest: " + ("OK" if ok else "FAILED"))
    return 0 if ok else 1


# ------------------------------------------------------------------ main

def main(argv):
    parser = argparse.ArgumentParser(description="Compile and run the JUnit 5 suite offline.")
    parser.add_argument("--selftest", action="store_true",
                        help="prove the runner detects failing suites")
    parser.add_argument("--workspace", default=None, help="Project root")
    parser.add_argument("--no-download", action="store_true", help="never touch the network")
    parser.add_argument("--quiet", action="store_true", help="only print the SUMMARY line")
    args = parser.parse_args(argv)

    workspace = Path(args.workspace).resolve() if args.workspace else WORKSPACE

    if args.selftest:
        return selftest()

    junit_jar = ensure_junit(allow_download=not args.no_download)
    if junit_jar is None:
        print("FAIL: run-tests: no JUnit console jar available", file=sys.stderr)
        print("SUMMARY tests=0 failures=0 errors=0")
        return 2

    result = execute(workspace, junit_jar, verbose=not args.quiet)
    if result.output and not args.quiet:
        print(result.output.rstrip())
    if result.note:
        print("run-tests: %s" % result.note)
    print(result.summary_line())
    return result.exit_code


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
