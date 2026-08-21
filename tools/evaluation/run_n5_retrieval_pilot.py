"""Run the frozen N5.2-R1 retrieval PILOT against DocQuery's public HTTP API."""

from __future__ import annotations

import argparse
import datetime as dt
import json
import os
import platform
import time
import urllib.error
import urllib.request
from pathlib import Path
from typing import Any

from n3_eval_common import atomic_write_json, latency_summary, load_jsonl, sha256_file


MODES = ("KEYWORD", "SEMANTIC", "HYBRID")
RETRYABLE_HTTP = {429, 500, 502, 503, 504}
SCHEMA_VERSION = "docquery-n5.2-r1-retrieval-pilot-v1"


def parse_args() -> argparse.Namespace:
    root = Path(__file__).resolve().parents[2]
    dataset = root / "evaluation" / "mmlongbench-docquery-v1"
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", default="http://127.0.0.1:8080")
    parser.add_argument("--knowledge-base-id", type=int, default=1)
    parser.add_argument("--token-env", default="DOCQUERY_N5_APPLICATION_CREDENTIAL")
    parser.add_argument("--dataset", type=Path, default=dataset)
    parser.add_argument(
        "--selection",
        type=Path,
        default=dataset / "n5.2-r1-pilot-selection.json",
    )
    parser.add_argument(
        "--output-dir",
        type=Path,
        default=dataset / "reports" / "n5.2-r1-pilot",
    )
    parser.add_argument("--timeout-seconds", type=float, default=90.0)
    return parser.parse_args()


def load_pilot(dataset: Path, selection_path: Path) -> tuple[dict[str, Any], list[dict[str, Any]]]:
    selection = json.loads(selection_path.read_text(encoding="utf-8"))
    case_by_id = {case["caseId"]: case for case in load_jsonl(dataset / "cases.jsonl")}
    ids = selection.get("caseIds", [])
    if len(ids) != 10 or len(set(ids)) != 10:
        raise ValueError("PILOT selection must contain exactly 10 unique caseIds")
    missing = [case_id for case_id in ids if case_id not in case_by_id]
    if missing:
        raise ValueError(f"PILOT selection contains unknown caseIds: {missing}")
    cases = [case_by_id[case_id] for case_id in ids]
    if any(case.get("answerability") != "ANSWERABLE" for case in cases):
        raise ValueError("PILOT selection must contain only ANSWERABLE cases")
    document_keys = [case["relevance"][0]["documentKey"] for case in cases]
    if len(set(document_keys)) != 10:
        raise ValueError("PILOT selection must cover 10 unique source documents")
    domains = {case["sourceDomain"] for case in cases}
    if len(domains) != 7:
        raise ValueError("PILOT selection must cover all 7 source domains")
    return selection, cases


def authorization_from_env(variable: str) -> str:
    value = os.environ.get(variable, "").strip()
    if not value:
        raise ValueError(f"environment variable {variable} is empty")
    return value if value.lower().startswith("bearer ") else f"Bearer {value}"


def one_http_attempt(
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
            "X-DocQuery-Trace-Id": f"n5.2-r1:{idempotency_key}",
            "X-DocQuery-Actor-Ref": "n5.2-r1-retrieval-pilot",
        },
    )
    started = time.perf_counter()
    try:
        with urllib.request.urlopen(request, timeout=timeout_seconds) as response:
            content = response.read().decode("utf-8")
            return {
                "ok": 200 <= response.status < 300,
                "httpStatus": response.status,
                "requestId": response.headers.get("X-DocQuery-Request-Id"),
                "latencyMs": round((time.perf_counter() - started) * 1000, 3),
                "body": json.loads(content) if content else None,
            }
    except urllib.error.HTTPError as exc:
        content = exc.read().decode("utf-8", errors="replace")
        try:
            body = json.loads(content) if content else None
        except json.JSONDecodeError:
            body = {"unparsedBody": content[:500]}
        return {
            "ok": False,
            "httpStatus": exc.code,
            "requestId": exc.headers.get("X-DocQuery-Request-Id"),
            "latencyMs": round((time.perf_counter() - started) * 1000, 3),
            "body": body,
        }
    except (urllib.error.URLError, TimeoutError) as exc:
        return {
            "ok": False,
            "httpStatus": None,
            "requestId": None,
            "latencyMs": round((time.perf_counter() - started) * 1000, 3),
            "transportError": type(exc).__name__,
            "body": None,
        }


