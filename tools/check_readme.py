#!/usr/bin/env python3
"""Read-only page check for README.md of Universal-Boss-Structure-Reset.

README.md is the project's shop window: it is what an mcmod.cn page opens with,
and a page that grows past one screen stops being read.  This checker keeps the
page publishable -- within its line cap, carrying every section a mod publisher
must show, and quoting facts that were read out of the sources instead of being
copied from an older revision.

Why this checker must never need write access
---------------------------------------------
tools/check_all.py runs it on a plain checkout, and the run-time check sandbox
denies writes outright.  A checker that copied README.md into a scratch
directory before judging it would then fail with a permission error that says
nothing about the page.  Every rule therefore works on text in memory: the
default run reads README.md and nothing else, and --selftest feeds the same
rules synthetically mutated strings instead of writing temporary files.

Rules (the rule id appears in every FAIL line):
    length              README.md is at most 110 lines
    code-blocks         at most one fenced code block, never a json fence
    intro               the blockquote right below the H1 is a single sentence
    sections            every required heading exists and has content
    docs-links          the documentation table links docs/DATAPACK.md and friends
    no-datapack-table   the datapack field table stays in docs/DATAPACK.md
    source-facts        modid, MC version, Forge range/version, licence, the item
                        id, the COMMON config path and the command root/leaf are
                        derived live from the sources and must appear verbatim

Usage:
    python tools/check_readme.py              # exit 0 when the page passes
    python tools/check_readme.py --selftest   # prove every rule rejects a defect
    python tools/check_readme.py --root DIR   # check another checkout
"""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    sys.stderr.reconfigure(encoding="utf-8", errors="replace")
except Exception:  # pragma: no cover - not available on every stream
    pass

SCRIPT = Path(__file__).resolve()
WORKSPACE = SCRIPT.parent.parent

README = "README.md"

# The page cap and the "one snippet at most" rule keep the README a summary.
MAX_LINES = 110
MAX_CODE_BLOCKS = 1

# token that must occur in a heading -> the section named in the FAIL line
REQUIRED_SECTIONS = (
    ("模组信息", "模组信息（加载器 / 支持版本 / modid / 前置依赖 / 许可 / 联机）"),
    ("特性", "特性"),
    ("快速上手", "快速开始"),
    ("配置", "配置"),
    ("命令", "命令"),
    ("兼容性与已知限制", "兼容性与已知问题"),
    ("文档", "文档链接"),
    ("许可", "许可与授权"),
)

REQUIRED_LINKS = (
    "docs/DATAPACK.md",
    "docs/LEARNING.md",
    "docs/KUBEJS.md",
    "docs/API.md",
    "docs/README.md",
)

# Content that belongs to docs/DATAPACK.md; a README carrying it has stopped
# being a summary and has started to duplicate a document that will drift.
FORBIDDEN_RESTATEMENTS = (
    ("| 字段 | 类型 |", "docs/DATAPACK.md 第 1 节的字段表"),
    ('"trigger"', "数据包定义 JSON 片段"),
)

HEADING_RE = re.compile(r"^(#{1,6})\s+(\S.*?)\s*$")
FENCE_RE = re.compile(r"(?m)^\s*```")
JSON_FENCE_RE = re.compile(r"(?m)^\s*```\s*json\b")


def read_text(path):
    return path.read_text(encoding="utf-8", errors="replace")


def headings(text):
    """[(line index, title)] for every markdown heading, in file order."""
    found = []
    for index, line in enumerate(text.splitlines()):
        match = HEADING_RE.match(line)
        if match:
            found.append((index, match.group(2)))
    return found


def property_value(text, key, default=None):
    """The value of a gradle.properties entry, without the trailing spaces."""
    match = re.search(r"(?m)^\s*" + re.escape(key) + r"\s*=\s*(.+?)\s*$", text)
    return match.group(1) if match else default


