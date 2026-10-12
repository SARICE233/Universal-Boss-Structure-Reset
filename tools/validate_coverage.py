#!/usr/bin/env python3
"""Coverage validator for the Cataclysm structure-reset definitions (Structure Reset).

Usage:
    python tools/validate_coverage.py             # exit 0 when every rule passes
    python tools/validate_coverage.py --selftest  # prove the validator detects defects

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
REFERENCE = Path("F:/Desktop/new1.20.1-master/new1.20.1-master")
REFERENCE_STRUCTURES = REFERENCE / "src/main/resources/data/cataclysm/worldgen/structure"
DEFINITIONS = WORKSPACE / "src/main/resources/data/structurereset/structure_resets"
# The Java config source is the single source of truth for the cooldown baseline:
# the expected value is parsed back out of it, never repeated as a literal here.
CONFIG_SOURCE = WORKSPACE / "src/main/java/com/structurereset/config/StructureResetConfig.java"

EXPECTED_COUNT = 14
NAMESPACE = "cataclysm"
ANCHOR_BLOCK = "cataclysm:boss_respawner"
RESPAWN_ACTION = "structurereset:respawn_boss"
REFILL_ACTION = "structurereset:refill_loot"

# Structures that genuinely have no boss in Cataclysm. They may only refill loot:
# they must not declare an anchor block and must not declare a respawn_boss
# action, so they can never summon a boss. The list is evidence based - every
# entity that spawns there (aptrgangr, kobolediator, amethyst_crab, ...) registers
# no boss bar (CMBossInfoServer / ServerBossEvent) in the mod source.
NO_BOSS_STRUCTURES = frozenset({
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
CONFIG_DEFAULT_PATTERNS = (
    re.compile(r'\.defineInRange\(\s*"defaultCooldownSeconds"\s*,\s*(-?\d+)\s*,'),
    re.compile(r'COMMON\.defaultCooldownSeconds\s*,\s*(-?\d+)'),
)


# ------------------------------------------------------------------ helpers

def _read_json(path: Path):
    return json.loads(path.read_text(encoding="utf-8"))


def _write_json(path, data) -> None:
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")


def reference_ids(reference_structures: Path):
    """The bare structure ids declared by the read-only Cataclysm source."""
    if not Path(reference_structures).is_dir():
        return None
    return sorted(p.stem for p in Path(reference_structures).glob("*.json") if p.is_file())


def in_scope(path, definitions_dir) -> bool:
    """True when the definition lives under the cataclysm/ subdirectory."""
    try:
        rel = Path(path).relative_to(Path(definitions_dir))
    except ValueError:
        return False
    return bool(rel.parts) and rel.parts[0] == NAMESPACE


def _structure_refs(value):
    """Every structure reference named by a definition's 'structure' field."""
    out = []
    if isinstance(value, str):
        out.append(value.strip())
    elif isinstance(value, list):
        for entry in value:
            out.extend(_structure_refs(entry))
    return out


def _local_id(ref: str, namespace: str = NAMESPACE):
    """The bare id of a reference inside 'namespace', else None."""
    text = ref.strip()
    if text.startswith("#"):
        text = text[1:]
    if ":" in text:
        ns, _, path = text.partition(":")
        return path if ns == namespace else None
    return text


def _is_number(value) -> bool:
    return isinstance(value, (int, float)) and not isinstance(value, bool)


def _is_integer(value) -> bool:
    """A JSON integer; a bool is not an integer even though bool subclasses int."""
    return isinstance(value, int) and not isinstance(value, bool)


def config_default_cooldown(source=None):
    """Parse the 'defaultCooldownSeconds' default out of the Java config source.

    Returns (value, problems).  value is None -- with the reason in problems --
    when the source file or the declaration cannot be found, so a broken parse is
    reported as a problem instead of raising.
    """
    path = Path(CONFIG_SOURCE if source is None else source)
    if not path.is_file():
        return None, ["default: %s does not exist (cannot read the "
                      "'defaultCooldownSeconds' default)" % path]
    try:
        text = path.read_text(encoding="utf-8")
    except Exception as exc:
        return None, ["default: %s could not be read (%s)" % (path, exc)]
    for pattern in CONFIG_DEFAULT_PATTERNS:
        match = pattern.search(text)
        if match:
            return int(match.group(1)), []
    return None, ["default: %s declares no 'defaultCooldownSeconds' default" % path]


# -------------------------------------------------------------------- check

