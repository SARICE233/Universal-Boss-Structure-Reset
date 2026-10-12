#!/usr/bin/env python3
"""Structural conformance check for the runtime engine and configuration (task T4).

T3 delivers the Minecraft-facing API surface ({@code com.structurereset.core});
T4 delivers the engine that actually performs a reset.  This checker asserts
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
    python tools/validate_api.py            # exit 0 when the engine layer is complete
    python tools/validate_api.py --selftest # prove every rule rejects a defect
"""

from __future__ import annotations

import argparse
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
IMPL = JAVA / "impl"
ACTION_DIR = IMPL / "action"
CONFIG = JAVA / "config" / "StructureResetConfig.java"

IMPL_FILES = (
    IMPL / "ResetEngine.java",
    IMPL / "ResetStateSavedData.java",
    IMPL / "DefinitionReloadListener.java",
    CONFIG,
)

# action id -> the source file that must declare it
ACTIONS = {
    "respawn_boss": "RespawnBossAction.java",
    "refill_loot": "RefillLootAction.java",
    "reset_blocks": "ResetBlocksAction.java",
    "clear_entities": "ClearEntitiesAction.java",
    "run_command": "RunCommandAction.java",
    "message": "MessageAction.java",
}
MIN_ACTIONS = 6

# the two scope branches the engine must contain
SCOPE_TOKENS = ("PER_PLAYER", "GLOBAL_SCOPE")

# configuration names the spec asks for; at least MIN_CONFIG_NAMES must be declared
CONFIG_NAMES = (
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
    "learningMinMaxHealth",
    "learningRequireOp",
)
MIN_CONFIG_NAMES = 12

SPEC_RE = re.compile(r"(?:Forge|Mod)ConfigSpec")


def declares_id(text, action_id):
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


def snake_case(name):
    """RespawnBoss -> respawn_boss."""
    out = []
    for index, char in enumerate(name):
        if char.isupper() and index > 0:
            out.append("_")
        out.append(char.lower())
    return "".join(out)


def implied_id(filename):
    """RespawnBossAction.java -> respawn_boss, or None."""
    base = filename[:-5] if filename.endswith(".java") else filename
    if not base.endswith("Action"):
        return None
    return snake_case(base[: -len("Action")])


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

    def action_files(self):
        directory = self.root / ACTION_DIR
        return sorted(directory.glob("*.java")) if directory.is_dir() else []

    # --------------------------------------------------------------- rules

    def rule_impl_files(self):
        rule = "impl-files"
        missing = [rel for rel in IMPL_FILES if not (self.root / rel).is_file()]
        self.counts[rule] = len(IMPL_FILES) - len(missing)
        for rel in missing:
            self.fail(rule, "missing " + rel.as_posix())

    def rule_action_classes(self):
        rule = "action-classes"
        files = self.action_files()
        self.counts[rule] = len(files)
        if len(files) < MIN_ACTIONS:
            self.fail(rule, "found %d action source(s) under %s, expected at least %d"
                      % (len(files), ACTION_DIR.as_posix(), MIN_ACTIONS))
        names = {path.name for path in files}
        for action_id, filename in sorted(ACTIONS.items()):
            if filename not in names:
                self.fail(rule, "%s is missing, so '%s' has no implementation"
                          % ((ACTION_DIR / filename).as_posix(), action_id))

    def rule_engine(self):
        rule = "engine"
        text = self.read(IMPL / "ResetEngine.java")
        if text is None:
            self.counts[rule] = 0
            self.fail(rule, (IMPL / "ResetEngine.java").as_posix() + " is missing")
            return
        matched = 0
        if "structureManager" in text:
            matched += 1
        else:
            self.fail(rule, "ResetEngine.java never consults the structure manager")
        for token in SCOPE_TOKENS:
            if re.search(r"\b" + token + r"\b", text) is not None:
                matched += 1
            else:
                self.fail(rule, "ResetEngine.java has no %s scope branch" % token)
        self.counts[rule] = matched

    def rule_config(self):
        rule = "config"
        text = self.read(CONFIG)
        if text is None:
            self.counts[rule] = 0
            self.fail(rule, CONFIG.as_posix() + " is missing")
            return
        if not SPEC_RE.search(text):
            self.fail(rule, "StructureResetConfig.java declares no Forge/Mod config spec")
        names = [name for name in CONFIG_NAMES if re.search(r"\b" + name + r"\b", text)]
        for name in CONFIG_NAMES:
            if name not in names:
                self.fail(rule, "StructureResetConfig.java has no '%s' entry" % name)
        if len(names) < MIN_CONFIG_NAMES:
            self.fail(rule, "only %d of the %d required configuration names are present"
                      % (len(names), len(CONFIG_NAMES)))
        self.counts[rule] = len(names)

    def rule_action_ids(self):
        rule = "action-ids"
        files = {path.name: path for path in self.action_files()}
        matched = 0
        for action_id, filename in sorted(ACTIONS.items()):
            path = files.get(filename)
            if path is None:
                continue
            implied = implied_id(filename)
            if implied != action_id:
                self.fail(rule, "%s implies the action id '%s', not '%s'"
                          % ((ACTION_DIR / filename).as_posix(), implied, action_id))
                continue
            text = path.read_text(encoding="utf-8", errors="replace")
            if declares_id(text, action_id):
                matched += 1
            else:
                self.fail(rule, "%s declares neither \"%s\" nor 'structurereset:%s'"
                          % ((ACTION_DIR / filename).as_posix(), action_id, action_id))
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

