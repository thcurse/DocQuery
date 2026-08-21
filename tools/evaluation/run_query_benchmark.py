"""Benchmark OWNER and Redis REPLAY paths through DocQuery public HTTP APIs."""

from __future__ import annotations

import argparse
import datetime as dt
import json
import platform
import sys
import time
import uuid
from concurrent.futures import ThreadPoolExecutor, as_completed
from pathlib import Path
from typing import Any

from n3_eval_common import atomic_write_json, authorization_from_env, latency_summary, post_json


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", default="http://127.0.0.1:8080")
    parser.add_argument("--knowledge-base-id", type=int, required=True)
    parser.add_argument("--token-env", default="DOCQUERY_APPLICATION_CREDENTIAL")
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument("--query", default="故障 E17 应如何复位？")
    parser.add_argument("--mode", choices=("KEYWORD", "SEMANTIC", "HYBRID"), default="HYBRID")
    parser.add_argument("--top-k", type=int, default=10)
    parser.add_argument("--requests", type=int, default=30)
    parser.add_argument("--concurrency", type=int, default=4)
    parser.add_argument("--operations", choices=("retrieve", "answer", "both"), default="both")
    parser.add_argument("--timeout-seconds", type=float, default=120.0)
    return parser.parse_args()


def execute_batch(
    operation: str,
    disposition: str,
    endpoint: str,
    authorization: str,
    keys: list[str],
    payload: dict[str, Any],
    concurrency: int,
    timeout_seconds: float,
) -> list[dict[str, Any]]:
    started = time.perf_counter()
    rows = []
    with ThreadPoolExecutor(max_workers=concurrency) as executor:
        futures = {
            executor.submit(post_json, endpoint, authorization, key, payload, timeout_seconds): index
            for index, key in enumerate(keys)
        }
        for future in as_completed(futures):
            response = future.result()
            rows.append({
                "operation": operation.upper(),
                "disposition": disposition,
                "requestIndex": futures[future],
                "http": response,
            })
    elapsed = time.perf_counter() - started
    for row in rows:
        row["batchElapsedSeconds"] = round(elapsed, 6)
    return sorted(rows, key=lambda row: row["requestIndex"])


def summarize(rows: list[dict[str, Any]]) -> dict[str, Any]:
    groups: dict[str, dict[str, Any]] = {}
    for operation in ("RETRIEVE", "ANSWER"):
        for disposition in ("OWNER", "REPLAY"):
            subset = [
                row for row in rows
                if row["operation"] == operation and row["disposition"] == disposition
            ]
            if not subset:
                continue
            elapsed = subset[0]["batchElapsedSeconds"]
            groups[f"{operation}_{disposition}"] = {
                "attempts": len(subset),
                "successes": sum(1 for row in subset if row["http"]["ok"]),
                "errors": sum(1 for row in subset if not row["http"]["ok"]),
                "throughputPerSecond": round(len(subset) / elapsed, 3) if elapsed else None,
                "latency": latency_summary([row["http"]["latencyMs"] for row in subset]),
            }
    return groups


def markdown(report: dict[str, Any]) -> str:
    lines = [
        "# DocQuery 查询性能基线",
        "",
        f"- runId: `{report['runId']}`",
        f"- generatedAt: `{report['generatedAt']}`",
        f"- baseUrl: `{report['configuration']['baseUrl']}`",
        f"- requests/group: `{report['configuration']['requests']}`",
        f"- concurrency: `{report['configuration']['concurrency']}`",
        "",
        "| Path | Success | Throughput/s | P50 ms | P95 ms | P99 ms |",
        "|---|---:|---:|---:|---:|---:|",
    ]
    for name, values in report["metrics"].items():
        latency = values["latency"]
        lines.append(
            f"| {name} | {values['successes']}/{values['attempts']} | "
            f"{values['throughputPerSecond']} | {latency['p50Ms']} | {latency['p95Ms']} | {latency['p99Ms']} |"
        )
    lines.extend([
        "",
        "OWNER 使用唯一幂等键；REPLAY 在 OWNER 完成后原样复用同一批键与请求体。脚本始终经过正式 HTTP、Credential、Grant、activeVersion、Redis 和查询审计链路。真实供应商与 Fake Gateway 的报告不得混为同一基线。",
        "",
    ])
    return "\n".join(lines)


def main() -> int:
    args = parse_args()
    if args.requests < 1 or args.concurrency < 1:
        raise ValueError("--requests and --concurrency must be positive")
    authorization = authorization_from_env(args.token_env)
    run_id = str(uuid.uuid4())
    base = args.base_url.rstrip("/")
    endpoint = f"{base}/api/v1/service/knowledge-bases/{args.knowledge_base_id}"
    operations = ("retrieve", "answer") if args.operations == "both" else (args.operations,)
    rows: list[dict[str, Any]] = []
    for operation in operations:
        keys = [f"n3-bench-{run_id}-{operation}-{index}" for index in range(args.requests)]
        payload = {"query": args.query, "mode": args.mode, "topK": args.top_k}
        url = f"{endpoint}/{operation}"
        owner = execute_batch(
            operation, "OWNER", url, authorization, keys, payload,
            args.concurrency, args.timeout_seconds,
        )
        rows.extend(owner)
        if all(row["http"]["ok"] for row in owner):
            rows.extend(execute_batch(
                operation, "REPLAY", url, authorization, keys, payload,
                args.concurrency, args.timeout_seconds,
            ))

    output = args.output_dir.resolve()
    output.mkdir(parents=True, exist_ok=True)
    raw = output / "raw-results.jsonl"
    raw.write_text(
        "".join(json.dumps(row, ensure_ascii=False) + "\n" for row in rows), encoding="utf-8"
    )
    report = {
        "schemaVersion": "n3-query-benchmark-v1",
        "runId": run_id,
        "generatedAt": dt.datetime.now(dt.timezone.utc).isoformat(),
        "configuration": {
            "baseUrl": base,
            "knowledgeBaseId": args.knowledge_base_id,
            "mode": args.mode,
            "topK": args.top_k,
            "requests": args.requests,
            "concurrency": args.concurrency,
            "operations": list(operations),
            "credentialEnvironmentVariable": args.token_env,
            "python": platform.python_version(),
            "platform": platform.platform(),
        },
        "metrics": summarize(rows),
        "artifacts": {"rawResults": raw.name},
    }
    atomic_write_json(output / "report.json", report)
    (output / "report.md").write_text(markdown(report), encoding="utf-8")
    failed = sum(1 for row in rows if not row["http"]["ok"])
    print(json.dumps({
        "runId": run_id, "report": str(output / "report.json"),
        "attempts": len(rows), "failures": failed,
    }, ensure_ascii=False))
    return 0 if failed == 0 else 2


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except ValueError as exc:
        print(f"configuration error: {exc}", file=sys.stderr)
        raise SystemExit(2)
