#!/usr/bin/env python3
"""Conformance check for the Structure Reset project skeleton (task T1).

The other offline tools check *behaviour* (Java syntax, resources, tests,
archives).  This one checks the *project skeleton* that has to exist before any
of them can mean anything: the ForgeGradle files, the Forge metadata, the main
class and the wrapper, each pinned to the exact versions this workspace targets.

It never runs Gradle (this machine cannot reach maven.minecraftforge.net) and it
never writes into the project tree: it only reads files and reports, so it is a
pure evidence producer.

Usage:
    python tools/validate_skeleton.py            # exit 0 when the skeleton is complete
    python tools/validate_skeleton.py --selftest # prove every rule rejects a defect
"""

from __future__ import annotations

import argparse
import contextlib
import io
import json
import re
import shutil
import sys
import tempfile
from pathlib import Path

try:
    import tomllib
except ModuleNotFoundError:  # pragma: no cover - Python < 3.11
    tomllib = None

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    sys.stderr.reconfigure(encoding="utf-8", errors="replace")
except Exception:  # pragma: no cover
    pass

SCRIPT = Path(__file__).resolve()
WORKSPACE = SCRIPT.parent.parent

MOD_ID = "structurereset"
MINECRAFT_VERSION = "1.20.1"
FORGE_VERSION = "47.3.22"
KUBEJS_VERSION = "2001.6.5-build.26"
MOD_VERSION = "0.1.0-alpha"

REQUIRED_FILES = (
    "settings.gradle",
    "build.gradle",
    "gradle.properties",
    "gradlew",
    "gradlew.bat",
    "gradle/wrapper/gradle-wrapper.properties",
    "gradle/wrapper/gradle-wrapper.jar",
    ".gitignore",
    "README.md",
    "docs/BUILD.md",
    "src/main/resources/META-INF/mods.toml",
    "src/main/resources/pack.mcmeta",
    "src/main/resources/assets/" + MOD_ID + "/lang/en_us.json",
    "src/main/resources/assets/" + MOD_ID + "/lang/zh_cn.json",
    "src/main/java/com/structurereset/StructureResetMod.java",
)

# gradle.properties -> the exact value this workspace must declare.
PROPERTIES = {
    "mod_id": MOD_ID,
    "mod_name": "Universal-Boss-Structure-Reset Mod",
    "mod_version": MOD_VERSION,
    "mod_group_id": "com.structurereset",
    "minecraft_version": MINECRAFT_VERSION,
    "forge_version": FORGE_VERSION,
    "kubejs_version": KUBEJS_VERSION,
}

# build.gradle -> plain substrings that must survive (no Groovy parser needed).
BUILD_GRADLE_SNIPPETS = (
    ("forgelgade plugin range", "id 'net.minecraftforge.gradle' version '[6.0,6.2)'"),
    ("java release 17", "JavaLanguageVersion.of(17)"),
    ("official mappings", "mappings channel: 'official', version: '1.20.1'"),
    ("forge dependency coordinate", "net.minecraftforge:forge:"),
    ("forge version placeholders", "${minecraft_version}-${forge_version}"),
    ("kubejs compileOnly", 'compileOnly "dev.latvian.mods:kubejs-forge:'),
    ("kubejs version placeholder", "${kubejs_version}"),
    ("junit jupiter test dependency", "junit-jupiter"),
    ("reobfuscation", "jar.finalizedBy('reobfJar')"),
)

SETTINGS_GRADLE_SNIPPETS = (
    ("pluginManagement block", "pluginManagement"),
    ("forge plugin repository", "maven.minecraftforge.net"),
    ("root project name", "rootProject.name"),
)


def _read_text(path: Path) -> str:
    return path.read_text(encoding="utf-8", errors="replace")