def check(definitions_dir, reference_structures, config_source=None):
    """Return (problems, stats) for one (definitions, reference, config) triple."""
    definitions_dir = Path(definitions_dir)
    reference_structures = Path(reference_structures)
    problems = []
    stats = {"structures": 0, "definitions": 0, "scoped": 0, "other": 0, "matched": 0}

    ids = reference_ids(reference_structures)
    if ids is None:
        problems.append("reference: %s is not a directory" % reference_structures)
        return problems, stats
    stats["structures"] = len(ids)
    if len(ids) != EXPECTED_COUNT:
        problems.append("reference: %s declares %d structure ids, expected %d (%s)"
                        % (reference_structures, len(ids), EXPECTED_COUNT, ", ".join(ids)))
    known = set(ids)

    if not definitions_dir.is_dir():
        problems.append("coverage: %s is not a directory" % definitions_dir)
        return problems, stats

    files = sorted(p for p in definitions_dir.rglob("*.json") if p.is_file())
    stats["definitions"] = len(files)
    scoped = [p for p in files if in_scope(p, definitions_dir)]
    others = [p for p in files if not in_scope(p, definitions_dir)]
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
            data = _read_json(path)
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
    expected_cooldown, default_problems = config_default_cooldown(config_source)
    problems.extend(default_problems)

    by_id = {}

    for path in scoped:
        data, label = load(path)
        if data is None:
            continue
        base_schema(data, label)

        raw = data.get("structure")
        if raw is not None:
            for ref in _structure_refs(raw):
                local = _local_id(ref)
                if local is None:
                    problems.append("extras: %s: structure '%s' is outside the %s namespace"
                                    % (label, ref, NAMESPACE))
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

        declared = [x for x in (_local_id(r) for r in _structure_refs(raw)) if x is not None] \
            if raw is not None else []
        bossless = bool(declared) and all(x in NO_BOSS_STRUCTURES for x in declared)

        if bossless:
            # A structure without a boss may only refill loot. It must not carry an
            # anchor block (which would let it be triggered elsewhere) and must not
            # declare a respawn_boss action (which would summon a boss that does not
            # belong there).
            if isinstance(anchor_list, list) and anchor_list:
                problems.append("anchor: %s: a no-boss structure must declare "
                                "'anchor_blocks': []" % label)
            if RESPAWN_ACTION in types:
                problems.append("actions: %s: a no-boss structure must not declare '%s'"
                                % (label, RESPAWN_ACTION))
            if isinstance(actions, list) and actions and REFILL_ACTION not in types:
                problems.append("actions: %s: 'actions' has no '%s' action"
                                % (label, REFILL_ACTION))
        else:
            if not isinstance(anchor_list, list) or not anchor_list:
                problems.append("anchor: %s: 'anchor_blocks' must be a non-empty array" % label)
            elif ANCHOR_BLOCK not in [a for a in anchor_list if isinstance(a, str)]:
                problems.append("anchor: %s: 'anchor_blocks' does not contain '%s'"
                                % (label, ANCHOR_BLOCK))

        if not isinstance(actions, list) or not actions:
            problems.append("actions: %s: 'actions' must be a non-empty array" % label)
        else:
            if not bossless and RESPAWN_ACTION not in types:
                problems.append("actions: %s: 'actions' has no '%s' action" % (label, RESPAWN_ACTION))
            if not bossless and REFILL_ACTION not in types:
                problems.append("actions: %s: 'actions' has no '%s' action" % (label, REFILL_ACTION))
            for action in actions:
                if isinstance(action, dict) and action.get("type") == RESPAWN_ACTION:
                    params = action.get("params")
                    entity = params.get("entity") if isinstance(params, dict) else None
                    if not isinstance(entity, str) or not entity.strip():
                        problems.append("actions: %s: '%s' action has no params.entity"
                                        % (label, RESPAWN_ACTION))

        if "radius" not in data:
            problems.append("fields: %s: missing required field 'radius'" % label)
        elif not _is_number(data["radius"]):
            problems.append("fields: %s: 'radius' must be a number" % label)

        if "cooldown_seconds" not in data:
            problems.append("cooldown: %s: missing required field 'cooldown_seconds'" % label)
        elif not _is_integer(data["cooldown_seconds"]) or data["cooldown_seconds"] <= 0:
            problems.append("cooldown: %s: 'cooldown_seconds' must be an integer greater "
                            "than 0, got %r" % (label, data["cooldown_seconds"]))
        elif expected_cooldown is not None and data["cooldown_seconds"] != expected_cooldown:
            problems.append("default: %s: 'cooldown_seconds' is %r but the "
                            "'defaultCooldownSeconds' default in %s is %d"
                            % (label, data["cooldown_seconds"], CONFIG_SOURCE,
                               expected_cooldown))

    for sid in sorted(known - set(by_id)):
        problems.append("coverage: no cataclysm/ definition matches 'cataclysm:%s'" % sid)
    stats["matched"] = len(known & set(by_id))

    return problems, stats


