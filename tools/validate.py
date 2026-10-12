#!/usr/bin/env python3
"""Generic, data-driven static validator for the Structure Reset workspace.

Usage:
    python tools/validate.py             # check the workspace, exit 0 when every rule passes
    python tools/validate.py --selftest  # check the validator itself (clean sample + defect samples)
    python tools/validate.py --jar       # re-run the resource rules inside the newest build/dist jar

The rules discover their own inputs (mod id, namespaces, registered items,
recipes, structure reset files) from the project contents; nothing is
hard-coded for one particular artefact.  Rules that need a source file that
does not exist yet are skipped.

Rule ids used in the FAIL lines:
    json-parse          every *.json under the resource root parses
    mods-toml           META-INF/mods.toml exists and carries the mandatory keys
    mod-items           every item found via register("id") has model + texture + lang + recipe
    model-refs          models/blockstates only reference files present in the pack
    structure-resets    data/*/structure_resets/*.json have structure + actions[].type
    recipes             data/*/recipes/*.json have type and result/ingredients
    lang-consistency    en_us.json and zh_cn.json have identical key sets
"""

from __future__ import annotations

import argparse
import json
import shutil
import sys
import tempfile
import zipfile
from pathlib import Path

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    sys.stderr.reconfigure(encoding="utf-8", errors="replace")
except Exception:  # pragma: no cover
    pass

SCRIPT = Path(__file__).resolve()
WORKSPACE = SCRIPT.parent.parent

# Secondary Gradle artefacts that must never be mistaken for the mod jar.
# "-dev.jar" is deliberately *not* listed: build/dist/<modid>-<version>-dev.jar
# produced by tools/package.py is the primary offline artefact.
SUFFIXES = ("-sources.jar", "-javadoc.jar")


# --------------------------------------------------------------------- utils

def _read_text(path: Path) -> str:
    return path.read_text(encoding="utf-8", errors="replace")


def _split_ref(ref: str):
    """Return (namespace or None, path) for a resource reference."""
    if ":" in ref:
        ns, _, path = ref.partition(":")
        return ns, path
    return None, ref


