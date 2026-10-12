#!/usr/bin/env python3
"""Validate the KubeJS integration of the Structure Reset workspace (task T6).

Usage:
    python tools/validate_kubejs.py             # exit 0 when every rule passes
    python tools/validate_kubejs.py --selftest  # prove each rule rejects an injected defect
    python tools/validate_kubejs.py --root DIR  # check another checkout

Rules (the rule id appears in every FAIL line):

    jar-cached        tools/lib/kubejs-forge-<version>.jar exists, is a real zip and
                      matches the kubejs_version pinned in gradle.properties
    discovery         the plugin discovery file name is *measured* from that jar
                      (tools/fetch_kubejs.py, same scanner) and the matching
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

from __future__ import annotations

import argparse
import re
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

sys.path.insert(0, str(SCRIPT.parent))
import fetch_kubejs  # noqa: E402  (sibling module; holds the measured scanner)

# Where the two candidate discovery artefacts live inside a mod jar.
DISCOVERY_LOCATIONS = {
    fetch_kubejs.PLUGINS_TXT: Path(fetch_kubejs.PLUGINS_TXT),
    fetch_kubejs.SERVICE_PATH: Path(fetch_kubejs.SERVICE_PATH),
}

PLUGIN_CLASS_FQN = "com.structurereset.kubejs.StructureResetKubeJSPlugin"
PLUGIN_SOURCE = Path("src/main/java/com/structurereset/kubejs/StructureResetKubeJSPlugin.java")
BINDING_SOURCES = {
    "plugin": Path("src/main/java/com/structurereset/kubejs/StructureResetKubeJSPlugin.java"),
    "bindings": Path("src/main/java/com/structurereset/kubejs/StructureResetBindings.java"),
    "events": Path("src/main/java/com/structurereset/kubejs/StructureResetEvents.java"),
    "reset-event": Path("src/main/java/com/structurereset/kubejs/ResetEventJS.java"),
    "reset-context": Path("src/main/java/com/structurereset/kubejs/ResetContextJS.java"),
}

EXAMPLE_DIR = Path("kubejs-examples")

# Binding names a pack author may use; the rule needs 3 different ones.
KNOWN_BINDINGS = ("StructureReset", "StructureResetEvents", "ServerEvents",
                  "ItemEvents", "PlayerEvents", "EntityEvents", "BlockEvents")

MIN_EXAMPLES = 4
MIN_BINDINGS = 3


def read_text(path: Path) -> str:
    return path.read_text(encoding="utf-8", errors="replace")


class Checker:
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
        return read_text(path)

    def gradle_property(self, key):
        text = self.text("gradle.properties") or ""
        match = re.search(r"(?m)^\s*" + re.escape(key) + r"\s*=\s*(.+?)\s*$", text)
        return match.group(1) if match else None

    # --------------------------------------------------------------- rules

    def jar_path(self):
        version = self.gradle_property("kubejs_version") or fetch_kubejs.KUBEJS_VERSION
        return self.root / "tools" / "lib" / ("kubejs-forge-%s.jar" % version)

    def rule_jar_cached(self):
        rule = "jar-cached"
        jar = self.jar_path()
        if not jar.is_file():
            self.fail(rule, "%s is missing (run python tools/fetch_kubejs.py)" % jar)
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
            self.fail(rule, "%s is not a readable zip (%s) -- re-run tools/fetch_kubejs.py"
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
        hits, shipped = fetch_kubejs.scan_jar(jar)
        names = set(needle for _, needle in hits)
        if not names:
            self.fail(rule, "no class in %s references any known discovery file name (%s)"
                      % (jar.name, ", ".join(sorted(DISCOVERY_LOCATIONS))))
            return None
        measured = sorted(names)
        # The resource the mod must ship: prefer kubejs.plugins.txt when both are
        # referenced, because that is the file KubeJS 2001.x actually lists in.
        if fetch_kubejs.PLUGINS_TXT in names:
            chosen = fetch_kubejs.PLUGINS_TXT
        else:
            chosen = fetch_kubejs.SERVICE_PATH
        self.notes.append("measured discovery names=%s chosen=%s" % (measured, chosen))
        location = DISCOVERY_LOCATIONS[chosen]
        resource = self.root / "src" / "main" / "resources" / location
        if not resource.is_file():
            self.fail(rule, "measured discovery artefact '%s' is not shipped: %s is missing"
                      % (chosen, resource))
            return None
        listed = [line.strip() for line in read_text(resource).splitlines()]
        listed = [line for line in listed if line and not line.startswith("#")]
        if PLUGIN_CLASS_FQN not in listed:
            self.fail(rule, "%s does not list the plugin class %s (found: %s)"
                      % (location, PLUGIN_CLASS_FQN, listed or "nothing"))
            return None
        self.notes.append("shipped=%s" % location.as_posix())
        self.pass_rule(rule)
        return listed

    def rule_plugin_listed(self, listed):
        rule = "plugin-listed"
        if not listed:
            self.fail(rule, "skipped: no discovery file")
            return
        source = self.text(PLUGIN_SOURCE)
        if source is None:
            self.fail(rule, "%s is missing" % PLUGIN_SOURCE.as_posix())
            return
        package = "package " + PLUGIN_CLASS_FQN.rsplit(".", 1)[0] + ";"
        if package not in source:
            self.fail(rule, "%s does not declare '%s'" % (PLUGIN_SOURCE.as_posix(), package))
            return
        simple = PLUGIN_CLASS_FQN.rsplit(".", 1)[1]
        if not re.search(r"class\s+" + re.escape(simple) + r"\b", source):
            self.fail(rule, "%s does not declare class %s" % (PLUGIN_SOURCE.as_posix(), simple))
            return
        self.pass_rule(rule)

    def rule_plugin_api(self):
        rule = "plugin-api"
        source = self.text(PLUGIN_SOURCE)
        if source is None:
            self.fail(rule, "%s is missing" % PLUGIN_SOURCE.as_posix())
            return
        if "KubeJSPlugin" not in source:
            self.fail(rule, "%s does not mention KubeJSPlugin" % PLUGIN_SOURCE.as_posix())
            return
        # Measured: dev.latvian.mods.kubejs.KubeJSPlugin is a *class* in KubeJS
        # 2001.x (javap on the real jar), so extending it is the correct form;
        # "implements" is accepted too in case a future version turns it into an
        # interface.
        if not re.search(r"(?:implements|extends)\s+[^{]*KubeJSPlugin", source):
            self.fail(rule, "%s neither extends nor implements KubeJSPlugin"
                      % PLUGIN_SOURCE.as_posix())
            return
        if not re.search(r"registerBindings\s*\(\s*BindingsEvent", source):
            self.fail(rule, "%s declares no registerBindings(BindingsEvent)" % PLUGIN_SOURCE.as_posix())
            return
        self.pass_rule(rule)

    def _declares(self, source, names):
        missing = [n for n in names if not re.search(r"\b" + re.escape(n) + r"\s*\(", source)]
        return missing

    def rule_bindings_api(self):
        rule = "bindings-api"
        path = BINDING_SOURCES["bindings"]
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
            path = BINDING_SOURCES[key]
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
            path = BINDING_SOURCES[key]
            source = self.text(path)
            if source is None:
                self.fail(rule, "%s is missing" % path.as_posix())
                return
            if not re.search(r"class\s+\w+", source):
                self.fail(rule, "%s declares no class" % path.as_posix())
                return
        event = self.text(BINDING_SOURCES["reset-event"]) or ""
        for member in ("cancel", "setMessage", "actionsRun", "definition"):
            if member not in event:
                self.fail(rule, "ResetEventJS does not expose '%s'" % member)
                return
        context = self.text(BINDING_SOURCES["reset-context"]) or ""
        for member in ("level", "player", "structure", "origin", "box", "success"):
            if member not in context:
                self.fail(rule, "ResetContextJS does not expose '%s'" % member)
                return
        self.pass_rule(rule)

    def rule_example_scripts(self):
        rule = "example-scripts"
        root = self.root / EXAMPLE_DIR
        if not root.is_dir():
            self.fail(rule, "%s is missing" % EXAMPLE_DIR.as_posix())
            return
        scripts = sorted(p for p in root.rglob("*.js") if p.is_file())
        if len(scripts) < MIN_EXAMPLES:
            self.fail(rule, "found %d .js file(s) under %s, need at least %d"
                      % (len(scripts), EXAMPLE_DIR.as_posix(), MIN_EXAMPLES))
            return
        for sub in ("server_scripts", "startup_scripts"):
            if not (root / sub).is_dir():
                self.fail(rule, "%s/%s is missing" % (EXAMPLE_DIR.as_posix(), sub))
                return
        used = set()
        for path in scripts:
            body = read_text(path)
            for name in KNOWN_BINDINGS:
                if re.search(r"\b" + re.escape(name) + r"\b", body):
                    used.add(name)
        if len(used) < MIN_BINDINGS:
            self.fail(rule, "the %d example scripts only use %d binding name(s) (%s), need %d"
                      % (len(scripts), len(used), ", ".join(sorted(used)) or "none", MIN_BINDINGS))
            return
        comments = [p for p in scripts if re.search(r"(?m)^\s*//", read_text(p))]
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
                body = read_text(path)
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

_REQUIRED = [
    "gradle.properties",
    "tools/fetch_kubejs.py",
    "tools/validate_kubejs.py",
    "src/main/resources/" + fetch_kubejs.PLUGINS_TXT,
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


def _copy_tree(source: Path, target: Path) -> None:
    """Copy everything a run needs, plus the cached jar, into a scratch root."""
    for relative in _REQUIRED:
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


def _edit(path: Path, old: str, new: str, count: int = 1) -> None:
    text = read_text(path)
    if old not in text:
        raise AssertionError("defect anchor missing in %s: %r" % (path.name, old))
    path.write_text(text.replace(old, new, count), encoding="utf-8")


def _defects():
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
        (root / "src/main/resources" / fetch_kubejs.PLUGINS_TXT).unlink()

    def discovery_lists_wrong_class(root):
        path = root / "src/main/resources" / fetch_kubejs.PLUGINS_TXT
        path.write_text("com.example.NotOurPlugin\n", encoding="utf-8")

    def missing_plugin_source(root):
        (root / plugin).unlink()

    def plugin_not_a_kubejs_plugin(root):
        _edit(root / plugin, "extends KubeJSPlugin", "extends Object")

    def missing_register_bindings(root):
        _edit(root / plugin, "void registerBindings(BindingsEvent event)",
              "void notRegisteringBindings(BindingsEvent event)")

    def missing_binding_method(root):
        _edit(root / bindings, "public static boolean setItemModel",
              "public static boolean setItemModell")

    def missing_event_method(root):
        _edit(root / events, 'GROUP.server("onPostReset"', 'GROUP.server("onAfterReset"')

    def missing_context_member(root):
        _edit(root / context, "box", "boundingBox")

    def too_few_examples(root):
        for name in ("02_recipe_overrides.js", "03_events_and_actions.js"):
            for path in (root / "kubejs-examples").rglob(name):
                path.unlink()

    def one_binding_only(root):
        for path in (root / "kubejs-examples").rglob("*.js"):
            text = read_text(path)
            for name in ("StructureResetEvents", "ServerEvents", "ItemEvents"):
                text = text.replace(name, "StructureReset")
            path.write_text(text, encoding="utf-8")

    def mixin_appears(root):
        path = root / plugin
        text = read_text(path)
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


def _quiet_exit_code(root: Path) -> int:
    import contextlib
    import io
    buffer = io.StringIO()
    with contextlib.redirect_stdout(buffer):
        return Checker(root).run()


def selftest() -> int:
    ok = True
    tmp = Path(tempfile.mkdtemp(prefix="structurereset-kubejs-validator-"))
    try:
        good = tmp / "good"
        _copy_tree(WORKSPACE, good)
        code = _quiet_exit_code(good)
        if code != 0:
            print("FAIL: selftest: the real workspace was rejected (exit %d)" % code)
            ok = False
        else:
            print("selftest: %-30s -> exit 0 (accepted)" % "real-workspace")

        for name, defect in _defects():
            target = tmp / ("bad-" + name)
            _copy_tree(WORKSPACE, target)
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
                print("selftest: %-30s -> exit %d (rejected)" % (name, code))

        empty = tmp / "empty"
        empty.mkdir(parents=True, exist_ok=True)
        if _quiet_exit_code(empty) == 0:
            print("FAIL: selftest: an empty root was accepted")
            ok = False
        else:
            print("selftest: %-30s -> rejected" % "empty-root")
    finally:
        shutil.rmtree(tmp, ignore_errors=True)

    print("validate-kubejs selftest: " + ("OK" if ok else "FAILED"))
    return 0 if ok else 1


def main(argv) -> int:
    parser = argparse.ArgumentParser(description="Check the KubeJS integration (task T6).")
    parser.add_argument("--selftest", action="store_true", help="check the checker itself")
    parser.add_argument("--root", default=None, help="project root (defaults to this script's parent)")
    args = parser.parse_args(argv)

    if args.selftest:
        return selftest()

    root = Path(args.root).resolve() if args.root else WORKSPACE
    return Checker(root).run()


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