def _check(root):
    return Checker(root).run()


def _replace(path, old, new):
    text = path.read_text(encoding="utf-8")
    if old not in text:
        raise AssertionError("defect anchor missing in %s: %r" % (path.name, old))
    path.write_text(text.replace(old, new), encoding="utf-8")


def _defects():
    def missing_engine(root):
        (root / IMPL / "ResetEngine.java").unlink()

    def missing_scope_branch(root):
        _replace(root / IMPL / "ResetEngine.java", "PER_PLAYER", "PER_PLAYER_SCOPE")

    def missing_config_spec(root):
        path = root / CONFIG
        text = path.read_text(encoding="utf-8")
        text = text.replace("ForgeConfigSpec", "ForgeSpec").replace("ModConfigSpec", "ModSpec")
        path.write_text(text, encoding="utf-8")

    def missing_action_file(root):
        (root / ACTION_DIR / "RunCommandAction.java").unlink()

    def wrong_action_id(root):
        _replace(root / ACTION_DIR / "MessageAction.java", 'NAME = "message"', 'NAME = "nope"')

    def missing_saved_data(root):
        (root / IMPL / "ResetStateSavedData.java").unlink()

    return (
        ("missing-engine", missing_engine),
        ("missing-scope-branch", missing_scope_branch),
        ("missing-config-spec", missing_config_spec),
        ("missing-action-file", missing_action_file),
        ("wrong-action-id", wrong_action_id),
        ("missing-saved-data", missing_saved_data),
    )


def selftest():
    tmp = Path(tempfile.mkdtemp(prefix="structurereset-api-selftest-"))
    ok = True
    try:
        clean = tmp / "clean"
        shutil.copytree(WORKSPACE / "src", clean / "src")
        failures = _check(clean)
        if failures:
            ok = False
            print("FAIL: selftest: the real engine layer was rejected (%d failure(s))" % len(failures))
            for failure in failures[:5]:
                print("      %s" % failure)
        else:
            print("selftest: %-24s -> accepted" % "real-engine-layer")

        for name, defect in _defects():
            target = tmp / ("bad-" + name)
            shutil.copytree(WORKSPACE / "src", target / "src")
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

    print("validate-api selftest: " + ("OK" if ok else "FAILED"))
    return 0 if ok else 1


# ------------------------------------------------------------------ main

def main(argv):
    parser = argparse.ArgumentParser(
        description="Check the Structure Reset runtime engine (task T4).")
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
            print("FAIL: validate-api: %s" % failure)
        print("validate-api: %d failure(s) (%s)" % (len(failures), checker.summary()))
        return 1
    print("OK: validate-api: %d impl file(s), %d action source(s), engine tokens=%d, "
          "%d config name(s), %d action id(s) (%s)"
          % (checker.counts["impl-files"], checker.counts["action-classes"],
             checker.counts["engine"], checker.counts["config"],
             checker.counts["action-ids"], checker.summary()))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