def report(problems, stats, label) -> int:
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

def _find(root, stem):
    matches = sorted(Path(root).rglob(stem + ".json"))
    if not matches:
        raise AssertionError("selftest: %s.json not found under %s" % (stem, root))
    return matches[0]


def _mutate(root, stem, func):
    path = _find(root, stem)
    data = _read_json(path)
    func(data)
    path.write_text(json.dumps(data, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")


def _copy_definitions(source, target):
    if Path(target).exists():
        shutil.rmtree(target, ignore_errors=True)
    shutil.copytree(source, target)


def _defects():
    """(name, mutation): each must be REJECTED by check()."""

    def missing_definition(root):
        _find(root, "abandoned_spire").unlink()

    def extra_structure_id(root):
        _write_json(Path(root) / "cataclysm" / "zzz_extra.json", {
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
        _mutate(root, "frosted_prison",
                lambda d: d.__setitem__("structure", "cataclysm:frosted_prisonn"))

    def missing_anchor_blocks(root):
        _mutate(root, "acropolis", lambda d: d.pop("anchor_blocks", None))

    def anchor_without_boss_respawner(root):
        _mutate(root, "acropolis", lambda d: d.__setitem__("anchor_blocks", ["minecraft:stone"]))

    def missing_respawn_boss(root):
        _mutate(root, "burning_arena", lambda d: d.__setitem__(
            "actions", [a for a in d["actions"] if a.get("type") != "structurereset:respawn_boss"]))

    def missing_refill_loot(root):
        _mutate(root, "burning_arena", lambda d: d.__setitem__(
            "actions", [a for a in d["actions"] if a.get("type") != "structurereset:refill_loot"]))

    def missing_cooldown_seconds(root):
        # a cataclysm/ definition without 'cooldown_seconds' must be rejected
        _mutate(root, "desert_site", lambda d: d.pop("cooldown_seconds", None))

    def cooldown_not_positive_integer(root):
        # a non-integer / non-positive 'cooldown_seconds' must be rejected
        _mutate(root, "desert_site", lambda d: d.__setitem__("cooldown_seconds", 0))
        _mutate(root, "amethyst_nest", lambda d: d.__setitem__("cooldown_seconds", True))

    def cooldown_mismatch_config_default(root):
        # a cataclysm/ 'cooldown_seconds' that disagrees with the
        # 'defaultCooldownSeconds' default parsed from the Java source must be
        # rejected; the injected value is derived from that parsed default.
        base, _ = config_default_cooldown()
        other = (base + 1) if base is not None else 1
        _mutate(root, "sunken_city", lambda d: d.__setitem__("cooldown_seconds", other))

    def no_boss_structure_with_respawn_boss(root):
        # a structure without a boss must never declare a respawn_boss action
        _mutate(root, "abandoned_spire", lambda d: d.__setitem__("actions", [
            {"type": "structurereset:respawn_boss", "params": {"entity": "cataclysm:maledictus"}},
            {"type": "structurereset:refill_loot"},
        ]))

    def no_boss_structure_with_anchor(root):
        # a structure without a boss must not carry an anchor block either
        _mutate(root, "desert_site", lambda d: d.__setitem__(
            "anchor_blocks", ["cataclysm:boss_respawner"]))

    def missing_radius(root):
        _mutate(root, "desert_site", lambda d: d.pop("radius", None))

    def invalid_json(root):
        _find(root, "sunken_city").write_text("{\n  // comments are not json\n}\n", encoding="utf-8")

    def out_of_scope_missing_structure(root):
        _write_json(Path(root) / "custom_broken.json",
                    {"actions": [{"type": "structurereset:message"}]})

    def out_of_scope_actions_not_array(root):
        _write_json(Path(root) / "custom_broken.json",
                    {"structure": "minecraft:stronghold", "actions": {}})

    def out_of_scope_action_without_type(root):
        _write_json(Path(root) / "custom_broken.json",
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


def _acceptances():
    """(name, mutation): each must be ACCEPTED by check() -- the scoping contract."""

    def minimal_example(root):
        _write_json(Path(root) / "custom_minimal_example.json", {
            "structure": "minecraft:stronghold",
            "actions": [{"type": "structurereset:message", "params": {"text": "hi"}}],
        })

    def no_boss_actions_example(root):
        _write_json(Path(root) / "custom_anchor_only.json", {
            "structure": "minecraft:woodland_mansion",
            "anchor_blocks": [],
            "cooldown_seconds": 1200,
            "actions": [{"type": "structurereset:clear_entities",
                         "params": {"entities": ["minecraft:zombie"]}}],
        })

    def tag_based_example(root):
        _write_json(Path(root) / "custom_tag_example.json", {
            "structure": "#minecraft:village",
            "radius": 64,
            "actions": [{"type": "structurereset:message"}],
        })

    def in_scope_boss_definition_still_checked(root):
        # a correctly built boss definition for a cataclysm/ structure stays fine;
        # its cooldown comes from the config source, not from a literal here.
        default, _ = config_default_cooldown()
        _write_json(Path(root) / "cataclysm" / "acropolis.json", {
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
        default, _ = config_default_cooldown()
        _write_json(Path(root) / "cataclysm" / "abandoned_spire.json", {
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
        default, _ = config_default_cooldown()
        seen = 0
        for path in sorted((Path(root) / "cataclysm").rglob("*.json")):
            data = _read_json(path)
            data["cooldown_seconds"] = default
            _write_json(path, data)
            seen += 1
        if seen != EXPECTED_COUNT:
            raise AssertionError("expected %d cataclysm/ definitions, found %d"
                                 % (EXPECTED_COUNT, seen))

    return (
        ("out-of-scope-minimal-example", minimal_example),
        ("out-of-scope-no-boss-actions", no_boss_actions_example),
        ("out-of-scope-tag-example", tag_based_example),
        ("in-scope-boss-definition", in_scope_boss_definition_still_checked),
        ("in-scope-no-boss-definition", in_scope_no_boss_definition_accepted),
        ("all-cooldowns-same-value", all_cooldowns_same_value),
    )


def selftest() -> int:
    if not DEFINITIONS.is_dir():
        print("FAIL: selftest: %s does not exist" % DEFINITIONS)
        return 1
    ok = True
    tmp = Path(tempfile.mkdtemp(prefix="structurereset-coverage-selftest-"))
    try:
        clean = tmp / "clean"
        _copy_definitions(DEFINITIONS, clean)
        problems, _ = check(clean, REFERENCE_STRUCTURES)
        if problems:
            ok = False
            print("FAIL: selftest: the real definitions were rejected (%d problem(s))" % len(problems))
            for problem in problems:
                print("      %s" % problem)
        else:
            print("selftest: %-34s -> accepted" % "real-definitions")

        empty_reference = tmp / "empty-reference"
        empty_reference.mkdir(parents=True, exist_ok=True)
        problems, _ = check(clean, empty_reference)
        if not problems:
            ok = False
            print("FAIL: selftest: an empty reference directory was accepted")
        else:
            print("selftest: %-34s -> rejected" % "empty-reference-dir")

        # a config source that cannot be parsed must be reported, not raised
        missing_config = tmp / "no-such-config" / "StructureResetConfig.java"
        try:
            problems, _ = check(clean, REFERENCE_STRUCTURES, config_source=missing_config)
        except Exception as exc:
            ok = False
            print("FAIL: selftest: a missing config source raised %s" % exc)
        else:
            if not problems:
                ok = False
                print("FAIL: selftest: a missing config source was accepted")
            else:
                print("selftest: %-34s -> rejected (%s)" % ("missing-config-source", problems[0]))

        for name, defect in _defects():
            target = tmp / ("bad-" + name)
            _copy_definitions(DEFINITIONS, target)
            try:
                defect(target)
            except Exception as exc:
                ok = False
                print("FAIL: selftest: defect %s could not be injected (%s)" % (name, exc))
                continue
            problems, _ = check(target, REFERENCE_STRUCTURES)
            if not problems:
                ok = False
                print("FAIL: selftest: defect '%s' was accepted (exit 0)" % name)
            else:
                print("selftest: %-34s -> rejected (%s)" % (name, problems[0]))

        for name, mutation in _acceptances():
            target = tmp / ("ok-" + name)
            _copy_definitions(DEFINITIONS, target)
            try:
                mutation(target)
            except Exception as exc:
                ok = False
                print("FAIL: selftest: case %s could not be built (%s)" % (name, exc))
                continue
            problems, _ = check(target, REFERENCE_STRUCTURES)
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

def main(argv) -> int:
    parser = argparse.ArgumentParser(description="Check Cataclysm structure-reset coverage.")
    parser.add_argument("--selftest", action="store_true",
                        help="prove the validator detects injected defects")
    parser.add_argument("--definitions", default=None, help="structure_resets root")
    parser.add_argument("--reference", default=None, help="Cataclysm worldgen/structure directory")
    args = parser.parse_args(argv)

    if args.selftest:
        return selftest()

    definitions = Path(args.definitions).resolve() if args.definitions else DEFINITIONS
    reference = Path(args.reference).resolve() if args.reference else REFERENCE_STRUCTURES
    problems, stats = check(definitions, reference)
    return report(problems, stats, str(definitions))


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