class PackValidator:
    def __init__(self, resources, java_root=None, label="workspace"):
        self.resources = Path(resources)
        self.java_root = Path(java_root) if java_root else None
        self.label = label
        self.failures = []
        self.counts = {}

    # ------------------------------------------------------------ plumbing

    def fail(self, rule, detail):
        self.failures.append((rule, detail))

    def rel(self, path) -> str:
        try:
            return Path(path).relative_to(self.resources).as_posix()
        except ValueError:
            return Path(path).as_posix()

    def namespaces(self):
        assets = self.resources / "assets"
        if not assets.is_dir():
            return []
        return sorted(p.name for p in assets.iterdir() if p.is_dir())

    def modid(self):
        toml = self.resources / "META-INF" / "mods.toml"
        if toml.is_file():
            import re
            m = re.search(r'(?m)^\s*modId\s*=\s*"([^"]+)"', _read_text(toml))
            if m:
                return m.group(1)
        ns = self.namespaces()
        return ns[0] if len(ns) == 1 else None

    def _texture_candidates(self, ns, path):
        base = self.resources / "assets" / ns / "textures"
        cands = []
        if Path(path).suffix:
            cands.append(base / path)
        else:
            cands.append(base / (path + ".png"))
        cands.append(base / path)
        return cands

    def _resolve_texture(self, ref, owner_ns):
        """Return the texture file of a reference, or None when it is missing."""
        ns, path = _split_ref(ref)
        if ns is None:
            ns = owner_ns
        if not ns or not (self.resources / "assets" / ns).is_dir():
            return None
        for cand in self._texture_candidates(ns, path):
            if cand.is_file():
                return cand
        return None

    def _check_texture_ref(self, rule, where, ref, owner_ns):
        """Strict for prefixed in-pack references, forgiving for unprefixed ones."""
        ns, path = _split_ref(ref)
        if ns is None:
            if self._resolve_texture(ref, owner_ns) is not None:
                return True
            return False  # may be a vanilla texture: not reportable here
        if not (self.resources / "assets" / ns).is_dir():
            return True  # reference outside this pack
        if self._resolve_texture(ref, owner_ns) is not None:
            return True
        self.fail(rule, "%s: texture '%s' resolves to assets/%s/textures/%s which is missing"
                  % (where, ref, ns, path))
        return False

    def _check_model_ref(self, rule, where, ref, default_ns="minecraft"):
        ns, path = _split_ref(ref)
        if ns is None:
            ns = default_ns
        if not ns or not (self.resources / "assets" / ns).is_dir():
            return  # vanilla or foreign namespace: cannot be checked inside this pack
        name = path if path.endswith(".json") else path + ".json"
        cand = self.resources / "assets" / ns / "models" / name
        if not cand.is_file():
            self.fail(rule, "%s: model '%s' resolves to assets/%s/models/%s which is missing"
                      % (where, ref, ns, name))

    # --------------------------------------------------------------- rules

    def rule_json_parse(self):
        rule = "json-parse"
        count = 0
        for path in sorted(self.resources.rglob("*.json")):
            count += 1
            try:
                json.loads(_read_text(path))
            except Exception as exc:
                self.fail(rule, "%s: invalid JSON (%s)" % (self.rel(path), exc))
        self.counts[rule] = count

    def rule_mods_toml(self):
        rule = "mods-toml"
        toml = self.resources / "META-INF" / "mods.toml"
        if not toml.is_file():
            self.fail(rule, "META-INF/mods.toml is missing")
            return
        import re
        text = _read_text(toml)
        for key in ("modId", "version", "displayName", "license"):
            if not re.search(r"(?m)^\s*" + key + r"\s*=", text):
                self.fail(rule, "mods.toml has no '" + key + "' entry")
        dep_modids = set()
        for block in re.split(r"(?m)^\s*\[\[dependencies\.", text)[1:]:
            m = re.search(r'(?m)^\s*modId\s*=\s*"([^"]+)"', block)
            if m:
                dep_modids.add(m.group(1))
        for needed in ("forge", "minecraft"):
            if needed not in dep_modids:
                self.fail(rule, "mods.toml has no [[dependencies.*]] block for '" + needed + "'")
        self.counts[rule] = 1

    def rule_mod_items(self):
        rule = "mod-items"
        if self.java_root is None or not self.java_root.is_dir():
            return
        sources = sorted(self.java_root.rglob("ModItems.java"))
        if not sources:
            return  # rule group is skipped while ModItems.java does not exist
        modid = self.modid()
        if not modid:
            self.fail(rule, "cannot determine the mod id (no mods.toml, ambiguous assets/ layout)")
            return
        import re
        ids = []
        for src in sources:
            for m in re.finditer(r'register\s*\(\s*"([^"]+)"', _read_text(src)):
                if m.group(1) not in ids:
                    ids.append(m.group(1))
        if not ids:
            self.fail(rule, "ModItems.java declares no register(\"id\") call (refusing to pass vacuously)")
            self.counts[rule] = 0
            return
        for item_id in ids:
            model = self.resources / "assets" / modid / "models" / "item" / (item_id + ".json")
            in_pack_texture = False
            if not model.is_file():
                self.fail(rule, "item '%s': missing model assets/%s/models/item/%s.json"
                          % (item_id, modid, item_id))
            else:
                where = "item '%s' model %s" % (item_id, self.rel(model))
                try:
                    data = json.loads(_read_text(model))
                except Exception:
                    data = None  # already reported by json-parse
                textures = []
                if isinstance(data, dict):
                    raw = data.get("textures")
                    if isinstance(raw, dict):
                        textures = [v for v in raw.values() if isinstance(v, str)]
                if not textures:
                    self.fail(rule, "%s: references no texture at all" % where)
                for ref in textures:
                    if self._resolve_texture(ref, modid) is not None:
                        in_pack_texture = True
                    elif ":" in ref:
                        self._check_texture_ref(rule, where, ref, modid)
                if textures and not in_pack_texture:
                    self.fail(rule, "%s: none of the referenced textures (%s) exists in this pack"
                              % (where, ", ".join(textures)))
            lang = self.resources / "assets" / modid / "lang" / "en_us.json"
            key = "item.%s.%s" % (modid, item_id)
            if not lang.is_file():
                self.fail(rule, "item '%s': missing %s" % (item_id, self.rel(lang)))
            else:
                try:
                    lang_data = json.loads(_read_text(lang))
                except Exception:
                    lang_data = None
                if isinstance(lang_data, dict) and key not in lang_data:
                    self.fail(rule, "item '%s': lang key '%s' missing from %s"
                              % (item_id, key, self.rel(lang)))
            if not self._recipe_produces(modid, item_id):
                self.fail(rule, "item '%s': no recipe under data/%s/recipes/ produces it"
                          % (item_id, modid))
        self.counts[rule] = len(ids)

    def _result_matches(self, result, item_id, modid):
        targets = {item_id, modid + ":" + item_id}
        if isinstance(result, str):
            return result.strip() in targets
        if isinstance(result, dict):
            for key in ("item", "id"):
                value = result.get(key)
                if isinstance(value, str) and value.strip() in targets:
                    return True
            return False
        if isinstance(result, list):
            return any(self._result_matches(entry, item_id, modid) for entry in result)
        return False

    def _recipe_produces(self, modid, item_id):
        recipe_dir = self.resources / "data" / modid / "recipes"
        if not recipe_dir.is_dir():
            return False
        for path in sorted(recipe_dir.rglob("*.json")):
            try:
                data = json.loads(_read_text(path))
            except Exception:
                continue
            if isinstance(data, dict) and self._result_matches(data.get("result"), item_id, modid):
                return True
        return False

    def rule_model_refs(self):
        rule = "model-refs"
        count = 0
        for path in sorted(self.resources.glob("assets/*/models/**/*.json")):
            parts = path.relative_to(self.resources / "assets").parts
            owner_ns = parts[0]
            try:
                data = json.loads(_read_text(path))
            except Exception:
                continue  # reported by json-parse
            count += 1
            where = self.rel(path)
            for kind, ref in self._collect_model_refs(data):
                if kind == "model":
                    self._check_model_ref(rule, where, ref, default_ns="minecraft")
                else:
                    self._check_texture_ref(rule, where, ref, owner_ns)
        for path in sorted(self.resources.glob("assets/*/blockstates/**/*.json")):
            parts = path.relative_to(self.resources / "assets").parts
            owner_ns = parts[0]
            try:
                data = json.loads(_read_text(path))
            except Exception:
                continue
            count += 1
            where = self.rel(path)
            for kind, ref in self._collect_blockstate_refs(data):
                if kind == "model":
                    self._check_model_ref(rule, where, ref, default_ns="minecraft")
                else:
                    self._check_texture_ref(rule, where, ref, owner_ns)
        self.counts[rule] = count

    def _collect_model_refs(self, node, out=None):
        if out is None:
            out = []
        if isinstance(node, dict):
            for key, value in node.items():
                if key == "parent" and isinstance(value, str):
                    out.append(("model", value))
                elif key == "textures" and isinstance(value, dict):
                    for entry in value.values():
                        if isinstance(entry, str):
                            out.append(("texture", entry))
                else:
                    self._collect_model_refs(value, out)
        elif isinstance(node, list):
            for entry in node:
                self._collect_model_refs(entry, out)
        return out

    def _collect_blockstate_refs(self, node, out=None):
        if out is None:
            out = []
        if isinstance(node, dict):
            for key, value in node.items():
                if key == "model" and isinstance(value, str):
                    out.append(("model", value))
                elif key == "textures" and isinstance(value, dict):
                    for entry in value.values():
                        if isinstance(entry, str):
                            out.append(("texture", entry))
                else:
                    self._collect_blockstate_refs(value, out)
        elif isinstance(node, list):
            for entry in node:
                self._collect_blockstate_refs(entry, out)
        return out

    def rule_structure_resets(self):
        rule = "structure-resets"
        count = 0
        for path in sorted(self.resources.glob("data/*/structure_resets/**/*.json")):
            count += 1
            try:
                data = json.loads(_read_text(path))
            except Exception:
                continue  # reported by json-parse
            where = self.rel(path)
            if not isinstance(data, dict):
                self.fail(rule, where + ": top level must be an object")
                continue
            if "structure" not in data:
                self.fail(rule, where + ": missing required field 'structure'")
            if "actions" not in data:
                self.fail(rule, where + ": missing required field 'actions'")
                continue
            actions = data.get("actions")
            if not isinstance(actions, list):
                self.fail(rule, where + ": 'actions' must be an array")
                continue
            for index, action in enumerate(actions):
                if not isinstance(action, dict):
                    self.fail(rule, "%s: actions[%d] must be an object" % (where, index))
                elif "type" not in action:
                    self.fail(rule, "%s: actions[%d] has no 'type' field" % (where, index))
        self.counts[rule] = count

    def rule_recipes(self):
        rule = "recipes"
        count = 0
        for path in sorted(self.resources.glob("data/*/recipes/**/*.json")):
            count += 1
            try:
                data = json.loads(_read_text(path))
            except Exception:
                continue
            where = self.rel(path)
            if not isinstance(data, dict):
                self.fail(rule, where + ": top level must be an object")
                continue
            if "type" not in data:
                self.fail(rule, where + ": missing required field 'type'")
            if "result" not in data and "ingredients" not in data:
                self.fail(rule, where + ": needs a 'result' or an 'ingredients' field")
        self.counts[rule] = count

    def _lang_keys(self, path):
        try:
            data = json.loads(_read_text(path))
        except Exception:
            return set()
        if not isinstance(data, dict):
            return set()
        return set(data.keys())

    def rule_lang_consistency(self):
        rule = "lang-consistency"
        count = 0
        for lang_dir in sorted(self.resources.glob("assets/*/lang")):
            files = {p.stem: p for p in lang_dir.glob("*.json")}
            if not files:
                continue
            count += 1
            if "en_us" not in files or "zh_cn" not in files:
                self.fail(rule, "%s: both en_us.json and zh_cn.json are required, found %s"
                          % (self.rel(lang_dir), ", ".join(sorted(p.name for p in files.values()))))
                continue
            en_keys = self._lang_keys(files["en_us"])
            zh_keys = self._lang_keys(files["zh_cn"])
            missing = sorted(en_keys - zh_keys)
            extra = sorted(zh_keys - en_keys)
            if missing or extra:
                detail = "%s: key sets differ" % self.rel(lang_dir)
                if missing:
                    detail += " (missing in zh_cn: %s)" % ", ".join(missing[:5])
                if extra:
                    detail += " (only in zh_cn: %s)" % ", ".join(extra[:5])
                self.fail(rule, detail)
        self.counts[rule] = count

    # ----------------------------------------------------------------- run

    def run(self):
        if not self.resources.is_dir():
            self.fail("resources-root", "resource root %s does not exist" % self.resources)
            return self
        checks = (
            ("json-parse", self.rule_json_parse),
            ("mods-toml", self.rule_mods_toml),
            ("mod-items", self.rule_mod_items),
            ("model-refs", self.rule_model_refs),
            ("structure-resets", self.rule_structure_resets),
            ("recipes", self.rule_recipes),
            ("lang-consistency", self.rule_lang_consistency),
        )
        for name, fn in checks:
            try:
                fn()
            except Exception as exc:  # a broken rule must fail loudly, not crash
                self.fail(name, "rule raised %s: %s" % (type(exc).__name__, exc))
        return self

    @property
    def passed(self):
        return not self.failures


