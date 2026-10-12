#!/usr/bin/env python3
"""Produce a real, inspectable jar offline -- without Gradle.

The restricted-network workspace cannot run ForgeGradle, so this script builds a
"dev" jar that is enough to validate resources and the Minecraft-free logic
layer:

1. every .java file under src/main/java is offered to javac (JDK 17);
2. files that cannot compile because they reference classes outside java.*,
   javax.* and com.google.gson.* (Forge, Minecraft, KubeJS, ...) are dropped,
   together with everything that depends on them, and the compilation is
   retried until it succeeds.  A genuine compile error -- anything that is not a
   missing type -- aborts the script instead of silently dropping the file;
3. the surviving classes are compiled into build/classes;
4. src/main/resources and the compiled classes are written into
   build/dist/<mod_id>-<mod_version>-dev.jar with a deterministic java.util.zip
   compatible writer: entries are sorted, timestamps and permissions are fixed,
   so packaging the same tree twice yields byte-identical archives.

The jar therefore never contains classes that need a Forge classpath; it is a
resource + pure-logic artefact, and the fact is printed at the end.

Usage:
    python tools/package.py             # build build/dist/<modid>-<version>-dev.jar
    python tools/package.py --selftest  # prove compilation, exclusion and determinism
"""

from __future__ import annotations

import argparse
import os
import re
import shutil
import subprocess
import sys
import tempfile
import zipfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import toolchain  # noqa: E402

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    sys.stderr.reconfigure(encoding="utf-8", errors="replace")
except Exception:  # pragma: no cover
    pass

SCRIPT = Path(__file__).resolve()
WORKSPACE = SCRIPT.parent.parent
LIB_DIR = SCRIPT.parent / "lib"
JAVA_RELEASE = "17"
FIXED_DATE = (2001, 1, 1, 0, 0, 0)

# javac diagnostics that mean "this type simply is not on our classpath".
# The keys are matched, not the (localised) text, so the classification is
# identical on an English, Chinese or any other Windows locale.
RESOLUTION_KEYS = (
    "compiler.err.cant.resolve",
    "compiler.err.doesnt.exist",
    "compiler.err.cant.access",
    "compiler.err.bad.class.file.header",
)
RESOLUTION_MARKERS = (
    "cannot find symbol",
    "does not exist",
    "cannot access",
    "bad class file",
)

DIAGNOSTIC_LINE = re.compile(
    r"^(?P<file>.+[.]java):[0-9]+(?::[0-9]+)?: (?P<key>compiler[.][A-Za-z0-9_.]+)(?P<rest>.*)$")

# Force English, ASCII-safe diagnostics: javac otherwise reports in the console
# code page, which turns into mojibake the moment Python decodes it as UTF-8.
JAVAC_LOCALE_FLAGS = (
    "-J-Duser.language=en",
    "-J-Duser.country=US",
    "-J-Dfile.encoding=UTF-8",
)


class PackageError(RuntimeError):
    """Raised when packaging cannot honestly produce a jar."""


# ------------------------------------------------------------- properties

def read_properties(path):
    """Minimal gradle.properties reader (key=value, # comments)."""
    values = {}
    path = Path(path)
    if not path.is_file():
        return values
    for line in path.read_text(encoding="utf-8", errors="replace").splitlines():
        stripped = line.strip()
        if not stripped or stripped.startswith("#") or stripped.startswith("!"):
            continue
        if "=" not in stripped:
            continue
        key, _, value = stripped.partition("=")
        values[key.strip()] = value.strip()
    return values


def project_metadata(workspace):
    """(mod_id, mod_version, mod_group) from gradle.properties with fallbacks."""
    props = read_properties(Path(workspace) / "gradle.properties")
    mod_id = props.get("mod_id") or "structurereset"
    mod_version = props.get("mod_version") or "0.0.0"
    group = props.get("mod_group_id") or "com.structurereset"
    return mod_id, mod_version, group


