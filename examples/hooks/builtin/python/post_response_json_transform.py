#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
POST_RESPONSE: JSON 格式转换脚本

通过环境变量控制转换策略：
- WORDBOOK_JSON_TRANSFORM
  - normalize_array (默认): 对象包一层数组，数组保持不变
  - object_to_array: 对象转数组；数组保持不变
  - array_to_object_by_word: 数组按“单词”字段转对象
- WORDBOOK_JSON_OUTPUT
  - minify (默认)
  - pretty
"""

import json
import os
import re
import sys
from typing import Any, Dict, Optional


def read_payload() -> Dict[str, Any]:
    raw = sys.stdin.read()
    if not raw.strip():
        return {}
    return json.loads(raw)


def strip_fence(text: str) -> str:
    candidate = (text or "").strip()
    m = re.match(r"^```(?:json)?\s*(.*?)\s*```$", candidate, flags=re.DOTALL | re.IGNORECASE)
    return m.group(1).strip() if m else candidate


def try_parse_json(text: str) -> Optional[Any]:
    content = strip_fence(text)
    try:
        return json.loads(content)
    except Exception:
        pass

    for left, right in [("[", "]"), ("{", "}")]:
        s = content.find(left)
        e = content.rfind(right)
        if s >= 0 and e > s:
            candidate = content[s:e + 1]
            try:
                return json.loads(candidate)
            except Exception:
                continue
    return None


def transform_data(data: Any, mode: str) -> Any:
    mode = (mode or "normalize_array").strip().lower()
    if mode in ("normalize_array", "object_to_array"):
        if isinstance(data, list):
            return data
        return [data]

    if mode == "array_to_object_by_word":
        if not isinstance(data, list):
            return data
        ret = {}
        for item in data:
            if isinstance(item, dict):
                word = str(item.get("单词", "")).strip()
                key = word if word else f"item_{len(ret)+1}"
                ret[key] = item
            else:
                ret[f"item_{len(ret)+1}"] = item
        return ret

    return data


def dump_data(data: Any, style: str) -> str:
    style = (style or "minify").strip().lower()
    if style == "pretty":
        return json.dumps(data, ensure_ascii=False, indent=2)
    return json.dumps(data, ensure_ascii=False, separators=(",", ":"))


def main() -> None:
    payload = read_payload()
    raw_response = str(payload.get("rawResponse", ""))
    parsed = try_parse_json(raw_response)

    if parsed is None:
        # 无法解析 JSON 则原样回传（去围栏）
        output = {"rawResponse": strip_fence(raw_response).strip()}
        sys.stdout.write(json.dumps(output, ensure_ascii=False))
        return

    mode = os.getenv("WORDBOOK_JSON_TRANSFORM", "normalize_array")
    style = os.getenv("WORDBOOK_JSON_OUTPUT", "minify")
    transformed = transform_data(parsed, mode)
    output = {"rawResponse": dump_data(transformed, style)}
    sys.stdout.write(json.dumps(output, ensure_ascii=False))


if __name__ == "__main__":
    main()