def source_facts(root):
    """The facts the page must quote, read live from the sources.

    Nothing here is hard coded: the modid, the versions, the licence, the item
    id, the config file name and the command names all come out of the files
    that actually drive the build, the registry, the config registration and
    the command tree, so a rename or a version bump makes this rule fail until
    the README is updated too.
    """
    properties = read_text(root / "gradle.properties")
    toml = read_text(root / "src/main/resources/META-INF/mods.toml")
    items = read_text(root / "src/main/java/com/structurereset/registry/ModItems.java")
    config = read_text(root / "src/main/java/com/structurereset/config/StructureResetConfig.java")
    command = read_text(root / "src/main/java/com/structurereset/command/StructureResetCommand.java")
    mod_id = property_value(properties, "mod_id", "")
    loader = re.search(r"loaderVersion\s*=\s*\"([^\"]+)\"", toml)
    licence = re.search(r"(?m)^license\s*=\s*\"([^\"]+)\"", toml)
    registered = re.search(r"ITEMS\.register\(\s*\"([^\"]+)\"", items)
    # Forge names a config file <modid>-<type>.toml; the type comes from the
    # registration itself, so the path below is derived, never guessed.
    config_types = set(re.findall(r"registerConfig\(ModConfig\.Type\.(\w+)", config))
    literals = re.findall(r'Commands\.literal\("([^"]+)"\)', command)
    # "/<root> <first sub command>" is simultaneously a command and not a file
    # path, so the README containing it cannot be satisfied by a stray path such
    # as config/<modid>-common.toml.
    command_head = "/" + literals[0] + " " + literals[1] if len(literals) >= 2 else ""
    return {
        "mod_id": mod_id,
        "minecraft_version": property_value(properties, "minecraft_version", ""),
        "forge_version": property_value(properties, "forge_version", ""),
        "loader_range": loader.group(1) if loader else "",
        "licence": licence.group(1) if licence else "",
        "item_id": mod_id + ":" + registered.group(1) if registered else "",
        "config_path": ("config/" + mod_id + "-common.toml") if "COMMON" in config_types else "",
        "command_head": command_head,
        "command_leaf": "teach-here" if "teach-here" in literals else "",
    }


def fact_requirements(facts):
    """(label, exact string the README must contain) for every source fact."""
    return (
        ("modid", "| modid | `" + facts["mod_id"] + "` |"),
        ("Minecraft 版本", facts["minecraft_version"]),
        ("Forge loaderVersion 范围", facts["loader_range"]),
        ("Forge 版本", facts["forge_version"]),
        ("许可", facts["licence"]),
        ("物品 id", facts["item_id"]),
        ("COMMON 配置文件", facts["config_path"]),
        ("命令头", facts["command_head"]),
        ("命令子项", facts["command_leaf"]),
    )


