#!/usr/bin/env python3
"""同步上游后剥离 CNB 残留（幂等、容错，不因找不到内容而失败）。

处理内容：
  1. utils/Github.java：删除 CNB 常量与 getCnbAsset/getJson/getApk/getAsset 等方法，
     并把 5 个 URL 常量纠正回本仓库 syzsky/webhtv
  2. 确认 CNB 同步 workflow 仍为禁用占位
改动会直接 git add/commit，供后续 push 步骤一起推送。
"""
import re
import subprocess
import sys
from pathlib import Path

GH = Path("app/src/main/java/com/fongmi/android/tv/utils/Github.java")
CNB_WF = Path(".github/workflows/cnb-release-sync.yml")
CNB_METHODS = {"getCnbAsset", "getJson", "getApk", "getAsset"}


def strip_java(text: str) -> str:
    lines = text.splitlines(keepends=True)
    out, i = [], 0
    while i < len(lines):
        line = lines[i]
        if re.match(r"\s*private\s+static\s+final\s+String\s+CNB\s*=", line):
            i += 1
            continue
        m = re.match(r"\s*public\s+static\s+String\s+(\w+)\s*\(", line)
        if m and m.group(1) in CNB_METHODS:
            depth = 0
            while i < len(lines):
                depth += lines[i].count("{") - lines[i].count("}")
                i += 1
                if depth <= 0:
                    break
            continue
        out.append(line)
        i += 1
    return "".join(out)


def main() -> int:
    changed = []
    if GH.exists():
        src = GH.read_text(encoding="utf-8")
        new = strip_java(src).replace("github.com/fish2018/webhtv", "github.com/syzsky/webhtv")
        if new != src:
            GH.write_text(new, encoding="utf-8")
            changed.append(str(GH))
    if CNB_WF.exists() and "disabled" not in CNB_WF.read_text(encoding="utf-8", errors="ignore"):
        print("warning: CNB workflow is not the disabled placeholder, please check")

    if changed:
        print("stripped:", ", ".join(changed))
        subprocess.run(["git", "add", "-A"], check=False)
        if subprocess.run(["git", "diff", "--cached", "--quiet"]).returncode != 0:
            subprocess.run(
                ["git", "commit", "-m", "chore: strip CNB residue after upstream sync"],
                check=False,
            )
        return 0

    print("no CNB residue found, nothing to do")
    return 0


if __name__ == "__main__":
    sys.exit(main())
