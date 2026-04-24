#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
PRE_REQUEST Hook 示例。
用途：
1. 在请求发出前，对 systemPrompt / userPrompt 做二次加工。
2. 演示如何读取 stdin JSON 并返回 stdout JSON。
"""

import json
import sys
from typing import Any, Dict


def read_payload() -> Dict[str, Any]:
    raw = sys.stdin.read()
    if not raw.strip():
        return {}
    return json.loads(raw)


def main() -> None:
    payload = read_payload()

    system_prompt = str(payload.get("systemPrompt", ""))
    user_prompt = str(payload.get("userPrompt", ""))
    source_language = str(payload.get("sourceLanguage", ""))
    target_language = str(payload.get("targetLanguage", ""))

    extra_rule = (
        "\n[Hook追加要求]\n"
        f"- Source language: {source_language}\n"
        f"- Target explanation language: {target_language}\n"
        "- 必须只返回 JSON，不允许 Markdown 代码块。"
    )
    if extra_rule not in system_prompt:
        system_prompt += extra_rule

    if "请严格返回 JSON 数组" not in user_prompt:
        user_prompt += "\n请严格返回 JSON 数组，禁止输出解释性文本。"

    output = {
        "systemPrompt": system_prompt,
        "userPrompt": user_prompt
    }
    sys.stdout.write(json.dumps(output, ensure_ascii=False))


if __name__ == "__main__":
    main()

