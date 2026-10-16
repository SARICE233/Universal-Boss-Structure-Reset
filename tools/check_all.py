#!/usr/bin/env python3
"""One command for every offline check of Universal-Boss-Structure-Reset.

This is the project's single verification entry point: it runs every surviving
check in order, prints exactly one line per check and exits non-zero as soon as
one of them fails.  The individual tools are still runnable on their own, but
they no longer have to be remembered or typed in sequence.

Usage:
    python tools/check_all.py               # run every check, one line each
    python tools/check_all.py --list        # print the checks and their commands
    python tools/check_all.py --only docs,kubejs
    python tools/check_all.py --skip tests,package
    python tools/check_all.py --selftest    # also prove each checker rejects defects

--list executes nothing at all: it only prints the table below, so it is safe on
a read-only checkout.
"""

from __future__ import annotations

import os
import subprocess
import sys
import time
from pathlib import Path

try:  # the checks print Chinese rule descriptions; keep the whole run UTF-8
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    sys.stderr.reconfigure(encoding="utf-8", errors="replace")
except Exception:  # pragma: no cover - not available on every stream
    pass

ROOT = Path(__file__).resolve().parent.parent

# name, what it proves, command (python <args>), optional selftest command
CHECKS = (
    ("privacy", "被跟踪的文件不含隐私材料（截图 / 日志 / 存档 / 密钥 / 本机绝对路径）",
     ("tools/validate_checks.py", "privacy"),
     None),
    ("skeleton", "工程骨架、版本号与 Forge 元数据自洽",
     ("tools/validate_checks.py", "skeleton"),
     ("tools/validate_checks.py", "skeleton", "--selftest")),
    ("pure-logic", "api 层不引用 Minecraft / Forge / KubeJS",
     ("tools/validate_checks.py", "pure-logic"),
     None),
    ("api", "引擎、配置与内置动作的实现完整且 id 一致",
     ("tools/validate_checks.py", "api"),
     ("tools/validate_checks.py", "api", "--selftest")),
    ("api-surface", "core API 表面、事件与示例定义完整",
     ("tools/validate_checks.py", "api-surface"),
     ("tools/validate_checks.py", "api-surface", "--selftest")),
    ("docs", "文档齐备、章节与引用一致",
     ("tools/validate_checks.py", "docs"),
     ("tools/validate_checks.py", "docs", "--selftest")),
    ("readme", "README 是可发布的精简页面（行数上限、必需章节、文档链接、事实与源码一致）",
     ("tools/check_readme.py",),
     ("tools/check_readme.py", "--selftest")),
    ("appearance", "物品、模型、贴图与配方齐备且贴图不是纯色占位",
     ("tools/validate_checks.py", "appearance"),
     None),
    ("coverage", "Cataclysm 14 条结构定义全部覆盖且字段合法",
     ("tools/validate_checks.py", "coverage"),
     ("tools/validate_checks.py", "coverage", "--selftest")),
    ("kubejs", "KubeJS 插件发现、绑定与示例脚本",
     ("tools/validate_checks.py", "kubejs"),
     ("tools/validate_checks.py", "kubejs", "--selftest")),
    ("syntax", "src/main/java 全部 .java 通过 JDK 17 javac 语法检查",
     ("tools/syntax_check.py",),
     ("tools/syntax_check.py", "--selftest")),
    ("resources", "资源与数据包规则（json / mods.toml / 模型 / 配方 / 语言键）",
     ("tools/validate.py",),
     ("tools/validate.py", "--selftest")),
    ("tests", "离线编译并真跑 JUnit 5 测试",
     ("tools/run_tests.py",),
     ("tools/run_tests.py", "--selftest")),
    ("package", "确定性产出 build/dist 的 dev jar",
     ("tools/package.py",),
     ("tools/package.py", "--selftest")),
    ("jar", "把资源规则在刚产出的 jar 内部重跑一遍",
     ("tools/validate.py", "--jar"),
     None),
)


def command_text(args):
    return "python " + " ".join(args)


def print_table():
    print("python tools/check_all.py --list   (read only: nothing below is executed)")
    width = max(len(name) for name, _, _, _ in CHECKS)
    for index, (name, what, args, selftest) in enumerate(CHECKS, start=1):
        print("%2d. %-*s  %s" % (index, width, name, command_text(args)))
        print("    %s" % what)
    print("--selftest additionally runs: %s"
          % ", ".join(name for name, _, _, st in CHECKS if st))


def select(names, only, skip):
    if only:
        wanted = {n.strip() for n in only.split(",") if n.strip()}
        unknown = wanted - set(names)
        if unknown:
            raise SystemExit("check_all: unknown check(s): %s" % ", ".join(sorted(unknown)))
        return [n for n in names if n in wanted]
    return [n for n in names if n not in skip]


def run(args, timeout):
    started = time.time()
    env = dict(os.environ, PYTHONIOENCODING="utf-8")
    try:
        completed = subprocess.run(
            [sys.executable] + list(args), cwd=str(ROOT), timeout=timeout, env=env,
            stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    except subprocess.TimeoutExpired:
        return 124, "timed out after %ds" % timeout, time.time() - started
    output = completed.stdout.decode("utf-8", "replace")
    return completed.returncode, output, time.time() - started


def tail(output, lines=12):
    kept = [line for line in output.splitlines() if line.strip()]
    return kept[-lines:]


def main(argv):
    only = skip = ""
    selftest = False
    listing = False
    timeout = 1800
    index = 0
    while index < len(argv):
        arg = argv[index]
        if arg == "--list":
            listing = True
        elif arg == "--selftest":
            selftest = True
        elif arg == "--only":
            index += 1
            only = argv[index]
        elif arg == "--skip":
            index += 1
            skip = argv[index]
        elif arg == "--timeout":
            index += 1
            timeout = int(argv[index])
        elif arg in ("-h", "--help"):
            print(__doc__.strip())
            return 0
        else:
            print("check_all: unknown argument %r (try --help)" % arg)
            return 2
        index += 1

    names = [name for name, _, _, _ in CHECKS]
    if listing:
        print_table()
        return 0

    selected = select(names, only, {n.strip() for n in skip.split(",") if n.strip()})
    if not selected:
        print("check_all: nothing selected")
        return 2
    if selftest:
        selected = selected + [name + " (selftest)" for name in selected
                               if dict((n, st) for n, _, _, st in CHECKS)[name]]

    failures = []
    for step, label in enumerate(selected, start=1):
        name = label.replace(" (selftest)", "")
        entry = dict((n, (w, a, st)) for n, w, a, st in CHECKS)[name]
        args = entry[2] if label.endswith("(selftest)") else entry[1]
        code, output, seconds = run(args, timeout)
        state = "ok  " if code == 0 else "FAIL"
        print("[%2d/%d] %s %-18s %6.1fs  %s"
              % (step, len(selected), state, label, seconds, command_text(args)))
        sys.stdout.flush()
        if code != 0:
            failures.append(label)
            for line in tail(output):
                print("        | %s" % line)
    if failures:
        print("check_all: FAILED (%d of %d): %s"
              % (len(failures), len(selected), ", ".join(failures)))
        return 1
    print("check_all: OK (%d check(s))" % len(selected))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
