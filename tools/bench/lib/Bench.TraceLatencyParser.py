#!/usr/bin/env python3
"""bench trace latency 响应解析器。

这个脚本只负责把 RCON 原始 bench 回包稳定地转成结构化 JSON，
避免 PowerShell 正则在超长响应和断行场景下承担主解析逻辑。
"""

from __future__ import annotations

import json
import re
import sys
from pathlib import Path
from typing import Any

VERTICAL_WHITESPACE_RE = re.compile(r"[\u000A\u000D\u0085\u2028\u2029]+")
ENTRY_RE = re.compile(
    r"\[RedstoneLink/Bench\]\s+trace_sync_latency_(?:summary|item)\b.*?"
    r"(?=\[RedstoneLink/Bench\]\s+trace_sync_latency_(?:summary|item)\b|$)",
    re.S,
)
FIELD_RE = re.compile(r"([A-Za-z][A-Za-z0-9]*)=([^\s\[]+)")
BOOL_RE = re.compile(r"(?i)^(?:true|tru|t|false|fals|f)")
LONG_RE = re.compile(r"^-?\d+")
INT_RE = re.compile(r"^-?\d+")
DIGIT_BOOL_RE = re.compile(r"^[01]")


def _normalize_text(text: str) -> str:
    return VERTICAL_WHITESPACE_RE.sub("", text or "")


def _parse_long(text: str) -> int:
    match = LONG_RE.match(text)
    if not match:
        raise ValueError(f"Invalid numeric latency field: {text}")
    return int(match.group(0))


def _parse_int(text: str) -> int:
    match = INT_RE.match(text)
    if not match:
        raise ValueError(f"Invalid numeric latency field: {text}")
    return int(match.group(0))


def _parse_bool(text: str) -> bool:
    bool_match = BOOL_RE.match(text)
    if bool_match:
        return bool_match.group(0).lower().startswith("t")
    digit_match = DIGIT_BOOL_RE.match(text)
    if digit_match:
        return digit_match.group(0) == "1"
    raise ValueError(f"Invalid boolean latency field: {text}")


def _convert_field(entry_kind: str, field_name: str, raw_value: str) -> Any:
    normalized = (raw_value or "").strip().rstrip(".,;")
    if normalized == "-":
        return None
    if entry_kind == "summary":
        if field_name in {
            "requested",
            "analyzed",
            "mounted",
            "matched",
            "unmatched",
            "expectedTickCount",
        }:
            return _parse_int(normalized)
        if field_name in {"expectedStartTick", "latestExpectedStartTick"}:
            return _parse_long(normalized)
        return normalized
    if entry_kind == "item":
        if field_name == "serial":
            return _parse_long(normalized)
        if field_name in {"matched", "mounted"}:
            return _parse_bool(normalized)
        if field_name in {"actualStartTick", "inputDelayTicks", "mountTick", "latestSampleTick"}:
            return _parse_long(normalized)
        if field_name == "eligibleSamples":
            return _parse_int(normalized)
        return normalized
    return normalized


def parse_response(text: str) -> dict[str, Any]:
    normalized = _normalize_text(text)
    summary: dict[str, Any] | None = None
    items: list[dict[str, Any]] = []
    for raw_entry in ENTRY_RE.findall(normalized):
        entry = raw_entry.strip()
        if not entry:
            continue
        if entry.startswith("[RedstoneLink/Bench] trace_sync_latency_summary"):
            entry_kind = "summary"
        elif entry.startswith("[RedstoneLink/Bench] trace_sync_latency_item"):
            entry_kind = "item"
        else:
            continue
        data: dict[str, Any] = {}
        for field_name, raw_value in FIELD_RE.findall(entry):
            data[field_name] = _convert_field(entry_kind, field_name, raw_value)
        if entry_kind == "summary":
            summary = data
        else:
            items.append(data)
    if summary is None:
        raise RuntimeError("trace_sync_latency response missing summary entry.")
    return {
        "summary": summary,
        "items": items,
    }


def main(argv: list[str]) -> int:
    if len(argv) != 3:
        print("Usage: Bench.TraceLatencyParser.py <input-path> <output-path>", file=sys.stderr)
        return 2
    input_path = Path(argv[1])
    output_path = Path(argv[2])
    text = input_path.read_text(encoding="utf-8")
    parsed = parse_response(text)
    output_path.write_text(
        json.dumps(parsed, ensure_ascii=True, separators=(",", ":")),
        encoding="utf-8",
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