def report(validator: PackValidator) -> int:
    for rule, detail in validator.failures:
        print("FAIL: %s: %s" % (rule, detail))
    if validator.failures:
        print("validate: %d failure(s) in %s" % (len(validator.failures), validator.label))
        return 1
    summary = ", ".join("%s=%d" % (k, v) for k, v in sorted(validator.counts.items()))
    print("OK: %s (%s)" % (validator.label, summary))
    return 0


def validate_workspace(workspace: Path) -> PackValidator:
    return PackValidator(
        workspace / "src" / "main" / "resources",
        workspace / "src" / "main" / "java",
        "workspace",
    ).run()


def _contains_mods_toml(path) -> bool:
    try:
        with zipfile.ZipFile(path) as archive:
            return "META-INF/mods.toml" in archive.namelist()
    except Exception:
        return False


def newest_jar(workspace: Path):
    """The newest mod jar: build/dist (tools/package.py) first, then build/libs (Gradle).

    Jars that actually contain META-INF/mods.toml win over ones that do not, so a
    stray sources/plain archive next to the mod jar can never be picked.
    """
    candidates = []
    for directory in (workspace / "build" / "dist", workspace / "build" / "libs"):
        if directory.is_dir():
            candidates.extend(p for p in directory.glob("*.jar")
                              if not p.name.endswith(SUFFIXES))
    if not candidates:
        return None
    mod_jars = [p for p in candidates if _contains_mods_toml(p)]
    pool = mod_jars if mod_jars else candidates
    return max(pool, key=lambda p: p.stat().st_mtime)