def post_with_one_retry(
    url: str,
    authorization: str,
    idempotency_key: str,
    payload: dict[str, Any],
    timeout_seconds: float,
) -> dict[str, Any]:
    attempts = []
    for number in (1, 2):
        result = one_http_attempt(url, authorization, idempotency_key, payload, timeout_seconds)
        attempts.append({key: value for key, value in result.items() if key != "body"})
        retryable = result.get("httpStatus") in RETRYABLE_HTTP or result.get("httpStatus") is None
        if result["ok"] or not retryable or number == 2:
            result["finalAttemptLatencyMs"] = result["latencyMs"]
            result["latencyMs"] = round(sum(item["latencyMs"] for item in attempts), 3)
            result["attemptCount"] = number
            result["attempts"] = attempts
            return result
    raise AssertionError("unreachable")


def safe_ranked_results(body: dict[str, Any] | None, name_to_key: dict[str, str]) -> list[dict[str, Any]]:
    ranked = []
    for index, result in enumerate((body or {}).get("results", []) or [], 1):
        evidence = []
        for item in result.get("evidence", []) or []:
            position = item.get("sourcePosition") or {}
            evidence.append({
                "blockId": item.get("blockId"),
                "pageNumber": position.get("pageNumber"),
                "truncated": bool(item.get("truncated")),
            })
        ranked.append({
            "rank": int(result.get("rank", index)),
            "documentKey": name_to_key.get(result.get("documentName")),
            "documentId": result.get("documentId"),
            "documentVersionId": result.get("documentVersionId"),
            "documentName": result.get("documentName"),
            "headingNodeId": result.get("headingNodeId"),
            "channels": result.get("channels", []),
            "keywordRank": result.get("keywordRank"),
            "semanticRank": result.get("semanticRank"),
            "evidence": evidence,
        })
    return sorted(ranked, key=lambda item: item["rank"])


def evaluate(case: dict[str, Any], ranked: list[dict[str, Any]]) -> dict[str, Any]:
    relevance = case["relevance"][0]
    gold_document = relevance["documentKey"]
    gold_pages = {int(page) for page in relevance["evidencePages"]}

    def document_hit(cutoff: int) -> float:
        return 1.0 if any(row["documentKey"] == gold_document for row in ranked[:cutoff]) else 0.0

    first_rank = next(
        (row["rank"] for row in ranked[:10] if row["documentKey"] == gold_document),
        None,
    )
    hit_pages = {
        int(item["pageNumber"])
        for row in ranked[:10]
        if row["documentKey"] == gold_document
        for item in row["evidence"]
        if item.get("pageNumber") is not None and int(item["pageNumber"]) in gold_pages
    }
    return {
        "documentRecallAt1": document_hit(1),
        "documentRecallAt5": document_hit(5),
        "documentRecallAt10": document_hit(10),
        "evidencePageRecallAt10": 1.0 if hit_pages else 0.0,
        "evidencePageCoverageAt10": len(hit_pages) / len(gold_pages),
        "allEvidencePagesHitAt10": 1.0 if hit_pages == gold_pages else 0.0,
        "reciprocalRankAt10": 1.0 / first_rank if first_rank else 0.0,
        "goldPages": sorted(gold_pages),
        "hitGoldPages": sorted(hit_pages),
    }


def mean(rows: list[dict[str, Any]], metric: str) -> float | None:
    values = [row["metrics"][metric] for row in rows if row.get("metrics")]
    return round(sum(values) / len(values), 6) if values else None


