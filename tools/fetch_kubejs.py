#!/usr/bin/env python3
"""Cache the real KubeJS jar and *measure* how it discovers plugins.

Usage:
    python tools/fetch_kubejs.py             # download (if needed) + scan, exit 0 on success
    python tools/fetch_kubejs.py --scan-only # never touch the network
    python tools/fetch_kubejs.py --selftest  # prove the scanner can tell the two cases apart

Why this script exists
----------------------
A Forge 1.20.1 mod registers a KubeJS plugin by shipping *some* file that KubeJS
scans at startup.  For KubeJS 2001.x that file is either

    kubejs.plugins.txt
    META-INF/services/dev.latvian.mods.kubejs.KubeJSPlugin

Guessing is not acceptable, so this script downloads the exact artefact pinned in
gradle.properties, then reads every .class inside it and reports which class
carries which discovery file name as a string constant.  The printed table is the
evidence quoted in docs/KUBEJS.md, and it decides what src/main/resources ships.

The jar is cached in tools/lib/ (never in src/, never packaged into the mod jar).
A browser User-Agent is used because maven.latvian.dev answers 403 to bot agents.
"""

from __future__ import annotations

import argparse
import shutil
import sys
import tempfile
import urllib.error
import urllib.request
import zipfile
from pathlib import Path

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    sys.stderr.reconfigure(encoding="utf-8", errors="replace")
except Exception:  # pragma: no cover
    pass

SCRIPT = Path(__file__).resolve()
WORKSPACE = SCRIPT.parent.parent
LIB = WORKSPACE / "tools" / "lib"

KUBEJS_VERSION = "2001.6.5-build.26"
KUBEJS_NAME = "kubejs-forge-" + KUBEJS_VERSION + ".jar"
KUBEJS_URL = ("https://maven.latvian.dev/releases/dev/latvian/mods/kubejs-forge/"
              + KUBEJS_VERSION + "/" + KUBEJS_NAME)
KUBEJS_JAR = LIB / KUBEJS_NAME

USER_AGENT = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
              "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36")

# The two discovery mechanisms that are plausible for KubeJS 2001.x.  The scan
# reports every one of them so the conclusion is measured, not assumed.
PLUGINS_TXT = "kubejs.plugins.txt"
SERVICE_PATH = "META-INF/services/dev.latvian.mods.kubejs.KubeJSPlugin"
CANDIDATES = (PLUGINS_TXT.encode("ascii"), SERVICE_PATH.encode("ascii"))

# Classes whose name suggests they *perform* the discovery (used for reporting).
SCANNER_HINTS = ("KubeJSPlugin", "PluginManager", "PluginLoader", "KubeJSPlugins",
                 "PluginDiscover")

ZIP_MAGIC = bytes([0x50, 0x4B, 0x03, 0x04])


def _jar_is_usable(path: Path) -> bool:
    """True only for a complete, readable, CRC-clean zip."""
    if not path.is_file():
        print("fetch-kubejs: " + str(path) + " is missing")
        return False
    try:
        with zipfile.ZipFile(path) as archive:
            bad = archive.testzip()
            if bad is not None:
                print("fetch-kubejs: cached jar has a corrupt entry: " + str(bad))
                return False
    except zipfile.BadZipFile as exc:
        print("fetch-kubejs: cached jar is not a readable zip (" + str(exc) + ")")
        return False
    except Exception as exc:  # pragma: no cover
        print("fetch-kubejs: cached jar could not be validated (" + str(exc) + ")")
        return False
    return True


def _http_get(url: str, offset: int):
    """Open url (optionally resuming at offset) with a browser User-Agent."""
    headers = {
        "User-Agent": USER_AGENT,
        "Accept": "application/java-archive, application/octet-stream, */*",
        "Accept-Language": "en-US,en;q=0.9",
    }
    if offset > 0:
        headers["Range"] = "bytes=" + str(offset) + "-"
    # maven.latvian.dev occasionally stalls for minutes without closing the
    # socket; a short timeout turns that into a spurious failure, so it is long.
    return urllib.request.urlopen(urllib.request.Request(url, headers=headers), timeout=300)


def modrinth_fallback(name: str):
    """Resolve the same artefact file name on the Modrinth CDN, or None.

    The canonical source is maven.latvian.dev; when it keeps dropping the
    transfer, the identical release file is fetched from the Modrinth mirror so
    the measurement can still be made.  The file name is matched exactly, and
    the result is validated as a zip before it is cached.
    """
    request = urllib.request.Request(
        "https://api.modrinth.com/v2/project/kubejs/version",
        headers={"User-Agent": USER_AGENT, "Accept": "application/json"})
    try:
        with urllib.request.urlopen(request, timeout=60) as response:
            import json as _json
            versions = _json.loads(response.read().decode("utf-8"))
    except Exception as exc:
        print("fetch-kubejs: could not query the Modrinth fallback (" + str(exc) + ")")
        return None
    for version in versions:
        for entry in version.get("files", []):
            if entry.get("filename") == name:
                return entry.get("url")
    return None


