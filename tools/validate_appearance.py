#!/usr/bin/env python3
"""Appearance validator for task T5 (item, models, textures, recipe, skins).

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
    python tools/validate_appearance.py     # exit 0 when every rule passes
"""

from __future__ import annotations

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

SCRIPT = Path(__file__).resolve()
WORKSPACE = SCRIPT.parent.parent
RESOURCES = WORKSPACE / "src" / "main" / "resources"
JAVA = WORKSPACE / "src" / "main" / "java"

MODID = "structurereset"
ITEM_ID = "structure_reset_core"
SKINS = ("structure_reset_core_skin1", "structure_reset_core_skin2")

ITEM_MODEL_DIR = RESOURCES / "assets" / MODID / "models" / "item"
LANG_DIR = RESOURCES / "assets" / MODID / "lang"
RECIPE = RESOURCES / "data" / MODID / "recipes" / (ITEM_ID + ".json")
APPEARANCE = RESOURCES / "data" / MODID / "item_appearance.json"
KUBEJS_MODEL = WORKSPACE / "kubejs-examples" / "assets" / MODID / "models" / "item" / (ITEM_ID + ".json")
KUBEJS_TEXTURE = WORKSPACE / "kubejs-examples" / "assets" / MODID / "textures" / "item" / "custom_core.png"

MIN_SIZE = 16