def validate_jar(workspace: Path) -> int:
    jar = newest_jar(workspace)
    if jar is None:
        print("FAIL: jar: no jar found under %s or %s"
              % (workspace / "build" / "dist", workspace / "build" / "libs"))
        return 1
    print("jar: checking %s" % jar)
    tmp = Path(tempfile.mkdtemp(prefix="structurereset-jar-"))
    try:
        try:
            with zipfile.ZipFile(jar) as archive:
                archive.extractall(tmp)
        except Exception as exc:
            print("FAIL: jar: cannot read %s (%s)" % (jar, exc))
            return 1
        java_root = workspace / "src" / "main" / "java"
        validator = PackValidator(tmp, java_root if java_root.is_dir() else None, "jar " + jar.name).run()
        return report(validator)
    finally:
        shutil.rmtree(tmp, ignore_errors=True)


# ---------------------------------------------------------------- selftest

SAMPLE_MODID = "example"

SAMPLE_MODS_TOML = """modLoader="javafml"
loaderVersion="[47,)"
license="MIT"

[[mods]]
modId="example"
version="1.0.0"
displayName="Example"
description='''
Sample pack used by the validator selftest.
'''

[[dependencies.example]]
modId="forge"
mandatory=true
versionRange="[47,)"
ordering="NONE"
side="BOTH"

[[dependencies.example]]
modId="minecraft"
mandatory=true
versionRange="[1.20.1,1.21)"
ordering="NONE"
side="BOTH"
"""