def download(dest: Path, urls, attempts: int = 4) -> bool:
    """Stream one jar into dest, resuming across dropped connections.

    maven.latvian.dev drops long transfers, so a single read() is not enough:
    every attempt resumes from whatever is already in the *.part file with a
    Range request, and the finished file is only swapped in once it is a
    CRC-clean zip.  A previous truncated cache is reused as the resume seed.
    """
    dest.parent.mkdir(parents=True, exist_ok=True)
    part = dest.with_name(dest.name + ".part")

    if not part.is_file() and dest.is_file() and dest.stat().st_size > 0:
        part.write_bytes(dest.read_bytes())
        print("fetch-kubejs: reusing the incomplete cache as a resume seed ("
              + str(part.stat().st_size) + " bytes)")

    if isinstance(urls, str):
        urls = [urls]
    for url in urls:
      for attempt in range(1, attempts + 1):
        have = part.stat().st_size if part.is_file() else 0
        try:
            response = _http_get(url, have)
        except urllib.error.HTTPError as exc:
            print("fetch-kubejs: HTTP " + str(exc.code) + " for " + url)
            break
        except Exception as exc:
            print("fetch-kubejs: attempt " + str(attempt) + " could not connect (" + str(exc) + ")")
            continue

        written = have
        total = None
        try:
            status = response.status
            if have > 0 and status != 206:
                print("fetch-kubejs: server ignored Range (HTTP " + str(status) + "); restarting")
                have = 0
                part.unlink()
            length = response.headers.get("Content-Length")
            total = (have + int(length)) if length is not None else None
            print("fetch-kubejs: attempt " + str(attempt) + " from byte " + str(have)
                  + ("" if total is None else " of " + str(total)) + " via " + url)
            # Re-sync after the Range decision above: when the server ignored
            # the Range header the part file was truncated, so the byte counter
            # must restart from 0 as well.
            written = have
            reported = 0
            with part.open("ab" if have else "wb") as handle:
                while True:
                    if total is not None and written >= total:
                        # The proxy in front of maven.latvian.dev keeps the
                        # socket open past Content-Length; read exactly the
                        # declared number of bytes and stop.
                        break
                    want = 65536 if total is None else min(65536, total - written)
                    chunk = response.read(want)
                    if not chunk:
                        break
                    handle.write(chunk)
                    written += len(chunk)
                    if written - reported >= 262144:
                        reported = written
                        handle.flush()
                        print("fetch-kubejs:   " + str(written)
                              + ("" if total is None else "/" + str(total)) + " bytes")
        except Exception as exc:
            print("fetch-kubejs: attempt " + str(attempt) + " dropped after "
                  + str(written) + " bytes (" + str(exc) + ")")
            continue
        finally:
            try:
                response.close()
            except Exception:
                pass

        if total is not None and written < total:
            print("fetch-kubejs: short read (" + str(written) + " of " + str(total) + ")")
            continue
        if not _jar_is_usable(part):
            print("fetch-kubejs: attempt " + str(attempt) + " produced no usable zip")
            if total is not None and part.stat().st_size >= total:
                part.unlink()
            continue

        part.replace(dest)
        print("fetch-kubejs: cached " + dest.name + " (" + str(dest.stat().st_size) + " bytes)")
        return True

    print("FAIL: fetch-kubejs: no source could deliver a complete jar")
    return False


def scan_jar(path: Path):
    """Return (hits, shipped).

    hits is a list of (class entry, discovery name) pairs: every class file whose
    constant pool contains the discovery file name as a UTF-8 string.
    shipped lists the plugin-list files the jar itself carries.
    """
    hits = []
    shipped = []
    with zipfile.ZipFile(path) as archive:
        names = archive.namelist()
        for name in names:
            lowered = name.lower()
            if lowered.endswith(".txt") and "plugin" in lowered:
                shipped.append(name)
        for name in names:
            if not name.endswith(".class"):
                continue
            blob = archive.read(name)
            for needle in CANDIDATES:
                if needle in blob:
                    hits.append((name, needle.decode("utf-8", "replace")))
    return hits, shipped


def report(path: Path) -> int:
    hits, shipped = scan_jar(path)
    print("fetch-kubejs: jar = " + str(path))
    if not hits:
        print("FAIL: fetch-kubejs: no class references any known discovery file name")
        return 1

    by_name = {}
    for entry, needle in hits:
        by_name.setdefault(needle, []).append(entry)

    print("fetch-kubejs: discovery file name <- referencing .class entries")
    for needle in sorted(by_name):
        print("  " + needle)
        for entry in sorted(by_name[needle]):
            print("      " + entry)
    print("fetch-kubejs: plugin-list files shipped inside the jar: "
          + (", ".join(sorted(shipped)) if shipped else "(none)"))
    for needle in sorted(by_name):
        readers = [e for e in by_name[needle] if any(h in e for h in SCANNER_HINTS)]
        print("fetch-kubejs: " + needle + " -> likely scanner: "
              + (", ".join(sorted(readers)) if readers else "(none matched by name)"))
    return 0


