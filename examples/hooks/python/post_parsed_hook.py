#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
POST_PARSED Hook 示例。
用途：
1. 在结构化结果完成后做轻量清洗。
2. 仅做“安全修改”，避免破坏必填字段与词条数量一致性。
"""

import json
import sys
from typing import Any, Dict


def read_payload() -> Dict[str, Any]:
    raw = sys.stdin.read()
    if not raw.strip():
        return {}
    return json.loads(raw)


def normalize_entries(entries: Dict[str, Any]) -> Dict[str, Any]:
    normalized: Dict[str, Any] = {}
    for key, value in entries.items():
        if not isinstance(value, dict):
            normalized[key] = value
            continue

        item = dict(value)
        if "单词" in item and isinstance(item["单词"], str):
            item["单词"] = item["单词"].strip()
        if not item.get("单词"):
            item["单词"] = key

        normalized[key] = item
    return normalized


def main() -> None:
    payload = read_payload()
    entries = payload.get("entries", {})
    if not isinstance(entries, dict):
        entries = {}

    output = {"entries": normalize_entries(entries)}
    sys.stdout.write(json.dumps(output, ensure_ascii=False))


if __name__ == "__main__":
    main()

