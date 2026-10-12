#!/usr/bin/env python3
"""Structural conformance check for the runtime API surface (task T3).

Structure Reset has two layers:

* {@code com.structurereset.api} - the Minecraft-free logic layer (T2), which is
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
    python tools/validate_api_surface.py            # exit 0 when the surface is complete
    python tools/validate_api_surface.py --selftest # prove every rule rejects a defect
"""

from __future__ import annotations

import argparse
import json
import re
import shutil
import sys
import tempfile
from pathlib import Path

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    sys.stderr.reconfigure(encoding="utf-8", errors="replace")
except Exception:  # pragma: no cover
    pass

SCRIPT = Path(__file__).resolve()
WORKSPACE = SCRIPT.parent.parent

JAVA = Path("src") / "main" / "java" / "com" / "structurereset"
API = JAVA / "api"
CORE = JAVA / "core"
# The three example definitions are documentation, not shipped data: they live
# in the examples/ datapack skeleton, which is NOT part of src/main/resources and
# therefore never ends up inside the jar.  They used to sit in the shipped
# structure_resets/ directory, where the mod loaded them as real definitions.
EXAMPLES = Path("examples") / "datapack" / "data" / "structurereset" / "structure_resets"
# The tree that really ships inside the jar: an example_*.json here would be
# loaded as a live definition on every resource reload (the bug this rule now
# prevents from coming back).
SHIPPED_RESETS = Path("src") / "main" / "resources" / "data" / "structurereset" / "structure_resets"

CORE_FILES = (
    "ResetContext.java",
    "ResetResult.java",
    "ResetActionType.java",
    "StructureResetApi.java",
    "event/ResetPreEvent.java",
    "event/ResetPostEvent.java",
)

PURE_API_FILES = (
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
)

