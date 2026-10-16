#!/usr/bin/env python3
"""Offline validator suite for Universal-Boss-Structure-Reset.

One file, one section per check.  The sections were merged mechanically from the
per-check scripts and the privacy gate that used to sit beside them: every rule
keeps the code it had, only each section's top level names are prefixed so that
the former modules can share one namespace.

Usage:
    python tools/validate_checks.py                     # run every section
    python tools/validate_checks.py --list              # sections and their scope
    python tools/validate_checks.py privacy --staged    # the commit gate, one section
    python tools/validate_checks.py docs                # run one section
    python tools/validate_checks.py docs --selftest     # that section rejects injected defects
    python tools/validate_checks.py --selftest          # every section's selftest

python tools/check_all.py is the project entry point; this module is the
implementation it drives and can still be used on its own, as above.
"""

from __future__ import annotations

import sys

# Shared facts for the two KubeJS sections below: the discovery file names a
# mod jar may carry and the compile-time version this workspace pins.  They are
# declared once, at import time, because the 'kubejs' section builds a dict from
# them while the module is still being read, and both sections must agree.
KUBEJS_PLUGINS_TXT = "kubejs.plugins.txt"
KUBEJS_SERVICE_PATH = "META-INF/services/dev.latvian.mods.kubejs.KubeJSPlugin"
KUBEJS_VERSION = "2001.6.5-build.26"
KUBEJS_CANDIDATES = (KUBEJS_PLUGINS_TXT.encode("ascii"),
                    KUBEJS_SERVICE_PATH.encode("ascii"))

# ==========================================================================
# section 'skeleton' -- 工程骨架、版本号与元数据一致性
# ==========================================================================

"""Conformance check for the structure of the Structure Reset workspace.

The other offline tools check *behaviour* (Java syntax, resources, tests,
archives).  This one checks the *project skeleton* that has to exist before any
of them can mean anything: the ForgeGradle files, the Forge metadata, the main
class and the wrapper, each pinned to the exact versions this workspace targets.

It never runs Gradle (this machine cannot reach maven.minecraftforge.net) and it
never writes into the project tree: it only reads files and reports, so it is a
pure evidence producer.

Usage:
    python tools/validate_checks.py skeleton            # exit 0 when the skeleton is complete
    python tools/validate_checks.py skeleton --selftest # prove every rule rejects a defect
"""


import argparse
import contextlib
import io
import json
import re
import shutil
import sys
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

skel_SCRIPT = Path(__file__).resolve()
skel_WORKSPACE = skel_SCRIPT.parent.parent

sys.path.insert(0, str(skel_SCRIPT.parent))
import toolchain  # noqa: E402  (sibling module: shared scratch directories)

skel_MOD_ID = "structurereset"
skel_MINECRAFT_VERSION = "1.20.1"
skel_FORGE_VERSION = "47.3.22"
skel_KUBEJS_VERSION = "2001.6.5-build.26"
# The version this workspace is on. The newest CHANGELOG release heading is the
# single source of truth, so a version bump only has to touch gradle.properties and
# CHANGELOG.md; the literal below is only the fallback for a missing heading.
skel_MOD_VERSION_FALLBACK = "0.1.0-alpha"


def skel_newest_changelog_version(workspace: Path):
    """The newest '## <version> - <title>' heading of CHANGELOG.md, or None."""
    try:
        text = (Path(workspace) / "CHANGELOG.md").read_text(encoding="utf-8", errors="replace")
    except OSError:
        return None
    match = re.search(r"^##\s+(\d+\.\d+\.\d+[^\s\u2014]*)\s+\u2014", text, re.M)
    return match.group(1) if match else None


skel_MOD_VERSION = skel_newest_changelog_version(skel_WORKSPACE) or skel_MOD_VERSION_FALLBACK

skel_REQUIRED_FILES = (
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
    "src/main/resources/assets/" + skel_MOD_ID + "/lang/en_us.json",
    "src/main/resources/assets/" + skel_MOD_ID + "/lang/zh_cn.json",
    "src/main/java/com/structurereset/StructureResetMod.java",
)

# gradle.properties -> the exact value this workspace must declare.
skel_PROPERTIES = {
    "mod_id": skel_MOD_ID,
    "mod_name": "Universal-Boss-Structure-Reset Mod",
    "mod_version": skel_MOD_VERSION,
    "mod_group_id": "com.structurereset",
    "minecraft_version": skel_MINECRAFT_VERSION,
    "forge_version": skel_FORGE_VERSION,
    "kubejs_version": skel_KUBEJS_VERSION,
}

# build.gradle -> plain substrings that must survive (no Groovy parser needed).
skel_BUILD_GRADLE_SNIPPETS = (
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

skel_SETTINGS_GRADLE_SNIPPETS = (
    ("pluginManagement block", "pluginManagement"),
    ("forge plugin repository", "maven.minecraftforge.net"),
    ("root project name", "rootProject.name"),
)


def skel__read_text(path: Path) -> str:
    return path.read_text(encoding="utf-8", errors="replace")


class skel_Checker:
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
        return skel__read_text(path) if path.is_file() else None

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
        missing = [name for name in skel_REQUIRED_FILES if not (self.root / name).is_file()]
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
               for key, expected in skel_PROPERTIES.items() if values.get(key) != expected]
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
        missing = [label for label, snippet in skel_BUILD_GRADLE_SNIPPETS
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
        missing = [label for label, snippet in skel_SETTINGS_GRADLE_SNIPPETS if snippet not in text]
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
        if block["modId"] != skel_MOD_ID:
            self.fail(rule, "[[mods]] modId is %r, expected %r" % (block["modId"], skel_MOD_ID))
            return {}
        dependencies = (data.get("dependencies") or {}).get(skel_MOD_ID)
        if not isinstance(dependencies, list):
            self.fail(rule, "no [[dependencies.%s]] blocks" % skel_MOD_ID)
            return {}
        by_modid = {entry.get("modId"): entry for entry in dependencies if isinstance(entry, dict)}
        for required in ("forge", "minecraft"):
            entry = by_modid.get(required)
            if entry is None:
                self.fail(rule, "no [[dependencies.%s]] block for '%s'" % (skel_MOD_ID, required))
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
            relative = "src/main/resources/assets/%s/lang/%s.json" % (skel_MOD_ID, locale)
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
        stray = sorted(key for key in keys["en_us"] if skel_MOD_ID not in key)
        if stray:
            self.fail(rule, "lang key(s) not namespaced to %s: %s" % (skel_MOD_ID, stray))
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
        if not re.search(r'MODID\s*=\s*"%s"' % skel_MOD_ID, text):
            self.fail(rule, 'MODID constant is not "%s"' % skel_MOD_ID)
            return
        if not re.search(r'@Mod\s*\(\s*(?:StructureResetMod\.MODID|"%s")\s*\)' % skel_MOD_ID, text):
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
        if match.group(1) != skel_MOD_ID:
            self.fail(rule, "gradle.properties mod_id=%r" % match.group(1))
            return
        blocks = mods_toml.get("mods") or []
        toml_modid = blocks[0].get("modId") if blocks else None
        if toml_modid != skel_MOD_ID:
            self.fail(rule, "mods.toml modId=%r" % toml_modid)
            return
        source = self.text("src/main/java/com/structurereset/StructureResetMod.java") or ""
        java_match = re.search(r'MODID\s*=\s*"([^"]+)"', source)
        java_modid = java_match.group(1) if java_match else None
        if java_modid != skel_MOD_ID:
            self.fail(rule, "StructureResetMod.MODID=%r" % java_modid)
            return
        assets = self.root / "src/main/resources/assets"
        names = sorted(p.name for p in assets.iterdir()) if assets.is_dir() else []
        if names != [skel_MOD_ID]:
            self.fail(rule, "assets/ namespaces are %s, expected ['%s']" % (names, skel_MOD_ID))
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
              % (self.passed, skel_MOD_ID, skel_MINECRAFT_VERSION, skel_FORGE_VERSION, skel_KUBEJS_VERSION))
        return 0


# ------------------------------------------------------------------ selftest

def skel__copy_skeleton(source: Path, target: Path) -> None:
    for relative in skel_REQUIRED_FILES:
        src = source / relative
        if not src.is_file():
            continue
        dst = target / relative
        dst.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(src, dst)


def skel__edit(path: Path, old: str, new: str, count: int = 1) -> None:
    text = skel__read_text(path)
    if old not in text:
        raise AssertionError("defect anchor missing in %s: %r" % (path.name, old))
    path.write_text(text.replace(old, new, count), encoding="utf-8")


def skel__drop_lines_containing(path: Path, needle: str) -> None:
    text = skel__read_text(path)
    lines = text.splitlines(keepends=True)
    kept = [line for line in lines if needle not in line]
    if len(kept) == len(lines):
        raise AssertionError("defect anchor missing in %s: %r" % (path.name, needle))
    path.write_text("".join(kept), encoding="utf-8")