SAMPLE_MOD_ITEMS = """package com.example;

public final class ModItems {

    public static void registerAll() {
        REGISTRY.register("widget", WidgetItem::new);
    }
}
"""

SAMPLE_STRUCTURE_RESET = {
    "structure": "cataclysm:ruined_citadel",
    "actions": [
        {"type": "structurereset:respawn_boss", "entity": "cataclysm:ender_guardian"},
        {"type": "structurereset:refill_loot"},
    ],
}

SAMPLE_RECIPE = {
    "type": "minecraft:crafting_shaped",
    "pattern": ["###", "#X#", "###"],
    "key": {"#": {"item": "minecraft:stone"}, "X": {"item": "minecraft:diamond"}},
    "result": {"item": "example:widget"},
}


def _write(path: Path, text) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text, encoding="utf-8")


def _write_json(path: Path, data) -> None:
    _write(path, json.dumps(data, indent=2) + "\n")


def make_clean_sample(root: Path) -> Path:
    res = root / "src" / "main" / "resources"
    java = root / "src" / "main" / "java"
    _write(res / "META-INF" / "mods.toml", SAMPLE_MODS_TOML)
    _write_json(res / "pack.mcmeta", {"pack": {"pack_format": 15, "description": "sample"}})
    _write(java / "com" / "example" / "ModItems.java", SAMPLE_MOD_ITEMS)

    ns = SAMPLE_MODID
    _write_json(res / "assets" / ns / "models" / "item" / "widget.json",
                {"parent": "item/generated", "textures": {"layer0": ns + ":item/widget"}})
    _write(res / "assets" / ns / "textures" / "item" / "widget.png", "PNG-BYTES")
    _write_json(res / "assets" / ns / "models" / "block" / "casing.json",
                {"parent": "minecraft:block/cube_all", "textures": {"all": ns + ":block/casing"}})
    _write(res / "assets" / ns / "textures" / "block" / "casing.png", "PNG-BYTES")
    _write_json(res / "assets" / ns / "blockstates" / "casing.json",
                {"variants": {"": {"model": ns + ":block/casing"}}})
    _write_json(res / "assets" / ns / "lang" / "en_us.json",
                {"item." + ns + ".widget": "Widget", "itemGroup." + ns: "Example"})
    _write_json(res / "assets" / ns / "lang" / "zh_cn.json",
                {"item." + ns + ".widget": "部件", "itemGroup." + ns: "示例"})
    _write_json(res / "data" / ns / "recipes" / "widget.json", SAMPLE_RECIPE)
    _write_json(res / "data" / ns / "structure_resets" / "sample.json", SAMPLE_STRUCTURE_RESET)
    return root


def _defect_missing_texture(root: Path):
    (root / "src/main/resources/assets" / SAMPLE_MODID / "textures/item/widget.png").unlink()


def _defect_missing_lang_key(root: Path):
    path = root / "src/main/resources/assets" / SAMPLE_MODID / "lang/en_us.json"
    data = json.loads(_read_text(path))
    data.pop("item.%s.widget" % SAMPLE_MODID, None)
    _write_json(path, data)


def _defect_missing_recipe(root: Path):
    (root / "src/main/resources/data" / SAMPLE_MODID / "recipes/widget.json").unlink()


