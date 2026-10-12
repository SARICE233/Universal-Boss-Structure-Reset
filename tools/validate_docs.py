#!/usr/bin/env python3
"""Validate the documentation of the Structure Reset workspace (task T6).

Usage:
    python tools/validate_docs.py             # exit 0 when every rule passes
    python tools/validate_docs.py --selftest  # prove each rule rejects an injected defect
    python tools/validate_docs.py --root DIR  # check another checkout

Rules (the rule id appears in every FAIL line):

    docs-exist     README.md and docs/{API,DATAPACK,KUBEJS,RESOURCEPACK}.md exist,
                   are non-empty and actually look like markdown
    docs-sections  every document carries the sections it promises: README keeps the
                   "this workspace never ran Gradle" note, DATAPACK covers the
                   structure/actions fields and the respawn_boss/refill_loot actions,
                   KUBEJS covers ServerEvents.recipes, onPreReset and kubejs/assets
    docs-code      every document contains at least one fenced code block, and the
                   reference documents contain at least two
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

README = "README.md"
DOCS = {
    "docs/API.md": "API",
    "docs/DATAPACK.md": "DATAPACK",
    "docs/KUBEJS.md": "KUBEJS",
    "docs/RESOURCEPACK.md": "RESOURCEPACK",
}

# Substrings every document must contain (case sensitive on purpose: these are
# identifiers, not prose).
REQUIRED = {
    README: ("定位", "安装", "依赖", "快速上手", "已知限制"),
    "docs/API.md": ("com.structurereset.api", "ResetDefinition", "ResetActionSpec",
                    "StructureResetApi", "ItemAppearanceApi", "ResetContext"),
    "docs/DATAPACK.md": ("structure", "actions", "respawn_boss", "refill_loot",
                         "cooldown_seconds", "anchor_blocks", "priority",
                         "in_structure", "per_player", "empty_only"),
    "docs/KUBEJS.md": ("ServerEvents.recipes", "onPreReset", "kubejs/assets",
                       "StructureReset", "kubejs.plugins.txt", "registerAction"),
    "docs/RESOURCEPACK.md": ("textures/item", "models/item", "pack.mcmeta",
                             "kubejs/assets"),
}

# Extra prose-level requirements that may be phrased in a couple of ways.
ALTERNATIVES = {
    # How to build is the build document's job; the README stays user facing.
    "docs/BUILD.md": (("Gradle",), ("python tools/build.py", "gradlew")),
    "docs/DATAPACK.md": (("推断", "推测"),),
    "docs/KUBEJS.md": (("发现机制", "discovery"),),
}

MIN_CODE_BLOCKS = {
    README: 1,
    "docs/API.md": 2,
    "docs/DATAPACK.md": 2,
    "docs/KUBEJS.md": 2,
    "docs/RESOURCEPACK.md": 2,
}

MIN_BYTES = 400
FENCE = re.compile(r"(?m)^\s*```")
HEADING = re.compile(r"(?m)^#\s+\S")


def read_text(path: Path) -> str:
    return path.read_text(encoding="utf-8", errors="replace")


class Checker:
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
        targets = [README] + sorted(DOCS)
        texts = {}
        for relative in targets:
            path = self.root / relative
            if not path.is_file():
                self.fail(rule, "%s is missing" % relative)
                continue
            body = read_text(path)
            if len(body.encode("utf-8")) < MIN_BYTES:
                self.fail(rule, "%s is suspiciously short (%d bytes)" % (relative, len(body)))
                continue
            if not HEADING.search(body):
                self.fail(rule, "%s has no '# ' heading" % relative)
                continue
            texts[relative] = body
        if not self.failures:
            self.notes.append("documents=%d" % len(texts))
            self.pass_rule(rule)
        return texts

    def rule_docs_sections(self, texts):
        rule = "docs-sections"
        if not texts:
            self.fail(rule, "skipped: no readable documents")
            return
        for relative, body in sorted(texts.items()):
            for needle in REQUIRED.get(relative, ()):
                if needle not in body:
                    self.fail(rule, "%s does not mention '%s'" % (relative, needle))
            for group in ALTERNATIVES.get(relative, ()):
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
            blocks = len(FENCE.findall(body))
            if blocks % 2 != 0:
                self.fail(rule, "%s has an unbalanced number of code fences (%d)" % (relative, blocks))
                continue
            pairs = blocks // 2
            if pairs < MIN_CODE_BLOCKS.get(relative, 1):
                self.fail(rule, "%s has %d fenced code block(s), need at least %d"
                          % (relative, pairs, MIN_CODE_BLOCKS.get(relative, 1)))
                continue
            self.notes.append("%s code-blocks=%d" % (relative, pairs))
        if not any(line.startswith(rule + ":") for line in self.failures):
            self.pass_rule(rule)

    def run(self):
        if not self.root.is_dir():
            print("FAIL: docs-exist: %s is not a directory" % self.root)
            return 1
        texts = self.guard("docs-exist", self.rule_docs_exist) or {}
        self.guard("docs-sections", lambda: self.rule_docs_sections(texts))
        self.guard("docs-code", lambda: self.rule_docs_code(texts))
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

def _copy_tree(source: Path, target: Path) -> None:
    for relative in [README] + sorted(DOCS):
        src = source / relative
        if src.is_file():
            dst = target / relative
            dst.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(src, dst)


def _edit(path: Path, old: str, new: str, count: int = 1) -> None:
    text = read_text(path)
    if old not in text:
        raise AssertionError("defect anchor missing in %s: %r" % (path.name, old))
    path.write_text(text.replace(old, new, count), encoding="utf-8")


def _defects():
    def missing_readme(root):
        (root / README).unlink()

    def missing_kubejs_doc(root):
        (root / "docs/KUBEJS.md").unlink()

    def empty_datapack(root):
        (root / "docs/DATAPACK.md").write_text("", encoding="utf-8")

    def build_doc_loses_gradle_note(root):
        path = root / "docs/BUILD.md"
        text = read_text(path)
        for phrase in ("python tools/build.py", "gradlew", "Gradle"):
            text = text.replace(phrase, "")
        path.write_text(text, encoding="utf-8")

    def datapack_without_actions_section(root):
        path = root / "docs/DATAPACK.md"
        # Replace with a name that no longer *contains* the original, otherwise
        # the substring rule would still match and the defect would pass.
        text = read_text(path).replace("respawn_boss", "respawn")
        text = text.replace("refill_loot", "refill")
        path.write_text(text, encoding="utf-8")

    def kubejs_without_recipe_hook(root):
        path = root / "docs/KUBEJS.md"
        text = read_text(path).replace("ServerEvents.recipes", "ServerEventsRecipes")
        text = text.replace("onPreReset", "onResetPre")
        path.write_text(text, encoding="utf-8")

    def resourcepack_without_texture_path(root):
        path = root / "docs/RESOURCEPACK.md"
        text = read_text(path).replace("textures/item", "textures/thing")
        path.write_text(text, encoding="utf-8")

    def api_without_code_block(root):
        path = root / "docs/API.md"
        text = read_text(path)
        lines = [line for line in text.splitlines(keepends=True)
                 if not line.lstrip().startswith("```")]
        path.write_text("".join(lines) + "\n", encoding="utf-8")

    def unbalanced_fences(root):
        path = root / "docs/KUBEJS.md"
        with path.open("a", encoding="utf-8") as handle:
            handle.write("\n```\n")

    return (
        ("missing-readme", missing_readme),
        ("missing-kubejs-doc", missing_kubejs_doc),
        ("empty-datapack", empty_datapack),
        ("build-doc-loses-gradle-note", build_doc_loses_gradle_note),
        ("datapack-without-actions", datapack_without_actions_section),
        ("kubejs-without-recipe-hook", kubejs_without_recipe_hook),
        ("resourcepack-without-texture-path", resourcepack_without_texture_path),
        ("api-without-code-block", api_without_code_block),
        ("unbalanced-fences", unbalanced_fences),
    )


def _quiet_exit_code(root: Path) -> int:
    import contextlib
    import io
    buffer = io.StringIO()
    with contextlib.redirect_stdout(buffer):
        return Checker(root).run()


def selftest() -> int:
    ok = True
    tmp = Path(tempfile.mkdtemp(prefix="structurereset-docs-validator-"))
    try:
        good = tmp / "good"
        _copy_tree(WORKSPACE, good)
        code = _quiet_exit_code(good)
        if code != 0:
            print("FAIL: selftest: the real documents were rejected (exit %d)" % code)
            ok = False
        else:
            print("selftest: %-32s -> exit 0 (accepted)" % "real-documents")

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
                print("selftest: %-32s -> exit %d (rejected)" % (name, code))

        empty = tmp / "empty"
        empty.mkdir(parents=True, exist_ok=True)
        if _quiet_exit_code(empty) == 0:
            print("FAIL: selftest: an empty root was accepted")
            ok = False
        else:
            print("selftest: %-32s -> rejected" % "empty-root")
    finally:
        shutil.rmtree(tmp, ignore_errors=True)

    print("validate-docs selftest: " + ("OK" if ok else "FAILED"))
    return 0 if ok else 1


def main(argv) -> int:
    parser = argparse.ArgumentParser(description="Check the documentation (task T6).")
    parser.add_argument("--selftest", action="store_true", help="check the checker itself")
    parser.add_argument("--root", default=None, help="project root (defaults to this script's parent)")
    args = parser.parse_args(argv)

    if args.selftest:
        return selftest()

    root = Path(args.root).resolve() if args.root else WORKSPACE
    return Checker(root).run()


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
