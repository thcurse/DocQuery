"""Run the frozen N5.2-R2 HYBRID retrieval evaluation on the 54-case held-out set."""

from __future__ import annotations

import argparse
import datetime as dt
import json
import platform
from pathlib import Path
from typing import Any

from n3_eval_common import atomic_write_json, latency_summary, load_jsonl, sha256_file
from run_n5_retrieval_pilot import (
    authorization_from_env,
    evaluate,
    post_with_one_retry,
    safe_ranked_results,
    write_jsonl,
)


MODE = "HYBRID"
TOP_K = 10
SCHEMA_VERSION = "docquery-n5.2-r2-heldout-v1"
QUALITY_METRICS = {
    "documentHitAt1": "documentRecallAt1",
    "documentHitAt5": "documentRecallAt5",
    "documentHitAt10": "documentRecallAt10",
    "anyGoldPageHitAt10": "evidencePageRecallAt10",
    "evidencePageCoverageAt10": "evidencePageCoverageAt10",
    "allGoldPagesHitAt10": "allEvidencePagesHitAt10",
    "reciprocalRankAt10": "reciprocalRankAt10",
}


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
        default=dataset / "n5.2-r2-heldout-selection.json",
    )
    parser.add_argument(
        "--output-dir",
        type=Path,
        default=dataset / "reports" / "n5.2-r2-heldout",
    )
    parser.add_argument("--timeout-seconds", type=float, default=90.0)
    return parser.parse_args()


def load_r2_cases(dataset: Path, selection_path: Path) -> tuple[dict[str, Any], list[dict[str, Any]]]:
    selection = json.loads(selection_path.read_text(encoding="utf-8"))
    pilot_path = dataset / selection.get("pilotSelection", "")
    if not pilot_path.is_file():
        raise ValueError("R2 selection must reference the frozen R1 selection")
    pilot = json.loads(pilot_path.read_text(encoding="utf-8"))
    cases = load_jsonl(dataset / "cases.jsonl")
    case_by_id = {case["caseId"]: case for case in cases}
    expected = [
        case["caseId"]
        for case in sorted(cases, key=lambda item: item["sourceRowIndex"])
        if case.get("answerability") == "ANSWERABLE"
        and case["caseId"] not in set(pilot["caseIds"])
    ]
    ids = selection.get("caseIds", [])
    if ids != expected or len(ids) != 54 or len(set(ids)) != 54:
        raise ValueError("R2 selection must be the ordered 54-case ANSWERABLE complement of R1")
    selected = [case_by_id[case_id] for case_id in ids]
    if any(len(case.get("relevance", [])) != 1 for case in selected):
        raise ValueError("R2 currently requires exactly one Gold document per case")
    if any(not case["relevance"][0].get("evidencePages") for case in selected):
        raise ValueError("R2 requires at least one Gold evidence page per case")
    return selection, selected


def mean_metric(rows: list[dict[str, Any]], source_metric: str) -> float | None:
    values = [float(row["metrics"][source_metric]) for row in rows if row.get("metrics")]
    return round(sum(values) / len(values), 6) if values else None


def quality_summary(rows: list[dict[str, Any]]) -> dict[str, Any]:
    return {
        output_name: mean_metric(rows, source_name)
        for output_name, source_name in QUALITY_METRICS.items()
    }


def normal_hybrid_rows(rows: list[dict[str, Any]]) -> list[dict[str, Any]]:
    return [
        row
        for row in rows
        if row["http"]["ok"]
        and not row["response"].get("degraded")
        and row["response"].get("executedMode") == MODE
        and row.get("metrics")
    ]


def aggregate_r2(rows: list[dict[str, Any]]) -> dict[str, Any]:
    successful = [row for row in rows if row["http"]["ok"]]
    normal = normal_hybrid_rows(rows)
    return {
        "attempts": len(rows),
        "successes": len(successful),
        "normalModeResponses": len(normal),
        "degradedResponses": sum(
            1 for row in successful if row["response"].get("degraded")
        ),
        "retryCount": sum(max(0, row["http"]["attemptCount"] - 1) for row in rows),
        "latency": latency_summary([row["http"]["latencyMs"] for row in successful]),
        "qualityPopulation": len(normal),
        **quality_summary(normal),
    }