def check_text(text, facts):
    """Run every rule over the page text; returns a list of "rule: detail"."""
    failures = []
    lines = text.splitlines()

    if len(lines) > MAX_LINES:
        failures.append("length: README.md 有 %d 行，超过上限 %d 行"
                        % (len(lines), MAX_LINES))

    fences = FENCE_RE.findall(text)
    if len(fences) % 2 != 0:
        failures.append("code-blocks: 代码围栏数为奇数 %d" % len(fences))
    elif len(fences) // 2 > MAX_CODE_BLOCKS:
        failures.append("code-blocks: README.md 有 %d 个代码块，最多 %d 个"
                        % (len(fences) // 2, MAX_CODE_BLOCKS))
    if JSON_FENCE_RE.search(text):
        failures.append("code-blocks: 出现 json 代码块；数据包 JSON 属于 docs/DATAPACK.md")

    # intro: the first non blank line below the H1 must be the one sentence pitch
    title_index = None
    for index, line in enumerate(lines):
        if line.startswith("# "):
            title_index = index
            break
    pitch = None
    if title_index is not None:
        for line in lines[title_index + 1:]:
            if line.strip():
                pitch = line.strip()
                break
    if pitch is None:
        failures.append("intro: 标题后没有简介段落")
    elif not pitch.startswith(">"):
        failures.append("intro: 简介不是标题后的引用块（缺少 >）：%s" % pitch[:40])
    else:
        quote = pitch.lstrip(">").strip()
        if not quote.endswith("。"):
            failures.append("intro: 简介没有以句号结束")
        elif quote.count("。") != 1:
            failures.append("intro: 简介不是一句话（%d 个句号）" % quote.count("。"))

    # sections: required headings exist and are not left empty
    found = headings(text)
    for token, label in REQUIRED_SECTIONS:
        position = None
        for index, (_line, title) in enumerate(found):
            if token in title:
                position = index
                break
        if position is None:
            failures.append("sections: 缺少必需章节 %s（标题需含 '%s'）" % (label, token))
            continue
        start = found[position][0]
        end = found[position + 1][0] if position + 1 < len(found) else len(lines)
        body = [line for line in lines[start + 1:end] if line.strip()]
        if not body:
            failures.append("sections: 章节 %s 没有内容" % label)

    for link in REQUIRED_LINKS:
        if link not in text:
            failures.append("docs-links: README 未链接 %s" % link)

    for needle, what in FORBIDDEN_RESTATEMENTS:
        if needle in text:
            failures.append("no-datapack-table: README 重复了 %s" % what)

    for label, needle in fact_requirements(facts):
        if not needle:
            failures.append("source-facts: 无法从源码读出 %s" % label)
        elif needle not in text:
            failures.append("source-facts: README 未出现 %s（源代码 %s）" % (label, needle))

    return failures


def report(text, facts, failures):
    if failures:
        for failure in failures:
            print("FAIL: check-readme: " + failure)
        print("check-readme: %d failure(s)" % len(failures))
        return 1
    fences = FENCE_RE.findall(text)
    print("OK: check-readme: %d 行（上限 %d）、%d 个代码块、%d 个必需章节、"
          "%d 个文档链接、%d 个源码事实"
          % (len(text.splitlines()), MAX_LINES, len(fences) // 2,
             len(REQUIRED_SECTIONS), len(REQUIRED_LINKS), len(fact_requirements(facts))))
    return 0


def selftest(root):
    """Prove the rules are not vacuous, without writing anything to disk."""
    text = read_text(root / README)
    facts = source_facts(root)
    ok = True

    current = check_text(text, facts)
    if current:
        ok = False
        print("FAIL: selftest: the real README.md was rejected (%s)" % current[0])
    else:
        print("selftest: %-20s -> accepted" % "real-readme")

    defects = (
        ("length", text + ("填充行\n" * MAX_LINES)),
        ("code-blocks", text + "\n```text\nx\n```\n"),
        ("code-blocks", text + "\n```json\n{}\n```\n"),
        ("intro", text.replace("> 面向", "面向", 1)),
        ("intro", text.replace("——Boss 重生、战利品重填。",
                               "。Boss 重生。战利品重填。", 1)),
        ("sections", text.replace("## 命令", "## 用法", 1)),
        ("sections", text.replace("## 特性\n\n", "## 特性\n\n## 空章节\n\n", 1)),
        ("docs-links", text.replace("docs/DATAPACK.md", "docs/GONE.md")),
        ("no-datapack-table", text + "\n| 字段 | 类型 | 必填 |\n"),
        ("source-facts", text.replace("| modid | `" + facts["mod_id"] + "` |",
                                      "| modid | other-mod |")),
        ("source-facts", text.replace(facts["minecraft_version"] or "1.20.1", "1.20")),
        ("source-facts", text.replace("config/" + facts["mod_id"] + "-common.toml",
                                      "config/other.toml")),
        ("source-facts", text.replace(facts["command_head"], "/other sub")),
    )
    for rule, mutated in defects:
        failures = check_text(mutated, facts)
        if mutated == text:
            ok = False
            print("FAIL: selftest: 缺陷 %s 的注入锚点不存在，规则未被真正检验" % rule)
            continue
        if any(failure.startswith(rule + ":") for failure in failures):
            print("selftest: %-20s -> rejected" % rule)
        else:
            ok = False
            print("FAIL: selftest: 注入缺陷 %s 未被拒绝" % rule)

    print("check-readme selftest: " + ("OK" if ok else "FAILED"))
    return 0 if ok else 1


def main(argv):
    parser = argparse.ArgumentParser(
        description="Read-only page check for README.md (line cap, sections, source facts).")
    parser.add_argument("--selftest", action="store_true", help="prove every rule rejects a defect")
    parser.add_argument("--root", default=None,
                        help="Project root (defaults to this script's parent)")
    args = parser.parse_args(argv)

    root = Path(args.root).resolve() if args.root else WORKSPACE
    if args.selftest:
        return selftest(root)

    readme = root / README
    if not readme.is_file():
        print("FAIL: check-readme: %s is missing" % readme)
        return 1
    text = read_text(readme)
    facts = source_facts(root)
    return report(text, facts, check_text(text, facts))


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
