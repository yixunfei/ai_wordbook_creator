#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
多源词表增补工具：
- 多文件合并
- 去重
- 排序 / 乱序

示例：
python wordlist_merge_dedup_sort_shuffle.py \
  --inputs a.txt b.txt c.txt \
  --output merged.txt \
  --encoding utf-8 \
  --order sort

python wordlist_merge_dedup_sort_shuffle.py \
  --inputs a.txt b.txt \
  --output merged_shuffled.txt \
  --order shuffle \
  --seed 42
"""

import argparse
import random
from pathlib import Path
from typing import Iterable, List


def read_words(path: Path, encoding: str) -> List[str]:
    words: List[str] = []
    with path.open("r", encoding=encoding) as f:
        for line in f:
            word = line.strip()
            if not word:
                continue
            if word.startswith("#"):
                continue
            words.append(word)
    return words


def dedup_keep_order(words: Iterable[str], case_insensitive: bool) -> List[str]:
    seen = set()
    ret: List[str] = []
    for w in words:
        key = w.lower() if case_insensitive else w
        if key in seen:
            continue
        seen.add(key)
        ret.append(w)
    return ret


def apply_order(words: List[str], order: str, seed: int, case_insensitive: bool) -> List[str]:
    mode = (order or "original").strip().lower()
    if mode == "sort":
        return sorted(words, key=(lambda x: x.lower()) if case_insensitive else None)
    if mode == "shuffle":
        copied = list(words)
        rng = random.Random(seed) if seed is not None else random.Random()
        rng.shuffle(copied)
        return copied
    return words


def main() -> None:
    parser = argparse.ArgumentParser(description="Merge multi-source word files with dedup/sort/shuffle")
    parser.add_argument("--inputs", nargs="+", required=True, help="input files")
    parser.add_argument("--output", required=True, help="output file")
    parser.add_argument("--encoding", default="utf-8", help="file encoding, default utf-8")
    parser.add_argument("--order", choices=["original", "sort", "shuffle"], default="original")
    parser.add_argument("--seed", type=int, default=None, help="shuffle seed, only for --order shuffle")
    parser.add_argument("--case-insensitive", action="store_true", default=False, help="dedup/sort case insensitive")
    args = parser.parse_args()

    merged: List[str] = []
    for p in args.inputs:
        path = Path(p)
        if not path.exists():
            raise FileNotFoundError(f"input file not found: {path}")
        merged.extend(read_words(path, args.encoding))

    merged = dedup_keep_order(merged, case_insensitive=args.case_insensitive)
    merged = apply_order(merged, args.order, args.seed, case_insensitive=args.case_insensitive)

    output_path = Path(args.output)
    output_path.parent.mkdir(parents=True, exist_ok=True)
    with output_path.open("w", encoding=args.encoding) as f:
        for w in merged:
            f.write(w + "\n")

    print(f"done. total={len(merged)}, output={output_path}")


if __name__ == "__main__":
    main()