API_METHODS = (
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

EVENT_TYPES = ("ResetPreEvent", "ResetPostEvent")

FORBIDDEN_IMPORT = re.compile(
    r"^\s*import\s+(?:static\s+)?(net[.]minecraft|net[.]minecraftforge|dev[.]latvian)")

MIN_EXAMPLES = 3


def declares(text, name):
    """True when a public static method of this name is declared in text."""
    pattern = (r"public\s+static\s+(?:final\s+)?[\w$.[\]<>,? ]+\s+"
               + re.escape(name) + r"\s*\(")
    return re.search(pattern, text) is not None


def is_tagged(value):
    """True when a structure reference (or a list of them) is a '#tag'."""
    if isinstance(value, str):
        return value.strip().startswith("#")
    if isinstance(value, list):
        return any(is_tagged(entry) for entry in value)
    return False


class Checker:
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
        missing = [rel for rel in CORE_FILES if not (self.root / CORE / rel).is_file()]
        self.counts[rule] = len(CORE_FILES) - len(missing)
        for rel in missing:
            self.fail(rule, "missing " + (CORE / rel).as_posix())

    def rule_api_purity(self):
        rule = "api-purity"
        directory = self.root / API
        sources = sorted(directory.rglob("*.java")) if directory.is_dir() else []
        present = [name for name in PURE_API_FILES if (directory / name).is_file()]
        self.counts[rule] = len(present)
        for name in PURE_API_FILES:
            if name not in present:
                self.fail(rule, "missing pure logic source " + (API / name).as_posix())
        for path in sources:
            if path.name not in PURE_API_FILES:
                self.fail(rule, "%s is not part of the Minecraft-free logic layer"
                          % (API / path.name).as_posix())
            for number, line in enumerate(
                    path.read_text(encoding="utf-8", errors="replace").splitlines(), 1):
                if FORBIDDEN_IMPORT.match(line):
                    self.fail(rule, "%s:%d imports %s, which would break the offline JUnit run"
                              % ((API / path.name).as_posix(), number, line.strip()))
                    break

    def rule_api_methods(self):
        rule = "api-methods"
        text = self.read(CORE / "StructureResetApi.java")
        if text is None:
            self.counts[rule] = 0
            self.fail(rule, (CORE / "StructureResetApi.java").as_posix() + " is missing")
            return
        declared = [name for name in API_METHODS if declares(text, name)]
        self.counts[rule] = len(declared)
        for name in API_METHODS:
            if name not in declared:
                self.fail(rule, "StructureResetApi.java does not declare a public static %s(...)" % name)

    def rule_api_events(self):
        rule = "api-events"
        text = self.read(CORE / "StructureResetApi.java")
        if text is None:
            self.counts[rule] = 0
            self.fail(rule, (CORE / "StructureResetApi.java").as_posix() + " is missing")
            return
        posts = len(re.findall(r"[.]post\s*\(", text))
        if posts < len(EVENT_TYPES):
            self.fail(rule, "expected one .post( call per reset event, found %d" % posts)
        found = 0
        for name in EVENT_TYPES:
            if re.search(r"\bnew\s+" + name + r"\s*\(", text) and posts > 0:
                found += 1
            else:
                self.fail(rule, "StructureResetApi.java neither constructs nor posts a %s" % name)
        self.counts[rule] = found

    def rule_examples(self):
        rule = "examples"
        directory = self.root / EXAMPLES
        files = sorted(directory.glob("example_*.json")) if directory.is_dir() else []
        self.counts[rule] = len(files)
        if len(files) < MIN_EXAMPLES:
            self.fail(rule, "found %d example_*.json under %s, expected at least %d"
                      % (len(files), EXAMPLES.as_posix(), MIN_EXAMPLES))
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
            if is_tagged(data.get("structure")):
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
        shipped = self.root / SHIPPED_RESETS
        if shipped.is_dir():
            for path in sorted(shipped.glob("example_*.json")):
                self.fail(rule, "%s/%s is shipped data: the mod would load it as a real "
                                 "definition, so examples must live under %s"
                          % (SHIPPED_RESETS.as_posix(), path.name, EXAMPLES.as_posix()))

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

def _check(root):
    return Checker(root).run()


def _copy_project(target):
    """Copy the checked trees (src/ and the examples/ datapack) into a root."""
    target = Path(target)
    shutil.copytree(WORKSPACE / "src", target / "src")
    shutil.copytree(WORKSPACE / "examples", target / "examples")
    return target


def _drop_lines_containing(path, needle):
    text = path.read_text(encoding="utf-8")
    lines = text.splitlines(keepends=True)
    kept = [line for line in lines if needle not in line]
    if len(kept) == len(lines):
        raise AssertionError("defect anchor missing in %s: %r" % (path.name, needle))
    path.write_text("".join(kept), encoding="utf-8")


def _defects():
    def missing_core_file(root):
        (root / CORE / "ResetContext.java").unlink()

    def missing_api_method(root):
        _drop_lines_containing(root / CORE / "StructureResetApi.java",
                               "public static boolean canReset")

    def missing_post_event(root):
        path = root / CORE / "StructureResetApi.java"
        text = path.read_text(encoding="utf-8")
        if "new ResetPostEvent(" not in text:
            raise AssertionError("defect anchor missing: new ResetPostEvent(")
        path.write_text(text.replace("new ResetPostEvent(", "new DisabledPostEvent("), encoding="utf-8")

    def minecraft_import_in_api(root):
        path = root / API / "ResetKey.java"
        text = path.read_text(encoding="utf-8")
        path.write_text("import net.minecraft.world.phys.Vec3;\n" + text, encoding="utf-8")

    def extra_api_source(root):
        (root / API / "LeakedRuntimeApi.java").write_text(
            "package com.structurereset.api;\n\npublic final class LeakedRuntimeApi {\n}\n",
            encoding="utf-8")

    def missing_example(root):
        (root / EXAMPLES / "example_shrine.json").unlink()

    def example_without_tag(root):
        path = root / EXAMPLES / "example_tag_based.json"
        text = path.read_text(encoding="utf-8")
        if '"#minecraft:village"' not in text:
            raise AssertionError("defect anchor missing: #minecraft:village")
        path.write_text(text.replace('"#minecraft:village"', '"minecraft:village"'), encoding="utf-8")

    def example_shipped_again(root):
        # the regression this rule exists to catch: an example copied back into
        # the shipped data tree, where the mod loads it as a live definition.
        (root / SHIPPED_RESETS).mkdir(parents=True, exist_ok=True)
        shutil.copyfile(root / EXAMPLES / "example_shrine.json",
                        root / SHIPPED_RESETS / "example_shrine.json")

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


def selftest():
    tmp = Path(tempfile.mkdtemp(prefix="structurereset-api-surface-selftest-"))
    ok = True
    try:
        clean = _copy_project(tmp / "clean")
        failures = _check(clean)
        if failures:
            ok = False
            print("FAIL: selftest: the real API surface was rejected (%d failure(s))" % len(failures))
            for failure in failures[:5]:
                print("      %s" % failure)
        else:
            print("selftest: %-24s -> accepted" % "real-api-surface")

        for name, defect in _defects():
            target = _copy_project(tmp / ("bad-" + name))
            try:
                defect(target)
            except Exception as exc:  # noqa: BLE001
                ok = False
                print("FAIL: selftest: defect %s could not be injected (%s)" % (name, exc))
                continue
            failures = _check(target)
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

def main(argv):
    parser = argparse.ArgumentParser(
        description="Check the Structure Reset runtime API surface (task T3).")
    parser.add_argument("--selftest", action="store_true", help="check the checker itself")
    parser.add_argument("--root", default=None,
                        help="Project root (defaults to this script's parent)")
    args = parser.parse_args(argv)

    if args.selftest:
        return selftest()

    root = Path(args.root).resolve() if args.root else WORKSPACE
    checker = Checker(root)
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


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