def build_combined_64(
    dataset: Path,
    r2_rows: list[dict[str, Any]],
    generated_at: str,
) -> dict[str, Any]:
    r1_raw = dataset / "reports" / "n5.2-r1-pilot" / "raw-results.jsonl"
    if not r1_raw.is_file():
        raise ValueError("frozen R1 raw results are required for the combined 64 summary")
    r1_hybrid = [row for row in load_jsonl(r1_raw) if row.get("mode") == MODE]
    r1_normal = normal_hybrid_rows(r1_hybrid)
    r2_normal = normal_hybrid_rows(r2_rows)
    combined = r1_normal + r2_normal
    case_ids = [row["caseId"] for row in combined]
    if len(case_ids) != len(set(case_ids)):
        raise ValueError("combined 64 summary contains duplicate case IDs")
    return {
        "schemaVersion": "docquery-n5.2-combined-64-descriptive-v1",
        "generatedAt": generated_at,
        "descriptiveOnly": True,
        "selectionBias": (
            "R1's 10 PILOT cases were used to select HYBRID; only the R2 54-case "
            "held-out report is the formal result"
        ),
        "r1PilotCases": len(r1_normal),
        "r2HeldoutCases": len(r2_normal),
        "qualityPopulation": len(combined),
        "complete": len(r1_normal) == 10 and len(r2_normal) == 54,
        **quality_summary(combined),
        "sources": {
            "r1RawResultsSha256": sha256_file(r1_raw),
            "r2RawResults": "raw-results.jsonl",
        },
    }


def markdown_report(report: dict[str, Any], combined: dict[str, Any]) -> str:
    metrics = report["metrics"]

    def fmt(value: float | None) -> str:
        return "N/A" if value is None else f"{value:.4f}"

    return "\n".join([
        "# DocQuery N5.2-R2 检索保留集报告",
        "",
        f"- 状态：`{report['status']}`",
        f"- 生成时间：`{report['generatedAt']}`",
        f"- 正式 held-out：{report['dataset']['caseCount']} 道，HYBRID，topK=10",
        f"- 成功：{metrics['successes']}/{metrics['attempts']}，降级：{metrics['degradedResponses']}，重试：{metrics['retryCount']}",
        "- Answer / DeepSeek：未调用",
        "",
        "## R2 held-out 54 主结果",
        "",
        "| Doc Hit@5 | Page Coverage@10 | MRR@10 | Any Page Hit@10 | All Pages Hit@10 | P50 ms | P95 ms |",
        "|---:|---:|---:|---:|---:|---:|---:|",
        f"| {fmt(metrics['documentHitAt5'])} | {fmt(metrics['evidencePageCoverageAt10'])} | "
        f"{fmt(metrics['reciprocalRankAt10'])} | {fmt(metrics['anyGoldPageHitAt10'])} | "
        f"{fmt(metrics['allGoldPagesHitAt10'])} | {metrics['latency']['p50Ms'] or 'N/A'} | "
        f"{metrics['latency']['p95Ms'] or 'N/A'} |",
        "",
        "## R1+R2 64 题补充汇总",
        "",
        "> 该汇总仅用于描述；R1 PILOT 已用于选择 HYBRID，不能冒充纯 held-out 分数。",
        "",
        "| 题数 | Doc Hit@5 | Page Coverage@10 | MRR@10 |",
        "|---:|---:|---:|---:|",
        f"| {combined['qualityPopulation']} | {fmt(combined['documentHitAt5'])} | "
        f"{fmt(combined['evidencePageCoverageAt10'])} | {fmt(combined['reciprocalRankAt10'])} |",
        "",
        "## 边界",
        "",
        "R2 只评估当前 100 份 READY PDF 上的顺序 HYBRID 检索，不包含 Answer 质量、不可回答题、并发容量、扫描件 OCR、复杂表格语义、精确供应商 Token 或金额。",
        "",
    ])


