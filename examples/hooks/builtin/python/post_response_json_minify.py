#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
POST_RESPONSE: JSON 压缩脚本

输入：
{
  "stage": "POST_RESPONSE",
  "rawResponse": "..."
}

输出：
{
  "rawResponse": "压缩后的 JSON 文本"
}
"""

import json
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


def try_extract_json_text(text: str) -> Optional[str]:
    content = strip_fence(text)
    # 先尝试整体解析
    try:
        json.loads(content)
        return content
    except Exception:
        pass

    # 再尝试提取最外层 JSON（数组优先）
    arr_start = content.find("[")
    arr_end = content.rfind("]")
    if arr_start >= 0 and arr_end > arr_start:
        candidate = content[arr_start:arr_end + 1]
        try:
            json.loads(candidate)
            return candidate
        except Exception:
            pass

    obj_start = content.find("{")
    obj_end = content.rfind("}")
    if obj_start >= 0 and obj_end > obj_start:
        candidate = content[obj_start:obj_end + 1]
        try:
            json.loads(candidate)
            return candidate
        except Exception:
            pass

    return None


def main() -> None:
    payload = read_payload()
    raw_response = str(payload.get("rawResponse", ""))

    candidate = try_extract_json_text(raw_response)
    if candidate is None:
        # 无法识别为 JSON 时，安全回退为原文本清理版
        output = {"rawResponse": strip_fence(raw_response).strip()}
        sys.stdout.write(json.dumps(output, ensure_ascii=False))
        return

    data = json.loads(candidate)
    minified = json.dumps(data, ensure_ascii=False, separators=(",", ":"))
    output = {"rawResponse": minified}
    sys.stdout.write(json.dumps(output, ensure_ascii=False))


if __name__ == "__main__":
    main()