def skel__defects():
    toml = "src/main/resources/META-INF/mods.toml"

    def missing_file(root: Path) -> None:
        (root / "gradle.properties").unlink()

    def wrong_mod_id(root: Path) -> None:
        skel__edit(root / "gradle.properties", "mod_id=" + skel_MOD_ID, "mod_id=wrongmod")

    def no_reobf(root: Path) -> None:
        skel__edit(root / "build.gradle", "jar.finalizedBy('reobfJar')", "// no reobf")

    def no_official_mappings(root: Path) -> None:
        skel__edit(root / "build.gradle", "channel: 'official'", "channel: 'parchment'")

    def no_kubejs(root: Path) -> None:
        skel__edit(root / "build.gradle", 'compileOnly "dev.latvian.mods:kubejs-forge:',
              "// kubejs bridge removed: ")

    def no_wrapper_distribution(root: Path) -> None:
        skel__edit(root / "gradle/wrapper/gradle-wrapper.properties",
              "gradle-8.8-bin.zip", "gradle.zip")

    def no_plugin_maven(root: Path) -> None:
        skel__edit(root / "settings.gradle", "maven.minecraftforge.net", "example.invalid")

    def corrupt_wrapper_jar(root: Path) -> None:
        (root / "gradle/wrapper/gradle-wrapper.jar").write_bytes(b"not a zip")

    def no_forge_dependency(root: Path) -> None:
        path = root / toml
        text = skel__read_text(path)
        head, _, _ = text.partition("[[dependencies.%s]]" % skel_MOD_ID)
        path.write_text(head + '[[dependencies.%s]]' % skel_MOD_ID
                        + '\nmodId="minecraft"\nmandatory=true\n', encoding="utf-8")

    def optional_forge_dependency(root: Path) -> None:
        path = root / toml
        text = skel__read_text(path)
        new, count = re.subn(r'(modId\s*=\s*"forge".*?mandatory\s*=\s*)true',
                             r"\1false", text, count=1, flags=re.DOTALL)
        if count == 0:
            raise AssertionError("defect anchor missing in mods.toml (mandatory=true)")
        path.write_text(new, encoding="utf-8")

    def missing_license(root: Path) -> None:
        skel__drop_lines_containing(root / toml, "license=")

    def pack_format_14(root: Path) -> None:
        skel__edit(root / "src/main/resources/pack.mcmeta", "15", "14")

    def lang_key_mismatch(root: Path) -> None:
        path = root / ("src/main/resources/assets/%s/lang/en_us.json" % skel_MOD_ID)
        data = json.loads(skel__read_text(path))
        data["item.%s.extra" % skel_MOD_ID] = "Extra"
        path.write_text(json.dumps(data, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")

    def modid_mismatch(root: Path) -> None:
        skel__edit(root / "src/main/java/com/structurereset/StructureResetMod.java",
              'MODID = "%s"' % skel_MOD_ID, 'MODID = "otherid"')

    def no_gradle_doc_note(root: Path) -> None:
        path = root / "docs/BUILD.md"
        text = skel__read_text(path)
        for phrase in ("JDK 17", "java 17", "build/libs"):
            text = text.replace(phrase, "")
        path.write_text(text, encoding="utf-8")

    def empty_gitignore(root: Path) -> None:
        (root / ".gitignore").write_text("# nothing\n", encoding="utf-8")

    def bad_main_package(root: Path) -> None:
        skel__edit(root / "src/main/java/com/structurereset/StructureResetMod.java",
              "package com.structurereset;", "package com.somethingelse;")

    def no_test_dependency(root: Path) -> None:
        skel__edit(root / "build.gradle", "junit-jupiter", "junit-quadrant")

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


def skel__quiet_exit_code(root: Path) -> int:
    buffer = io.StringIO()
    with contextlib.redirect_stdout(buffer):
        return skel_Checker(root).run()


def skel_selftest() -> int:
    if tomllib is None:  # pragma: no cover - Python < 3.11
        print("FAIL: selftest: tomllib is unavailable (Python 3.11+ required)")
        return 1
    tmp = toolchain.make_scratch_dir("structurereset-skeleton-selftest", skel_WORKSPACE)
    ok = True
    try:
        good = tmp / "good"
        skel__copy_skeleton(skel_WORKSPACE, good)
        code = skel__quiet_exit_code(good)
        if code != 0:
            print("FAIL: selftest: the real skeleton was rejected (exit %d)" % code)
            ok = False
        else:
            print("selftest: %-26s -> exit 0 (accepted)" % "real-skeleton")

        for name, defect in skel__defects():
            target = tmp / ("bad-" + name)
            skel__copy_skeleton(skel_WORKSPACE, target)
            try:
                defect(target)
            except AssertionError as exc:
                print("FAIL: selftest: defect %s could not be injected (%s)" % (name, exc))
                ok = False
                continue
            code = skel__quiet_exit_code(target)
            if code == 0:
                print("FAIL: selftest: defect '%s' was accepted (exit 0)" % name)
                ok = False
            else:
                print("selftest: %-26s -> exit %d (rejected)" % (name, code))

        empty = tmp / "empty-root"
        empty.mkdir(parents=True, exist_ok=True)
        code = skel__quiet_exit_code(empty)
        if code == 0:
            print("FAIL: selftest: an empty root was accepted")
            ok = False
        else:
            print("selftest: %-26s -> exit %d (rejected)" % ("empty-root", code))
    finally:
        shutil.rmtree(tmp, ignore_errors=True)

    print("validate-skeleton selftest: " + ("OK" if ok else "FAILED"))
    return 0 if ok else 1


def skel_main(argv) -> int:
    parser = argparse.ArgumentParser(description="Check the structure of the Structure Reset workspace.")
    parser.add_argument("--selftest", action="store_true", help="check the checker itself")
    parser.add_argument("--root", default=None,
                        help="Project root (defaults to this script's parent)")
    args = parser.parse_args(argv)

    if args.selftest:
        return skel_selftest()

    root = Path(args.root).resolve() if args.root else skel_WORKSPACE
    return skel_Checker(root).run()




# ==========================================================================
# section 'pure-logic' -- api 层不依赖 Minecraft / Forge
# ==========================================================================

"""Validate that the com.structurereset.api package stays Minecraft free.

The api layer is the part of this mod that must compile against nothing but the
JDK and gson, which is what lets tools/run_tests.py exercise it offline.  This
checker fails when

* any .java file below src/main/java/com/structurereset/api imports
  net.minecraft.*, net.minecraftforge.* or dev.latvian.* (wildcards included);
* that directory holds fewer than 11 .java files, so an empty or half deleted
  api package can never pass silently.

Usage:
    python tools/validate_checks.py pure-logic     # exit 0 when the api layer is pure

Every path is derived from Path(__file__), nothing is hard coded.
"""


import re
import sys
from pathlib import Path

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    sys.stderr.reconfigure(encoding="utf-8", errors="replace")
except Exception:  # pragma: no cover
    pass

pure_SCRIPT = Path(__file__).resolve()
pure_WORKSPACE = pure_SCRIPT.parent.parent
pure_API_DIR = pure_WORKSPACE / "src" / "main" / "java" / "com" / "structurereset" / "api"

# The api layer must not shrink back to a stub: it ships 11 classes.
pure_MIN_SOURCES = 11

pure_FORBIDDEN_ROOTS = ("net.minecraft", "net.minecraftforge", "dev.latvian")

pure_IMPORT_RE = re.compile(
    r"^\s*import\s+(?:static\s+)?([A-Za-z_$][A-Za-z0-9_$]*(?:\.[A-Za-z0-9_$*]+)*)\s*;")


def pure_forbidden_root(imported: str):
    """The forbidden package root an imported name belongs to, or None."""
    for root in pure_FORBIDDEN_ROOTS:
        if imported == root or imported.startswith(root + "."):
            return root
    return None


def pure_scan(path: Path):
    """Every forbidden import of one file as (line number, imported name, root)."""
    violations = []
    text = path.read_text(encoding="utf-8", errors="replace")
    for number, line in enumerate(text.splitlines(), 1):
        match = pure_IMPORT_RE.match(line)
        if not match:
            continue
        imported = match.group(1)
        root = pure_forbidden_root(imported)
        if root is not None:
            violations.append((number, imported, root))
    return violations


def pure_main() -> int:
    if not pure_API_DIR.is_dir():
        print("FAIL: validate-pure-logic: missing directory %s" % pure_API_DIR)
        return 1

    sources = sorted(pure_API_DIR.rglob("*.java"))
    violations = []
    for source in sources:
        for number, imported, root in pure_scan(source):
            violations.append((source, number, imported, root))

    ok = True
    if violations:
        ok = False
        print("FAIL: validate-pure-logic: Minecraft/Forge/KubeJS imports found:")
        for source, number, imported, root in violations:
            print("  %s:%d: import %s (forbidden root '%s')"
                  % (source, number, imported, root))
    if len(sources) < pure_MIN_SOURCES:
        ok = False
        print("FAIL: validate-pure-logic: found %d api source(s) below %s, expected at least %d"
              % (len(sources), pure_API_DIR, pure_MIN_SOURCES))
    if not ok:
        return 1

    print("OK: validate-pure-logic: %d api source(s), no net.minecraft / "
          "net.minecraftforge / dev.latvian import" % len(sources))
    return 0




# ==========================================================================
# section 'api' -- 引擎、配置与内置动作的实现
# ==========================================================================

"""Structural conformance check for the runtime engine and the configuration.

The Minecraft-facing API surface lives in {@code com.structurereset.core}; the
engine that actually performs a reset is checked here.  This checker asserts
that the engine, its persistent reset clock, the data pack reload listener, the
configuration and the six built in actions all exist and are still wired to the
ids the data packs use.

Rules (rule ids appear in the FAIL lines):
    impl-files      impl/ResetEngine, impl/ResetStateSavedData,
                    impl/DefinitionReloadListener and config/StructureResetConfig exist
    action-classes  impl/action holds at least six action sources, one per built in id
    engine          ResetEngine consults the structure manager and branches on both the
                    global and the per player reset scope
    config          StructureResetConfig builds Forge/Mod config specs and declares the
                    required configuration names
    action-ids      every action source declares the namespaced id its file name implies

Every rule reports how many entries it parsed; a rule that parses nothing is a
failure, so this checker can never pass vacuously.

Usage:
    python tools/validate_checks.py api            # exit 0 when the engine layer is complete
    python tools/validate_checks.py api --selftest # prove every rule rejects a defect
"""


import argparse
import re
import shutil
import sys
from pathlib import Path

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    sys.stderr.reconfigure(encoding="utf-8", errors="replace")
except Exception:  # pragma: no cover
    pass

impl_SCRIPT = Path(__file__).resolve()
impl_WORKSPACE = impl_SCRIPT.parent.parent

sys.path.insert(0, str(impl_SCRIPT.parent))
import toolchain  # noqa: E402  (sibling module: shared scratch directories)

impl_JAVA = Path("src") / "main" / "java" / "com" / "structurereset"
impl_IMPL = impl_JAVA / "impl"
impl_ACTION_DIR = impl_IMPL / "action"
impl_CONFIG = impl_JAVA / "config" / "StructureResetConfig.java"

impl_IMPL_FILES = (
    impl_IMPL / "ResetEngine.java",
    impl_IMPL / "ResetStateSavedData.java",
    impl_IMPL / "DefinitionReloadListener.java",
    impl_CONFIG,
)

# action id -> the source file that must declare it
impl_ACTIONS = {
    "respawn_boss": "RespawnBossAction.java",
    "refill_loot": "RefillLootAction.java",
    "reset_blocks": "ResetBlocksAction.java",
    "clear_entities": "ClearEntitiesAction.java",
    "run_command": "RunCommandAction.java",
    "message": "MessageAction.java",
}
impl_MIN_ACTIONS = 6

# the two scope branches the engine must contain
impl_SCOPE_TOKENS = ("PER_PLAYER", "GLOBAL_SCOPE")

# configuration names the spec asks for; at least MIN_CONFIG_NAMES must be declared
impl_CONFIG_NAMES = (
    "enabled",
    "defaultRadius",
    "defaultCooldownSeconds",
    "defaultMode",
    "consumeItem",
    "structureSearchRadius",
    "notifyPlayer",
    "debugLogging",
    "learningEnabled",
    "learningRadius",
    "learningBossTags",
    "learningRequireOp",
)
impl_MIN_CONFIG_NAMES = 12

impl_SPEC_RE = re.compile(r"(?:Forge|Mod)ConfigSpec")


def impl_declares_id(text, action_id):
    """True when the source declares the action id as a literal.

    The javadoc of the action also spells the id ('{@code structurereset:message}'),
    so only real declarations count: the NAME constant or a ResourceLocation call.
    """
    quoted = '"' + re.escape(action_id) + '"'
    patterns = (
        r"NAME\s*=\s*" + quoted,
        r"ResourceLocation\s*\([^)]*" + quoted,
        r"ID\s*=\s*new\s+ResourceLocation\([^)]*" + quoted,
    )
    return any(re.search(pattern, text) is not None for pattern in patterns)


def impl_snake_case(name):
    """RespawnBoss -> respawn_boss."""
    out = []
    for index, char in enumerate(name):
        if char.isupper() and index > 0:
            out.append("_")
        out.append(char.lower())
    return "".join(out)


def impl_implied_id(filename):
    """RespawnBossAction.java -> respawn_boss, or None."""
    base = filename[:-5] if filename.endswith(".java") else filename
    if not base.endswith("Action"):
        return None
    return impl_snake_case(base[: -len("Action")])


class impl_Checker:
    """Read-only rule runner; collects failures instead of raising."""

    def __init__(self, root):
        self.root = Path(root)
        self.failures = []
        self.counts = {}

    # ------------------------------------------------------------ plumbing

    def fail(self, rule, detail):
        self.failures.append("%s: %s" % (rule, detail))

    def read(self, relative):
        path = self.root / relative
        if not path.is_file():
            return None
        return path.read_text(encoding="utf-8", errors="replace")

    def action_files(self):
        directory = self.root / impl_ACTION_DIR
        return sorted(directory.glob("*.java")) if directory.is_dir() else []

    # --------------------------------------------------------------- rules

    def rule_impl_files(self):
        rule = "impl-files"
        missing = [rel for rel in impl_IMPL_FILES if not (self.root / rel).is_file()]
        self.counts[rule] = len(impl_IMPL_FILES) - len(missing)
        for rel in missing:
            self.fail(rule, "missing " + rel.as_posix())

    def rule_action_classes(self):
        rule = "action-classes"
        files = self.action_files()
        self.counts[rule] = len(files)
        if len(files) < impl_MIN_ACTIONS:
            self.fail(rule, "found %d action source(s) under %s, expected at least %d"
                      % (len(files), impl_ACTION_DIR.as_posix(), impl_MIN_ACTIONS))
        names = {path.name for path in files}
        for action_id, filename in sorted(impl_ACTIONS.items()):
            if filename not in names:
                self.fail(rule, "%s is missing, so '%s' has no implementation"
                          % ((impl_ACTION_DIR / filename).as_posix(), action_id))

    def rule_engine(self):
        rule = "engine"
        text = self.read(impl_IMPL / "ResetEngine.java")
        if text is None:
            self.counts[rule] = 0
            self.fail(rule, (impl_IMPL / "ResetEngine.java").as_posix() + " is missing")
            return
        matched = 0
        if "structureManager" in text:
            matched += 1
        else:
            self.fail(rule, "ResetEngine.java never consults the structure manager")
        for token in impl_SCOPE_TOKENS:
            if re.search(r"\b" + token + r"\b", text) is not None:
                matched += 1
            else:
                self.fail(rule, "ResetEngine.java has no %s scope branch" % token)
        self.counts[rule] = matched

    def rule_config(self):
        rule = "config"
        text = self.read(impl_CONFIG)
        if text is None:
            self.counts[rule] = 0
            self.fail(rule, impl_CONFIG.as_posix() + " is missing")
            return
        if not impl_SPEC_RE.search(text):
            self.fail(rule, "StructureResetConfig.java declares no Forge/Mod config spec")
        names = [name for name in impl_CONFIG_NAMES if re.search(r"\b" + name + r"\b", text)]
        for name in impl_CONFIG_NAMES:
            if name not in names:
                self.fail(rule, "StructureResetConfig.java has no '%s' entry" % name)
        if len(names) < impl_MIN_CONFIG_NAMES:
            self.fail(rule, "only %d of the %d required configuration names are present"
                      % (len(names), len(impl_CONFIG_NAMES)))
        self.counts[rule] = len(names)

    def rule_action_ids(self):
        rule = "action-ids"
        files = {path.name: path for path in self.action_files()}
        matched = 0
        for action_id, filename in sorted(impl_ACTIONS.items()):
            path = files.get(filename)
            if path is None:
                continue
            implied = impl_implied_id(filename)
            if implied != action_id:
                self.fail(rule, "%s implies the action id '%s', not '%s'"
                          % ((impl_ACTION_DIR / filename).as_posix(), implied, action_id))
                continue
            text = path.read_text(encoding="utf-8", errors="replace")
            if impl_declares_id(text, action_id):
                matched += 1
            else:
                self.fail(rule, "%s declares neither \"%s\" nor 'structurereset:%s'"
                          % ((impl_ACTION_DIR / filename).as_posix(), action_id, action_id))
        self.counts[rule] = matched

    # ---------------------------------------------------------------- drive

    def run(self):
        self.rule_impl_files()
        self.rule_action_classes()
        self.rule_engine()
        self.rule_config()
        self.rule_action_ids()
        for rule, count in sorted(self.counts.items()):
            if count == 0:
                self.fail(rule, "parsed 0 entries, which counts as a failure")
        return self.failures

    def summary(self):
        return " ".join("%s=%d" % (rule, self.counts[rule]) for rule in sorted(self.counts))


# ---------------------------------------------------------------- selftest

def impl__check(root):
    return impl_Checker(root).run()


def impl__replace(path, old, new):
    text = path.read_text(encoding="utf-8")
    if old not in text:
        raise AssertionError("defect anchor missing in %s: %r" % (path.name, old))
    path.write_text(text.replace(old, new), encoding="utf-8")


def impl__defects():
    def missing_engine(root):
        (root / impl_IMPL / "ResetEngine.java").unlink()

    def missing_scope_branch(root):
        impl__replace(root / impl_IMPL / "ResetEngine.java", "PER_PLAYER", "PER_PLAYER_SCOPE")

    def missing_config_spec(root):
        path = root / impl_CONFIG
        text = path.read_text(encoding="utf-8")
        text = text.replace("ForgeConfigSpec", "ForgeSpec").replace("ModConfigSpec", "ModSpec")
        path.write_text(text, encoding="utf-8")

    def missing_action_file(root):
        (root / impl_ACTION_DIR / "RunCommandAction.java").unlink()

    def wrong_action_id(root):
        impl__replace(root / impl_ACTION_DIR / "MessageAction.java", 'NAME = "message"', 'NAME = "nope"')

    def missing_saved_data(root):
        (root / impl_IMPL / "ResetStateSavedData.java").unlink()

    return (
        ("missing-engine", missing_engine),
        ("missing-scope-branch", missing_scope_branch),
        ("missing-config-spec", missing_config_spec),
        ("missing-action-file", missing_action_file),
        ("wrong-action-id", wrong_action_id),
        ("missing-saved-data", missing_saved_data),
    )


def impl_selftest():
    tmp = toolchain.make_scratch_dir("structurereset-api-selftest", impl_WORKSPACE)
    ok = True
    try:
        clean = tmp / "clean"
        shutil.copytree(impl_WORKSPACE / "src", clean / "src")
        failures = impl__check(clean)
        if failures:
            ok = False
            print("FAIL: selftest: the real engine layer was rejected (%d failure(s))" % len(failures))
            for failure in failures[:5]:
                print("      %s" % failure)
        else:
            print("selftest: %-24s -> accepted" % "real-engine-layer")

        for name, defect in impl__defects():
            target = tmp / ("bad-" + name)
            shutil.copytree(impl_WORKSPACE / "src", target / "src")
            try:
                defect(target)
            except Exception as exc:  # noqa: BLE001
                ok = False
                print("FAIL: selftest: defect %s could not be injected (%s)" % (name, exc))
                continue
            failures = impl__check(target)
            if not failures:
                ok = False
                print("FAIL: selftest: defect '%s' was accepted (exit 0)" % name)
            else:
                print("selftest: %-24s -> rejected (%s)" % (name, failures[0]))
    finally:
        shutil.rmtree(tmp, ignore_errors=True)

    print("validate-api selftest: " + ("OK" if ok else "FAILED"))
    return 0 if ok else 1


# ------------------------------------------------------------------ main

def impl_main(argv):
    parser = argparse.ArgumentParser(
        description="Check the Structure Reset runtime engine and configuration.")
    parser.add_argument("--selftest", action="store_true", help="check the checker itself")
    parser.add_argument("--root", default=None,
                        help="Project root (defaults to this script's parent)")
    args = parser.parse_args(argv)

    if args.selftest:
        return impl_selftest()

    root = Path(args.root).resolve() if args.root else impl_WORKSPACE
    checker = impl_Checker(root)
    failures = checker.run()
    if failures:
        for failure in failures:
            print("FAIL: validate-api: %s" % failure)
        print("validate-api: %d failure(s) (%s)" % (len(failures), checker.summary()))
        return 1
    print("OK: validate-api: %d impl file(s), %d action source(s), engine tokens=%d, "
          "%d config name(s), %d action id(s) (%s)"
          % (checker.counts["impl-files"], checker.counts["action-classes"],
             checker.counts["engine"], checker.counts["config"],
             checker.counts["action-ids"], checker.summary()))
    return 0




# ==========================================================================
# section 'api-surface' -- core API 表面与两个事件
# ==========================================================================

"""Structural conformance check for the runtime API surface of Structure Reset.

Structure Reset has two layers:

* {@code com.structurereset.api} - the Minecraft-free logic layer, which is
  the only thing the offline JUnit run compiles;
* {@code com.structurereset.core} - the Minecraft-facing public API (this task):
  the reset context, the reset result, the action interface, the static entry
  point and the two Forge events.

This checker asserts that the second layer really exists and that the first one
stayed pure, so a rename, a dropped method or an accidental Minecraft import in
the logic layer cannot slip through unnoticed.

Rules (rule ids appear in the FAIL lines):
    core-files    ResetContext/ResetResult/ResetActionType/StructureResetApi and
                  core/event/ResetPreEvent + core/event/ResetPostEvent exist
    api-purity    com.structurereset.api still holds exactly the pure logic layer
                  and imports nothing from net.minecraft/net.minecraftforge
    api-methods   StructureResetApi declares its 11 public static entry points
    api-events    StructureResetApi constructs and posts both reset events
    examples      at least three example_*.json definitions under
                  examples/datapack/data/structurereset/structure_resets (they
                  are documentation only and are never shipped in the jar), each
                  with structure + actions, at least one of them tag based

Every rule reports how many entries it parsed; a rule that parses nothing is a
failure, so this checker can never pass vacuously.

Usage:
    python tools/validate_checks.py api-surface            # exit 0 when the surface is complete
    python tools/validate_checks.py api-surface --selftest # prove every rule rejects a defect
"""


import argparse
import json
import re
import shutil
import sys
from pathlib import Path

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    sys.stderr.reconfigure(encoding="utf-8", errors="replace")
except Exception:  # pragma: no cover
    pass

surf_SCRIPT = Path(__file__).resolve()
surf_WORKSPACE = surf_SCRIPT.parent.parent

sys.path.insert(0, str(surf_SCRIPT.parent))
import toolchain  # noqa: E402  (sibling module: shared scratch directories)

surf_JAVA = Path("src") / "main" / "java" / "com" / "structurereset"
surf_API = surf_JAVA / "api"
surf_CORE = surf_JAVA / "core"
# The three example definitions are documentation, not shipped data: they live
# in the examples/ datapack skeleton, which is NOT part of src/main/resources and
# therefore never ends up inside the jar.  They used to sit in the shipped
# structure_resets/ directory, where the mod loaded them as real definitions.
surf_EXAMPLES = Path("examples") / "datapack" / "data" / "structurereset" / "structure_resets"
# The tree that really ships inside the jar: an example_*.json here would be
# loaded as a live definition on every resource reload (the bug this rule now
# prevents from coming back).
surf_SHIPPED_RESETS = Path("src") / "main" / "resources" / "data" / "structurereset" / "structure_resets"

surf_CORE_FILES = (
    "ResetContext.java",
    "ResetResult.java",
    "ResetActionType.java",
    "StructureResetApi.java",
    "event/ResetPreEvent.java",
    "event/ResetPostEvent.java",
)

surf_PURE_API_FILES = (
    "StructureRef.java",
    "ResetMode.java",
    "ResetTrigger.java",
    "ResetActionSpec.java",
    "ResetDefinition.java",
    "DefinitionException.java",
    "DefinitionParser.java",
    "DefinitionSelector.java",
    "CooldownPolicy.java",
    "ResetKey.java",
    "Placeholders.java",
    "LearnedContainer.java",
    "LearnedAnchor.java",
    "LearnedAnchorSelector.java",
    "ItemSettings.java",
    "RespawnPoint.java",
    "RespawnBranch.java",
    "RespawnChoice.java",
    "RespawnPlanner.java",
    "SpawnHeights.java",
    "SpawnVerdict.java",
    # The pure parser of the optional Boss Checklist id list: gson only, so it
    # belongs to the same Minecraft-free layer and is covered by the offline
    # JUnit run (src/test/java/.../BossChecklistIdsTest.java).
    "BossChecklistIds.java",
)

surf_API_METHODS = (
    "registerActionType",
    "registerDefinition",
    "removeDefinition",
    "definitions",
    "definition",
    "addPreListener",
    "addPostListener",
    "findDefinitionAt",
    "canReset",
    "triggerReset",
    "registeredActionTypeIds",
)

surf_EVENT_TYPES = ("ResetPreEvent", "ResetPostEvent")

surf_FORBIDDEN_IMPORT = re.compile(
    r"^\s*import\s+(?:static\s+)?(net[.]minecraft|net[.]minecraftforge|dev[.]latvian)")

surf_MIN_EXAMPLES = 3


def surf_declares(text, name):
    """True when a public static method of this name is declared in text."""
    pattern = (r"public\s+static\s+(?:final\s+)?[\w$.[\]<>,? ]+\s+"
               + re.escape(name) + r"\s*\(")
    return re.search(pattern, text) is not None


def surf_is_tagged(value):
    """True when a structure reference (or a list of them) is a '#tag'."""
    if isinstance(value, str):
        return value.strip().startswith("#")
    if isinstance(value, list):
        return any(surf_is_tagged(entry) for entry in value)
    return False


class surf_Checker:
    """Read-only rule runner; collects failures instead of raising."""

    def __init__(self, root):
        self.root = Path(root)
        self.failures = []
        self.counts = {}

    # ------------------------------------------------------------ plumbing

    def fail(self, rule, detail):
        self.failures.append("%s: %s" % (rule, detail))

    def read(self, relative):
        path = self.root / relative
        if not path.is_file():
            return None
        return path.read_text(encoding="utf-8", errors="replace")

    # --------------------------------------------------------------- rules

    def rule_core_files(self):
        rule = "core-files"
        missing = [rel for rel in surf_CORE_FILES if not (self.root / surf_CORE / rel).is_file()]
        self.counts[rule] = len(surf_CORE_FILES) - len(missing)
        for rel in missing:
            self.fail(rule, "missing " + (surf_CORE / rel).as_posix())

    def rule_api_purity(self):
        rule = "api-purity"
        directory = self.root / surf_API
        sources = sorted(directory.rglob("*.java")) if directory.is_dir() else []
        present = [name for name in surf_PURE_API_FILES if (directory / name).is_file()]
        self.counts[rule] = len(present)
        for name in surf_PURE_API_FILES:
            if name not in present:
                self.fail(rule, "missing pure logic source " + (surf_API / name).as_posix())
        for path in sources:
            if path.name not in surf_PURE_API_FILES:
                self.fail(rule, "%s is not part of the Minecraft-free logic layer"
                          % (surf_API / path.name).as_posix())
            for number, line in enumerate(
                    path.read_text(encoding="utf-8", errors="replace").splitlines(), 1):
                if surf_FORBIDDEN_IMPORT.match(line):
                    self.fail(rule, "%s:%d imports %s, which would break the offline JUnit run"
                              % ((surf_API / path.name).as_posix(), number, line.strip()))
                    break

    def rule_api_methods(self):
        rule = "api-methods"
        text = self.read(surf_CORE / "StructureResetApi.java")
        if text is None:
            self.counts[rule] = 0
            self.fail(rule, (surf_CORE / "StructureResetApi.java").as_posix() + " is missing")
            return
        declared = [name for name in surf_API_METHODS if surf_declares(text, name)]
        self.counts[rule] = len(declared)
        for name in surf_API_METHODS:
            if name not in declared:
                self.fail(rule, "StructureResetApi.java does not declare a public static %s(...)" % name)

    def rule_api_events(self):
        rule = "api-events"
        text = self.read(surf_CORE / "StructureResetApi.java")
        if text is None:
            self.counts[rule] = 0
            self.fail(rule, (surf_CORE / "StructureResetApi.java").as_posix() + " is missing")
            return
        posts = len(re.findall(r"[.]post\s*\(", text))
        if posts < len(surf_EVENT_TYPES):
            self.fail(rule, "expected one .post( call per reset event, found %d" % posts)
        found = 0
        for name in surf_EVENT_TYPES:
            if re.search(r"\bnew\s+" + name + r"\s*\(", text) and posts > 0:
                found += 1
            else:
                self.fail(rule, "StructureResetApi.java neither constructs nor posts a %s" % name)
        self.counts[rule] = found

    def rule_examples(self):
        rule = "examples"
        directory = self.root / surf_EXAMPLES
        files = sorted(directory.glob("example_*.json")) if directory.is_dir() else []
        self.counts[rule] = len(files)
        if len(files) < surf_MIN_EXAMPLES:
            self.fail(rule, "found %d example_*.json under %s, expected at least %d"
                      % (len(files), surf_EXAMPLES.as_posix(), surf_MIN_EXAMPLES))
        tagged = 0
        for path in files:
            label = path.name
            try:
                data = json.loads(path.read_text(encoding="utf-8", errors="replace"))
            except Exception as exc:  # noqa: BLE001
                self.fail(rule, "%s: invalid JSON (%s)" % (label, exc))
                continue
            if not isinstance(data, dict):
                self.fail(rule, label + ": the top level must be a JSON object")
                continue
            if "structure" not in data:
                self.fail(rule, label + ": missing the required field 'structure'")
            if surf_is_tagged(data.get("structure")):
                tagged += 1
            actions = data.get("actions")
            if not isinstance(actions, list) or not actions:
                self.fail(rule, label + ": 'actions' must be a non-empty array")
                continue
            for index, action in enumerate(actions):
                if (not isinstance(action, dict) or not isinstance(action.get("type"), str)
                        or not action["type"].strip()):
                    self.fail(rule, "%s: actions[%d] must be an object with a non-blank 'type'"
                              % (label, index))
        if files and tagged == 0:
            self.fail(rule, "at least one example must reference a structure tag ('#namespace:path')")
        shipped = self.root / surf_SHIPPED_RESETS
        if shipped.is_dir():
            for path in sorted(shipped.glob("example_*.json")):
                self.fail(rule, "%s/%s is shipped data: the mod would load it as a real "
                                 "definition, so examples must live under %s"
                          % (surf_SHIPPED_RESETS.as_posix(), path.name, surf_EXAMPLES.as_posix()))

    # ---------------------------------------------------------------- drive

    def run(self):
        self.rule_core_files()
        self.rule_api_purity()
        self.rule_api_methods()
        self.rule_api_events()
        self.rule_examples()
        for rule, count in sorted(self.counts.items()):
            if count == 0:
                self.fail(rule, "parsed 0 entries, which counts as a failure")
        return self.failures

    def summary(self):
        return " ".join("%s=%d" % (rule, self.counts[rule]) for rule in sorted(self.counts))


# ---------------------------------------------------------------- selftest

def surf__check(root):
    return surf_Checker(root).run()


def surf__copy_project(target):
    """Copy the checked trees (src/ and the examples/ datapack) into a root."""
    target = Path(target)
    shutil.copytree(surf_WORKSPACE / "src", target / "src")
    shutil.copytree(surf_WORKSPACE / "examples", target / "examples")
    return target


def surf__drop_lines_containing(path, needle):
    text = path.read_text(encoding="utf-8")
    lines = text.splitlines(keepends=True)
    kept = [line for line in lines if needle not in line]
    if len(kept) == len(lines):
        raise AssertionError("defect anchor missing in %s: %r" % (path.name, needle))
    path.write_text("".join(kept), encoding="utf-8")


def surf__defects():
    def missing_core_file(root):
        (root / surf_CORE / "ResetContext.java").unlink()

    def missing_api_method(root):
        surf__drop_lines_containing(root / surf_CORE / "StructureResetApi.java",
                               "public static boolean canReset")

    def missing_post_event(root):
        path = root / surf_CORE / "StructureResetApi.java"
        text = path.read_text(encoding="utf-8")
        if "new ResetPostEvent(" not in text:
            raise AssertionError("defect anchor missing: new ResetPostEvent(")
        path.write_text(text.replace("new ResetPostEvent(", "new DisabledPostEvent("), encoding="utf-8")

    def minecraft_import_in_api(root):
        path = root / surf_API / "ResetKey.java"
        text = path.read_text(encoding="utf-8")
        path.write_text("import net.minecraft.world.phys.Vec3;\n" + text, encoding="utf-8")

    def extra_api_source(root):
        (root / surf_API / "LeakedRuntimeApi.java").write_text(
            "package com.structurereset.api;\n\npublic final class LeakedRuntimeApi {\n}\n",
            encoding="utf-8")

    def missing_example(root):
        (root / surf_EXAMPLES / "example_shrine.json").unlink()

    def example_without_tag(root):
        path = root / surf_EXAMPLES / "example_tag_based.json"
        text = path.read_text(encoding="utf-8")
        if '"#minecraft:village"' not in text:
            raise AssertionError("defect anchor missing: #minecraft:village")
        path.write_text(text.replace('"#minecraft:village"', '"minecraft:village"'), encoding="utf-8")

    def example_shipped_again(root):
        # the regression this rule exists to catch: an example copied back into
        # the shipped data tree, where the mod loads it as a live definition.
        (root / surf_SHIPPED_RESETS).mkdir(parents=True, exist_ok=True)
        shutil.copyfile(root / surf_EXAMPLES / "example_shrine.json",
                        root / surf_SHIPPED_RESETS / "example_shrine.json")

    return (
        ("missing-core-file", missing_core_file),
        ("missing-api-method", missing_api_method),
        ("missing-post-event", missing_post_event),
        ("minecraft-import-in-api", minecraft_import_in_api),
        ("extra-api-source", extra_api_source),
        ("missing-example", missing_example),
        ("example-without-tag", example_without_tag),
        ("example-shipped-again", example_shipped_again),
    )


def surf_selftest():
    tmp = toolchain.make_scratch_dir("structurereset-api-surface-selftest", surf_WORKSPACE)
    ok = True
    try:
        clean = surf__copy_project(tmp / "clean")
        failures = surf__check(clean)
        if failures:
            ok = False
            print("FAIL: selftest: the real API surface was rejected (%d failure(s))" % len(failures))
            for failure in failures[:5]:
                print("      %s" % failure)
        else:
            print("selftest: %-24s -> accepted" % "real-api-surface")

        for name, defect in surf__defects():
            target = surf__copy_project(tmp / ("bad-" + name))
            try:
                defect(target)
            except Exception as exc:  # noqa: BLE001
                ok = False
                print("FAIL: selftest: defect %s could not be injected (%s)" % (name, exc))
                continue
            failures = surf__check(target)
            if not failures:
                ok = False
                print("FAIL: selftest: defect '%s' was accepted (exit 0)" % name)
            else:
                print("selftest: %-24s -> rejected (%s)" % (name, failures[0]))
    finally:
        shutil.rmtree(tmp, ignore_errors=True)

    print("validate-api-surface selftest: " + ("OK" if ok else "FAILED"))
    return 0 if ok else 1


# ------------------------------------------------------------------ main

def surf_main(argv):
    parser = argparse.ArgumentParser(
        description="Check the runtime API surface of Structure Reset (api and core packages).")
    parser.add_argument("--selftest", action="store_true", help="check the checker itself")
    parser.add_argument("--root", default=None,
                        help="Project root (defaults to this script's parent)")
    args = parser.parse_args(argv)

    if args.selftest:
        return surf_selftest()

    root = Path(args.root).resolve() if args.root else surf_WORKSPACE
    checker = surf_Checker(root)
    failures = checker.run()
    if failures:
        for failure in failures:
            print("FAIL: validate-api-surface: %s" % failure)
        print("validate-api-surface: %d failure(s) (%s)" % (len(failures), checker.summary()))
        return 1
    print("OK: validate-api-surface: %d core file(s), %d pure api file(s), %d method(s), "
          "%d posted event(s), %d example(s) (%s)"
          % (checker.counts["core-files"], checker.counts["api-purity"],
             checker.counts["api-methods"], checker.counts["api-events"],
             checker.counts["examples"], checker.summary()))
    return 0




# ==========================================================================
# section 'coverage' -- Cataclysm 14 条结构定义的覆盖率
# ==========================================================================

"""Coverage validator for the Cataclysm structure-reset definitions (Structure Reset).

Usage:
    python tools/validate_checks.py coverage             # exit 0 when every rule passes
    python tools/validate_checks.py coverage --selftest  # prove the validator detects defects

Scope: the completeness rules below apply ONLY to definitions under
data/structurereset/structure_resets/cataclysm/.  Any other reset definition in
that tree (hand written examples, other namespaces) only has to satisfy the base
schema -- a "structure" field and an "actions" array whose entries carry a
"type" -- and is deliberately exempt from the Cataclysm completeness rules.

Rules (rule ids appear in the FAIL lines):
    reference    the read-only Cataclysm source declares exactly 14 structure ids
    schema       every definition is an object with "structure" and a well formed "actions" array
    coverage     every one of the 14 ids has a cataclysm/ definition with an exact structure match
    extras       no cataclysm/ definition refers to a structure id outside that set
    anchor       every cataclysm/ definition anchors on cataclysm:boss_respawner
    actions      every cataclysm/ definition respawns a boss and refills loot
    fields       every cataclysm/ definition carries a numeric radius
    cooldown     every cataclysm/ definition carries a positive integer cooldown_seconds
    default      every cataclysm/ cooldown_seconds equals the 'defaultCooldownSeconds'
                 default parsed out of StructureResetConfig.java (never duplicated here)
"""


import argparse
import json
import re
import shutil
import sys
from pathlib import Path

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    sys.stderr.reconfigure(encoding="utf-8", errors="replace")
except Exception:  # pragma: no cover
    pass

cover_SCRIPT = Path(__file__).resolve()
cover_WORKSPACE = cover_SCRIPT.parent.parent

sys.path.insert(0, str(cover_SCRIPT.parent))
import toolchain  # noqa: E402  (sibling module: shared scratch directories)
cover_REFERENCE = Path("F:/Desktop/new1.20.1-master/new1.20.1-master")
cover_REFERENCE_STRUCTURES = cover_REFERENCE / "src/main/resources/data/cataclysm/worldgen/structure"
cover_DEFINITIONS = cover_WORKSPACE / "src/main/resources/data/structurereset/structure_resets"
# The Java config source is the single source of truth for the cooldown baseline:
# the expected value is parsed back out of it, never repeated as a literal here.
cover_CONFIG_SOURCE = cover_WORKSPACE / "src/main/java/com/structurereset/config/StructureResetConfig.java"

cover_EXPECTED_COUNT = 14
cover_NAMESPACE = "cataclysm"
cover_ANCHOR_BLOCK = "cataclysm:boss_respawner"
cover_RESPAWN_ACTION = "structurereset:respawn_boss"
cover_REFILL_ACTION = "structurereset:refill_loot"

# Structures that genuinely have no boss in Cataclysm. They may only refill loot:
# they must not declare an anchor block and must not declare a respawn_boss
# action, so they can never summon a boss. The list is evidence based - every
# entity that spawns there (aptrgangr, kobolediator, amethyst_crab, ...) registers
# no boss bar (CMBossInfoServer / ServerBossEvent) in the mod source.
cover_NO_BOSS_STRUCTURES = frozenset({
    "abandoned_spire",
    "abandoned_temple",
    "abandoned_village",
    "amethyst_nest",
    "desert_site",
    "desert_occupied_village",
})

# 'defaultCooldownSeconds' is declared by the COMMON spec
# (defineInRange("defaultCooldownSeconds", <default>, <min>, <max>)); the
# accessor repeats it as the null-safe fallback.  Either spelling is accepted.
cover_CONFIG_DEFAULT_PATTERNS = (
    re.compile(r'\.defineInRange\(\s*"defaultCooldownSeconds"\s*,\s*(-?\d+)\s*,'),
    re.compile(r'COMMON\.defaultCooldownSeconds\s*,\s*(-?\d+)'),
)


# ------------------------------------------------------------------ helpers

def cover__read_json(path: Path):
    return json.loads(path.read_text(encoding="utf-8"))


def cover__write_json(path, data) -> None:
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")


def cover_reference_ids(reference_structures: Path):
    """The bare structure ids declared by the read-only Cataclysm source."""
    if not Path(reference_structures).is_dir():
        return None
    return sorted(p.stem for p in Path(reference_structures).glob("*.json") if p.is_file())


def cover_in_scope(path, definitions_dir) -> bool:
    """True when the definition lives under the cataclysm/ subdirectory."""
    try:
        rel = Path(path).relative_to(Path(definitions_dir))
    except ValueError:
        return False
    return bool(rel.parts) and rel.parts[0] == cover_NAMESPACE


def cover__structure_refs(value):
    """Every structure reference named by a definition's 'structure' field."""
    out = []
    if isinstance(value, str):
        out.append(value.strip())
    elif isinstance(value, list):
        for entry in value:
            out.extend(cover__structure_refs(entry))
    return out


def cover__local_id(ref: str, namespace: str = cover_NAMESPACE):
    """The bare id of a reference inside 'namespace', else None."""
    text = ref.strip()
    if text.startswith("#"):
        text = text[1:]
    if ":" in text:
        ns, _, path = text.partition(":")
        return path if ns == namespace else None
    return text


def cover__is_number(value) -> bool:
    return isinstance(value, (int, float)) and not isinstance(value, bool)


def cover__is_integer(value) -> bool:
    """A JSON integer; a bool is not an integer even though bool subclasses int."""
    return isinstance(value, int) and not isinstance(value, bool)


def cover_config_default_cooldown(source=None):
    """Parse the 'defaultCooldownSeconds' default out of the Java config source.

    Returns (value, problems).  value is None -- with the reason in problems --
    when the source file or the declaration cannot be found, so a broken parse is
    reported as a problem instead of raising.
    """
    path = Path(cover_CONFIG_SOURCE if source is None else source)
    if not path.is_file():
        return None, ["default: %s does not exist (cannot read the "
                      "'defaultCooldownSeconds' default)" % path]
    try:
        text = path.read_text(encoding="utf-8")
    except Exception as exc:
        return None, ["default: %s could not be read (%s)" % (path, exc)]
    for pattern in cover_CONFIG_DEFAULT_PATTERNS:
        match = pattern.search(text)
        if match:
            return int(match.group(1)), []
    return None, ["default: %s declares no 'defaultCooldownSeconds' default" % path]


# -------------------------------------------------------------------- check

def cover_check(definitions_dir, reference_structures, config_source=None):
    """Return (problems, stats) for one (definitions, reference, config) triple."""
    definitions_dir = Path(definitions_dir)
    reference_structures = Path(reference_structures)
    problems = []
    stats = {"structures": 0, "definitions": 0, "scoped": 0, "other": 0, "matched": 0}

    ids = cover_reference_ids(reference_structures)
    if ids is None:
        problems.append("reference: %s is not a directory" % reference_structures)
        return problems, stats
    stats["structures"] = len(ids)
    if len(ids) != cover_EXPECTED_COUNT:
        problems.append("reference: %s declares %d structure ids, expected %d (%s)"
                        % (reference_structures, len(ids), cover_EXPECTED_COUNT, ", ".join(ids)))
    known = set(ids)

    if not definitions_dir.is_dir():
        problems.append("coverage: %s is not a directory" % definitions_dir)
        return problems, stats

    files = sorted(p for p in definitions_dir.rglob("*.json") if p.is_file())
    stats["definitions"] = len(files)
    scoped = [p for p in files if cover_in_scope(p, definitions_dir)]
    others = [p for p in files if not cover_in_scope(p, definitions_dir)]
    stats["scoped"] = len(scoped)
    stats["other"] = len(others)

    def label_of(path) -> str:
        try:
            return Path(path).relative_to(definitions_dir).as_posix()
        except ValueError:
            return Path(path).name

    def load(path):
        label = label_of(path)
        try:
            data = cover__read_json(path)
        except Exception as exc:
            problems.append("schema: %s: invalid JSON (%s)" % (label, exc))
            return None, label
        if not isinstance(data, dict):
            problems.append("schema: %s: top level must be an object" % label)
            return None, label
        return data, label

    def base_schema(data, label) -> None:
        """The only rules that apply outside cataclysm/."""
        if "structure" not in data:
            problems.append("schema: %s: missing required field 'structure'" % label)
        actions = data.get("actions")
        if not isinstance(actions, list):
            problems.append("schema: %s: 'actions' must be an array" % label)
            return
        for index, action in enumerate(actions):
            if not isinstance(action, dict):
                problems.append("schema: %s: actions[%d] must be an object" % (label, index))
            elif not isinstance(action.get("type"), str) or not action["type"].strip():
                problems.append("schema: %s: actions[%d] has no 'type' field" % (label, index))

    # ---- everything outside cataclysm/: base schema only -------------------
    for path in others:
        data, label = load(path)
        if data is not None:
            base_schema(data, label)

    # ---- cataclysm/: base schema plus the completeness rules ---------------
    # The cooldown baseline lives in the Java config source; parsing it here
    # keeps this validator and the mod from drifting apart.
    expected_cooldown, default_problems = cover_config_default_cooldown(config_source)
    problems.extend(default_problems)

    by_id = {}

    for path in scoped:
        data, label = load(path)
        if data is None:
            continue
        base_schema(data, label)

        raw = data.get("structure")
        if raw is not None:
            for ref in cover__structure_refs(raw):
                local = cover__local_id(ref)
                if local is None:
                    problems.append("extras: %s: structure '%s' is outside the %s namespace"
                                    % (label, ref, cover_NAMESPACE))
                    continue
                if local not in known:
                    problems.append("extras: %s: unknown structure id '%s' (not one of the %d "
                                    "Cataclysm structures)" % (label, ref, len(ids)))
                    continue
                if local in by_id:
                    problems.append("extras: %s: structure 'cataclysm:%s' is already defined by %s"
                                    % (label, local, label_of(by_id[local])))
                    continue
                by_id[local] = path

        anchor_list = data.get("anchor_blocks")
        actions = data.get("actions")
        types = [a.get("type") for a in actions if isinstance(a, dict)] if isinstance(actions, list) else []

        declared = [x for x in (cover__local_id(r) for r in cover__structure_refs(raw)) if x is not None] \
            if raw is not None else []
        bossless = bool(declared) and all(x in cover_NO_BOSS_STRUCTURES for x in declared)

        if bossless:
            # A structure without a boss may only refill loot. It must not carry an
            # anchor block (which would let it be triggered elsewhere) and must not
            # declare a respawn_boss action (which would summon a boss that does not
            # belong there).
            if isinstance(anchor_list, list) and anchor_list:
                problems.append("anchor: %s: a no-boss structure must declare "
                                "'anchor_blocks': []" % label)
            if cover_RESPAWN_ACTION in types:
                problems.append("actions: %s: a no-boss structure must not declare '%s'"
                                % (label, cover_RESPAWN_ACTION))
            if isinstance(actions, list) and actions and cover_REFILL_ACTION not in types:
                problems.append("actions: %s: 'actions' has no '%s' action"
                                % (label, cover_REFILL_ACTION))
        else:
            if not isinstance(anchor_list, list) or not anchor_list:
                problems.append("anchor: %s: 'anchor_blocks' must be a non-empty array" % label)
            elif cover_ANCHOR_BLOCK not in [a for a in anchor_list if isinstance(a, str)]:
                problems.append("anchor: %s: 'anchor_blocks' does not contain '%s'"
                                % (label, cover_ANCHOR_BLOCK))

        if not isinstance(actions, list) or not actions:
            problems.append("actions: %s: 'actions' must be a non-empty array" % label)
        else:
            if not bossless and cover_RESPAWN_ACTION not in types:
                problems.append("actions: %s: 'actions' has no '%s' action" % (label, cover_RESPAWN_ACTION))
            if not bossless and cover_REFILL_ACTION not in types:
                problems.append("actions: %s: 'actions' has no '%s' action" % (label, cover_REFILL_ACTION))
            for action in actions:
                if isinstance(action, dict) and action.get("type") == cover_RESPAWN_ACTION:
                    params = action.get("params")
                    entity = params.get("entity") if isinstance(params, dict) else None
                    if not isinstance(entity, str) or not entity.strip():
                        problems.append("actions: %s: '%s' action has no params.entity"
                                        % (label, cover_RESPAWN_ACTION))

        if "radius" not in data:
            problems.append("fields: %s: missing required field 'radius'" % label)
        elif not cover__is_number(data["radius"]):
            problems.append("fields: %s: 'radius' must be a number" % label)

        if "cooldown_seconds" not in data:
            problems.append("cooldown: %s: missing required field 'cooldown_seconds'" % label)
        elif not cover__is_integer(data["cooldown_seconds"]) or data["cooldown_seconds"] <= 0:
            problems.append("cooldown: %s: 'cooldown_seconds' must be an integer greater "
                            "than 0, got %r" % (label, data["cooldown_seconds"]))
        elif expected_cooldown is not None and data["cooldown_seconds"] != expected_cooldown:
            problems.append("default: %s: 'cooldown_seconds' is %r but the "
                            "'defaultCooldownSeconds' default in %s is %d"
                            % (label, data["cooldown_seconds"], cover_CONFIG_SOURCE,
                               expected_cooldown))

    for sid in sorted(known - set(by_id)):
        problems.append("coverage: no cataclysm/ definition matches 'cataclysm:%s'" % sid)
    stats["matched"] = len(known & set(by_id))

    return problems, stats


def cover_report(problems, stats, label) -> int:
    for problem in problems:
        print("FAIL: %s" % problem)
    if problems:
        print("validate-coverage: %d failure(s) in %s" % (len(problems), label))
        return 1
    print("OK: %s (structures=%d definitions=%d scoped=%d other=%d matched=%d)"
          % (label, stats["structures"], stats["definitions"],
             stats["scoped"], stats["other"], stats["matched"]))
    return 0


# ----------------------------------------------------------------- selftest

def cover__find(root, stem):
    matches = sorted(Path(root).rglob(stem + ".json"))
    if not matches:
        raise AssertionError("selftest: %s.json not found under %s" % (stem, root))
    return matches[0]


def cover__mutate(root, stem, func):
    path = cover__find(root, stem)
    data = cover__read_json(path)
    func(data)
    path.write_text(json.dumps(data, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")


def cover__copy_definitions(source, target):
    if Path(target).exists():
        shutil.rmtree(target, ignore_errors=True)
    shutil.copytree(source, target)


def cover__defects():
    """(name, mutation): each must be REJECTED by check()."""

    def missing_definition(root):
        cover__find(root, "abandoned_spire").unlink()

    def extra_structure_id(root):
        cover__write_json(Path(root) / "cataclysm" / "zzz_extra.json", {
            "structure": "cataclysm:zzz_extra",
            "radius": 64,
            "cooldown_seconds": 999,
            "anchor_blocks": ["cataclysm:boss_respawner"],
            "actions": [
                {"type": "structurereset:respawn_boss", "params": {"entity": "cataclysm:ignis"}},
                {"type": "structurereset:refill_loot"},
            ],
        })

    def misspelled_structure_id(root):
        cover__mutate(root, "frosted_prison",
                lambda d: d.__setitem__("structure", "cataclysm:frosted_prisonn"))

    def missing_anchor_blocks(root):
        cover__mutate(root, "acropolis", lambda d: d.pop("anchor_blocks", None))

    def anchor_without_boss_respawner(root):
        cover__mutate(root, "acropolis", lambda d: d.__setitem__("anchor_blocks", ["minecraft:stone"]))

    def missing_respawn_boss(root):
        cover__mutate(root, "burning_arena", lambda d: d.__setitem__(
            "actions", [a for a in d["actions"] if a.get("type") != "structurereset:respawn_boss"]))

    def missing_refill_loot(root):
        cover__mutate(root, "burning_arena", lambda d: d.__setitem__(
            "actions", [a for a in d["actions"] if a.get("type") != "structurereset:refill_loot"]))

    def missing_cooldown_seconds(root):
        # a cataclysm/ definition without 'cooldown_seconds' must be rejected
        cover__mutate(root, "desert_site", lambda d: d.pop("cooldown_seconds", None))

    def cooldown_not_positive_integer(root):
        # a non-integer / non-positive 'cooldown_seconds' must be rejected
        cover__mutate(root, "desert_site", lambda d: d.__setitem__("cooldown_seconds", 0))
        cover__mutate(root, "amethyst_nest", lambda d: d.__setitem__("cooldown_seconds", True))

    def cooldown_mismatch_config_default(root):
        # a cataclysm/ 'cooldown_seconds' that disagrees with the
        # 'defaultCooldownSeconds' default parsed from the Java source must be
        # rejected; the injected value is derived from that parsed default.
        base, _ = cover_config_default_cooldown()
        other = (base + 1) if base is not None else 1
        cover__mutate(root, "sunken_city", lambda d: d.__setitem__("cooldown_seconds", other))

    def no_boss_structure_with_respawn_boss(root):
        # a structure without a boss must never declare a respawn_boss action
        cover__mutate(root, "abandoned_spire", lambda d: d.__setitem__("actions", [
            {"type": "structurereset:respawn_boss", "params": {"entity": "cataclysm:maledictus"}},
            {"type": "structurereset:refill_loot"},
        ]))

    def no_boss_structure_with_anchor(root):
        # a structure without a boss must not carry an anchor block either
        cover__mutate(root, "desert_site", lambda d: d.__setitem__(
            "anchor_blocks", ["cataclysm:boss_respawner"]))

    def missing_radius(root):
        cover__mutate(root, "desert_site", lambda d: d.pop("radius", None))

    def invalid_json(root):
        cover__find(root, "sunken_city").write_text("{\n  // comments are not json\n}\n", encoding="utf-8")

    def out_of_scope_missing_structure(root):
        cover__write_json(Path(root) / "custom_broken.json",
                    {"actions": [{"type": "structurereset:message"}]})

    def out_of_scope_actions_not_array(root):
        cover__write_json(Path(root) / "custom_broken.json",
                    {"structure": "minecraft:stronghold", "actions": {}})

    def out_of_scope_action_without_type(root):
        cover__write_json(Path(root) / "custom_broken.json",
                    {"structure": "minecraft:stronghold", "actions": [{"params": {}}]})

    return (
        ("missing-definition", missing_definition),
        ("extra-structure-id", extra_structure_id),
        ("misspelled-structure-id", misspelled_structure_id),
        ("missing-anchor-blocks", missing_anchor_blocks),
        ("anchor-without-boss-respawner", anchor_without_boss_respawner),
        ("missing-respawn-boss", missing_respawn_boss),
        ("missing-refill-loot", missing_refill_loot),
        ("missing-cooldown-seconds", missing_cooldown_seconds),
        ("cooldown-not-positive-integer", cooldown_not_positive_integer),
        ("cooldown-mismatch-config-default", cooldown_mismatch_config_default),
        ("no-boss-structure-with-respawn-boss", no_boss_structure_with_respawn_boss),
        ("no-boss-structure-with-anchor", no_boss_structure_with_anchor),
        ("missing-radius", missing_radius),
        ("invalid-json", invalid_json),
        ("out-of-scope-missing-structure", out_of_scope_missing_structure),
        ("out-of-scope-actions-not-array", out_of_scope_actions_not_array),
        ("out-of-scope-action-without-type", out_of_scope_action_without_type),
    )


def cover__acceptances():
    """(name, mutation): each must be ACCEPTED by check() -- the scoping contract."""

    def minimal_example(root):
        cover__write_json(Path(root) / "custom_minimal_example.json", {
            "structure": "minecraft:stronghold",
            "actions": [{"type": "structurereset:message", "params": {"text": "hi"}}],
        })

    def no_boss_actions_example(root):
        cover__write_json(Path(root) / "custom_anchor_only.json", {
            "structure": "minecraft:woodland_mansion",
            "anchor_blocks": [],
            "cooldown_seconds": 1200,
            "actions": [{"type": "structurereset:clear_entities",
                         "params": {"entities": ["minecraft:zombie"]}}],
        })

    def tag_based_example(root):
        cover__write_json(Path(root) / "custom_tag_example.json", {
            "structure": "#minecraft:village",
            "radius": 64,
            "actions": [{"type": "structurereset:message"}],
        })

    def in_scope_boss_definition_still_checked(root):
        # a correctly built boss definition for a cataclysm/ structure stays fine;
        # its cooldown comes from the config source, not from a literal here.
        default, _ = cover_config_default_cooldown()
        cover__write_json(Path(root) / "cataclysm" / "acropolis.json", {
            "structure": "cataclysm:acropolis",
            "radius": 96,
            "cooldown_seconds": default,
            "anchor_blocks": ["cataclysm:boss_respawner"],
            "actions": [
                {"type": "structurereset:respawn_boss", "params": {"entity": "cataclysm:scylla"}},
                {"type": "structurereset:refill_loot"},
            ],
        })

    def in_scope_no_boss_definition_accepted(root):
        # a structure that genuinely has no boss is allowed to exist as a loot-only
        # definition: no anchor block, no respawn_boss, but a refill action.
        default, _ = cover_config_default_cooldown()
        cover__write_json(Path(root) / "cataclysm" / "abandoned_spire.json", {
            "structure": "cataclysm:abandoned_spire",
            "radius": 48,
            "cooldown_seconds": default,
            "anchor_blocks": [],
            "actions": [{"type": "structurereset:refill_loot",
                         "params": {"mode": "empty_only"}}],
        })

    def all_cooldowns_same_value(root):
        # every cataclysm/ definition carrying the very same cooldown_seconds
        # (the config default) is the intended state and must be accepted.
        default, _ = cover_config_default_cooldown()
        seen = 0
        for path in sorted((Path(root) / "cataclysm").rglob("*.json")):
            data = cover__read_json(path)
            data["cooldown_seconds"] = default
            cover__write_json(path, data)
            seen += 1
        if seen != cover_EXPECTED_COUNT:
            raise AssertionError("expected %d cataclysm/ definitions, found %d"
                                 % (cover_EXPECTED_COUNT, seen))

    return (
        ("out-of-scope-minimal-example", minimal_example),
        ("out-of-scope-no-boss-actions", no_boss_actions_example),
        ("out-of-scope-tag-example", tag_based_example),
        ("in-scope-boss-definition", in_scope_boss_definition_still_checked),
        ("in-scope-no-boss-definition", in_scope_no_boss_definition_accepted),
        ("all-cooldowns-same-value", all_cooldowns_same_value),
    )


def cover_selftest() -> int:
    if not cover_DEFINITIONS.is_dir():
        print("FAIL: selftest: %s does not exist" % cover_DEFINITIONS)
        return 1
    ok = True
    tmp = toolchain.make_scratch_dir("structurereset-coverage-selftest", cover_WORKSPACE)
    try:
        clean = tmp / "clean"
        cover__copy_definitions(cover_DEFINITIONS, clean)
        problems, _ = cover_check(clean, cover_REFERENCE_STRUCTURES)
        if problems:
            ok = False
            print("FAIL: selftest: the real definitions were rejected (%d problem(s))" % len(problems))
            for problem in problems:
                print("      %s" % problem)
        else:
            print("selftest: %-34s -> accepted" % "real-definitions")

        empty_reference = tmp / "empty-reference"
        empty_reference.mkdir(parents=True, exist_ok=True)
        problems, _ = cover_check(clean, empty_reference)
        if not problems:
            ok = False
            print("FAIL: selftest: an empty reference directory was accepted")
        else:
            print("selftest: %-34s -> rejected" % "empty-reference-dir")

        # a config source that cannot be parsed must be reported, not raised
        missing_config = tmp / "no-such-config" / "StructureResetConfig.java"
        try:
            problems, _ = cover_check(clean, cover_REFERENCE_STRUCTURES, config_source=missing_config)
        except Exception as exc:
            ok = False
            print("FAIL: selftest: a missing config source raised %s" % exc)
        else:
            if not problems:
                ok = False
                print("FAIL: selftest: a missing config source was accepted")
            else:
                print("selftest: %-34s -> rejected (%s)" % ("missing-config-source", problems[0]))

        for name, defect in cover__defects():
            target = tmp / ("bad-" + name)
            cover__copy_definitions(cover_DEFINITIONS, target)
            try:
                defect(target)
            except Exception as exc:
                ok = False
                print("FAIL: selftest: defect %s could not be injected (%s)" % (name, exc))
                continue
            problems, _ = cover_check(target, cover_REFERENCE_STRUCTURES)
            if not problems:
                ok = False
                print("FAIL: selftest: defect '%s' was accepted (exit 0)" % name)
            else:
                print("selftest: %-34s -> rejected (%s)" % (name, problems[0]))

        for name, mutation in cover__acceptances():
            target = tmp / ("ok-" + name)
            cover__copy_definitions(cover_DEFINITIONS, target)
            try:
                mutation(target)
            except Exception as exc:
                ok = False
                print("FAIL: selftest: case %s could not be built (%s)" % (name, exc))
                continue
            problems, _ = cover_check(target, cover_REFERENCE_STRUCTURES)
            if problems:
                ok = False
                print("FAIL: selftest: case '%s' was rejected (%s)" % (name, problems[0]))
            else:
                print("selftest: %-34s -> accepted" % name)
    finally:
        shutil.rmtree(tmp, ignore_errors=True)

    print("validate-coverage selftest: " + ("OK" if ok else "FAILED"))
    return 0 if ok else 1


# --------------------------------------------------------------------- main

def cover_main(argv) -> int:
    parser = argparse.ArgumentParser(description="Check Cataclysm structure-reset coverage.")
    parser.add_argument("--selftest", action="store_true",
                        help="prove the validator detects injected defects")
    parser.add_argument("--definitions", default=None, help="structure_resets root")
    parser.add_argument("--reference", default=None, help="Cataclysm worldgen/structure directory")
    args = parser.parse_args(argv)

    if args.selftest:
        return cover_selftest()

    definitions = Path(args.definitions).resolve() if args.definitions else cover_DEFINITIONS
    reference = Path(args.reference).resolve() if args.reference else cover_REFERENCE_STRUCTURES
    problems, stats = cover_check(definitions, reference)
    return cover_report(problems, stats, str(definitions))




# ==========================================================================
# section 'appearance' -- 物品、模型、贴图与配方
# ==========================================================================

"""Appearance validator: item, models, textures, recipe and skins.

It checks the concrete deliverables of the reset item, not the mod as a whole:

* the item model and the two skin models exist and are valid JSON;
* every texture an item model references exists as a PNG, can really be opened
  by Pillow, is at least 16x16 and is not a flat single colour placeholder;
* the three shipped icons are pairwise different, so the skins are visually
  distinguishable and not the same file under three names;
* the shaped recipe exists, is a crafting_shaped recipe and its result is
  structurereset:structure_reset_core;
* data/structurereset/item_appearance.json exists and parses;
* the KubeJS resource-pack template (item model override + texture) exists;
* en_us.json and zh_cn.json both carry item.structurereset.structure_reset_core
  and have exactly the same key set;
* the Java entry points of the task are present (ModItems registration string,
  ItemAppearanceApi methods, the ModifyBakingResult client hook and the
  StructureResetItem use()/appendHoverText() overrides).

Usage:
    python tools/validate_checks.py appearance     # exit 0 when every rule passes
"""


import hashlib
import json
import sys
from pathlib import Path

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    sys.stderr.reconfigure(encoding="utf-8", errors="replace")
except Exception:  # pragma: no cover
    pass

try:
    from PIL import Image
except ImportError:  # reported as a problem instead of a stack trace
    Image = None

appear_SCRIPT = Path(__file__).resolve()
appear_WORKSPACE = appear_SCRIPT.parent.parent
appear_RESOURCES = appear_WORKSPACE / "src" / "main" / "resources"
appear_JAVA = appear_WORKSPACE / "src" / "main" / "java"

appear_MODID = "structurereset"
appear_ITEM_ID = "structure_reset_core"
appear_SKINS = ("structure_reset_core_skin1", "structure_reset_core_skin2")

appear_ITEM_MODEL_DIR = appear_RESOURCES / "assets" / appear_MODID / "models" / "item"
appear_LANG_DIR = appear_RESOURCES / "assets" / appear_MODID / "lang"
appear_RECIPE = appear_RESOURCES / "data" / appear_MODID / "recipes" / (appear_ITEM_ID + ".json")
appear_APPEARANCE = appear_RESOURCES / "data" / appear_MODID / "item_appearance.json"
appear_KUBEJS_MODEL = appear_WORKSPACE / "kubejs-examples" / "assets" / appear_MODID / "models" / "item" / (appear_ITEM_ID + ".json")
appear_KUBEJS_TEXTURE = appear_WORKSPACE / "kubejs-examples" / "assets" / appear_MODID / "textures" / "item" / "custom_core.png"

appear_MIN_SIZE = 16

appear_JAVA_CHECKS = (
    ("registry/ModItems.java", ("class ModItems", 'register("' + appear_ITEM_ID + '"', "DeferredRegister", "register(IEventBus")),
    ("item/StructureResetItem.java", ("extends Item", "InteractionResultHolder<ItemStack> use(",
                                      "appendHoverText(", "ResetEngine.blockerFor(",
                                      "ResetAim.beginAiming(")),
    # The crosshair driven respawn: the cast completion is what triggers the reset
    # and applies the vanilla cooldown whose purple overlay is the post reset
    # cooldown. Both phases, the safe landing check and the outline must exist.
    ("impl/ResetAim.java", ("PHASE_AIMING", "PHASE_CASTING", "lockAndCast(", "triggerReset(",
                            "getCooldowns().addCooldown(", "isSafeStanding(", "sendParticles(")),
    # The boss registry: "preserve at generation, confirm at death".
    ("impl/BossRegistryData.java", ("class BossRegistryData", "extends SavedData",
                                    "awaitingRespawn(", "bossUuid")),
    ("core/ItemAppearanceApi.java", ("setModelOverride(", "clearModelOverride(", "modelOverride(")),
    ("client/StructureResetClient.java", ("Mod.EventBusSubscriber", "ModelEvent.ModifyBakingResult", "Dist.CLIENT")),
)


class appear_Report:
    """Collects problems and the number of checks that ran."""

    def __init__(self):
        self.problems = []
        self.checks = 0

    def fail(self, rule, detail):
        self.problems.append((rule, detail))

    def ok(self):
        self.checks += 1


def appear_rel(path):
    try:
        return Path(path).relative_to(appear_WORKSPACE).as_posix()
    except ValueError:
        return Path(path).as_posix()


def appear_read_json(path, rule, report):
    """Parsed JSON of a file, or None (the problem is recorded)."""
    if not path.is_file():
        report.fail(rule, "%s is missing" % appear_rel(path))
        return None
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except Exception as exc:  # noqa: BLE001 - any JSON problem is a failure
        report.fail(rule, "%s is not valid JSON (%s)" % (appear_rel(path), exc))
        return None


def appear_texture_path(ns, path):
    return appear_RESOURCES / "assets" / ns / "textures" / (path + ".png")


def appear_check_png(candidate, where, rule, report, seen):
    """Prove that one PNG is real, non flat art of at least 16x16."""
    label = appear_rel(candidate)
    if not candidate.is_file():
        report.fail(rule, "%s: %s is missing" % (where, label))
        return
    if Image is None:
        report.fail(rule, "%s: Pillow is not available, %s cannot be inspected (python -m pip install Pillow)"
                   % (where, label))
        return
    try:
        with Image.open(candidate) as image:
            image.load()
            width, height = image.size
            raw = image.convert("RGBA").tobytes()
    except Exception as exc:  # noqa: BLE001 - a broken PNG is a failure
        report.fail(rule, "%s: %s cannot be opened by Pillow (%s)" % (where, label, exc))
        return
    if width < appear_MIN_SIZE or height < appear_MIN_SIZE:
        report.fail(rule, "%s: %s is %dx%d, smaller than %dx%d" % (where, label, width, height, appear_MIN_SIZE, appear_MIN_SIZE))
        return
    packed = set(zip(raw[0::4], raw[1::4], raw[2::4], raw[3::4]))
    if len(packed) < 2:
        report.fail(rule, "%s: %s is a single colour placeholder" % (where, label))
        return
    seen[label] = hashlib.sha1(raw).hexdigest()
    report.ok()


def appear_check_texture(ref, where, rule, report, seen):
    """Resolve one in-pack texture reference and prove the PNG is real art."""
    ns, _, path = ref.partition(":")
    if not ns or not path:
        report.fail(rule, "%s: texture '%s' is not a namespace:path reference" % (where, ref))
        return
    candidate = appear_texture_path(ns, path)
    if not candidate.is_file():
        report.fail(rule, "%s: texture '%s' resolves to %s which is missing" % (where, ref, appear_rel(candidate)))
        return
    appear_check_png(candidate, where, rule, report, seen)


def appear_check_item_model(name, rule, report, seen):
    """One item model: valid JSON, generated parent, real texture."""
    path = appear_ITEM_MODEL_DIR / (name + ".json")
    data = appear_read_json(path, rule, report)
    if data is None:
        return None
    if not isinstance(data, dict):
        report.fail(rule, "%s: top level must be an object" % appear_rel(path))
        return None
    if data.get("parent") != "minecraft:item/generated":
        report.fail(rule, "%s: parent must be 'minecraft:item/generated', got %r" % (appear_rel(path), data.get("parent")))
    textures = data.get("textures")
    if not isinstance(textures, dict) or not textures:
        report.fail(rule, "%s: no 'textures' object" % appear_rel(path))
    else:
        for key in sorted(textures):
            value = textures[key]
            if not isinstance(value, str):
                report.fail(rule, "%s: textures.%s must be a string" % (appear_rel(path), key))
                continue
            appear_check_texture(value, appear_rel(path), rule, report, seen)
    report.ok()
    return data


def appear_check_overrides(model, report):
    rule = "model-overrides"
    overrides = model.get("overrides") if isinstance(model, dict) else None
    if not isinstance(overrides, list) or not overrides:
        report.fail(rule, "the item model declares no 'overrides' array, so the skins are unreachable")
        return
    by_data = {}
    for entry in overrides:
        if not isinstance(entry, dict):
            report.fail(rule, "an override entry is not an object")
            continue
        predicate = entry.get("predicate")
        value = predicate.get("custom_model_data") if isinstance(predicate, dict) else None
        by_data[value] = entry.get("model")
    for index, skin in enumerate(appear_SKINS, start=1):
        expected = "%s:item/%s" % (appear_MODID, skin)
        actual = by_data.get(index)
        if actual != expected:
            report.fail(rule, "custom_model_data %d must point at '%s', got %r" % (index, expected, actual))
        else:
            report.ok()


def appear_check_recipe(report):
    rule = "recipe"
    data = appear_read_json(appear_RECIPE, rule, report)
    if data is None:
        return
    if not isinstance(data, dict):
        report.fail(rule, "%s: top level must be an object" % appear_rel(appear_RECIPE))
        return
    if data.get("type") != "minecraft:crafting_shaped":
        report.fail(rule, "%s: type must be 'minecraft:crafting_shaped', got %r" % (appear_rel(appear_RECIPE), data.get("type")))
    pattern = data.get("pattern")
    if not isinstance(pattern, list) or len(pattern) != 3:
        report.fail(rule, "%s: 'pattern' must be three rows" % appear_rel(appear_RECIPE))
    else:
        for row in pattern:
            if not isinstance(row, str) or len(row) != 3:
                report.fail(rule, "%s: every pattern row must be three characters, got %r" % (appear_rel(appear_RECIPE), row))
    keys = data.get("key")
    if not isinstance(keys, dict) or not keys:
        report.fail(rule, "%s: 'key' must map every pattern symbol" % appear_rel(appear_RECIPE))
    else:
        symbols = set()
        if isinstance(pattern, list):
            for row in pattern:
                if isinstance(row, str):
                    symbols.update(character for character in row if character != " ")
        unmapped = sorted(symbol for symbol in symbols if symbol not in keys)
        if unmapped:
            report.fail(rule, "%s: pattern symbol(s) %s have no 'key' entry"
                        % (appear_rel(appear_RECIPE), ", ".join(unmapped)))
        else:
            report.ok()
    result = data.get("result")
    item = result.get("item") if isinstance(result, dict) else result
    if item != "%s:%s" % (appear_MODID, appear_ITEM_ID):
        report.fail(rule, "%s: result must be '%s:%s', got %r" % (appear_rel(appear_RECIPE), appear_MODID, appear_ITEM_ID, item))
    else:
        report.ok()


def appear_check_appearance(report):
    rule = "item-appearance"
    data = appear_read_json(appear_APPEARANCE, rule, report)
    if data is None:
        return
    if not isinstance(data, dict):
        report.fail(rule, "%s: top level must be an object" % appear_rel(appear_APPEARANCE))
        return
    items = data.get("items")
    if not isinstance(items, dict):
        report.fail(rule, "%s: 'items' must be an object mapping item ids to model ids" % appear_rel(appear_APPEARANCE))
        return
    for key in sorted(items):
        if not isinstance(key, str) or key.count(":") != 1:
            report.fail(rule, "%s: %r is not a namespace:path item id" % (appear_rel(appear_APPEARANCE), key))
    report.ok()


def appear_check_kubejs(report, seen):
    rule = "kubejs-example"
    data = appear_read_json(appear_KUBEJS_MODEL, rule, report)
    if data is not None:
        if not isinstance(data, dict) or not isinstance(data.get("textures"), dict):
            report.fail(rule, "%s: expected a model with a 'textures' object" % appear_rel(appear_KUBEJS_MODEL))
        else:
            report.ok()
    appear_check_png(appear_KUBEJS_TEXTURE, "kubejs-examples", rule, report, seen)


def appear_check_lang(report):
    rule = "lang"
    key = "item.%s.%s" % (appear_MODID, appear_ITEM_ID)
    key_sets = {}
    for locale in ("en_us", "zh_cn"):
        path = appear_LANG_DIR / (locale + ".json")
        data = appear_read_json(path, rule, report)
        if data is None or not isinstance(data, dict):
            continue
        key_sets[locale] = set(data)
        if key not in data:
            report.fail(rule, "%s: key '%s' is missing" % (appear_rel(path), key))
        else:
            report.ok()
    if len(key_sets) == 2:
        only_en = sorted(key_sets["en_us"] - key_sets["zh_cn"])
        only_zh = sorted(key_sets["zh_cn"] - key_sets["en_us"])
        if only_en or only_zh:
            report.fail(rule, "key sets differ (only en_us=%s, only zh_cn=%s)" % (only_en, only_zh))
        else:
            report.ok()


def appear_check_java(report):
    rule = "java-entry-points"
    for relative, needles in appear_JAVA_CHECKS:
        path = appear_JAVA / "com" / "structurereset" / relative
        if not path.is_file():
            report.fail(rule, "%s is missing" % appear_rel(path))
            continue
        text = path.read_text(encoding="utf-8", errors="replace")
        missing = [needle for needle in needles if needle not in text]
        if missing:
            report.fail(rule, "%s does not declare: %s" % (appear_rel(path), ", ".join(missing)))
        else:
            report.ok()


def appear_check_distinct(report, seen):
    rule = "textures-distinct"
    by_digest = {}
    for label, digest in seen.items():
        by_digest.setdefault(digest, []).append(label)
    duplicates = [labels for labels in by_digest.values() if len(labels) > 1]
    if duplicates:
        for labels in duplicates:
            report.fail(rule, "identical textures: %s" % ", ".join(sorted(labels)))
        return
    report.ok()


def appear_main() -> int:
    report = appear_Report()
    seen = {}

    base = appear_check_item_model(appear_ITEM_ID, "item-model", report, seen)
    for skin in appear_SKINS:
        appear_check_item_model(skin, "skin-model", report, seen)
    if isinstance(base, dict):
        appear_check_overrides(base, report)
        overrides = base.get("overrides")
        if isinstance(overrides, list):
            for entry in overrides:
                target = entry.get("model") if isinstance(entry, dict) else None
                if isinstance(target, str):
                    ns, _, path = target.partition(":")
                    if ns and path:
                        model_file = appear_RESOURCES / "assets" / ns / "models" / (path + ".json")
                        if not model_file.is_file():
                            report.fail("model-overrides",
                                        "override '%s' has no model file (%s)" % (target, appear_rel(model_file)))
    appear_check_recipe(report)
    appear_check_appearance(report)
    appear_check_kubejs(report, seen)
    appear_check_lang(report)
    appear_check_java(report)
    appear_check_distinct(report, seen)

    if report.problems:
        for rule, detail in report.problems:
            print("FAIL: %s: %s" % (rule, detail))
        print("validate-appearance: %d problem(s)" % len(report.problems))
        return 1
    print("OK: validate-appearance (%d check(s): models=%d textures=%d)"
          % (report.checks, 3, len(seen)))
    return 0




# ==========================================================================
# section 'kubejs' -- KubeJS 集成与插件发现
# ==========================================================================

"""Validate the KubeJS integration of the Structure Reset workspace.

Usage:
    python tools/validate_checks.py kubejs             # exit 0 when every rule passes
    python tools/validate_checks.py kubejs --selftest  # prove each rule rejects an injected defect
    python tools/validate_checks.py kubejs --root DIR  # check another checkout

Rules (the rule id appears in every FAIL line):

    jar-cached        tools/lib/kubejs-forge-<version>.jar exists, is a real zip and
                      matches the kubejs_version pinned in gradle.properties
    discovery         the plugin discovery file name is *measured* from that jar
                      (the same scanner the 'kubejs-fetch' section uses) and the matching
                      resource file exists under src/main/resources
    plugin-listed     the discovery file names a plugin class, and that class
                      exists as a .java source whose package/class match
    plugin-api        StructureResetKubeJSPlugin implements KubeJSPlugin and
                      declares registerBindings(...)
    bindings-api      the StructureReset binding exposes register/remove/list/
                      reload/registerAction/setItemModel/clearItemModel
    events-api        the StructureResetEvents binding exposes onPreReset/onPostReset
    example-scripts   at least 4 .js under kubejs-examples/, covering at least
                      3 different binding names
    no-mixin          no Mixin is used anywhere under src/ (project constraint)
"""


import argparse
import re
import shutil
import sys
import zipfile
from pathlib import Path

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    sys.stderr.reconfigure(encoding="utf-8", errors="replace")
except Exception:  # pragma: no cover
    pass

kjs_SCRIPT = Path(__file__).resolve()
kjs_WORKSPACE = kjs_SCRIPT.parent.parent

sys.path.insert(0, str(kjs_SCRIPT.parent))
import toolchain  # noqa: E402  (sibling module: shared scratch directories)

# Where the two candidate discovery artefacts live inside a mod jar.  The names
# come from the shared KUBEJS_* constants at the top of this file; the byte
# scanner in the 'kubejs-fetch' section below is the one both sections use.
kjs_DISCOVERY_LOCATIONS = {
    KUBEJS_PLUGINS_TXT: Path(KUBEJS_PLUGINS_TXT),
    KUBEJS_SERVICE_PATH: Path(KUBEJS_SERVICE_PATH),
}

kjs_PLUGIN_CLASS_FQN = "com.structurereset.kubejs.StructureResetKubeJSPlugin"
kjs_PLUGIN_SOURCE = Path("src/main/java/com/structurereset/kubejs/StructureResetKubeJSPlugin.java")
kjs_BINDING_SOURCES = {
    "plugin": Path("src/main/java/com/structurereset/kubejs/StructureResetKubeJSPlugin.java"),
    "bindings": Path("src/main/java/com/structurereset/kubejs/StructureResetBindings.java"),
    "events": Path("src/main/java/com/structurereset/kubejs/StructureResetEvents.java"),
    "reset-event": Path("src/main/java/com/structurereset/kubejs/ResetEventJS.java"),
    "reset-context": Path("src/main/java/com/structurereset/kubejs/ResetContextJS.java"),
}

kjs_EXAMPLE_DIR = Path("kubejs-examples")

# Binding names a pack author may use; the rule needs 3 different ones.
kjs_KNOWN_BINDINGS = ("StructureReset", "StructureResetEvents", "ServerEvents",
                  "ItemEvents", "PlayerEvents", "EntityEvents", "BlockEvents")

kjs_MIN_EXAMPLES = 4
kjs_MIN_BINDINGS = 3


def kjs_read_text(path: Path) -> str:
    return path.read_text(encoding="utf-8", errors="replace")


class kjs_Checker:
    def __init__(self, root: Path):
        self.root = Path(root)
        self.failures = []
        self.passed = 0
        self.notes = []

    # ------------------------------------------------------------ plumbing

    def fail(self, rule, detail):
        self.failures.append("%s: %s" % (rule, detail))

    def pass_rule(self, rule):
        self.passed += 1

    def guard(self, rule, func, default=None):
        try:
            return func()
        except Exception as exc:  # a broken rule must never look like a pass
            self.fail(rule, "rule raised %s: %s" % (type(exc).__name__, exc))
            return default

    def text(self, relative):
        path = self.root / relative
        if not path.is_file():
            return None
        return kjs_read_text(path)

    def gradle_property(self, key):
        text = self.text("gradle.properties") or ""
        match = re.search(r"(?m)^\s*" + re.escape(key) + r"\s*=\s*(.+?)\s*$", text)
        return match.group(1) if match else None

    # --------------------------------------------------------------- rules

    def jar_path(self):
        version = self.gradle_property("kubejs_version") or KUBEJS_VERSION
        return self.root / "tools" / "lib" / ("kubejs-forge-%s.jar" % version)

    def rule_jar_cached(self):
        rule = "jar-cached"
        jar = self.jar_path()
        if not jar.is_file():
            self.fail(rule, "%s is missing (run python tools/validate_checks.py kubejs-fetch)" % jar)
            return None
        if not jar.is_file() or jar.stat().st_size == 0:
            self.fail(rule, "%s is empty" % jar)
            return None
        try:
            with zipfile.ZipFile(jar) as archive:
                bad = archive.testzip()
                if bad is not None:
                    self.fail(rule, "%s has a corrupt entry: %s" % (jar, bad))
                    return None
        except zipfile.BadZipFile as exc:
            self.fail(rule, "%s is not a readable zip (%s) -- re-run tools/validate_checks.py kubejs-fetch"
                      % (jar, exc))
            return None
        version = self.gradle_property("kubejs_version")
        if version and ("kubejs-forge-%s.jar" % version) != jar.name:
            self.fail(rule, "gradle.properties pins kubejs_version=%s but %s was inspected"
                      % (version, jar.name))
            return None
        self.notes.append("jar=%s (%d bytes)" % (jar.name, jar.stat().st_size))
        self.pass_rule(rule)
        return jar

    def rule_discovery(self, jar):
        rule = "discovery"
        if jar is None:
            self.fail(rule, "skipped: no usable KubeJS jar")
            return None
        hits, shipped = fetch_scan_jar(jar)
        names = set(needle for _, needle in hits)
        if not names:
            self.fail(rule, "no class in %s references any known discovery file name (%s)"
                      % (jar.name, ", ".join(sorted(kjs_DISCOVERY_LOCATIONS))))
            return None
        measured = sorted(names)
        # The resource the mod must ship: prefer kubejs.plugins.txt when both are
        # referenced, because that is the file KubeJS 2001.x actually lists in.
        if KUBEJS_PLUGINS_TXT in names:
            chosen = KUBEJS_PLUGINS_TXT
        else:
            chosen = KUBEJS_SERVICE_PATH
        self.notes.append("measured discovery names=%s chosen=%s" % (measured, chosen))
        location = kjs_DISCOVERY_LOCATIONS[chosen]
        resource = self.root / "src" / "main" / "resources" / location
        if not resource.is_file():
            self.fail(rule, "measured discovery artefact '%s' is not shipped: %s is missing"
                      % (chosen, resource))
            return None
        listed = [line.strip() for line in kjs_read_text(resource).splitlines()]
        listed = [line for line in listed if line and not line.startswith("#")]
        if kjs_PLUGIN_CLASS_FQN not in listed:
            self.fail(rule, "%s does not list the plugin class %s (found: %s)"
                      % (location, kjs_PLUGIN_CLASS_FQN, listed or "nothing"))
            return None
        self.notes.append("shipped=%s" % location.as_posix())
        self.pass_rule(rule)
        return listed

    def rule_plugin_listed(self, listed):
        rule = "plugin-listed"
        if not listed:
            self.fail(rule, "skipped: no discovery file")
            return
        source = self.text(kjs_PLUGIN_SOURCE)
        if source is None:
            self.fail(rule, "%s is missing" % kjs_PLUGIN_SOURCE.as_posix())
            return
        package = "package " + kjs_PLUGIN_CLASS_FQN.rsplit(".", 1)[0] + ";"
        if package not in source:
            self.fail(rule, "%s does not declare '%s'" % (kjs_PLUGIN_SOURCE.as_posix(), package))
            return
        simple = kjs_PLUGIN_CLASS_FQN.rsplit(".", 1)[1]
        if not re.search(r"class\s+" + re.escape(simple) + r"\b", source):
            self.fail(rule, "%s does not declare class %s" % (kjs_PLUGIN_SOURCE.as_posix(), simple))
            return
        self.pass_rule(rule)

    def rule_plugin_api(self):
        rule = "plugin-api"
        source = self.text(kjs_PLUGIN_SOURCE)
        if source is None:
            self.fail(rule, "%s is missing" % kjs_PLUGIN_SOURCE.as_posix())
            return
        if "KubeJSPlugin" not in source:
            self.fail(rule, "%s does not mention KubeJSPlugin" % kjs_PLUGIN_SOURCE.as_posix())
            return
        # Measured: dev.latvian.mods.kubejs.KubeJSPlugin is a *class* in KubeJS
        # 2001.x (javap on the real jar), so extending it is the correct form;
        # "implements" is accepted too in case a future version turns it into an
        # interface.
        if not re.search(r"(?:implements|extends)\s+[^{]*KubeJSPlugin", source):
            self.fail(rule, "%s neither extends nor implements KubeJSPlugin"
                      % kjs_PLUGIN_SOURCE.as_posix())
            return
        if not re.search(r"registerBindings\s*\(\s*BindingsEvent", source):
            self.fail(rule, "%s declares no registerBindings(BindingsEvent)" % kjs_PLUGIN_SOURCE.as_posix())
            return
        self.pass_rule(rule)

    def _declares(self, source, names):
        missing = [n for n in names if not re.search(r"\b" + re.escape(n) + r"\s*\(", source)]
        return missing

    def rule_bindings_api(self):
        rule = "bindings-api"
        path = kjs_BINDING_SOURCES["bindings"]
        source = self.text(path)
        if source is None:
            self.fail(rule, "%s is missing" % path.as_posix())
            return
        needed = ("register", "remove", "list", "reload", "registerAction",
                  "setItemModel", "clearItemModel")
        missing = self._declares(source, needed)
        if missing:
            self.fail(rule, "%s does not declare %s" % (path.as_posix(), ", ".join(missing)))
            return
        if "StructureReset" not in source:
            self.fail(rule, "%s does not expose the global name StructureReset" % path.as_posix())
            return
        self.pass_rule(rule)

    def rule_events_api(self):
        rule = "events-api"
        for key, needed in (("events", ("onPreReset", "onPostReset")),):
            path = kjs_BINDING_SOURCES[key]
            source = self.text(path)
            if source is None:
                self.fail(rule, "%s is missing" % path.as_posix())
                return
            # The handler names are the *script* names of the EventHandler fields
            # (GROUP.server("onPreReset", ...)), so the quoted name is what is
            # checked here -- KubeJS turns the field into the callable script
            # member, not a Java method with that name.  A quoted-literal check
            # also rejects renaming the member while leaving the javadoc alone.
            missing = [n for n in needed if ('"' + n + '"') not in source]
            if missing:
                self.fail(rule, "%s does not mention %s" % (path.as_posix(), ", ".join(missing)))
                return
            if "EventGroup" not in source or "EventHandler" not in source:
                self.fail(rule, "%s does not declare the KubeJS event group/handlers"
                          % path.as_posix())
                return
        for key in ("reset-event", "reset-context"):
            path = kjs_BINDING_SOURCES[key]
            source = self.text(path)
            if source is None:
                self.fail(rule, "%s is missing" % path.as_posix())
                return
            if not re.search(r"class\s+\w+", source):
                self.fail(rule, "%s declares no class" % path.as_posix())
                return
        event = self.text(kjs_BINDING_SOURCES["reset-event"]) or ""
        for member in ("cancel", "setMessage", "actionsRun", "definition"):
            if member not in event:
                self.fail(rule, "ResetEventJS does not expose '%s'" % member)
                return
        context = self.text(kjs_BINDING_SOURCES["reset-context"]) or ""
        for member in ("level", "player", "structure", "origin", "box", "success"):
            if member not in context:
                self.fail(rule, "ResetContextJS does not expose '%s'" % member)
                return
        self.pass_rule(rule)

    def rule_example_scripts(self):
        rule = "example-scripts"
        root = self.root / kjs_EXAMPLE_DIR
        if not root.is_dir():
            self.fail(rule, "%s is missing" % kjs_EXAMPLE_DIR.as_posix())
            return
        scripts = sorted(p for p in root.rglob("*.js") if p.is_file())
        if len(scripts) < kjs_MIN_EXAMPLES:
            self.fail(rule, "found %d .js file(s) under %s, need at least %d"
                      % (len(scripts), kjs_EXAMPLE_DIR.as_posix(), kjs_MIN_EXAMPLES))
            return
        for sub in ("server_scripts", "startup_scripts"):
            if not (root / sub).is_dir():
                self.fail(rule, "%s/%s is missing" % (kjs_EXAMPLE_DIR.as_posix(), sub))
                return
        used = set()
        for path in scripts:
            body = kjs_read_text(path)
            for name in kjs_KNOWN_BINDINGS:
                if re.search(r"\b" + re.escape(name) + r"\b", body):
                    used.add(name)
        if len(used) < kjs_MIN_BINDINGS:
            self.fail(rule, "the %d example scripts only use %d binding name(s) (%s), need %d"
                      % (len(scripts), len(used), ", ".join(sorted(used)) or "none", kjs_MIN_BINDINGS))
            return
        comments = [p for p in scripts if re.search(r"(?m)^\s*//", kjs_read_text(p))]
        if len(comments) != len(scripts):
            self.fail(rule, "%d of %d example scripts carry no // comment"
                      % (len(scripts) - len(comments), len(scripts)))
            return
        self.notes.append("examples=%d bindings=%s" % (len(scripts), ",".join(sorted(used))))
        self.pass_rule(rule)

    def rule_no_mixin(self):
        rule = "no-mixin"
        root = self.root / "src"
        offenders = []
        if root.is_dir():
            for path in root.rglob("*"):
                if not path.is_file():
                    continue
                if path.suffix not in (".java", ".json", ".toml", ".properties", ".mcmeta"):
                    continue
                body = kjs_read_text(path)
                if "@Mixin" in body or "org.spongepowered" in body or "mixins.json" in body:
                    offenders.append(path.relative_to(self.root).as_posix())
        if offenders:
            self.fail(rule, "Mixin found in: %s" % ", ".join(sorted(offenders)))
            return
        self.pass_rule(rule)

    # --------------------------------------------------------------- drive

    def run(self):
        if not self.root.is_dir():
            print("FAIL: workspace: %s is not a directory" % self.root)
            return 1
        jar = self.guard("jar-cached", self.rule_jar_cached)
        listed = self.guard("discovery", lambda: self.rule_discovery(jar))
        self.guard("plugin-listed", lambda: self.rule_plugin_listed(listed))
        self.guard("plugin-api", self.rule_plugin_api)
        self.guard("bindings-api", self.rule_bindings_api)
        self.guard("events-api", self.rule_events_api)
        self.guard("example-scripts", self.rule_example_scripts)
        self.guard("no-mixin", self.rule_no_mixin)
        if self.failures:
            for line in self.failures:
                print("FAIL: " + line)
            print("FAILED: kubejs (%d failure(s), %d rule(s) passed)" % (len(self.failures), self.passed))
            return 1
        for note in self.notes:
            print("kubejs: " + note)
        print("OK: kubejs (%d rule(s) passed)" % self.passed)
        return 0


# ------------------------------------------------------------------ selftest

kjs__REQUIRED = [
    "gradle.properties",
    "tools/validate_checks.py",
    "src/main/resources/" + KUBEJS_PLUGINS_TXT,
    "src/main/java/com/structurereset/kubejs/StructureResetKubeJSPlugin.java",
    "src/main/java/com/structurereset/kubejs/StructureResetBindings.java",
    "src/main/java/com/structurereset/kubejs/StructureResetEvents.java",
    "src/main/java/com/structurereset/kubejs/ResetEventJS.java",
    "src/main/java/com/structurereset/kubejs/ResetContextJS.java",
    "kubejs-examples/server_scripts/01_custom_definition.js",
    "kubejs-examples/server_scripts/02_recipe_overrides.js",
    "kubejs-examples/server_scripts/03_events_and_actions.js",
    "kubejs-examples/startup_scripts/04_item_appearance.js",
]


def kjs__copy_tree(source: Path, target: Path) -> None:
    """Copy everything a run needs, plus the cached jar, into a scratch root."""
    for relative in kjs__REQUIRED:
        src = source / relative
        if not src.is_file():
            continue
        dst = target / relative
        dst.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(src, dst)
    (target / "src" / "main" / "resources").mkdir(parents=True, exist_ok=True)
    for png in (source / "kubejs-examples").rglob("*.js"):
        relative = png.relative_to(source)
        dst = target / relative
        dst.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(png, dst)
    lib = target / "tools" / "lib"
    lib.mkdir(parents=True, exist_ok=True)
    for jar in (source / "tools" / "lib").glob("kubejs-forge-*.jar"):
        shutil.copyfile(jar, lib / jar.name)


def kjs__edit(path: Path, old: str, new: str, count: int = 1) -> None:
    text = kjs_read_text(path)
    if old not in text:
        raise AssertionError("defect anchor missing in %s: %r" % (path.name, old))
    path.write_text(text.replace(old, new, count), encoding="utf-8")


def kjs__defects():
    plugin = "src/main/java/com/structurereset/kubejs/StructureResetKubeJSPlugin.java"
    bindings = "src/main/java/com/structurereset/kubejs/StructureResetBindings.java"
    events = "src/main/java/com/structurereset/kubejs/StructureResetEvents.java"
    context = "src/main/java/com/structurereset/kubejs/ResetContextJS.java"

    def missing_jar(root):
        for jar in (root / "tools" / "lib").glob("kubejs-forge-*.jar"):
            jar.unlink()

    def truncated_jar(root):
        for jar in (root / "tools" / "lib").glob("kubejs-forge-*.jar"):
            jar.write_bytes(b"PK\x03\x04" + b"\x00" * 64)

    def missing_discovery_file(root):
        (root / "src/main/resources" / KUBEJS_PLUGINS_TXT).unlink()

    def discovery_lists_wrong_class(root):
        path = root / "src/main/resources" / KUBEJS_PLUGINS_TXT
        path.write_text("com.example.NotOurPlugin\n", encoding="utf-8")

    def missing_plugin_source(root):
        (root / plugin).unlink()

    def plugin_not_a_kubejs_plugin(root):
        kjs__edit(root / plugin, "extends KubeJSPlugin", "extends Object")

    def missing_register_bindings(root):
        kjs__edit(root / plugin, "void registerBindings(BindingsEvent event)",
              "void notRegisteringBindings(BindingsEvent event)")

    def missing_binding_method(root):
        kjs__edit(root / bindings, "public static boolean setItemModel",
              "public static boolean setItemModell")

    def missing_event_method(root):
        kjs__edit(root / events, 'GROUP.server("onPostReset"', 'GROUP.server("onAfterReset"')

    def missing_context_member(root):
        kjs__edit(root / context, "box", "boundingBox")

    def too_few_examples(root):
        # Delete down to MIN_EXAMPLES - 1 instead of naming two files: the
        # example set is allowed to grow, and a defect that deletes a fixed
        # pair stops proving anything as soon as it does.
        scripts = sorted(p for p in (root / "kubejs-examples").rglob("*.js") if p.is_file())
        for path in scripts[kjs_MIN_EXAMPLES - 1:]:
            path.unlink()

    def one_binding_only(root):
        for path in (root / "kubejs-examples").rglob("*.js"):
            text = kjs_read_text(path)
            for name in ("StructureResetEvents", "ServerEvents", "ItemEvents"):
                text = text.replace(name, "StructureReset")
            path.write_text(text, encoding="utf-8")

    def mixin_appears(root):
        path = root / plugin
        text = kjs_read_text(path)
        path.write_text("@Mixin(StructureResetMod.class)\n" + text, encoding="utf-8")

    return (
        ("missing-jar", missing_jar),
        ("truncated-jar", truncated_jar),
        ("missing-discovery-file", missing_discovery_file),
        ("discovery-lists-wrong-class", discovery_lists_wrong_class),
        ("missing-plugin-source", missing_plugin_source),
        ("plugin-not-a-kubejs-plugin", plugin_not_a_kubejs_plugin),
        ("missing-register-bindings", missing_register_bindings),
        ("missing-binding-method", missing_binding_method),
        ("missing-event-method", missing_event_method),
        ("missing-context-member", missing_context_member),
        ("too-few-examples", too_few_examples),
        ("one-binding-only", one_binding_only),
        ("mixin-appears", mixin_appears),
    )


def kjs__quiet_exit_code(root: Path) -> int:
    import contextlib
    import io
    buffer = io.StringIO()
    with contextlib.redirect_stdout(buffer):
        return kjs_Checker(root).run()


def kjs_selftest() -> int:
    ok = True
    tmp = toolchain.make_scratch_dir("structurereset-kubejs-validator", kjs_WORKSPACE)
    try:
        good = tmp / "good"
        kjs__copy_tree(kjs_WORKSPACE, good)
        code = kjs__quiet_exit_code(good)
        if code != 0:
            print("FAIL: selftest: the real workspace was rejected (exit %d)" % code)
            ok = False
        else:
            print("selftest: %-30s -> exit 0 (accepted)" % "real-workspace")

        for name, defect in kjs__defects():
            target = tmp / ("bad-" + name)
            kjs__copy_tree(kjs_WORKSPACE, target)
            try:
                defect(target)
            except AssertionError as exc:
                print("FAIL: selftest: defect %s could not be injected (%s)" % (name, exc))
                ok = False
                continue
            code = kjs__quiet_exit_code(target)
            if code == 0:
                print("FAIL: selftest: defect '%s' was accepted (exit 0)" % name)
                ok = False
            else:
                print("selftest: %-30s -> exit %d (rejected)" % (name, code))

        empty = tmp / "empty"
        empty.mkdir(parents=True, exist_ok=True)
        if kjs__quiet_exit_code(empty) == 0:
            print("FAIL: selftest: an empty root was accepted")
            ok = False
        else:
            print("selftest: %-30s -> rejected" % "empty-root")
    finally:
        shutil.rmtree(tmp, ignore_errors=True)

    print("validate-kubejs selftest: " + ("OK" if ok else "FAILED"))
    return 0 if ok else 1


def kjs_main(argv) -> int:
    parser = argparse.ArgumentParser(description="Check the KubeJS integration of the Structure Reset workspace.")
    parser.add_argument("--selftest", action="store_true", help="check the checker itself")
    parser.add_argument("--root", default=None, help="project root (defaults to this script's parent)")
    args = parser.parse_args(argv)

    if args.selftest:
        return kjs_selftest()

    root = Path(args.root).resolve() if args.root else kjs_WORKSPACE
    return kjs_Checker(root).run()




# ==========================================================================
# section 'docs' -- 文档结构与引用一致性
# ==========================================================================

"""Validate the documentation of the Structure Reset workspace.

Usage:
    python tools/validate_checks.py docs             # exit 0 when every rule passes
    python tools/validate_checks.py docs --selftest  # prove each rule rejects an injected defect
    python tools/validate_checks.py docs --root DIR  # check another checkout

Rules (the rule id appears in every FAIL line):

    docs-exist     README.md and docs/{API,DATAPACK,KUBEJS,RESOURCEPACK}.md exist,
                   are non-empty and actually look like markdown
    docs-sections  every document carries the sections it promises: README keeps the
                   "this workspace never ran Gradle" note, DATAPACK covers the
                   structure/actions fields and the respawn_boss/refill_loot actions,
                   KUBEJS covers ServerEvents.recipes, onPreReset and kubejs/assets,
                   and a document that is only named by the rule tables, such as
                   docs/BUILD.md, is checked as soon as it exists
    docs-code      every document contains at least one fenced code block, and the
                   reference documents contain at least two
"""


import argparse
import re
import shutil
import sys
from pathlib import Path

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    sys.stderr.reconfigure(encoding="utf-8", errors="replace")
except Exception:  # pragma: no cover
    pass

doc_SCRIPT = Path(__file__).resolve()
doc_WORKSPACE = doc_SCRIPT.parent.parent

sys.path.insert(0, str(doc_SCRIPT.parent))
import toolchain  # noqa: E402  (sibling module: shared scratch directories)

doc_README = "README.md"
doc_DOCS = {
    "docs/README.md": "README (index)",
    "docs/API.md": "API",
    "docs/BUILD.md": "BUILD",
    "docs/DATAPACK.md": "DATAPACK",
    "docs/KUBEJS.md": "KUBEJS",
    "docs/LEARNING.md": "LEARNING",
    "docs/RESOURCEPACK.md": "RESOURCEPACK",
}

# Substrings every document must contain (case sensitive on purpose: these are
# identifiers, not prose).
doc_REQUIRED = {
    doc_README: ("安装", "依赖", "快速上手", "已知限制", "docs/DATAPACK.md"),
    "docs/API.md": ("com.structurereset.api", "ResetDefinition", "ResetActionSpec",
                    "StructureResetApi", "ItemAppearanceApi", "ResetContext"),
    "docs/DATAPACK.md": ("structure", "actions", "respawn_boss", "refill_loot",
                         "cooldown_seconds", "anchor_blocks", "priority",
                         "in_structure", "per_player", "empty_only"),
    "docs/KUBEJS.md": ("ServerEvents.recipes", "onPreReset", "kubejs/assets",
                       "StructureReset", "kubejs.plugins.txt", "registerAction"),
    "docs/RESOURCEPACK.md": ("textures/item", "models/item", "pack.mcmeta",
                             "kubejs/assets"),
    "docs/LEARNING.md": ("learningRadius", "learningBossTags", "teach-here",
                         "LearnedAnchor", "无法还原"),
    "docs/BUILD.md": ("check_all.py", "gradlew", "build/libs", "JDK"),
    "docs/README.md": ("DATAPACK.md", "LEARNING.md", "KUBEJS.md", "RESOURCEPACK.md",
                       "API.md", "BUILD.md"),
}

# Extra prose-level requirements that may be phrased in a couple of ways.
doc_ALTERNATIVES = {
    # How to build is the build document's job; the README stays user facing.
    "docs/BUILD.md": (("Gradle",), ("gradlew", "check_all.py")),
    "docs/DATAPACK.md": (("推断", "推测"),),
    "docs/KUBEJS.md": (("发现机制", "discovery"),),
}

doc_MIN_CODE_BLOCKS = {
    doc_README: 1,
    "docs/README.md": 0,          # an index page: links, not snippets
    "docs/API.md": 2,
    "docs/BUILD.md": 2,
    "docs/DATAPACK.md": 2,
    "docs/KUBEJS.md": 2,
    "docs/LEARNING.md": 2,
    "docs/RESOURCEPACK.md": 2,
}

doc_MIN_BYTES = 400
doc_FENCE = re.compile(r"(?m)^\s*```")
doc_HEADING = re.compile(r"(?m)^#\s+\S")


def doc_read_text(path: Path) -> str:
    return path.read_text(encoding="utf-8", errors="replace")


class doc_Checker:
    def __init__(self, root: Path):
        self.root = Path(root)
        self.failures = []
        self.passed = 0
        self.notes = []

    def fail(self, rule, detail):
        self.failures.append("%s: %s" % (rule, detail))

    def pass_rule(self, rule):
        self.passed += 1

    def guard(self, rule, func):
        try:
            return func()
        except Exception as exc:
            self.fail(rule, "rule raised %s: %s" % (type(exc).__name__, exc))

    def rule_docs_exist(self):
        rule = "docs-exist"
        targets = [doc_README] + sorted(doc_DOCS)
        texts = {}
        for relative in targets:
            path = self.root / relative
            if not path.is_file():
                self.fail(rule, "%s is missing" % relative)
                continue
            body = doc_read_text(path)
            if len(body.encode("utf-8")) < doc_MIN_BYTES:
                self.fail(rule, "%s is suspiciously short (%d bytes)" % (relative, len(body)))
                continue
            if not doc_HEADING.search(body):
                self.fail(rule, "%s has no '# ' heading" % relative)
                continue
            texts[relative] = body
        if not self.failures:
            self.notes.append("documents=%d" % len(texts))
            self.pass_rule(rule)
        return texts

    def documents_in_scope(self, texts):
        """texts plus every document the tables name that is present on disk.

        docs-exist keeps its documented scope (README plus DOCS), but a rule
        table entry must not be skipped just because its document is not in
        DOCS: docs/BUILD.md is named only in ALTERNATIVES, and without this the
        rule that guards its build instructions could never fire.
        """
        scope = dict(texts)
        for relative in sorted((set(doc_REQUIRED) | set(doc_ALTERNATIVES)) - set(scope)):
            path = self.root / relative
            if path.is_file():
                scope[relative] = doc_read_text(path)
        return scope

    def rule_docs_sections(self, texts):
        rule = "docs-sections"
        if not texts:
            self.fail(rule, "skipped: no readable documents")
            return
        for relative, body in sorted(self.documents_in_scope(texts).items()):
            for needle in doc_REQUIRED.get(relative, ()):
                if needle not in body:
                    self.fail(rule, "%s does not mention '%s'" % (relative, needle))
            for group in doc_ALTERNATIVES.get(relative, ()):
                if not any(alternative in body for alternative in group):
                    self.fail(rule, "%s mentions none of %s"
                              % (relative, ", ".join("'%s'" % a for a in group)))
        if not any(line.startswith(rule + ":") for line in self.failures):
            self.pass_rule(rule)

    def rule_docs_code(self, texts):
        rule = "docs-code"
        if not texts:
            self.fail(rule, "skipped: no readable documents")
            return
        for relative, body in sorted(texts.items()):
            blocks = len(doc_FENCE.findall(body))
            if blocks % 2 != 0:
                self.fail(rule, "%s has an unbalanced number of code fences (%d)" % (relative, blocks))
                continue
            pairs = blocks // 2
            if pairs < doc_MIN_CODE_BLOCKS.get(relative, 1):
                self.fail(rule, "%s has %d fenced code block(s), need at least %d"
                          % (relative, pairs, doc_MIN_CODE_BLOCKS.get(relative, 1)))
                continue
            self.notes.append("%s code-blocks=%d" % (relative, pairs))
        if not any(line.startswith(rule + ":") for line in self.failures):
            self.pass_rule(rule)

    def rule_docs_referenced(self):
        """Every document on disk must be reachable from an index page.

        One home per fact only works while readers can find the home: a
        docs/*.md that no index names is the file that goes stale unnoticed.
        """
        rule = "docs-referenced"
        indexes = []
        for relative in (doc_README, "docs/README.md"):
            path = self.root / relative
            if path.is_file():
                indexes.append((relative, doc_read_text(path)))
        if not indexes:
            self.fail(rule, "neither README.md nor docs/README.md exists")
            return
        directory = self.root / "docs"
        documents = sorted(directory.glob("*.md")) if directory.is_dir() else []
        if not documents:
            self.fail(rule, "docs/ holds no markdown file")
            return
        referenced = 0
        for path in documents:
            relative = "docs/" + path.name
            if relative == "docs/README.md":
                continue
            if not any(relative in body or path.name in body for _, body in indexes):
                self.fail(rule, "%s is referenced from neither README.md nor docs/README.md"
                          % relative)
                continue
            referenced += 1
        if referenced == 0:
            self.fail(rule, "no document is referenced from an index page")
            return
        self.notes.append("referenced docs=%d" % referenced)
        self.pass_rule(rule)

    def run(self):
        if not self.root.is_dir():
            print("FAIL: docs-exist: %s is not a directory" % self.root)
            return 1
        texts = self.guard("docs-exist", self.rule_docs_exist) or {}
        self.guard("docs-sections", lambda: self.rule_docs_sections(texts))
        self.guard("docs-code", lambda: self.rule_docs_code(texts))
        self.guard("docs-referenced", self.rule_docs_referenced)
        if self.failures:
            for line in self.failures:
                print("FAIL: " + line)
            print("FAILED: docs (%d failure(s), %d rule(s) passed)"
                  % (len(self.failures), self.passed))
            return 1
        for note in self.notes:
            print("docs: " + note)
        print("OK: docs (%d rule(s) passed)" % self.passed)
        return 0


# ------------------------------------------------------------------ selftest

def doc__copy_tree(source: Path, target: Path) -> None:
    """Copy every document the rules look at into a selftest sandbox.

    The set is the union of the tables the rules are built from, not just DOCS:
    docs/BUILD.md is named in ALTERNATIVES and by a defect of its own, and
    leaving it out made that defect crash the selftest instead of testing the
    rule it exists for.
    """
    relatives = {doc_README} | set(doc_DOCS) | set(doc_REQUIRED) | set(doc_ALTERNATIVES)
    for relative in sorted(relatives):
        src = source / relative
        if src.is_file():
            dst = target / relative
            dst.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(src, dst)


def doc__edit(path: Path, old: str, new: str, count: int = 1) -> None:
    text = doc_read_text(path)
    if old not in text:
        raise AssertionError("defect anchor missing in %s: %r" % (path.name, old))
    path.write_text(text.replace(old, new, count), encoding="utf-8")


def doc__defects():
    def missing_readme(root):
        (root / doc_README).unlink()

    def missing_kubejs_doc(root):
        (root / "docs/KUBEJS.md").unlink()

    def empty_datapack(root):
        (root / "docs/DATAPACK.md").write_text("", encoding="utf-8")

    def build_doc_loses_gradle_note(root):
        path = root / "docs/BUILD.md"
        if not path.is_file():
            raise AssertionError("docs/BUILD.md is missing from the sandbox")
        text = doc_read_text(path)
        for phrase in ("check_all.py", "gradlew", "Gradle"):
            text = text.replace(phrase, "")
        path.write_text(text, encoding="utf-8")

    def datapack_without_actions_section(root):
        path = root / "docs/DATAPACK.md"
        # Replace with a name that no longer *contains* the original, otherwise
        # the substring rule would still match and the defect would pass.
        text = doc_read_text(path).replace("respawn_boss", "respawn")
        text = text.replace("refill_loot", "refill")
        path.write_text(text, encoding="utf-8")

    def kubejs_without_recipe_hook(root):
        path = root / "docs/KUBEJS.md"
        text = doc_read_text(path).replace("ServerEvents.recipes", "ServerEventsRecipes")
        text = text.replace("onPreReset", "onResetPre")
        path.write_text(text, encoding="utf-8")

    def resourcepack_without_texture_path(root):
        path = root / "docs/RESOURCEPACK.md"
        text = doc_read_text(path).replace("textures/item", "textures/thing")
        path.write_text(text, encoding="utf-8")

    def api_without_code_block(root):
        path = root / "docs/API.md"
        text = doc_read_text(path)
        lines = [line for line in text.splitlines(keepends=True)
                 if not line.lstrip().startswith("```")]
        path.write_text("".join(lines) + "\n", encoding="utf-8")

    def unbalanced_fences(root):
        path = root / "docs/KUBEJS.md"
        with path.open("a", encoding="utf-8") as handle:
            handle.write("\n```\n")

    def orphan_document(root):
        # An index page that stops naming a document is how a doc goes stale.
        (root / "docs/ORPHAN.md").write_text(
            "# ORPHAN\n\n" + ("orphan body " * 60) + "\n", encoding="utf-8")

    def index_without_links(root):
        path = root / "docs/README.md"
        if not path.is_file():
            raise AssertionError("docs/README.md is missing from the sandbox")
        path.write_text("# 文档索引\n\n没有链接。\n" + ("x" * 500) + "\n",
                        encoding="utf-8")

    return (
        ("missing-readme", missing_readme),
        ("orphan-document", orphan_document),
        ("index-without-links", index_without_links),
        ("missing-kubejs-doc", missing_kubejs_doc),
        ("empty-datapack", empty_datapack),
        ("build-doc-loses-gradle-note", build_doc_loses_gradle_note),
        ("datapack-without-actions", datapack_without_actions_section),
        ("kubejs-without-recipe-hook", kubejs_without_recipe_hook),
        ("resourcepack-without-texture-path", resourcepack_without_texture_path),
        ("api-without-code-block", api_without_code_block),
        ("unbalanced-fences", unbalanced_fences),
    )


def doc__quiet_exit_code(root: Path) -> int:
    import contextlib
    import io
    buffer = io.StringIO()
    with contextlib.redirect_stdout(buffer):
        return doc_Checker(root).run()


def doc_selftest() -> int:
    ok = True
    tmp = toolchain.make_scratch_dir("structurereset-docs-validator", doc_WORKSPACE)
    try:
        good = tmp / "good"
        doc__copy_tree(doc_WORKSPACE, good)
        code = doc__quiet_exit_code(good)
        if code != 0:
            print("FAIL: selftest: the real documents were rejected (exit %d)" % code)
            ok = False
        else:
            print("selftest: %-32s -> exit 0 (accepted)" % "real-documents")

        for name, defect in doc__defects():
            target = tmp / ("bad-" + name)
            doc__copy_tree(doc_WORKSPACE, target)
            try:
                defect(target)
            except AssertionError as exc:
                print("FAIL: selftest: defect %s could not be injected (%s)" % (name, exc))
                ok = False
                continue
            code = doc__quiet_exit_code(target)
            if code == 0:
                print("FAIL: selftest: defect '%s' was accepted (exit 0)" % name)
                ok = False
            else:
                print("selftest: %-32s -> exit %d (rejected)" % (name, code))

        empty = tmp / "empty"
        empty.mkdir(parents=True, exist_ok=True)
        if doc__quiet_exit_code(empty) == 0:
            print("FAIL: selftest: an empty root was accepted")
            ok = False
        else:
            print("selftest: %-32s -> rejected" % "empty-root")
    finally:
        shutil.rmtree(tmp, ignore_errors=True)

    print("validate-docs selftest: " + ("OK" if ok else "FAILED"))
    return 0 if ok else 1


def doc_main(argv) -> int:
    parser = argparse.ArgumentParser(description="Check the documentation of the Structure Reset workspace.")
    parser.add_argument("--selftest", action="store_true", help="check the checker itself")
    parser.add_argument("--root", default=None, help="project root (defaults to this script's parent)")
    args = parser.parse_args(argv)

    if args.selftest:
        return doc_selftest()

    root = Path(args.root).resolve() if args.root else doc_WORKSPACE
    return doc_Checker(root).run()




# ==========================================================================
# section 'kubejs-fetch' -- 缓存 KubeJS 编译期 jar（唯一联网步骤）
# ==========================================================================

"""Cache the real KubeJS jar and *measure* how it discovers plugins.

Usage:
    python tools/validate_checks.py kubejs-fetch             # download (if needed) + scan, exit 0 on success
    python tools/validate_checks.py kubejs-fetch --scan-only # never touch the network
    python tools/validate_checks.py kubejs-fetch --selftest  # prove the scanner can tell the two cases apart

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


import argparse
import shutil
import sys
import urllib.error
import urllib.request
import zipfile
from pathlib import Path

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    sys.stderr.reconfigure(encoding="utf-8", errors="replace")
except Exception:  # pragma: no cover
    pass

fetch_SCRIPT = Path(__file__).resolve()
fetch_WORKSPACE = fetch_SCRIPT.parent.parent

sys.path.insert(0, str(fetch_SCRIPT.parent))
import toolchain  # noqa: E402  (sibling module: shared scratch directories)
fetch_LIB = fetch_WORKSPACE / "tools" / "lib"

fetch_KUBEJS_VERSION = KUBEJS_VERSION
fetch_KUBEJS_NAME = "kubejs-forge-" + fetch_KUBEJS_VERSION + ".jar"
fetch_KUBEJS_URL = ("https://maven.latvian.dev/releases/dev/latvian/mods/kubejs-forge/"
              + fetch_KUBEJS_VERSION + "/" + fetch_KUBEJS_NAME)
fetch_KUBEJS_JAR = fetch_LIB / fetch_KUBEJS_NAME

fetch_USER_AGENT = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
              "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36")

# The two discovery mechanisms that are plausible for KubeJS 2001.x.  The scan
# reports every one of them so the conclusion is measured, not assumed.
fetch_PLUGINS_TXT = KUBEJS_PLUGINS_TXT
fetch_SERVICE_PATH = KUBEJS_SERVICE_PATH
fetch_CANDIDATES = KUBEJS_CANDIDATES

# Classes whose name suggests they *perform* the discovery (used for reporting).
fetch_SCANNER_HINTS = ("KubeJSPlugin", "PluginManager", "PluginLoader", "KubeJSPlugins",
                 "PluginDiscover")

fetch_ZIP_MAGIC = bytes([0x50, 0x4B, 0x03, 0x04])


def fetch__jar_is_usable(path: Path) -> bool:
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


def fetch__http_get(url: str, offset: int):
    """Open url (optionally resuming at offset) with a browser User-Agent."""
    headers = {
        "User-Agent": fetch_USER_AGENT,
        "Accept": "application/java-archive, application/octet-stream, */*",
        "Accept-Language": "en-US,en;q=0.9",
    }
    if offset > 0:
        headers["Range"] = "bytes=" + str(offset) + "-"
    # maven.latvian.dev occasionally stalls for minutes without closing the
    # socket; a short timeout turns that into a spurious failure, so it is long.
    return urllib.request.urlopen(urllib.request.Request(url, headers=headers), timeout=300)


def fetch_modrinth_fallback(name: str):
    """Resolve the same artefact file name on the Modrinth CDN, or None.

    The canonical source is maven.latvian.dev; when it keeps dropping the
    transfer, the identical release file is fetched from the Modrinth mirror so
    the measurement can still be made.  The file name is matched exactly, and
    the result is validated as a zip before it is cached.
    """
    request = urllib.request.Request(
        "https://api.modrinth.com/v2/project/kubejs/version",
        headers={"User-Agent": fetch_USER_AGENT, "Accept": "application/json"})
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


def fetch_download(dest: Path, urls, attempts: int = 4) -> bool:
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
            response = fetch__http_get(url, have)
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
        if not fetch__jar_is_usable(part):
            print("fetch-kubejs: attempt " + str(attempt) + " produced no usable zip")
            if total is not None and part.stat().st_size >= total:
                part.unlink()
            continue

        part.replace(dest)
        print("fetch-kubejs: cached " + dest.name + " (" + str(dest.stat().st_size) + " bytes)")
        return True

    print("FAIL: fetch-kubejs: no source could deliver a complete jar")
    return False


def fetch_scan_jar(path: Path):
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
            for needle in fetch_CANDIDATES:
                if needle in blob:
                    hits.append((name, needle.decode("utf-8", "replace")))
    return hits, shipped


def fetch_report(path: Path) -> int:
    hits, shipped = fetch_scan_jar(path)
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
        readers = [e for e in by_name[needle] if any(h in e for h in fetch_SCANNER_HINTS)]
        print("fetch-kubejs: " + needle + " -> likely scanner: "
              + (", ".join(sorted(readers)) if readers else "(none matched by name)"))
    return 0


# ------------------------------------------------------------------ selftest

fetch__CAFEBABE = bytes([0xCA, 0xFE, 0xBA, 0xBE, 0x00, 0x00, 0x00, 0x34])
fetch__FAKE_PLUGINS = fetch__CAFEBABE + fetch_PLUGINS_TXT.encode("ascii") + bytes(4)
fetch__FAKE_SERVICE = fetch__CAFEBABE + fetch_SERVICE_PATH.encode("ascii") + bytes(4)
fetch__FAKE_INNOCENT = fetch__CAFEBABE + b"net/minecraft/world/item/Item" + bytes(8)


def fetch_selftest() -> int:
    ok = True
    tmp = toolchain.make_scratch_dir("structurereset-kubejs-selftest", fetch_WORKSPACE)
    try:
        target = tmp / "sample.jar"
        with zipfile.ZipFile(target, "w") as archive:
            archive.writestr("com/example/Plugins.class", fetch__FAKE_PLUGINS)
            archive.writestr("com/example/Service.class", fetch__FAKE_SERVICE)
            archive.writestr("com/example/Item.class", fetch__FAKE_INNOCENT)
            archive.writestr(fetch_PLUGINS_TXT, "com.example.Plugins" + chr(10))

        hits, shipped = fetch_scan_jar(target)
        found = set(needle for _, needle in hits)
        if found != {fetch_PLUGINS_TXT, fetch_SERVICE_PATH}:
            print("FAIL: selftest: scanner missed a discovery name (" + repr(sorted(found)) + ")")
            ok = False
        else:
            print("selftest: " + "synthetic jar".ljust(34) + " -> both names found")
        owners = dict((needle, entry) for entry, needle in hits)
        if owners.get(fetch_PLUGINS_TXT) != "com/example/Plugins.class":
            print("FAIL: selftest: wrong owner for " + fetch_PLUGINS_TXT + " (" + repr(owners) + ")")
            ok = False
        if owners.get(fetch_SERVICE_PATH) != "com/example/Service.class":
            print("FAIL: selftest: wrong owner for the service path (" + repr(owners) + ")")
            ok = False
        if shipped != [fetch_PLUGINS_TXT]:
            print("FAIL: selftest: shipped plugin list not reported (" + repr(shipped) + ")")
            ok = False

        innocent = tmp / "innocent.jar"
        with zipfile.ZipFile(innocent, "w") as archive:
            archive.writestr("com/example/Item.class", fetch__FAKE_INNOCENT)
        hits, _ = fetch_scan_jar(innocent)
        if hits:
            print("FAIL: selftest: an unrelated class was reported (" + repr(hits) + ")")
            ok = False
        else:
            print("selftest: " + "jar without plugins".ljust(34) + " -> no false positive")

        truncated = tmp / "truncated.jar"
        truncated.write_bytes(fetch_ZIP_MAGIC + bytes(32))
        if fetch__jar_is_usable(truncated):
            print("FAIL: selftest: a truncated jar was accepted as usable")
            ok = False
        else:
            print("selftest: " + "truncated jar".ljust(34) + " -> rejected as unusable")

        empty = tmp / "empty.jar"
        with zipfile.ZipFile(empty, "w") as archive:
            archive.writestr("kubejs.plugins.txt", "")
        if fetch_report(empty) == 0:
            print("FAIL: selftest: a jar without any reference was accepted by report()")
            ok = False
        else:
            print("selftest: " + "jar without references".ljust(34) + " -> report() failed")
    finally:
        shutil.rmtree(tmp, ignore_errors=True)

    print("fetch-kubejs selftest: " + ("OK" if ok else "FAILED"))
    return 0 if ok else 1


def fetch_main(argv) -> int:
    parser = argparse.ArgumentParser(description="Cache and inspect the KubeJS jar.")
    parser.add_argument("--scan-only", "--offline", dest="scan_only", action="store_true",
                        help="never download; fail when the cached jar is missing")
    parser.add_argument("--selftest", action="store_true", help="check the scanner itself")
    parser.add_argument("--jar", default=None, help="inspect another jar instead")
    parser.add_argument("--url", default=None, help="download from another url")
    parser.add_argument("--force", action="store_true", help="re-download even when cached")
    args = parser.parse_args(argv)

    if args.selftest:
        return fetch_selftest()

    if args.force and args.scan_only:
        print("FAIL: fetch-kubejs: --force and --scan-only are mutually exclusive")
        return 1

    jar = Path(args.jar).resolve() if args.jar else fetch_KUBEJS_JAR
    usable = (not args.force) and fetch__jar_is_usable(jar)
    if usable:
        print("fetch-kubejs: cached jar is valid (" + jar.name + ")")
    else:
        if args.scan_only:
            print("FAIL: fetch-kubejs: " + str(jar) + " is missing or unusable, --scan-only given")
            return 1
        # Only now is a mirror worth resolving: a cached jar costs no network.
        if args.url:
            urls = [args.url]
        elif jar == fetch_KUBEJS_JAR:
            urls = [fetch_KUBEJS_URL]
            fallback = fetch_modrinth_fallback(fetch_KUBEJS_NAME)
            if fallback:
                urls.append(fallback)
                print("fetch-kubejs: mirror fallback available at " + fallback)
        else:
            urls = [jar.as_uri()]
        if not fetch_download(jar, urls):
            return 1

    return fetch_report(jar)




# ==========================================================================
# section 'privacy' -- 被跟踪文件不含隐私材料（截图 / 日志 / 存档 / 密钥 / 本机绝对路径）
# ==========================================================================

"""Refuse to publish anything that is not the addon's own source.

This repository is public, so a path that carries something personal - a chat
screenshot, a log, a world save, an absolute path of the author's machine, a
token - is a leak the moment it is pushed.  This section is the gate: it is what
.githooks/pre-commit calls before every commit and what check_all.py runs first:

    python tools/validate_checks.py privacy           # the files git tracks
    python tools/validate_checks.py privacy --staged  # the files staged for the next commit
    python tools/validate_checks.py privacy --all     # everything on disk, ignored files too

Each finding is printed with the reason, and the exit code is 1 when anything was
found, so a hook that calls this stops the commit.
"""

import argparse
import re
import subprocess
import sys
from pathlib import Path

priv_SCRIPT = Path(__file__).resolve()
priv_WORKSPACE = priv_SCRIPT.parent.parent

# Paths that must never be published, with the reason shown to the author.
priv_FORBIDDEN_PATHS = (
    (r"(^|/)chat-img-src(/|$)", "私人聊天截图目录"),
    (r"(^|/)screenshots?(/|$)", "截图目录"),
    (r"(^|/)reports?(/|$)", "本地审计报告（含本机绝对路径）"),
    (r"(^|/)logs?(/|$)", "日志目录"),
    (r"(^|/)saves?(/|$)", "存档目录"),
    (r"(^|/)crash-reports?(/|$)", "崩溃报告目录"),
    (r"(^|/)backups?(/|$)", "备份目录"),
    (r"(^|/)\.env", "环境变量文件"),
    (r"(^|/)id_(rsa|ed25519|ecdsa)", "私钥"),
    (r"\.(pem|key|ppk|p12|jks)$", "密钥材料"),
)

# Extensions that only ever appear as personal material or as a build artefact.
priv_FORBIDDEN_EXTENSIONS = (".zip", ".rar", ".7z", ".mp4", ".mov", ".log", ".dat", ".hprof")

# The addon's own textures are generated programmatically and recorded in
# reports/audit-texture-provenance.json; docs/images/ holds the release artwork
# (project-logo.png) that the README shows. Anything else that is an image is
# very likely a screenshot.
priv_ALLOWED_IMAGE_DIRS = ("src/main/resources/assets/", "kubejs-examples/assets/",
                          "docs/images/")

# Content that must never reach a public repository.
priv_FORBIDDEN_CONTENT = (
    (re.compile(r"gho_[A-Za-z0-9]{20,}|ghp_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,}"),
     "GitHub 令牌"),
    (re.compile(r"-----BEGIN [A-Z ]*PRIVATE KEY-----"), "私钥内容"),
    (re.compile(r"(?i)(api[_-]?key|secret|password|passwd|token)\s*[:=]\s*[\"'][^\"']{8,}"),
     "疑似凭据"),
    (re.compile(r"[A-Za-z]:\\\\PCL2|C:\\\\Users\\\\[^\\\\\s]+"), "本机绝对路径"),
)

priv_TEXT_SUFFIXES = {".md", ".txt", ".json", ".json5", ".toml", ".properties", ".gradle",
                 ".java", ".py", ".js", ".sh", ".ps1", ".yml", ".yaml", ".mcmeta", ".gitignore"}


def priv_tracked_files() -> list:
    """The files git knows about (or has staged), relative to the repository root."""
    try:
        out = subprocess.run(["git", "ls-files"], cwd=str(priv_WORKSPACE), check=True,
                             capture_output=True, text=True).stdout
    except Exception as exc:  # pragma: no cover - git is expected to be present
        print("FAIL: could not ask git for the file list: %s" % exc, file=sys.stderr)
        return []
    return [line for line in out.splitlines() if line.strip()]


def priv_staged_files() -> list:
    try:
        out = subprocess.run(["git", "diff", "--cached", "--name-only"], cwd=str(priv_WORKSPACE),
                             check=True, capture_output=True, text=True).stdout
    except Exception:  # pragma: no cover
        return []
    return [line for line in out.splitlines() if line.strip()]


def priv_all_files() -> list:
    found = []
    for path in priv_WORKSPACE.rglob("*"):
        if not path.is_file():
            continue
        rel = path.relative_to(priv_WORKSPACE).as_posix()
        if rel.startswith(".git/") or rel == ".git":
            continue
        found.append(rel)
    return sorted(found)


def priv_check(rel: str) -> list:
    """Every reason this path must not be published."""
    reasons = []
    lowered = rel.lower()
    for pattern, why in priv_FORBIDDEN_PATHS:
        if re.search(pattern, lowered):
            reasons.append(why)
    suffix = Path(rel).suffix.lower()
    if suffix in priv_FORBIDDEN_EXTENSIONS:
        reasons.append("不应入库的文件类型 %s" % suffix)
    if suffix in (".png", ".jpg", ".jpeg", ".gif", ".webp"):
        if not any(rel.startswith(allowed) for allowed in priv_ALLOWED_IMAGE_DIRS):
            reasons.append("模组资源目录之外的图片（疑似截图）")
    if suffix == ".jar" and rel != "gradle/wrapper/gradle-wrapper.jar":
        reasons.append("jar 属于构建产物（走 Release 分发）")
    if suffix in priv_TEXT_SUFFIXES:
        path = priv_WORKSPACE / rel
        try:
            text = path.read_text(encoding="utf-8", errors="replace")
        except OSError:
            text = ""
        for pattern, why in priv_FORBIDDEN_CONTENT:
            if pattern.search(text):
                reasons.append("内容命中：%s" % why)
    return reasons


def priv_main(argv: list) -> int:
    parser = argparse.ArgumentParser(description="Block private material from being committed.")
    parser.add_argument("--staged", action="store_true", help="only the staged files")
    parser.add_argument("--all", action="store_true", help="every file on disk, ignored ones too")
    args = parser.parse_args(argv)

    files = priv_all_files() if args.all else (priv_staged_files() if args.staged else priv_tracked_files())
    print("privacy: %d file(s) to inspect" % len(files))
    print()

    findings = []
    for rel in files:
        for why in priv_check(rel):
            findings.append((rel, why))

    if not findings:
        print("OK: nothing personal found; the list is safe to publish")
        return 0

    print("FAIL: %d finding(s)" % len(findings), file=sys.stderr)
    for rel, why in findings:
        print("  %s  --  %s" % (rel, why), file=sys.stderr)
    print()
    print("Remove the files (and, if they were ever pushed, rewrite the history before pushing).",
          file=sys.stderr)
    return 1


# ==========================================================================
# dispatcher
# ==========================================================================

SECTIONS = (
    ('privacy', '被跟踪文件不含隐私材料（截图 / 日志 / 存档 / 密钥 / 本机绝对路径）'),
    ('skeleton', '工程骨架、版本号与元数据一致性'),
    ('pure-logic', 'api 层不依赖 Minecraft / Forge'),
    ('api', '引擎、配置与内置动作的实现'),
    ('api-surface', 'core API 表面与两个事件'),
    ('coverage', 'Cataclysm 14 条结构定义的覆盖率'),
    ('appearance', '物品、模型、贴图与配方'),
    ('kubejs', 'KubeJS 集成与插件发现'),
    ('docs', '文档结构与引用一致性'),
    ('kubejs-fetch', '缓存 KubeJS 编译期 jar（唯一联网步骤）'),
)

def _run_privacy(argv):
    return priv_main(argv)


def _run_skeleton(argv):
    return skel_main(argv)

def _run_pure_logic(argv):
    return pure_main()

def _run_api(argv):
    return impl_main(argv)

def _run_api_surface(argv):
    return surf_main(argv)

def _run_coverage(argv):
    return cover_main(argv)

def _run_appearance(argv):
    return appear_main()


def _run_resources(argv):
    # 'resources' is the historical name of the resource-pack rule set; the
    # rules themselves live in the appearance checker, which validates the item
    # model, textures, recipe and the en_us/zh_cn language files. Kept as an
    # alias so a caller that asks for the resources section still gets them.
    return appear_main()

def _run_kubejs(argv):
    return kjs_main(argv)

def _run_docs(argv):
    return doc_main(argv)

def _run_kubejs_fetch(argv):
    return fetch_main(argv)

_RUNNERS = {
    'privacy': _run_privacy,
    'skeleton': _run_skeleton,
    'pure-logic': _run_pure_logic,
    'api': _run_api,
    'api-surface': _run_api_surface,
    'coverage': _run_coverage,
    'appearance': _run_appearance,
    'resources': _run_resources,
    'kubejs': _run_kubejs,
    'docs': _run_docs,
    'kubejs-fetch': _run_kubejs_fetch,
}

_SELFTESTS = {
    'skeleton': skel_selftest,
    'api': impl_selftest,
    'api-surface': surf_selftest,
    'coverage': cover_selftest,
    'kubejs': kjs_selftest,
    'docs': doc_selftest,
    'kubejs-fetch': fetch_selftest,
}

def _print_sections():
    for name, description in SECTIONS:
        print("%-14s %s" % (name, description))


def main(argv=None):
    argv = list(sys.argv[1:] if argv is None else argv)
    if "--list" in argv or "--help" in argv or "-h" in argv:
        _print_sections()
        return 0
    selftest = "--selftest" in argv
    argv = [a for a in argv if a != "--selftest"]
    names = [a for a in argv if not a.startswith("-")]
    if not names:
        names = [n for n, _ in SECTIONS]
        if selftest:
            names = [n for n, _ in SECTIONS if n in _SELFTESTS]
    failed = []
    for name in names:
        if name not in _RUNNERS:
            print("FAIL: unknown section %r (try --list)" % name)
            return 2
        if selftest:
            if name not in _SELFTESTS:
                print("validate-checks: %-14s no selftest" % name)
                continue
            code = _SELFTESTS[name]()
        else:
            code = _RUNNERS[name](argv[1:] if len(names) == 1 else [])
        if code:
            failed.append(name)
            print("FAIL: %s (exit %s)" % (name, code))
        else:
            print("ok: %s" % name)
    if failed:
        print("validate-checks: FAILED (%s)" % ", ".join(failed))
        return 1
    print("validate-checks: OK (%d section(s))" % len(names))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