def main() -> int:
    args = parse_args()
    dataset = args.dataset.resolve()
    selection_path = args.selection.resolve()
    output_dir = args.output_dir.resolve()
    selection, cases = load_r2_cases(dataset, selection_path)
    manifest_path = dataset / "manifest.json"
    cases_path = dataset / "cases.jsonl"
    pilot_selection_path = dataset / selection["pilotSelection"]
    r1_report_path = dataset / "reports" / "n5.2-r1-pilot" / "report.json"
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    names = [document["displayName"] for document in manifest["documents"]]
    if len(names) != len(set(names)):
        raise ValueError("manifest displayName values must be unique")
    name_to_key = {
        document["displayName"]: document["documentKey"]
        for document in manifest["documents"]
    }
    authorization = authorization_from_env(args.token_env)
    endpoint = (
        f"{args.base_url.rstrip('/')}/api/v1/service/knowledge-bases/"
        f"{args.knowledge_base_id}/retrieve"
    )

    output_dir.mkdir(parents=True, exist_ok=True)
    raw_path = output_dir / "raw-results.jsonl"
    rows = load_jsonl(raw_path) if raw_path.exists() else []
    allowed = {(case["caseId"], MODE) for case in cases}
    existing = [(row.get("caseId"), row.get("mode")) for row in rows]
    if any(key not in allowed for key in existing) or len(existing) != len(set(existing)):
        raise ValueError("existing raw-results.jsonl is not a valid resumable R2 result set")
    completed = set(existing)

    for case in cases:
        if (case["caseId"], MODE) in completed:
            continue
        idempotency_key = f"n5.2-r2-heldout-v1-{case['caseId']}-hybrid"
        http = post_with_one_retry(
            endpoint,
            authorization,
            idempotency_key,
            {"query": case["question"], "mode": MODE, "topK": TOP_K},
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
            "mode": MODE,
            "topK": TOP_K,
            "http": http,
            "response": response,
            "metrics": evaluate(case, ranked) if http["ok"] else {},
        })
        write_jsonl(raw_path, rows)

    metrics = aggregate_r2(rows)
    all_success = len(rows) == 54 and metrics["successes"] == 54
    all_normal = metrics["normalModeResponses"] == 54
    status = (
        "COMPLETED_WITH_REQUEST_FAILURES" if not all_success
        else "COMPLETED_WITH_DEGRADATION" if not all_normal
        else "COMPLETED"
    )
    generated_at = dt.datetime.now(dt.timezone.utc).isoformat()
    combined = build_combined_64(dataset, rows, generated_at)
    report = {
        "schemaVersion": SCHEMA_VERSION,
        "status": status,
        "generatedAt": generated_at,
        "dataset": {
            "version": manifest["datasetVersion"],
            "caseCount": len(cases),
            "manifestSha256": sha256_file(manifest_path),
            "casesSha256": sha256_file(cases_path),
            "selectionSha256": sha256_file(selection_path),
            "pilotSelectionSha256": sha256_file(pilot_selection_path),
            "selectionPolicy": selection["selectionPolicy"],
        },
        "configuration": {
            "baseUrl": args.base_url.rstrip("/"),
            "knowledgeBaseId": args.knowledge_base_id,
            "mode": MODE,
            "topK": TOP_K,
            "rankingVersion": "retrieve-ranking-v1",
            "parameterAdjustmentUsed": False,
            "answerExecuted": False,
            "credentialEnvironmentVariable": args.token_env,
            "python": platform.python_version(),
            "platform": platform.platform(),
        },
        "modeSelectionEvidence": {
            "r1ReportSha256": sha256_file(r1_report_path),
            "decision": "HYBRID selected from the frozen R1 three-mode PILOT",
        },
        "usageBoundary": {
            "deepSeekCalls": 0,
            "queryEmbeddingExactTokenUsage": "NOT_AVAILABLE_IN_CURRENT_PUBLIC_API",
            "queryEmbeddingLogicalRequestUpperBound": 54,
        },
        "metrics": metrics,
        "artifacts": {
            "rawResults": raw_path.name,
            "combined64": "combined-64.json",
        },
    }
    atomic_write_json(output_dir / "report.json", report)
    atomic_write_json(output_dir / "combined-64.json", combined)
    (output_dir / "report.md").write_text(
        markdown_report(report, combined), encoding="utf-8"
    )
    print(json.dumps({
        "status": status,
        "logicalRequests": len(rows),
        "qualityPopulation": metrics["qualityPopulation"],
        "report": str(output_dir / "report.json"),
    }, ensure_ascii=False))
    return 0 if status == "COMPLETED" and combined["complete"] else 2


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except ValueError as exc:
        print(f"configuration error: {exc}", file=__import__("sys").stderr)
        raise SystemExit(2)