class Checker:
    """Read-only rule runner; collects failures instead of raising."""

    def __init__(self, root: Path):
        self.root = Path(root)
        self.failures = []
        self.passed = 0

    # ------------------------------------------------------------- plumbing
    def fail(self, rule: str, detail: str) -> None:
        self.failures.append("%s: %s" % (rule, detail))
        print("FAIL: %s: %s" % (rule, detail))

    def pass_rule(self, rule: str) -> None:
        self.passed += 1
        print("ok: %s" % rule)

    def text(self, relative: str):
        path = self.root / relative
        return _read_text(path) if path.is_file() else None

    def guard(self, rule: str, func, default=None):
        """A rule must never escape as a stack trace; a crash is a failure."""
        try:
            return func()
        except Exception as exc:  # noqa: BLE001
            self.fail(rule, "unexpected error while checking (%s: %s)"
                      % (type(exc).__name__, exc))
            return default

    # --------------------------------------------------------------- rules
    def rule_files(self) -> None:
        rule = "files"
        missing = [name for name in REQUIRED_FILES if not (self.root / name).is_file()]
        if missing:
            self.fail(rule, "missing file(s): " + ", ".join(sorted(missing)))
            return
        head = (self.root / "gradle/wrapper/gradle-wrapper.jar").read_bytes()[:4]
        if head != b"PK\x03\x04":
            self.fail(rule, "gradle/wrapper/gradle-wrapper.jar is not a zip archive")
            return
        self.pass_rule(rule)

    def rule_gradle_properties(self) -> None:
        rule = "gradle-properties"
        text = self.text("gradle.properties")
        if text is None:
            self.fail(rule, "gradle.properties is missing")
            return
        values = {}
        for line in text.splitlines():
            line = line.strip()
            if not line or line.startswith("#") or "=" not in line:
                continue
            key, _, value = line.partition("=")
            values[key.strip()] = value.strip()
        bad = ["%s=%r (expected %r)" % (key, values.get(key), expected)
               for key, expected in PROPERTIES.items() if values.get(key) != expected]
        if bad:
            self.fail(rule, "wrong value(s): " + "; ".join(bad))
            return
        self.pass_rule(rule)

    def rule_build_gradle(self) -> None:
        rule = "build-gradle"
        text = self.text("build.gradle")
        if text is None:
            self.fail(rule, "build.gradle is missing")
            return
        compact = re.sub(r"\s+", " ", text)
        missing = [label for label, snippet in BUILD_GRADLE_SNIPPETS
                   if re.sub(r"\s+", " ", snippet) not in compact]
        if missing:
            self.fail(rule, "build.gradle lacks: " + ", ".join(missing))
            return
        self.pass_rule(rule)

    def rule_settings_gradle(self) -> None:
        rule = "settings-gradle"
        text = self.text("settings.gradle")
        if text is None:
            self.fail(rule, "settings.gradle is missing")
            return
        missing = [label for label, snippet in SETTINGS_GRADLE_SNIPPETS if snippet not in text]
        if missing:
            self.fail(rule, "settings.gradle lacks: " + ", ".join(missing))
            return
        self.pass_rule(rule)

    def rule_wrapper(self) -> None:
        rule = "wrapper"
        text = self.text("gradle/wrapper/gradle-wrapper.properties")
        if text is None:
            self.fail(rule, "gradle/wrapper/gradle-wrapper.properties is missing")
            return
        if not re.search(r"gradle-\d+\.\d+(?:\.\d+)?-bin\.zip", text):
            self.fail(rule, "no gradle-<version>-bin.zip distributionUrl")
            return
        for script in ("gradlew", "gradlew.bat"):
            body = self.text(script)
            if body is None or "gradle" not in body.lower():
                self.fail(rule, "%s does not look like the Gradle wrapper script" % script)
                return
        self.pass_rule(rule)

    def rule_mods_toml(self) -> dict:
        rule = "mods-toml"
        text = self.text("src/main/resources/META-INF/mods.toml")
        if text is None:
            self.fail(rule, "META-INF/mods.toml is missing")
            return {}
        if tomllib is None:  # pragma: no cover - Python < 3.11
            self.fail(rule, "tomllib is unavailable (Python 3.11+ required)")
            return {}
        try:
            data = tomllib.loads(text)
        except Exception as exc:
            self.fail(rule, "mods.toml is not valid TOML (%s)" % exc)
            return {}
        if data.get("modLoader") != "javafml":
            self.fail(rule, 'modLoader must be "javafml", got %r' % data.get("modLoader"))
            return {}
        if not data.get("loaderVersion"):
            self.fail(rule, "loaderVersion is missing")
            return {}
        if not data.get("license"):
            self.fail(rule, "license is missing")
            return {}
        mods = data.get("mods")
        if not isinstance(mods, list) or len(mods) != 1:
            self.fail(rule, "exactly one [[mods]] block is required")
            return {}
        block = mods[0]
        for key in ("modId", "version", "displayName", "description"):
            if not block.get(key):
                self.fail(rule, "[[mods]] is missing '%s'" % key)
                return {}
        if block["modId"] != MOD_ID:
            self.fail(rule, "[[mods]] modId is %r, expected %r" % (block["modId"], MOD_ID))
            return {}
        dependencies = (data.get("dependencies") or {}).get(MOD_ID)
        if not isinstance(dependencies, list):
            self.fail(rule, "no [[dependencies.%s]] blocks" % MOD_ID)
            return {}
        by_modid = {entry.get("modId"): entry for entry in dependencies if isinstance(entry, dict)}
        for required in ("forge", "minecraft"):
            entry = by_modid.get(required)
            if entry is None:
                self.fail(rule, "no [[dependencies.%s]] block for '%s'" % (MOD_ID, required))
                return {}
            if entry.get("mandatory") is not True:
                self.fail(rule, "the '%s' dependency must be mandatory=true" % required)
                return {}
        self.pass_rule(rule)
        return data

    def rule_pack_mcmeta(self) -> None:
        rule = "pack-mcmeta"
        text = self.text("src/main/resources/pack.mcmeta")
        if text is None:
            self.fail(rule, "src/main/resources/pack.mcmeta is missing")
            return
        try:
            data = json.loads(text)
        except Exception as exc:
            self.fail(rule, "pack.mcmeta is not valid JSON (%s)" % exc)
            return
        pack_format = (data.get("pack") or {}).get("pack_format")
        if pack_format != 15:
            self.fail(rule, "pack_format must be 15 for 1.20.1, got %r" % pack_format)
            return
        self.pass_rule(rule)

    def rule_lang(self) -> None:
        rule = "lang"
        keys = {}
        for locale in ("en_us", "zh_cn"):
            relative = "src/main/resources/assets/%s/lang/%s.json" % (MOD_ID, locale)
            text = self.text(relative)
            if text is None:
                self.fail(rule, "%s is missing" % relative)
                return
            try:
                data = json.loads(text)
            except Exception as exc:
                self.fail(rule, "%s is not valid JSON (%s)" % (relative, exc))
                return
            if not isinstance(data, dict) or not data:
                self.fail(rule, "%s must be a non-empty JSON object" % relative)
                return
            keys[locale] = set(data)
        only_en = sorted(keys["en_us"] - keys["zh_cn"])
        only_zh = sorted(keys["zh_cn"] - keys["en_us"])
        if only_en or only_zh:
            self.fail(rule, "key sets differ: only en_us=%s, only zh_cn=%s" % (only_en, only_zh))
            return
        stray = sorted(key for key in keys["en_us"] if MOD_ID not in key)
        if stray:
            self.fail(rule, "lang key(s) not namespaced to %s: %s" % (MOD_ID, stray))
            return
        self.pass_rule(rule)

    def rule_main_class(self) -> None:
        rule = "main-class"
        text = self.text("src/main/java/com/structurereset/StructureResetMod.java")
        if text is None:
            self.fail(rule, "StructureResetMod.java is missing")
            return
        if not re.search(r"(?m)^package\s+com\.structurereset\s*;", text):
            self.fail(rule, "package com.structurereset; is missing")
            return
        if not re.search(r'MODID\s*=\s*"%s"' % MOD_ID, text):
            self.fail(rule, 'MODID constant is not "%s"' % MOD_ID)
            return
        if not re.search(r'@Mod\s*\(\s*(?:StructureResetMod\.MODID|"%s")\s*\)' % MOD_ID, text):
            self.fail(rule, "@Mod(...) is missing or does not reference the mod id")
            return
        self.pass_rule(rule)

    def rule_docs(self) -> None:
        rule = "docs"
        build_doc = self.text("docs/BUILD.md")
        if build_doc is None:
            self.fail(rule, "docs/BUILD.md is missing")
            return
        lowered = build_doc.lower()
        if "gradlew" not in lowered:
            self.fail(rule, "docs/BUILD.md does not document the gradlew build")
            return
        # The rule used to demand a statement that no Gradle build had ever run in
        # this workspace. Builds work now, so it asks for what a reader actually
        # needs instead: the toolchain the build requires and where the jar lands.
        if "jdk 17" not in lowered and "java 17" not in lowered:
            self.fail(rule, "docs/BUILD.md does not state the JDK 17 requirement")
            return
        if "build/libs" not in build_doc:
            self.fail(rule, "docs/BUILD.md does not name the built jar under build/libs")
            return
        if self.text("README.md") is None:
            self.fail(rule, "README.md is missing")
            return
        self.pass_rule(rule)

    def rule_gitignore(self) -> None:
        rule = "gitignore"
        text = self.text(".gitignore")
        if text is None:
            self.fail(rule, ".gitignore is missing")
            return
        entries = {line.strip() for line in text.splitlines() if line.strip()}
        for needed in (".gradle/", "build/"):
            if needed not in entries:
                self.fail(rule, ".gitignore does not ignore %s" % needed)
                return
        self.pass_rule(rule)

    def rule_modid_consistency(self, mods_toml: dict) -> None:
        rule = "modid-consistency"
        properties = self.text("gradle.properties") or ""
        match = re.search(r"(?m)^\s*mod_id\s*=\s*(.+?)\s*$", properties)
        if match is None:
            self.fail(rule, "cannot read mod_id from gradle.properties")
            return
        if match.group(1) != MOD_ID:
            self.fail(rule, "gradle.properties mod_id=%r" % match.group(1))
            return
        blocks = mods_toml.get("mods") or []
        toml_modid = blocks[0].get("modId") if blocks else None
        if toml_modid != MOD_ID:
            self.fail(rule, "mods.toml modId=%r" % toml_modid)
            return
        source = self.text("src/main/java/com/structurereset/StructureResetMod.java") or ""
        java_match = re.search(r'MODID\s*=\s*"([^"]+)"', source)
        java_modid = java_match.group(1) if java_match else None
        if java_modid != MOD_ID:
            self.fail(rule, "StructureResetMod.MODID=%r" % java_modid)
            return
        assets = self.root / "src/main/resources/assets"
        names = sorted(p.name for p in assets.iterdir()) if assets.is_dir() else []
        if names != [MOD_ID]:
            self.fail(rule, "assets/ namespaces are %s, expected ['%s']" % (names, MOD_ID))
            return
        self.pass_rule(rule)

    # ---------------------------------------------------------------- drive
    def run(self) -> int:
        if not self.root.is_dir():
            print("FAIL: workspace: %s is not a directory" % self.root)
            return 1
        self.guard("files", self.rule_files)
        self.guard("gradle-properties", self.rule_gradle_properties)
        self.guard("build-gradle", self.rule_build_gradle)
        self.guard("settings-gradle", self.rule_settings_gradle)
        self.guard("wrapper", self.rule_wrapper)
        mods_toml = self.guard("mods-toml", self.rule_mods_toml, {}) or {}
        self.guard("pack-mcmeta", self.rule_pack_mcmeta)
        self.guard("lang", self.rule_lang)
        self.guard("main-class", self.rule_main_class)
        self.guard("docs", self.rule_docs)
        self.guard("gitignore", self.rule_gitignore)
        self.guard("modid-consistency", lambda: self.rule_modid_consistency(mods_toml))
        if self.failures:
            print("FAILED: skeleton (%d failure(s), %d rule(s) passed)"
                  % (len(self.failures), self.passed))
            return 1
        print("OK: skeleton (%d rule(s) passed; %s / Forge %s %s / KubeJS %s)"
              % (self.passed, MOD_ID, MINECRAFT_VERSION, FORGE_VERSION, KUBEJS_VERSION))
        return 0