def aggregate(rows: list[dict[str, Any]]) -> dict[str, Any]:
    output: dict[str, Any] = {}
    for mode in MODES:
        subset = [row for row in rows if row["mode"] == mode]
        successful = [row for row in subset if row["http"]["ok"]]
        normal = [
            row for row in successful
            if not row["response"].get("degraded") and row["response"].get("executedMode") == mode
        ]
        metric_rows = normal if mode == "HYBRID" else successful
        output[mode] = {
            "attempts": len(subset),
            "successes": len(successful),
            "normalModeResponses": len(normal),
            "degradedResponses": sum(1 for row in successful if row["response"].get("degraded")),
            "retryCount": sum(max(0, row["http"]["attemptCount"] - 1) for row in subset),
            "latency": latency_summary([row["http"]["latencyMs"] for row in successful]),
            "qualityPopulation": len(metric_rows),
            **{
                metric: mean(metric_rows, metric)
                for metric in (
                    "documentRecallAt1",
                    "documentRecallAt5",
                    "documentRecallAt10",
                    "evidencePageRecallAt10",
                    "evidencePageCoverageAt10",
                    "allEvidencePagesHitAt10",
                    "reciprocalRankAt10",
                )
            },
        }
    return output


def markdown_report(report: dict[str, Any]) -> str:
    lines = [
        "# DocQuery N5.2-R1 检索 PILOT 报告",
        "",
        f"- 状态：`{report['status']}`",
        f"- 生成时间：`{report['generatedAt']}`",
        f"- PILOT：{report['dataset']['caseCount']} 道可回答题，30 个逻辑检索请求",
        f"- KnowledgeBase：`{report['configuration']['knowledgeBaseId']}`",
        f"- Ranking：`retrieve-ranking-v1`，`topK=10`",
        "- Answer / DeepSeek：未调用",
        "",
        "## 三种模式",
        "",
        "| 模式 | 成功 | 降级 | Doc R@5 | Page R@10 | MRR@10 | P95 ms |",
        "|---|---:|---:|---:|---:|---:|---:|",
    ]
    for mode in MODES:
        value = report["metrics"][mode]
        fmt = lambda item: "N/A" if item is None else f"{item:.4f}"
        lines.append(
            f"| {mode} | {value['successes']}/{value['attempts']} | {value['degradedResponses']} | "
            f"{fmt(value['documentRecallAt5'])} | {fmt(value['evidencePageRecallAt10'])} | "
            f"{fmt(value['reciprocalRankAt10'])} | {value['latency']['p95Ms'] or 'N/A'} |"
        )
    lines.extend([
        "",
        "## 边界",
        "",
        "该报告只代表固定 10 道 PILOT 在当前 100 份 READY PDF、当前 Application Grant、activeVersion 快照和 `retrieve-ranking-v1` 下的真实外部 HTTP 结果。PILOT 不是剩余 54 道正式验收分数，不包含 Answer 质量、DeepSeek 成本或并发压测。",
        "",
    ])
    return "\n".join(lines)