# --------------------------------------------------------------- gson jar

def find_gson():
    """A locally available gson jar, or None (never downloaded)."""
    override = os.environ.get("STRUCTURERESET_GSON")
    if override and Path(override).is_file():
        return Path(override)
    if LIB_DIR.is_dir():
        local = sorted(LIB_DIR.glob("gson-*.jar"))
        if local:
            return local[-1]
    roots = []
    gradle_home = os.environ.get("GRADLE_USER_HOME")
    if gradle_home:
        roots.append(Path(gradle_home) / "caches" / "modules-2" / "files-2.1")
    home = os.environ.get("USERPROFILE") or os.environ.get("HOME")
    if home:
        roots.append(Path(home) / ".gradle" / "caches" / "modules-2" / "files-2.1")
    found = []
    for root in roots:
        gson_root = root / "com.google.code.gson" / "gson"
        if gson_root.is_dir():
            found.extend(sorted(gson_root.glob("*/**/gson-*.jar")))
    if not found:
        return None
    # Numeric version order, so gson-2.10.1 beats gson-2.8.9 (a plain string sort
    # would pick the older one).
    return max(found, key=lambda path: [int(part) for part in re.findall(r"[0-9]+", path.name)] or [0])


# ---------------------------------------------------------------- javac

def collect_java(root):
    root = Path(root)
    return sorted(root.rglob("*.java")) if root.is_dir() else []


def compile_sources(javac_path, sources, classpath, out_dir):
    out_dir = Path(out_dir)
    out_dir.mkdir(parents=True, exist_ok=True)
    command = [str(javac_path)] + list(JAVAC_LOCALE_FLAGS) + [
        "-XDrawDiagnostics", "-encoding", "UTF-8", "-proc:none",
        "--release", JAVA_RELEASE]
    if classpath:
        command += ["-classpath", str(classpath)]
    command += ["-d", str(out_dir)]
    command += [str(source) for source in sources]
    proc = subprocess.run(command, capture_output=True, text=True,
                          encoding="utf-8", errors="replace")
    return proc.returncode, (proc.stdout or "") + (proc.stderr or "")


def classify_diagnostics(output):
    """Split javac -XDrawDiagnostics output into unresolvable files and real errors.

    javac only prints a bare file name when the source lives outside the current
    working directory, so the file is identified both by its resolved path and by
    its base name; a Java class name is unique inside this project, so the base
    name is unambiguous.
    """
    unresolvable_paths = set()
    unresolvable_names = set()
    genuine = []
    for line in output.splitlines():
        match = DIAGNOSTIC_LINE.match(line.strip())
        if not match:
            continue
        key = match.group("key")
        rest = match.group("rest")
        if key.startswith(RESOLUTION_KEYS) or any(m in rest for m in RESOLUTION_MARKERS):
            reported = Path(match.group("file"))
            unresolvable_paths.add(reported.resolve())
            unresolvable_names.add(reported.name)
        else:
            genuine.append(line.strip())
    return unresolvable_paths, unresolvable_names, genuine


EXTERNAL_IMPORT_ROOTS = (
    "net.minecraft.",
    "net.minecraftforge.",
    "dev.latvian.",
    "com.mojang.",
)

IMPORT_LINE = re.compile(r"^\s*import\s+(?:static\s+)?([A-Za-z0-9_.]+)\s*;")


def _needs_external_classpath(source):
    """True when the source imports a package only the real mod classpath has.

    Minecraft, Forge, KubeJS and Mojang's datafixerupper are not on the
    JDK + gson classpath this offline packager can build, and javac emits
    cascading *secondary* diagnostics for them, for example a
    'method does not override superclass' error on a method whose superclass
    could not be resolved.  Those cascades are not real defects, so such sources
    are recognised by their imports and excluded before javac sees them; the
    javac pass below then stays meaningful for genuinely broken pure sources.
    """
    try:
        text = Path(source).read_text(encoding="utf-8", errors="replace")
    except OSError:
        return False
    for line in text.splitlines():
        match = IMPORT_LINE.match(line)
        if match and match.group(1).startswith(EXTERNAL_IMPORT_ROOTS):
            return True
    return False


