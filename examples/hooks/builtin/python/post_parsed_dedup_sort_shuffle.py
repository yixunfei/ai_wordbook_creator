#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
POST_PARSED: 条目去重/排序/乱序脚本

环境变量：
- WORDBOOK_ENTRY_ORDER: original | sort | shuffle（默认 original）
- WORDBOOK_SHUFFLE_SEED: 乱序随机种子（可选）
"""

import json
import os
import random
import sys
from typing import Any, Dict, List, Tuple


def read_payload() -> Dict[str, Any]:
    raw = sys.stdin.read()
    if not raw.strip():
        return {}
    return json.loads(raw)


def normalize_word(word: str) -> str:
    return (word or "").strip().lower()


def dedup_entries(entries: Dict[str, Any]) -> List[Tuple[str, Any]]:
    seen = set()
    result: List[Tuple[str, Any]] = []
    for key, value in entries.items():
        # 优先用 JSON 内部“单词”字段作为归一键
        if isinstance(value, dict):
            token = str(value.get("单词", key))
        else:
            token = key
        norm = normalize_word(token)
        if norm in seen:
            continue
        seen.add(norm)
        result.append((key, value))
    return result


def reorder(items: List[Tuple[str, Any]]) -> List[Tuple[str, Any]]:
    mode = os.getenv("WORDBOOK_ENTRY_ORDER", "original").strip().lower()
    if mode == "sort":
        return sorted(items, key=lambda kv: normalize_word(kv[0]))
    if mode == "shuffle":
        seed = os.getenv("WORDBOOK_SHUFFLE_SEED", "").strip()
        rng = random.Random(int(seed)) if seed else random.Random()
        copied = list(items)
        rng.shuffle(copied)
        return copied
    return items


def main() -> None:
    payload = read_payload()
    entries = payload.get("entries", {})
    if not isinstance(entries, dict):
        entries = {}

    items = dedup_entries(entries)
    items = reorder(items)

    output_entries: Dict[str, Any] = {}
    for key, value in items:
        output_entries[key] = value

    output = {"entries": output_entries}
    sys.stdout.write(json.dumps(output, ensure_ascii=False))


if __name__ == "__main__":
    main()