# ------------------------------------------------------------------ selftest

def _copy_skeleton(source: Path, target: Path) -> None:
    for relative in REQUIRED_FILES:
        src = source / relative
        if not src.is_file():
            continue
        dst = target / relative
        dst.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(src, dst)


def _edit(path: Path, old: str, new: str, count: int = 1) -> None:
    text = _read_text(path)
    if old not in text:
        raise AssertionError("defect anchor missing in %s: %r" % (path.name, old))
    path.write_text(text.replace(old, new, count), encoding="utf-8")


def _drop_lines_containing(path: Path, needle: str) -> None:
    text = _read_text(path)
    lines = text.splitlines(keepends=True)
    kept = [line for line in lines if needle not in line]
    if len(kept) == len(lines):
        raise AssertionError("defect anchor missing in %s: %r" % (path.name, needle))
    path.write_text("".join(kept), encoding="utf-8")


def _defects():
    toml = "src/main/resources/META-INF/mods.toml"

    def missing_file(root: Path) -> None:
        (root / "gradle.properties").unlink()

    def wrong_mod_id(root: Path) -> None:
        _edit(root / "gradle.properties", "mod_id=" + MOD_ID, "mod_id=wrongmod")

    def no_reobf(root: Path) -> None:
        _edit(root / "build.gradle", "jar.finalizedBy('reobfJar')", "// no reobf")

    def no_official_mappings(root: Path) -> None:
        _edit(root / "build.gradle", "channel: 'official'", "channel: 'parchment'")

    def no_kubejs(root: Path) -> None:
        _edit(root / "build.gradle", 'compileOnly "dev.latvian.mods:kubejs-forge:',
              "// kubejs bridge removed: ")

    def no_wrapper_distribution(root: Path) -> None:
        _edit(root / "gradle/wrapper/gradle-wrapper.properties",
              "gradle-8.8-bin.zip", "gradle.zip")

    def no_plugin_maven(root: Path) -> None:
        _edit(root / "settings.gradle", "maven.minecraftforge.net", "example.invalid")

    def corrupt_wrapper_jar(root: Path) -> None:
        (root / "gradle/wrapper/gradle-wrapper.jar").write_bytes(b"not a zip")

    def no_forge_dependency(root: Path) -> None:
        path = root / toml
        text = _read_text(path)
        head, _, _ = text.partition("[[dependencies.%s]]" % MOD_ID)
        path.write_text(head + '[[dependencies.%s]]' % MOD_ID
                        + '\nmodId="minecraft"\nmandatory=true\n', encoding="utf-8")

    def optional_forge_dependency(root: Path) -> None:
        path = root / toml
        text = _read_text(path)
        new, count = re.subn(r'(modId\s*=\s*"forge".*?mandatory\s*=\s*)true',
                             r"\1false", text, count=1, flags=re.DOTALL)
        if count == 0:
            raise AssertionError("defect anchor missing in mods.toml (mandatory=true)")
        path.write_text(new, encoding="utf-8")

    def missing_license(root: Path) -> None:
        _drop_lines_containing(root / toml, "license=")

    def pack_format_14(root: Path) -> None:
        _edit(root / "src/main/resources/pack.mcmeta", "15", "14")

    def lang_key_mismatch(root: Path) -> None:
        path = root / ("src/main/resources/assets/%s/lang/en_us.json" % MOD_ID)
        data = json.loads(_read_text(path))
        data["item.%s.extra" % MOD_ID] = "Extra"
        path.write_text(json.dumps(data, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")

    def modid_mismatch(root: Path) -> None:
        _edit(root / "src/main/java/com/structurereset/StructureResetMod.java",
              'MODID = "%s"' % MOD_ID, 'MODID = "otherid"')

    def no_gradle_doc_note(root: Path) -> None:
        path = root / "docs/BUILD.md"
        text = _read_text(path)
        for phrase in ("JDK 17", "java 17", "build/libs"):
            text = text.replace(phrase, "")
        path.write_text(text, encoding="utf-8")

    def empty_gitignore(root: Path) -> None:
        (root / ".gitignore").write_text("# nothing\n", encoding="utf-8")

    def bad_main_package(root: Path) -> None:
        _edit(root / "src/main/java/com/structurereset/StructureResetMod.java",
              "package com.structurereset;", "package com.somethingelse;")

    def no_test_dependency(root: Path) -> None:
        _edit(root / "build.gradle", "junit-jupiter", "junit-quadrant")

    return (
        ("missing-file", missing_file),
        ("wrong-mod-id", wrong_mod_id),
        ("no-reobf", no_reobf),
        ("no-official-mappings", no_official_mappings),
        ("no-kubejs", no_kubejs),
        ("no-wrapper-distribution", no_wrapper_distribution),
        ("no-plugin-maven", no_plugin_maven),
        ("corrupt-wrapper-jar", corrupt_wrapper_jar),
        ("no-forge-dependency", no_forge_dependency),
        ("optional-forge-dependency", optional_forge_dependency),
        ("missing-license", missing_license),
        ("pack-format-14", pack_format_14),
        ("lang-key-mismatch", lang_key_mismatch),
        ("modid-mismatch", modid_mismatch),
        ("bad-main-package", bad_main_package),
        ("no-test-dependency", no_test_dependency),
        ("no-gradle-doc-note", no_gradle_doc_note),
        ("empty-gitignore", empty_gitignore),
    )


def _quiet_exit_code(root: Path) -> int:
    buffer = io.StringIO()
    with contextlib.redirect_stdout(buffer):
        return Checker(root).run()


def selftest() -> int:
    if tomllib is None:  # pragma: no cover - Python < 3.11
        print("FAIL: selftest: tomllib is unavailable (Python 3.11+ required)")
        return 1
    tmp = Path(tempfile.mkdtemp(prefix="structurereset-skeleton-selftest-"))
    ok = True
    try:
        good = tmp / "good"
        _copy_skeleton(WORKSPACE, good)
        code = _quiet_exit_code(good)
        if code != 0:
            print("FAIL: selftest: the real skeleton was rejected (exit %d)" % code)
            ok = False
        else:
            print("selftest: %-26s -> exit 0 (accepted)" % "real-skeleton")

        for name, defect in _defects():
            target = tmp / ("bad-" + name)
            _copy_skeleton(WORKSPACE, target)
            try:
                defect(target)
            except AssertionError as exc:
                print("FAIL: selftest: defect %s could not be injected (%s)" % (name, exc))
                ok = False
                continue
            code = _quiet_exit_code(target)
            if code == 0:
                print("FAIL: selftest: defect '%s' was accepted (exit 0)" % name)
                ok = False
            else:
                print("selftest: %-26s -> exit %d (rejected)" % (name, code))

        empty = tmp / "empty-root"
        empty.mkdir(parents=True, exist_ok=True)
        code = _quiet_exit_code(empty)
        if code == 0:
            print("FAIL: selftest: an empty root was accepted")
            ok = False
        else:
            print("selftest: %-26s -> exit %d (rejected)" % ("empty-root", code))
    finally:
        shutil.rmtree(tmp, ignore_errors=True)

    print("validate-skeleton selftest: " + ("OK" if ok else "FAILED"))
    return 0 if ok else 1


def main(argv) -> int:
    parser = argparse.ArgumentParser(description="Check the project skeleton (task T1).")
    parser.add_argument("--selftest", action="store_true", help="check the checker itself")
    parser.add_argument("--root", default=None,
                        help="Project root (defaults to this script's parent)")
    args = parser.parse_args(argv)

    if args.selftest:
        return selftest()

    root = Path(args.root).resolve() if args.root else WORKSPACE
    return Checker(root).run()


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