def select_pure_sources(javac_path, sources, classpath, scratch_dir, log=None):
    """Return (compiled sources, dropped sources) for a Minecraft-free subset.

    Raises PackageError when javac reports anything that is not a missing type,
    so a genuinely broken pure source can never be dropped silently.
    """
    remaining = []
    dropped = []
    for source in sources:
        if _needs_external_classpath(source):
            dropped.append(source)
        else:
            remaining.append(source)
    if dropped and log is not None:
        log("package: excluding %d source(s) that need a Forge/Minecraft classpath"
            % len(dropped))
    while remaining:
        shutil.rmtree(scratch_dir, ignore_errors=True)
        code, output = compile_sources(javac_path, remaining, classpath, scratch_dir)
        if code == 0:
            return remaining, dropped
        unresolvable_paths, unresolvable_names, genuine = classify_diagnostics(output)
        if genuine:
            raise PackageError("javac reported a real compile error:\n" + "\n".join(genuine))
        doomed = [source for source in remaining
                  if Path(source).resolve() in unresolvable_paths
                  or Path(source).name in unresolvable_names]
        if not doomed:
            raise PackageError("javac failed without naming a source file:\n" + output.strip())
        for source in doomed:
            dropped.append(source)
        remaining = [source for source in remaining if source not in doomed]
        if log is not None:
            log("package: excluding %d source(s) that need a Forge/Minecraft classpath"
                % len(doomed))
    return [], dropped


# ------------------------------------------------------------------ jar

def collect_entries(workspace, classes_dir):
    """Every entry of the jar as (archive name, bytes), ready to be sorted."""
    entries = {}
    resources = Path(workspace) / "src" / "main" / "resources"
    if resources.is_dir():
        for path in sorted(resources.rglob("*")):
            if path.is_file():
                entries[path.relative_to(resources).as_posix()] = path.read_bytes()
    classes_dir = Path(classes_dir)
    if classes_dir.is_dir():
        for path in sorted(classes_dir.rglob("*.class")):
            entries[path.relative_to(classes_dir).as_posix()] = path.read_bytes()
    return entries


def manifest_text(mod_id, mod_version, group):
    return ("Manifest-Version: 1.0\r\n"
            "Implementation-Title: %s\r\n"
            "Implementation-Version: %s\r\n"
            "Implementation-Vendor: %s\r\n"
            "Mod-Id: %s\r\n"
            "Built-By: tools/package.py (offline, no Gradle)\r\n"
            "\r\n" % (mod_id, mod_version, group, mod_id))