def _defect_bad_json(root: Path):
    _write(root / "src/main/resources/assets" / SAMPLE_MODID / "models/block/casing.json", "{ this is not json")


def _defect_bad_structure_reset(root: Path):
    _write_json(root / "src/main/resources/data" / SAMPLE_MODID / "structure_resets/broken.json",
                {"structure": "cataclysm:ruined_citadel"})


def _defect_bad_structure_actions(root: Path):
    _write_json(root / "src/main/resources/data" / SAMPLE_MODID / "structure_resets/broken.json",
                {"structure": "cataclysm:ruined_citadel", "actions": [{"entity": "minecraft:pig"}]})


def _defect_missing_parent_model(root: Path):
    path = root / "src/main/resources/assets" / SAMPLE_MODID / "models/block/casing.json"
    data = json.loads(_read_text(path))
    data["parent"] = SAMPLE_MODID + ":block/does_not_exist"
    _write_json(path, data)


def _defect_missing_blockstate_model(root: Path):
    path = root / "src/main/resources/assets" / SAMPLE_MODID / "blockstates/casing.json"
    data = json.loads(_read_text(path))
    data["variants"][""]["model"] = SAMPLE_MODID + ":block/does_not_exist"
    _write_json(path, data)


def _defect_empty_mod_items(root: Path):
    _write(root / "src/main/java/com/example/ModItems.java",
           "package com.example;\n\npublic final class ModItems {\n}\n")


def _defect_bad_recipe(root: Path):
    _write_json(root / "src/main/resources/data" / SAMPLE_MODID / "recipes/widget.json",
                {"result": {"item": SAMPLE_MODID + ":widget"}})


def _defect_lang_mismatch(root: Path):
    path = root / "src/main/resources/assets" / SAMPLE_MODID / "lang/en_us.json"
    data = json.loads(_read_text(path))
    data["tooltip.example.only_english"] = "Only English"
    _write_json(path, data)


def _defect_missing_mods_toml(root: Path):
    (root / "src/main/resources/META-INF/mods.toml").unlink()


DEFECTS = (
    ("missing-texture", _defect_missing_texture),
    ("missing-lang-key", _defect_missing_lang_key),
    ("missing-recipe", _defect_missing_recipe),
    ("bad-json", _defect_bad_json),
    ("bad-structure-resets", _defect_bad_structure_reset),
    ("bad-structure-actions", _defect_bad_structure_actions),
    ("missing-parent-model", _defect_missing_parent_model),
    ("missing-blockstate-model", _defect_missing_blockstate_model),
    ("empty-mod-items", _defect_empty_mod_items),
    ("bad-recipe", _defect_bad_recipe),
    ("lang-mismatch", _defect_lang_mismatch),
    ("missing-mods-toml", _defect_missing_mods_toml),
)


def selftest() -> int:
    tmp = Path(tempfile.mkdtemp(prefix="structurereset-validate-selftest-"))
    ok = True
    try:
        clean = tmp / "clean"
        make_clean_sample(clean)
        validator = validate_workspace(clean)
        if not validator.passed:
            ok = False
            print("FAIL: selftest: the clean sample did not pass")
            for rule, detail in validator.failures:
                print("      clean sample -> %s: %s" % (rule, detail))

        for name, mutate in DEFECTS:
            sample = tmp / name
            shutil.copytree(clean, sample)
            mutate(sample)
            broken = validate_workspace(sample)
            if broken.passed:
                ok = False
                print("FAIL: selftest: defect sample '%s' unexpectedly passed" % name)
    finally:
        shutil.rmtree(tmp, ignore_errors=True)

    print("validate selftest: " + ("OK" if ok else "FAILED"))
    return 0 if ok else 1


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description="Static validator for the Structure Reset workspace.")
    parser.add_argument("--selftest", action="store_true", help="check the validator itself")
    parser.add_argument("--jar", action="store_true", help="re-run the resource rules inside the newest jar")
    parser.add_argument("--workspace", default=None, help="Project root (defaults to this script's parent)")
    args = parser.parse_args(list(sys.argv[1:] if argv is None else argv))

    workspace = Path(args.workspace).resolve() if args.workspace else WORKSPACE

    if args.selftest:
        return selftest()
    if args.jar:
        return validate_jar(workspace)
    return report(validate_workspace(workspace))


if __name__ == "__main__":
    sys.exit(main())
