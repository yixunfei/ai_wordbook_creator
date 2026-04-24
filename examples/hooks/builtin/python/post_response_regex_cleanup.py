#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
POST_RESPONSE: 正则清理脚本

默认清理：
- 去掉 markdown 代码围栏
- \r\n / \n / \t 统一替换为空格
- 多空白压缩为单空格

可选环境变量：
- WORDBOOK_REGEX: 自定义正则
- WORDBOOK_REPLACE: 替换内容（默认空串）
"""

import json
import os
import re
import sys
from typing import Any, Dict


def read_payload() -> Dict[str, Any]:
    raw = sys.stdin.read()
    if not raw.strip():
        return {}
    return json.loads(raw)


def strip_fence(text: str) -> str:
    candidate = (text or "").strip()
    m = re.match(r"^```(?:json)?\s*(.*?)\s*```$", candidate, flags=re.DOTALL | re.IGNORECASE)
    return m.group(1).strip() if m else candidate


def normalize_whitespace(text: str) -> str:
    t = text.replace("\r\n", " ").replace("\n", " ").replace("\t", " ")
    t = re.sub(r"\s{2,}", " ", t)
    return t.strip()


def main() -> None:
    payload = read_payload()
    raw_response = str(payload.get("rawResponse", ""))

    cleaned = normalize_whitespace(strip_fence(raw_response))

    custom_regex = os.getenv("WORDBOOK_REGEX", "").strip()
    if custom_regex:
        replace_to = os.getenv("WORDBOOK_REPLACE", "")
        cleaned = re.sub(custom_regex, replace_to, cleaned, flags=re.DOTALL)

    output = {"rawResponse": cleaned}
    sys.stdout.write(json.dumps(output, ensure_ascii=False))


if __name__ == "__main__":
    main()

