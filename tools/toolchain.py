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

It also owns the one scratch-directory helper every offline tool uses, because
some sandboxes let a process write files under %TEMP% but refuse to create a
directory there.  make_scratch_dir() tries the system temp directory first and
falls back to <workspace>/build, so no tool has to call tempfile.mkdtemp()
itself.  Set STRUCTURERESET_SCRATCH_ROOT (or pass root=) to replace the
system-temp candidate: point it at a directory that cannot hold a new directory
and the workspace fallback is exercised on a machine whose real %TEMP% is
writable.  A rejected candidate is reported on stderr, so the fallback the run
actually used is visible in its output.
"""

from __future__ import annotations

import atexit
import os
import re
import shutil
import subprocess
import sys
import tempfile
import uuid
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


# ------------------------------------------------------ private scratch dirs

#: Root the fallback scratch directory hangs off: <WORKSPACE>/build/<prefix>-...
WORKSPACE = Path(__file__).resolve().parent.parent

#: Optional replacement for the system temp candidate.  Unset by default, so
#: the normal order (system temp, then <workspace>/build) is unchanged.
SCRATCH_ROOT_ENV = "STRUCTURERESET_SCRATCH_ROOT"

#: Scratch directories this process created and has not removed yet.
_SCRATCH_DIRS = []


def _scratch_bases(workspace=None, root=None):
    """Candidate parents of a scratch directory, most preferred first.

    Without an override the system temp directory comes first -- that is where a
    scratch directory belongs -- and <workspace>/build comes last, because some
    sandboxes accept files under %TEMP% yet refuse to create a directory there.

    An explicit override (the root argument, or STRUCTURERESET_SCRATCH_ROOT)
    *replaces* the system temp candidate instead of being tried in front of it:
    pointing it at a path that cannot hold a new directory makes the workspace
    fallback happen on a machine whose real %TEMP% is perfectly writable, which
    is how that fallback is exercised rather than assumed.
    """
    workspace = Path(workspace if workspace is not None else WORKSPACE)
    bases = []
    override = root if root else os.environ.get(SCRATCH_ROOT_ENV)
    if override:
        base = Path(override)
        bases.append(base if base.is_absolute() else workspace / base)
    else:
        bases.append(Path(tempfile.gettempdir()))
    bases.append(workspace / "build")
    return bases


def _accepts_new_directory(base, prefix):
    """True when a new directory really can be created below base.

    tempfile.mkdtemp() retries a PermissionError TMP_MAX times -- 2**31-1 on
    Windows -- for as long as os.access() still reports the parent as writable,
    so a parent that denies new subdirectories turns into an effectively endless
    spin of failed mkdir() calls.  A single os.mkdir() probe settles it at once.
    """
    probe = Path(base) / ("%s-probe-%s" % (prefix, uuid.uuid4().hex[:12]))
    try:
        probe.mkdir()
    except OSError:
        return False
    try:
        probe.rmdir()
    except OSError:  # pragma: no cover - the probe directory is ours
        pass
    return True


def _note(message):
    """Diagnostic on stderr for a run that had to skip a scratch base."""
    print("toolchain: " + message, file=sys.stderr)


def make_scratch_dir(prefix, workspace=None, subdirs=(), root=None):
    """Create and register a private scratch directory, or raise RuntimeError.

    The system temp directory is tried first; when a directory cannot be created
    there (OSError -- e.g. a sandbox forbidding nested directories under %TEMP%)
    the helper falls back to <workspace>/build/<prefix>-<pid>-<random>.  Every
    candidate is made with tempfile.mkdtemp(), which is atomic and collision
    free, and subdirs are created inside it right away, so a caller cannot fail
    halfway through building a nested layout.  Pass root (or set
    STRUCTURERESET_SCRATCH_ROOT) to put a different base in the system temp slot.

    Every refused base and the base that was finally used are reported on
    stderr, so the fallback a run actually took can be read from its output.
    The normal path -- system temp writable, no override -- prints nothing.

    The directory is registered for cleanup: cleanup_scratch_dirs() removes
    whatever is still registered when the process exits, so a caller that never
    removes its scratch directory still leaves nothing behind.
    """
    problem = None
    fell_back = False
    for base in _scratch_bases(workspace, root):
        try:
            base.mkdir(parents=True, exist_ok=True)
        except OSError as exc:  # a file is in the way, or the parent is refused
            problem = exc
        else:
            if _accepts_new_directory(base, prefix):
                try:
                    directory = Path(tempfile.mkdtemp(
                        prefix="%s-%d-" % (prefix, os.getpid()), dir=str(base)))
                    for name in subdirs:
                        (directory / name).mkdir(parents=True, exist_ok=True)
                except OSError as exc:  # unwritable parent: next candidate
                    problem = exc
                else:
                    _SCRATCH_DIRS.append(directory)
                    if fell_back:
                        _note("using workspace fallback scratch directory %s" % directory)
                    return directory
            else:
                problem = PermissionError("cannot create a directory below %s" % base)
        _note("scratch base %s is unusable (%s); trying the next candidate"
              % (base, problem))
        fell_back = True
    raise RuntimeError("no writable private scratch directory for %r (%s)"
                       % (prefix, problem))


def release_scratch_dir(path):
    """Forget a registered scratch directory a caller has removed itself."""
    try:
        _SCRATCH_DIRS.remove(Path(path))
    except ValueError:
        pass


def cleanup_scratch_dirs():
    """Remove every registered scratch directory that still exists."""
    while _SCRATCH_DIRS:
        shutil.rmtree(_SCRATCH_DIRS.pop(), ignore_errors=True)


atexit.register(cleanup_scratch_dirs)