# ------------------------------------------------------------------ selftest

_CAFEBABE = bytes([0xCA, 0xFE, 0xBA, 0xBE, 0x00, 0x00, 0x00, 0x34])
_FAKE_PLUGINS = _CAFEBABE + PLUGINS_TXT.encode("ascii") + bytes(4)
_FAKE_SERVICE = _CAFEBABE + SERVICE_PATH.encode("ascii") + bytes(4)
_FAKE_INNOCENT = _CAFEBABE + b"net/minecraft/world/item/Item" + bytes(8)


def selftest() -> int:
    ok = True
    tmp = Path(tempfile.mkdtemp(prefix="structurereset-kubejs-selftest-"))
    try:
        target = tmp / "sample.jar"
        with zipfile.ZipFile(target, "w") as archive:
            archive.writestr("com/example/Plugins.class", _FAKE_PLUGINS)
            archive.writestr("com/example/Service.class", _FAKE_SERVICE)
            archive.writestr("com/example/Item.class", _FAKE_INNOCENT)
            archive.writestr(PLUGINS_TXT, "com.example.Plugins" + chr(10))

        hits, shipped = scan_jar(target)
        found = set(needle for _, needle in hits)
        if found != {PLUGINS_TXT, SERVICE_PATH}:
            print("FAIL: selftest: scanner missed a discovery name (" + repr(sorted(found)) + ")")
            ok = False
        else:
            print("selftest: " + "synthetic jar".ljust(34) + " -> both names found")
        owners = dict((needle, entry) for entry, needle in hits)
        if owners.get(PLUGINS_TXT) != "com/example/Plugins.class":
            print("FAIL: selftest: wrong owner for " + PLUGINS_TXT + " (" + repr(owners) + ")")
            ok = False
        if owners.get(SERVICE_PATH) != "com/example/Service.class":
            print("FAIL: selftest: wrong owner for the service path (" + repr(owners) + ")")
            ok = False
        if shipped != [PLUGINS_TXT]:
            print("FAIL: selftest: shipped plugin list not reported (" + repr(shipped) + ")")
            ok = False

        innocent = tmp / "innocent.jar"
        with zipfile.ZipFile(innocent, "w") as archive:
            archive.writestr("com/example/Item.class", _FAKE_INNOCENT)
        hits, _ = scan_jar(innocent)
        if hits:
            print("FAIL: selftest: an unrelated class was reported (" + repr(hits) + ")")
            ok = False
        else:
            print("selftest: " + "jar without plugins".ljust(34) + " -> no false positive")

        truncated = tmp / "truncated.jar"
        truncated.write_bytes(ZIP_MAGIC + bytes(32))
        if _jar_is_usable(truncated):
            print("FAIL: selftest: a truncated jar was accepted as usable")
            ok = False
        else:
            print("selftest: " + "truncated jar".ljust(34) + " -> rejected as unusable")

        empty = tmp / "empty.jar"
        with zipfile.ZipFile(empty, "w") as archive:
            archive.writestr("kubejs.plugins.txt", "")
        if report(empty) == 0:
            print("FAIL: selftest: a jar without any reference was accepted by report()")
            ok = False
        else:
            print("selftest: " + "jar without references".ljust(34) + " -> report() failed")
    finally:
        shutil.rmtree(tmp, ignore_errors=True)

    print("fetch-kubejs selftest: " + ("OK" if ok else "FAILED"))
    return 0 if ok else 1


def main(argv) -> int:
    parser = argparse.ArgumentParser(description="Cache and inspect the KubeJS jar.")
    parser.add_argument("--scan-only", "--offline", dest="scan_only", action="store_true",
                        help="never download; fail when the cached jar is missing")
    parser.add_argument("--selftest", action="store_true", help="check the scanner itself")
    parser.add_argument("--jar", default=None, help="inspect another jar instead")
    parser.add_argument("--url", default=None, help="download from another url")
    parser.add_argument("--force", action="store_true", help="re-download even when cached")
    args = parser.parse_args(argv)

    if args.selftest:
        return selftest()

    if args.force and args.scan_only:
        print("FAIL: fetch-kubejs: --force and --scan-only are mutually exclusive")
        return 1

    jar = Path(args.jar).resolve() if args.jar else KUBEJS_JAR
    usable = (not args.force) and _jar_is_usable(jar)
    if usable:
        print("fetch-kubejs: cached jar is valid (" + jar.name + ")")
    else:
        if args.scan_only:
            print("FAIL: fetch-kubejs: " + str(jar) + " is missing or unusable, --scan-only given")
            return 1
        # Only now is a mirror worth resolving: a cached jar costs no network.
        if args.url:
            urls = [args.url]
        elif jar == KUBEJS_JAR:
            urls = [KUBEJS_URL]
            fallback = modrinth_fallback(KUBEJS_NAME)
            if fallback:
                urls.append(fallback)
                print("fetch-kubejs: mirror fallback available at " + fallback)
        else:
            urls = [jar.as_uri()]
        if not download(jar, urls):
            return 1

    return report(jar)


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