def write_jar(jar_path, entries):
    """Deterministic zip writer: sorted names, fixed timestamp and permissions."""
    jar_path = Path(jar_path)
    jar_path.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(jar_path, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
        for name in sorted(entries):
            info = zipfile.ZipInfo(name, date_time=FIXED_DATE)
            info.compress_type = zipfile.ZIP_DEFLATED
            info.create_system = 3
            info.external_attr = 0o644 << 16
            archive.writestr(info, entries[name])
    return jar_path


def build(workspace, out_dir=None, quiet=False, log=print):
    """Compile the pure subset and package it; returns a result dictionary."""
    workspace = Path(workspace).resolve()
    mod_id, mod_version, group = project_metadata(workspace)
    out_dir = Path(out_dir) if out_dir else workspace / "build" / "dist"
    classes_dir = workspace / "build" / "classes"
    scratch_dir = workspace / "build" / "pure-scratch"
    jar_path = out_dir / ("%s-%s-dev.jar" % (mod_id, mod_version))

    all_sources = collect_java(workspace / "src" / "main" / "java")
    if not all_sources:
        raise PackageError("no .java source found under %s" % (workspace / "src" / "main" / "java"))
    junit_jar = workspace / "tools" / "lib" / "junit-platform-console-standalone-1.10.2.jar"

    jdk = toolchain.find_jdk17()
    javac_path = toolchain.javac(jdk)
    gson = find_gson()
    classpath_entries = []
    if gson is not None:
        classpath_entries.append(str(gson))
    if junit_jar.is_file():
        classpath_entries.append(str(junit_jar))
    classpath = os.pathsep.join(classpath_entries)

    if not quiet:
        log("package: " + toolchain.describe(jdk))
        log("package: classpath = %s" % (classpath or "(none)"))
        log("package: %d source file(s) offered to javac" % len(all_sources))

    compiled, dropped = select_pure_sources(javac_path, all_sources, classpath,
                                            scratch_dir, log=None if quiet else log)
    if dropped and not quiet:
        log("package: %d source file(s) need a Forge/Minecraft classpath and are not packaged"
            % len(dropped))

    shutil.rmtree(classes_dir, ignore_errors=True)
    if compiled:
        code, output = compile_sources(javac_path, compiled, classpath, classes_dir)
        if code != 0:
            raise PackageError("final compilation failed:\n" + output.strip())
    shutil.rmtree(scratch_dir, ignore_errors=True)

    entries = collect_entries(workspace, classes_dir)
    entries["META-INF/MANIFEST.MF"] = manifest_text(mod_id, mod_version, group).encode("utf-8")
    write_jar(jar_path, entries)

    class_count = sum(1 for name in entries if name.endswith(".class"))
    result = {
        "jar": jar_path,
        "mod_id": mod_id,
        "mod_version": mod_version,
        "sources_total": len(all_sources),
        "sources_compiled": len(compiled),
        "sources_dropped": len(dropped),
        "classes": class_count,
        "entries": len(entries),
        "bytes": jar_path.stat().st_size,
    }
    return result


# ------------------------------------------------------------- selftest

SELFTEST_GRADLE_PROPERTIES = """mod_id=sample
mod_version=9.9.9-test
mod_group_id=com.example
"""

SELFTEST_MODS_TOML = """modLoader="javafml"
loaderVersion="[47,)"
license="MIT"

[[mods]]
modId="sample"
version="9.9.9"
displayName="Sample"
description='''
Selftest fixture.
'''

[[dependencies.sample]]
modId="forge"
mandatory=true
versionRange="[47,)"
ordering="NONE"
side="BOTH"

[[dependencies.sample]]
modId="minecraft"
mandatory=true
versionRange="[1.20.1,1.21)"
ordering="NONE"
side="BOTH"
"""

PURE_SOURCE = """package sample;

public final class Pure {

    private final int value;

    public Pure(int value) {
        this.value = value;
    }

    public int doubled() {
        return value * 2;
    }
}
"""

NEEDS_MINECRAFT_SOURCE = """package sample;

import net.minecraft.world.item.Item;

public final class NeedsMinecraft {

    private Item item;

    public Item item() {
        return item;
    }
}
"""

CALLER_SOURCE = """package sample;

public final class Caller {

    public NeedsMinecraft create() {
        return new NeedsMinecraft();
    }
}
"""

BROKEN_SOURCE = """package sample;

public final class BrokenPure {

    public int broken() {
        int x = ;
        return x;
    }
}
"""


def _write(path, text):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text, encoding="utf-8")
    return path


def _make_sample(root):
    _write(root / "gradle.properties", SELFTEST_GRADLE_PROPERTIES)
    _write(root / "src" / "main" / "resources" / "META-INF" / "mods.toml", SELFTEST_MODS_TOML)
    _write(root / "src" / "main" / "resources" / "assets" / "sample" / "lang" / "en_us.json",
           '{\n  "item.sample.widget": "Widget"\n}\n')
    _write(root / "src" / "main" / "java" / "sample" / "Pure.java", PURE_SOURCE)
    _write(root / "src" / "main" / "java" / "sample" / "NeedsMinecraft.java",
           NEEDS_MINECRAFT_SOURCE)
    _write(root / "src" / "main" / "java" / "sample" / "Caller.java", CALLER_SOURCE)
    return root


