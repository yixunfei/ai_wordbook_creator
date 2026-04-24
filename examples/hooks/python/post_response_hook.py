#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
POST_RESPONSE Hook 示例。
用途：
1. 清理模型返回中的 Markdown 代码围栏。
2. 将清理后的内容回写到 rawResponse。
"""

import json
import re
import sys
from typing import Any, Dict


def read_payload() -> Dict[str, Any]:
    raw = sys.stdin.read()
    if not raw.strip():
        return {}
    return json.loads(raw)


def strip_markdown_fence(text: str) -> str:
    candidate = (text or "").strip()
    # 常见格式：
    # ```json
    # ...
    # ```
    m = re.match(r"^```(?:json)?\s*(.*?)\s*```$", candidate, flags=re.DOTALL | re.IGNORECASE)
    if m:
        return m.group(1).strip()
    return candidate


def main() -> None:
    payload = read_payload()
    raw_response = str(payload.get("rawResponse", ""))
    cleaned = strip_markdown_fence(raw_response)

    output = {"rawResponse": cleaned}
    sys.stdout.write(json.dumps(output, ensure_ascii=False))


if __name__ == "__main__":
    main()

