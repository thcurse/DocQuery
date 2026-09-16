"""Run the frozen N5.2-O1-P3 targeted retrieval diagnostic."""

from __future__ import annotations

import argparse
import datetime as dt
import json
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


SCHEMA_VERSION = "docquery-n5.2-o1-p3-v1"


def parse_args() -> argparse.Namespace:
    root = Path(__file__).resolve().parents[2]
    dataset = root / "evaluation" / "mmlongbench-docquery-v1"
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", default="http://127.0.0.1:8080")
    parser.add_argument("--knowledge-base-id", type=int, default=1)
    parser.add_argument("--token-env", default="DOCQUERY_N5_APPLICATION_CREDENTIAL")
    parser.add_argument("--dataset", type=Path, default=dataset)
    parser.add_argument(
        "--selection", type=Path, default=dataset / "n5.2-o1-p3-selection.json"
    )
    parser.add_argument(
        "--baseline",
        type=Path,
        default=dataset / "reports" / "n5.2-r2-heldout" / "raw-results.jsonl",
    )
    parser.add_argument(
        "--output-dir",
        type=Path,
        default=dataset / "reports" / "n5.2-o1-p3",
    )
    parser.add_argument("--timeout-seconds", type=float, default=90.0)
    return parser.parse_args()


def mean(rows: list[dict[str, Any]], metric: str) -> float | None:
    values = [float(row["metrics"][metric]) for row in rows if row.get("metrics")]
    return round(sum(values) / len(values), 6) if values else None


def summarize(rows: list[dict[str, Any]]) -> dict[str, Any]:
    successful = [row for row in rows if row["http"]["ok"]]
    normal = [
        row
        for row in successful
        if not row["response"].get("degraded")
        and row["response"].get("executedMode") == "HYBRID"
        and row.get("metrics")
    ]
    return {
        "cases": len(rows),
        "successes": len(successful),
        "normalModeResponses": len(normal),
        "degradedResponses": sum(1 for row in successful if row["response"].get("degraded")),
        "retryCount": sum(max(0, row["http"]["attemptCount"] - 1) for row in rows),
        "documentHitAt10": mean(normal, "documentRecallAt10"),
        "anyGoldPageHitAt10": mean(normal, "evidencePageRecallAt10"),
        "evidencePageCoverageAt10": mean(normal, "evidencePageCoverageAt10"),
        "reciprocalRankAt10": mean(normal, "reciprocalRankAt10"),
        "latency": latency_summary([row["http"]["latencyMs"] for row in successful]),
    }


def metric(row: dict[str, Any], name: str) -> float:
    return float(row.get("metrics", {}).get(name, 0.0))