def write_jsonl(path: Path, rows: list[dict[str, Any]]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(
        "".join(json.dumps(row, ensure_ascii=False) + "\n" for row in rows),
        encoding="utf-8",
    )


def main() -> int:
    args = parse_args()
    dataset = args.dataset.resolve()
    selection_path = args.selection.resolve()
    output_dir = args.output_dir.resolve()
    selection, cases = load_pilot(dataset, selection_path)
    manifest_path = dataset / "manifest.json"
    cases_path = dataset / "cases.jsonl"
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    name_to_key = {
        document["displayName"]: document["documentKey"] for document in manifest["documents"]
    }
    authorization = authorization_from_env(args.token_env)
    endpoint = (
        f"{args.base_url.rstrip('/')}/api/v1/service/knowledge-bases/"
        f"{args.knowledge_base_id}/retrieve"
    )

    output_dir.mkdir(parents=True, exist_ok=True)
    raw_path = output_dir / "raw-results.jsonl"
    rows = load_jsonl(raw_path) if raw_path.exists() else []
    allowed = {(case["caseId"], mode) for case in cases for mode in MODES}
    existing_keys = [(row.get("caseId"), row.get("mode")) for row in rows]
    if any(key not in allowed for key in existing_keys) or len(existing_keys) != len(set(existing_keys)):
        raise ValueError("existing raw-results.jsonl is not a valid resumable R1 result set")
    completed = {(row["caseId"], row["mode"]) for row in rows}

    for case in cases:
        for mode in MODES:
            if (case["caseId"], mode) in completed:
                continue
            idempotency_key = f"n5.2-r1-pilot-v1-{case['caseId']}-{mode.lower()}"
            http = post_with_one_retry(
                endpoint,
                authorization,
                idempotency_key,
                {"query": case["question"], "mode": mode, "topK": 10},
                args.timeout_seconds,
            )
            body = http.pop("body", None)
            ranked = safe_ranked_results(body, name_to_key) if http["ok"] else []
            response = {
                "queryExecutionId": (body or {}).get("queryExecutionId"),
                "requestedMode": (body or {}).get("requestedMode"),
                "executedMode": (body or {}).get("executedMode"),
                "degraded": bool((body or {}).get("degraded")),
                "degradationReason": (body or {}).get("degradationReason"),
                "resultCount": len(ranked),
                "results": ranked,
            }
            if not http["ok"]:
                response["errorCode"] = (body or {}).get("code") or (body or {}).get("errorCode")
            rows.append({
                "schemaVersion": SCHEMA_VERSION,
                "caseId": case["caseId"],
                "sourceRowIndex": case["sourceRowIndex"],
                "sourceDomain": case["sourceDomain"],
                "answerFormat": case["answerFormat"],
                "mode": mode,
                "topK": 10,
                "http": http,
                "response": response,
                "metrics": evaluate(case, ranked) if http["ok"] else {},
            })
            write_jsonl(raw_path, rows)

    metrics = aggregate(rows)
    all_success = len(rows) == 30 and all(row["http"]["ok"] for row in rows)
    degraded = any(row["response"].get("degraded") for row in rows)
    status = (
        "COMPLETED_WITH_REQUEST_FAILURES" if not all_success
        else "COMPLETED_WITH_DEGRADATION" if degraded
        else "COMPLETED"
    )
    report = {
        "schemaVersion": SCHEMA_VERSION,
        "status": status,
        "generatedAt": dt.datetime.now(dt.timezone.utc).isoformat(),
        "dataset": {
            "version": manifest["datasetVersion"],
            "manifestSha256": sha256_file(manifest_path),
            "casesSha256": sha256_file(cases_path),
            "selectionSha256": sha256_file(selection_path),
            "selectionPolicy": selection["selectionPolicy"],
            "caseIds": selection["caseIds"],
            "caseCount": len(cases),
        },
        "configuration": {
            "baseUrl": args.base_url.rstrip("/"),
            "knowledgeBaseId": args.knowledge_base_id,
            "modes": list(MODES),
            "topK": 10,
            "rankingVersion": "retrieve-ranking-v1",
            "answerExecuted": False,
            "credentialEnvironmentVariable": args.token_env,
            "python": platform.python_version(),
            "platform": platform.platform(),
        },
        "usageBoundary": {
            "deepSeekCalls": 0,
            "queryEmbeddingExactTokenUsage": "NOT_AVAILABLE_IN_CURRENT_PUBLIC_API",
            "queryEmbeddingLogicalRequestUpperBound": 20,
        },
        "metrics": metrics,
        "artifacts": {"rawResults": raw_path.name},
    }
    atomic_write_json(output_dir / "report.json", report)
    (output_dir / "report.md").write_text(markdown_report(report), encoding="utf-8")
    print(json.dumps({
        "status": status,
        "logicalRequests": len(rows),
        "report": str(output_dir / "report.json"),
    }, ensure_ascii=False))
    return 0 if all_success and not degraded else 2


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except ValueError as exc:
        print(f"configuration error: {exc}", file=os.sys.stderr)
        raise SystemExit(2)
