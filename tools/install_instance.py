#!/usr/bin/env python3
"""Install the freshly built jar into every Minecraft instance that already has it.

Nothing about the jar name or about the instance list is hard coded any more: the
source is the newest mod jar under `build/libs`, and the targets are the
instances under the version root whose `mods` folder already contains a
`structurereset-*.jar`. That way a version bump cannot silently point the tool at
a jar that no longer exists, and an instance that never had the addon is never
touched.

    python tools/install_instance.py
    python tools/install_instance.py --instance <名字> --instance <名字>
    python tools/install_instance.py --all          # every instance with a mods folder
    python tools/install_instance.py --source PATH  # install a specific jar
    python tools/install_instance.py --dry-run      # only report what would happen

For each target the script copies through a temporary file followed by
`os.replace`, so a half-written jar can never be left behind for Forge to load,
deletes older versions of the addon so two copies can never load at once, and
then proves the install: the size and the SHA-256 of the source and of every
target are compared, and the exit code is 0 only when they all match.
"""

from __future__ import annotations

import argparse
import hashlib
import os
import shutil
import sys
from pathlib import Path

try:  # keep console output from exploding on legacy code pages
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    sys.stderr.reconfigure(encoding="utf-8", errors="replace")
except Exception:  # pragma: no cover
    pass

SCRIPT = Path(__file__).resolve()
WORKSPACE = SCRIPT.parent.parent
LIB_DIR = WORKSPACE / "build" / "libs"
VERSION_ROOT = Path(os.environ.get("UBSR_VERSION_ROOT", Path.home() / "AppData" / "Roaming" / ".minecraft" / "versions"))

# The name of the addon, used to find both the built jar and the installs.
ARTIFACT = "structurereset"

# Suffixes that are not the playable jar.
NOT_PLAYABLE = ("-sources", "-javadoc", "-dev", "-slim")

CHUNK = 1024 * 1024


def find_source() -> Path | None:
    """The newest playable jar under build/libs, whatever its version is."""
    if not LIB_DIR.is_dir():
        return None
    jars = [p for p in LIB_DIR.glob(ARTIFACT + "-*.jar")
            if not any(p.stem.endswith(suffix) for suffix in NOT_PLAYABLE)]
    if not jars:
        return None
    return max(jars, key=lambda p: p.stat().st_mtime)


def mods_dirs(instance_names: list, include_all: bool) -> list:
    """The mods folders to install into, with the reason each one was chosen."""
    found = []
    if not VERSION_ROOT.is_dir():
        return found
    for instance in sorted(VERSION_ROOT.iterdir()):
        if not instance.is_dir():
            continue
        mods = instance / "mods"
        if not mods.is_dir():
            continue
        has_addon = any(mods.glob(ARTIFACT + "-*.jar"))
        chosen = include_all or instance.name in instance_names or has_addon
        if chosen:
            found.append((instance.name, mods, "has the addon already" if has_addon
                          else ("requested" if instance.name in instance_names else "--all")))
    return found


def sha256_of(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        while True:
            block = handle.read(CHUNK)
            if not block:
                break
            digest.update(block)
    return digest.hexdigest()


def describe(label: str, path: Path) -> tuple:
    size = path.stat().st_size
    digest = sha256_of(path)
    print("%s: %s" % (label, path))
    print("%s size   = %d bytes" % (label, size))
    print("%s sha256 = %s" % (label, digest))
    return size, digest


def install_one(source: Path, source_size: int, source_digest: str, name: str, mods: Path,
                dry_run: bool) -> bool:
    """Installs into one instance and returns True when the result is verified."""
    target = mods / source.name
    print("--- instance: %s" % name)

    stale = [p for p in mods.glob(ARTIFACT + "-*.jar") if p.name != source.name]
    for old in stale:
        if dry_run:
            print("  would remove older jar: %s" % old.name)
        else:
            old.unlink()
            print("  removed older jar: %s" % old.name)

    if dry_run:
        print("  would install: %s" % source.name)
        return True

    temp = target.with_name(target.name + ".installing.tmp")
    try:
        shutil.copyfile(source, temp)
        os.replace(temp, target)
    except OSError as exc:
        print("  FAIL: could not copy %s -> %s (%s)" % (source, target, exc), file=sys.stderr)
        try:
            temp.unlink()
        except OSError:
            pass
        return False

    target_size, target_digest = describe("  target", target)
    if target_size != source_size or target_digest != source_digest:
        print("  FAIL: installed jar is not byte-identical to the built jar", file=sys.stderr)
        return False
    print("  OK: byte-identical (size=%d sha256=%s)" % (target_size, target_digest))
    return True


def main(argv: list) -> int:
    global VERSION_ROOT  # noqa: PLW0603 - one shot override from the command line

    parser = argparse.ArgumentParser(
        description="Install the built jar into every instance that already has it.")
    parser.add_argument("--source", default=None,
                        help="jar to install (default: newest playable jar under build/libs)")
    parser.add_argument("--instance", action="append", default=[],
                        help="instance name to install into; may be repeated")
    parser.add_argument("--all", action="store_true",
                        help="install into every instance that has a mods folder")
    parser.add_argument("--version-root", default=str(VERSION_ROOT),
                        help="folder that holds the instances (default: %s)" % VERSION_ROOT)
    parser.add_argument("--dry-run", action="store_true",
                        help="report what would happen without touching any file")
    args = parser.parse_args(argv)
    VERSION_ROOT = Path(args.version_root)

    print("install_instance.py -- copy the built jar into the test instances")
    print()

    source = Path(args.source) if args.source else find_source()
    if source is None or not source.is_file():
        print("FAIL: no built jar found; run the build first or pass --source", file=sys.stderr)
        return 1

    targets = mods_dirs(args.instance, args.all)
    if not targets:
        print("FAIL: no instance with a mods folder was found under %s" % VERSION_ROOT,
              file=sys.stderr)
        return 1

    source_size, source_digest = describe("source", source)
    print()
    print("instances (%d):" % len(targets))
    for name, mods, why in targets:
        print("  %-18s %s  [%s]" % (name, mods, why))
    print()

    ok = True
    for name, mods, _why in targets:
        ok = install_one(source, source_size, source_digest, name, mods, args.dry_run) and ok

    print()
    if not ok:
        print("FAIL: at least one instance was not installed correctly", file=sys.stderr)
        return 1
    print("OK: %d instance(s) hold %s (sha256=%s)" % (len(targets), source.name, source_digest))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