def selftest():
    """Prove compilation, exclusion of Minecraft classes and deterministic output."""
    tmp = Path(tempfile.mkdtemp(prefix="structurereset-package-selftest-"))
    ok = True
    silent = lambda message: None
    try:
        sample = _make_sample(tmp / "sample")

        first_dir = tmp / "out-first"
        result = build(sample, first_dir, quiet=True, log=silent)
        print("selftest: packaged %s" % result["jar"])
        print("selftest: compiled=%d dropped=%d classes=%d entries=%d"
              % (result["sources_compiled"], result["sources_dropped"],
                 result["classes"], result["entries"]))

        with zipfile.ZipFile(result["jar"]) as archive:
            names = archive.namelist()

        if "sample/Pure.class" not in names:
            print("FAIL: selftest: the pure class was not compiled into the jar")
            ok = False
        if "sample/NeedsMinecraft.class" in names or "sample/Caller.class" in names:
            print("FAIL: selftest: a Minecraft-dependent class leaked into the jar")
            ok = False
        for required in ("META-INF/mods.toml", "assets/sample/lang/en_us.json",
                         "META-INF/MANIFEST.MF"):
            if required not in names:
                print("FAIL: selftest: resource '%s' is missing from the jar" % required)
                ok = False
        if result["sources_dropped"] != 2:
            print("FAIL: selftest: expected exactly 2 dropped sources, got %d"
                  % result["sources_dropped"])
            ok = False

        second_dir = tmp / "out-second"
        second = build(sample, second_dir, quiet=True, log=silent)
        if result["jar"].read_bytes() != second["jar"].read_bytes():
            print("FAIL: selftest: packaging the same tree twice is not deterministic")
            ok = False

        broken = _make_sample(tmp / "broken")
        _write(broken / "src" / "main" / "java" / "sample" / "BrokenPure.java", BROKEN_SOURCE)
        try:
            build(broken, tmp / "out-broken", quiet=True, log=silent)
        except PackageError as exc:
            print("selftest: a genuinely broken source is refused (%s)"
                  % str(exc).splitlines()[0])
        else:
            print("FAIL: selftest: a genuinely broken source was silently dropped")
            ok = False
    except Exception as exc:  # a crashing selftest is a failing selftest
        print("FAIL: selftest raised %s: %s" % (type(exc).__name__, exc))
        ok = False
    finally:
        shutil.rmtree(tmp, ignore_errors=True)

    print("package selftest: " + ("OK" if ok else "FAILED"))
    return 0 if ok else 1


# ------------------------------------------------------------------ main

def main(argv):
    parser = argparse.ArgumentParser(description="Offline packaging of the dev jar.")
    parser.add_argument("--selftest", action="store_true",
                        help="prove compilation, exclusion and determinism")
    parser.add_argument("--workspace", default=None, help="Project root")
    parser.add_argument("--out-dir", default=None,
                        help="Output directory (default: <workspace>/build/dist)")
    parser.add_argument("--quiet", action="store_true", help="only print the artefact line")
    args = parser.parse_args(argv)

    if args.selftest:
        return selftest()

    workspace = Path(args.workspace).resolve() if args.workspace else WORKSPACE
    try:
        result = build(workspace, args.out_dir, quiet=args.quiet)
    except PackageError as exc:
        print("FAIL: package: %s" % exc, file=sys.stderr)
        return 1

    print("package: wrote %s" % result["jar"])
    print("package: %d class file(s), %d entry/entries, %d byte(s)"
          % (result["classes"], result["entries"], result["bytes"]))
    print("package: note -- this jar only carries resources and Minecraft-free classes; "
          "classes that need the Forge classpath are compiled by Gradle, not here.")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