JAVA_CHECKS = (
    ("registry/ModItems.java", ("class ModItems", 'register("' + ITEM_ID + '"', "DeferredRegister", "register(IEventBus")),
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


class Report:
    """Collects problems and the number of checks that ran."""

    def __init__(self):
        self.problems = []
        self.checks = 0

    def fail(self, rule, detail):
        self.problems.append((rule, detail))

    def ok(self):
        self.checks += 1


def rel(path):
    try:
        return Path(path).relative_to(WORKSPACE).as_posix()
    except ValueError:
        return Path(path).as_posix()


def read_json(path, rule, report):
    """Parsed JSON of a file, or None (the problem is recorded)."""
    if not path.is_file():
        report.fail(rule, "%s is missing" % rel(path))
        return None
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except Exception as exc:  # noqa: BLE001 - any JSON problem is a failure
        report.fail(rule, "%s is not valid JSON (%s)" % (rel(path), exc))
        return None


def texture_path(ns, path):
    return RESOURCES / "assets" / ns / "textures" / (path + ".png")


def check_png(candidate, where, rule, report, seen):
    """Prove that one PNG is real, non flat art of at least 16x16."""
    label = rel(candidate)
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
    if width < MIN_SIZE or height < MIN_SIZE:
        report.fail(rule, "%s: %s is %dx%d, smaller than %dx%d" % (where, label, width, height, MIN_SIZE, MIN_SIZE))
        return
    packed = set(zip(raw[0::4], raw[1::4], raw[2::4], raw[3::4]))
    if len(packed) < 2:
        report.fail(rule, "%s: %s is a single colour placeholder" % (where, label))
        return
    seen[label] = hashlib.sha1(raw).hexdigest()
    report.ok()


def check_texture(ref, where, rule, report, seen):
    """Resolve one in-pack texture reference and prove the PNG is real art."""
    ns, _, path = ref.partition(":")
    if not ns or not path:
        report.fail(rule, "%s: texture '%s' is not a namespace:path reference" % (where, ref))
        return
    candidate = texture_path(ns, path)
    if not candidate.is_file():
        report.fail(rule, "%s: texture '%s' resolves to %s which is missing" % (where, ref, rel(candidate)))
        return
    check_png(candidate, where, rule, report, seen)


def check_item_model(name, rule, report, seen):
    """One item model: valid JSON, generated parent, real texture."""
    path = ITEM_MODEL_DIR / (name + ".json")
    data = read_json(path, rule, report)
    if data is None:
        return None
    if not isinstance(data, dict):
        report.fail(rule, "%s: top level must be an object" % rel(path))
        return None
    if data.get("parent") != "minecraft:item/generated":
        report.fail(rule, "%s: parent must be 'minecraft:item/generated', got %r" % (rel(path), data.get("parent")))
    textures = data.get("textures")
    if not isinstance(textures, dict) or not textures:
        report.fail(rule, "%s: no 'textures' object" % rel(path))
    else:
        for key in sorted(textures):
            value = textures[key]
            if not isinstance(value, str):
                report.fail(rule, "%s: textures.%s must be a string" % (rel(path), key))
                continue
            check_texture(value, rel(path), rule, report, seen)
    report.ok()
    return data


def check_overrides(model, report):
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
    for index, skin in enumerate(SKINS, start=1):
        expected = "%s:item/%s" % (MODID, skin)
        actual = by_data.get(index)
        if actual != expected:
            report.fail(rule, "custom_model_data %d must point at '%s', got %r" % (index, expected, actual))
        else:
            report.ok()


def check_recipe(report):
    rule = "recipe"
    data = read_json(RECIPE, rule, report)
    if data is None:
        return
    if not isinstance(data, dict):
        report.fail(rule, "%s: top level must be an object" % rel(RECIPE))
        return
    if data.get("type") != "minecraft:crafting_shaped":
        report.fail(rule, "%s: type must be 'minecraft:crafting_shaped', got %r" % (rel(RECIPE), data.get("type")))
    pattern = data.get("pattern")
    if not isinstance(pattern, list) or len(pattern) != 3:
        report.fail(rule, "%s: 'pattern' must be three rows" % rel(RECIPE))
    else:
        for row in pattern:
            if not isinstance(row, str) or len(row) != 3:
                report.fail(rule, "%s: every pattern row must be three characters, got %r" % (rel(RECIPE), row))
    keys = data.get("key")
    if not isinstance(keys, dict) or not keys:
        report.fail(rule, "%s: 'key' must map every pattern symbol" % rel(RECIPE))
    else:
        symbols = set()
        if isinstance(pattern, list):
            for row in pattern:
                if isinstance(row, str):
                    symbols.update(character for character in row if character != " ")
        unmapped = sorted(symbol for symbol in symbols if symbol not in keys)
        if unmapped:
            report.fail(rule, "%s: pattern symbol(s) %s have no 'key' entry"
                        % (rel(RECIPE), ", ".join(unmapped)))
        else:
            report.ok()
    result = data.get("result")
    item = result.get("item") if isinstance(result, dict) else result
    if item != "%s:%s" % (MODID, ITEM_ID):
        report.fail(rule, "%s: result must be '%s:%s', got %r" % (rel(RECIPE), MODID, ITEM_ID, item))
    else:
        report.ok()


def check_appearance(report):
    rule = "item-appearance"
    data = read_json(APPEARANCE, rule, report)
    if data is None:
        return
    if not isinstance(data, dict):
        report.fail(rule, "%s: top level must be an object" % rel(APPEARANCE))
        return
    items = data.get("items")
    if not isinstance(items, dict):
        report.fail(rule, "%s: 'items' must be an object mapping item ids to model ids" % rel(APPEARANCE))
        return
    for key in sorted(items):
        if not isinstance(key, str) or key.count(":") != 1:
            report.fail(rule, "%s: %r is not a namespace:path item id" % (rel(APPEARANCE), key))
    report.ok()


def check_kubejs(report, seen):
    rule = "kubejs-example"
    data = read_json(KUBEJS_MODEL, rule, report)
    if data is not None:
        if not isinstance(data, dict) or not isinstance(data.get("textures"), dict):
            report.fail(rule, "%s: expected a model with a 'textures' object" % rel(KUBEJS_MODEL))
        else:
            report.ok()
    check_png(KUBEJS_TEXTURE, "kubejs-examples", rule, report, seen)


def check_lang(report):
    rule = "lang"
    key = "item.%s.%s" % (MODID, ITEM_ID)
    key_sets = {}
    for locale in ("en_us", "zh_cn"):
        path = LANG_DIR / (locale + ".json")
        data = read_json(path, rule, report)
        if data is None or not isinstance(data, dict):
            continue
        key_sets[locale] = set(data)
        if key not in data:
            report.fail(rule, "%s: key '%s' is missing" % (rel(path), key))
        else:
            report.ok()
    if len(key_sets) == 2:
        only_en = sorted(key_sets["en_us"] - key_sets["zh_cn"])
        only_zh = sorted(key_sets["zh_cn"] - key_sets["en_us"])
        if only_en or only_zh:
            report.fail(rule, "key sets differ (only en_us=%s, only zh_cn=%s)" % (only_en, only_zh))
        else:
            report.ok()


def check_java(report):
    rule = "java-entry-points"
    for relative, needles in JAVA_CHECKS:
        path = JAVA / "com" / "structurereset" / relative
        if not path.is_file():
            report.fail(rule, "%s is missing" % rel(path))
            continue
        text = path.read_text(encoding="utf-8", errors="replace")
        missing = [needle for needle in needles if needle not in text]
        if missing:
            report.fail(rule, "%s does not declare: %s" % (rel(path), ", ".join(missing)))
        else:
            report.ok()


def check_distinct(report, seen):
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


def main() -> int:
    report = Report()
    seen = {}

    base = check_item_model(ITEM_ID, "item-model", report, seen)
    for skin in SKINS:
        check_item_model(skin, "skin-model", report, seen)
    if isinstance(base, dict):
        check_overrides(base, report)
        overrides = base.get("overrides")
        if isinstance(overrides, list):
            for entry in overrides:
                target = entry.get("model") if isinstance(entry, dict) else None
                if isinstance(target, str):
                    ns, _, path = target.partition(":")
                    if ns and path:
                        model_file = RESOURCES / "assets" / ns / "models" / (path + ".json")
                        if not model_file.is_file():
                            report.fail("model-overrides",
                                        "override '%s' has no model file (%s)" % (target, rel(model_file)))
    check_recipe(report)
    check_appearance(report)
    check_kubejs(report, seen)
    check_lang(report)
    check_java(report)
    check_distinct(report, seen)

    if report.problems:
        for rule, detail in report.problems:
            print("FAIL: %s: %s" % (rule, detail))
        print("validate-appearance: %d problem(s)" % len(report.problems))
        return 1
    print("OK: validate-appearance (%d check(s): models=%d textures=%d)"
          % (report.checks, 3, len(seen)))
    return 0


if __name__ == "__main__":
    sys.exit(main())
