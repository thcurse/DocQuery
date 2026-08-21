"""Shared, dependency-free helpers for DocQuery N3 evaluation tools."""

from __future__ import annotations

import hashlib
import html
import json
import math
import os
import re
import time
import unicodedata
import urllib.error
import urllib.request
from pathlib import Path
from typing import Any


def normalize_text(value: str | None) -> str:
    visible = html.unescape(value or "")
    return re.sub(r"\s+", "", unicodedata.normalize("NFKC", visible)).casefold()


def percentile(values: list[float], quantile: float) -> float | None:
    if not values:
        return None
    ordered = sorted(values)
    index = max(0, math.ceil(quantile * len(ordered)) - 1)
    return round(ordered[index], 3)


def latency_summary(values: list[float]) -> dict[str, Any]:
    return {
        "count": len(values),
        "p50Ms": percentile(values, 0.50),
        "p95Ms": percentile(values, 0.95),
        "p99Ms": percentile(values, 0.99),
        "minMs": round(min(values), 3) if values else None,
        "maxMs": round(max(values), 3) if values else None,
        "meanMs": round(sum(values) / len(values), 3) if values else None,
    }


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def load_jsonl(path: Path) -> list[dict[str, Any]]:
    rows = []
    for number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        if line.strip():
            try:
                rows.append(json.loads(line))
            except json.JSONDecodeError as exc:
                raise ValueError(f"{path}:{number}: invalid JSON: {exc}") from exc
    return rows


def authorization_from_env(variable: str) -> str:
    value = os.environ.get(variable, "").strip()
    if not value:
        raise ValueError(f"environment variable {variable} is empty")
    return value if value.lower().startswith("bearer ") else f"Bearer {value}"


def post_json(
    url: str,
    authorization: str,
    idempotency_key: str,
    payload: dict[str, Any],
    timeout_seconds: float,
) -> dict[str, Any]:
    request = urllib.request.Request(
        url=url,
        method="POST",
        data=json.dumps(payload, ensure_ascii=False).encode("utf-8"),
        headers={
            "Authorization": authorization,
            "Content-Type": "application/json",
            "Accept": "application/json",
            "Idempotency-Key": idempotency_key,
            "X-DocQuery-Trace-Id": f"n3-eval:{idempotency_key}",
            "X-DocQuery-Actor-Ref": "n3-evaluation-runner",
        },
    )
    started = time.perf_counter()
    try:
        with urllib.request.urlopen(request, timeout=timeout_seconds) as response:
            body = response.read().decode("utf-8")
            return {
                "ok": 200 <= response.status < 300,
                "httpStatus": response.status,
                "requestId": response.headers.get("X-DocQuery-Request-Id"),
                "latencyMs": round((time.perf_counter() - started) * 1000, 3),
                "body": json.loads(body) if body else None,
            }
    except urllib.error.HTTPError as exc:
        body = exc.read().decode("utf-8", errors="replace")
        try:
            parsed = json.loads(body) if body else None
        except json.JSONDecodeError:
            parsed = {"unparsedBody": body[:1000]}
        return {
            "ok": False,
            "httpStatus": exc.code,
            "requestId": exc.headers.get("X-DocQuery-Request-Id"),
            "latencyMs": round((time.perf_counter() - started) * 1000, 3),
            "body": parsed,
        }
    except (urllib.error.URLError, TimeoutError) as exc:
        return {
            "ok": False,
            "httpStatus": None,
            "requestId": None,
            "latencyMs": round((time.perf_counter() - started) * 1000, 3),
            "transportError": str(exc),
            "body": None,
        }


def atomic_write_json(path: Path, value: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(path.suffix + ".tmp")
    temporary.write_text(
        json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    temporary.replace(path)