def main() -> int:
    args = parse_args()
    dataset = args.dataset.resolve()
    selection_path = args.selection.resolve()
    baseline_path = args.baseline.resolve()
    output_dir = args.output_dir.resolve()
    selection = json.loads(selection_path.read_text(encoding="utf-8"))
    primary_ids = selection["primaryCaseIds"]
    visual_ids = selection["visualBoundaryCaseIds"]
    all_ids = primary_ids + visual_ids
    if len(all_ids) != 8 or len(set(all_ids)) != 8:
        raise ValueError("P3 selection must contain six primary and two distinct visual cases")

    cases = load_jsonl(dataset / "cases.jsonl")
    case_by_id = {case["caseId"]: case for case in cases}
    if any(case_id not in case_by_id for case_id in all_ids):
        raise ValueError("P3 selection references an unknown case")
    selected = [case_by_id[case_id] for case_id in all_ids]

    baseline_rows = load_jsonl(baseline_path)
    baseline_by_id = {row["caseId"]: row for row in baseline_rows}
    if any(case_id not in baseline_by_id for case_id in all_ids):
        raise ValueError("formal R2 baseline is incomplete for the P3 selection")

    manifest = json.loads((dataset / "manifest.json").read_text(encoding="utf-8"))
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
    existing = [row.get("caseId") for row in rows]
    if any(case_id not in all_ids for case_id in existing) or len(existing) != len(set(existing)):
        raise ValueError("existing P3 raw results are not resumable")
    completed = set(existing)

    for case in selected:
        if case["caseId"] in completed:
            continue
        http = post_with_one_retry(
            endpoint,
            authorization,
            f"n5.2-o1-p3-v1-{case['caseId']}-hybrid",
            {"query": case["question"], "mode": "HYBRID", "topK": 10},
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
            "population": "PRIMARY" if case["caseId"] in primary_ids else "VISUAL_BOUNDARY",
            "mode": "HYBRID",
            "topK": 10,
            "http": http,
            "response": response,
            "metrics": evaluate(case, ranked) if http["ok"] else {},
        })
        write_jsonl(raw_path, rows)

    after_by_id = {row["caseId"]: row for row in rows}
    primary_before = [baseline_by_id[case_id] for case_id in primary_ids]
    primary_after = [after_by_id[case_id] for case_id in primary_ids if case_id in after_by_id]
    visual_before = [baseline_by_id[case_id] for case_id in visual_ids]
    visual_after = [after_by_id[case_id] for case_id in visual_ids if case_id in after_by_id]
    acceptance = selection["acceptance"]

    preserved_ids = [
        case_id for case_id in primary_ids
        if metric(baseline_by_id[case_id], "documentRecallAt10") == 1.0
    ]
    preserved = all(
        metric(after_by_id.get(case_id, {}), "documentRecallAt10") == 1.0
        for case_id in preserved_ids
    )
    recovered_document_ids = [
        case_id for case_id in primary_ids
        if metric(baseline_by_id[case_id], "documentRecallAt10") == 0.0
        and metric(after_by_id.get(case_id, {}), "documentRecallAt10") == 1.0
    ]
    improved_page_ids = [
        case_id for case_id in primary_ids
        if metric(baseline_by_id[case_id], "evidencePageRecallAt10") == 0.0
        and metric(after_by_id.get(case_id, {}), "evidencePageRecallAt10") == 1.0
    ]
    before_summary = summarize(primary_before)
    after_summary = summarize(primary_after)
    coverage_increased = (
        after_summary["evidencePageCoverageAt10"] is not None
        and after_summary["evidencePageCoverageAt10"]
        > before_summary["evidencePageCoverageAt10"]
    )
    complete_normal = len(primary_after) == 6 and after_summary["normalModeResponses"] == 6
    passed = (
        complete_normal
        and (preserved or not acceptance["preservePriorDocumentHits"])
        and len(recovered_document_ids) >= acceptance["minimumPriorDocumentMissesRecovered"]
        and len(improved_page_ids) >= acceptance["minimumPriorZeroPageCasesImproved"]
        and (coverage_increased or not acceptance["requirePrimaryMacroPageCoverageIncrease"])
    )

    comparisons = []
    for case_id in all_ids:
        before = baseline_by_id[case_id]
        after = after_by_id.get(case_id, {})
        comparisons.append({
            "caseId": case_id,
            "population": "PRIMARY" if case_id in primary_ids else "VISUAL_BOUNDARY",
            "before": before.get("metrics", {}),
            "after": after.get("metrics", {}),
        })

    report = {
        "schemaVersion": SCHEMA_VERSION,
        "status": "COMPLETED_PASS" if passed else "COMPLETED_FAIL" if len(rows) == 8 else "INCOMPLETE",
        "generatedAt": dt.datetime.now(dt.timezone.utc).isoformat(),
        "diagnosticOnly": True,
        "selectionBias": "Cases were selected from known R2 failures; do not report as held-out quality.",
        "validityFindings": {
            "singleDocumentTaskMismatch": (
                "MMLongBench-Doc questions are authored for QA over a given document; "
                "generic questions are not necessarily sufficient to identify one document "
                "inside a 100-document knowledge base."
            ),
            "duplicateSource": {
                "documents": ["NYU_graduate.pdf", "mi_phone.pdf"],
                "sameSha256": "16ee1cfbd58f9c6a3793c66ff5f4ec38dd864ac9fd7a9b7186f8af7db53f870b",
                "impact": "Document ranking between these two labels is not identifiable from content.",
            },
            "pageCoordinateMismatch": {
                "document": "NYU_graduate.pdf",
                "goldCoordinate": "printed page label",
                "serviceCoordinate": "physical PDF page number",
                "verifiedMappings": {"21": 23, "28": 30},
                "impact": (
                    "Frozen page metrics count two semantically correct NYU evidence pages "
                    "as misses; no corrected aggregate is claimed without a complete mapping."
                ),
            },
        },
        "configuration": {"mode": "HYBRID", "topK": 10, "answerExecuted": False},
        "primary": {"before": before_summary, "after": after_summary},
        "visualBoundary": {
            "affectsAcceptance": False,
            "before": summarize(visual_before),
            "after": summarize(visual_after),
        },
        "acceptance": {
            "passed": passed,
            "completeNormal": complete_normal,
            "priorDocumentHitCaseIds": preserved_ids,
            "priorDocumentHitsPreserved": preserved,
            "recoveredDocumentMissCaseIds": recovered_document_ids,
            "improvedPriorZeroPageCaseIds": improved_page_ids,
            "primaryMacroPageCoverageIncreased": coverage_increased,
        },
        "comparisons": comparisons,
        "sources": {
            "selectionSha256": sha256_file(selection_path),
            "baselineRawResultsSha256": sha256_file(baseline_path),
            "rawResults": raw_path.name,
        },
    }
    atomic_write_json(output_dir / "report.json", report)
    (output_dir / "report.md").write_text(
        "\n".join([
            "# DocQuery N5.2-O1-P3 定向复验报告",
            "",
            f"- 状态：`{report['status']}`",
            "- 性质：已知失败样本优化后诊断，不是新的 held-out 成绩",
            "- 模式：HYBRID，topK=10；Answer 未执行",
            "",
            "| 主样本 | Doc Hit@10 | Any Page Hit@10 | Page Coverage@10 | MRR@10 |",
            "|---|---:|---:|---:|---:|",
            f"| R2 基线 | {before_summary['documentHitAt10']:.4f} | {before_summary['anyGoldPageHitAt10']:.4f} | {before_summary['evidencePageCoverageAt10']:.4f} | {before_summary['reciprocalRankAt10']:.4f} |",
            f"| P3 重建后 | {after_summary['documentHitAt10'] or 0:.4f} | {after_summary['anyGoldPageHitAt10'] or 0:.4f} | {after_summary['evidencePageCoverageAt10'] or 0:.4f} | {after_summary['reciprocalRankAt10'] or 0:.4f} |",
            "",
            f"- 原有文档命中保持：`{preserved}`",
            f"- 恢复文档漏检：{len(recovered_document_ids)}，`{', '.join(recovered_document_ids) or '无'}`",
            f"- 原零页样本获得证据页：{len(improved_page_ids)}，`{', '.join(improved_page_ids) or '无'}`",
            f"- 主样本平均页覆盖率提升：`{coverage_increased}`",
            "- 两个视觉边界样本只记录，不影响通过与否。",
            "",
            "## 有效性发现",
            "",
            "- MMLongBench-Doc 原题是在已给定单份文档的前提下提问，通用问题不能公平衡量 100 文档中的文档定位。",
            "- `NYU_graduate.pdf` 与 `mi_phone.pdf` 字节级重复但 Gold 文档标签不同，内容检索无法区分。",
            "- NYU Gold 页 21/28 是印刷页码，分别对应 PDF 物理页 23/30；当前冻结算法直接比较物理页，因而把两个正确证据页计为 miss。",
            "- 页码坐标未完成全量映射前，不生成修正后的汇总分数。",
            "",
        ]),
        encoding="utf-8",
    )
    print(json.dumps({"status": report["status"], "report": str(output_dir / "report.json")}, ensure_ascii=False))
    return 0 if passed else 2


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except ValueError as exc:
        print(f"configuration error: {exc}", file=__import__("sys").stderr)
        raise SystemExit(2)
