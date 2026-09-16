"""Run the frozen N5.2-R3 80-case valid retrieval evaluation."""

from __future__ import annotations

import argparse
import ast
import datetime as dt
import json
import os
import platform
from collections import Counter, defaultdict
from pathlib import Path
from typing import Any

from pypdf import PdfReader

from n3_eval_common import atomic_write_json, latency_summary, load_jsonl, sha256_file
from run_n5_retrieval_pilot import post_with_one_retry, safe_ranked_results, write_jsonl


MODE = "HYBRID"
TOP_K = 10
SCHEMA_VERSION = "docquery-n5.2-r3-valid-v1"
METRICS = (
    "documentHitAt1",
    "documentHitAt3",
    "documentHitAt5",
    "documentHitAt10",
    "anyGoldPageHitAt10",
    "evidencePageCoverageAt10",
    "allGoldPagesHitAt10",
    "reciprocalRankAt10",
)


def parse_args() -> argparse.Namespace:
    root = Path(__file__).resolve().parents[2]
    dataset = root / "evaluation" / "mmlongbench-docquery-v1"
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", default="http://127.0.0.1:8080")
    parser.add_argument("--knowledge-base-id", type=int, default=1)
    parser.add_argument("--token-env", default="DOCQUERY_N5_APPLICATION_CREDENTIAL")
    parser.add_argument("--env-file", type=Path, default=root / ".env")
    parser.add_argument("--dataset", type=Path, default=dataset)
    parser.add_argument(
        "--selection", type=Path, default=dataset / "n5.2-r3-valid-selection.json"
    )
    parser.add_argument(
        "--output-dir", type=Path, default=dataset / "reports" / "n5.2-r3-valid"
    )
    parser.add_argument("--timeout-seconds", type=float, default=90.0)
    parser.add_argument("--validate-only", action="store_true")
    return parser.parse_args()


def parse_list(value: Any, field: str, row_index: int) -> list[Any]:
    if isinstance(value, list):
        return value
    try:
        parsed = ast.literal_eval(str(value))
    except (ValueError, SyntaxError) as exc:
        raise ValueError(f"source row {row_index} has invalid {field}") from exc
    if not isinstance(parsed, list):
        raise ValueError(f"source row {row_index} {field} must be a list")
    return parsed


def page_mapping(pdf_path: Path, printed_pages: list[int]) -> dict[str, Any]:
    reader = PdfReader(pdf_path)
    labels = reader.page_labels
    printed_to_physical: dict[str, int] = {}
    for printed in sorted(set(printed_pages)):
        matches = [index + 1 for index, label in enumerate(labels) if str(label) == str(printed)]
        if len(matches) != 1:
            raise ValueError(
                f"Gold PageLabel {printed} is not unique in {pdf_path.name}: {matches}"
            )
        printed_to_physical[str(printed)] = matches[0]
    return {
        "source": "PDF_PAGE_LABELS",
        "pdfPageCount": len(reader.pages),
        "printedToPhysical": printed_to_physical,
        "physicalGoldPages": sorted(set(printed_to_physical.values())),
    }


def load_r3_cases(
    dataset: Path, selection_path: Path
) -> tuple[dict[str, Any], list[dict[str, Any]], list[dict[str, Any]], dict[str, Any]]:
    selection = json.loads(selection_path.read_text(encoding="utf-8"))
    formal_indexes = selection.get("formalSourceRowIndexes", [])
    reserve_indexes = selection.get("reserveSourceRowIndexes", [])
    if len(formal_indexes) != 80 or len(set(formal_indexes)) != 80:
        raise ValueError("R3 formal selection must contain exactly 80 unique source rows")
    if len(reserve_indexes) != 3 or len(set(reserve_indexes)) != 3:
        raise ValueError("R3 reserve selection must contain exactly 3 unique source rows")
    if set(formal_indexes).intersection(reserve_indexes):
        raise ValueError("R3 formal and reserve selections overlap")
    if formal_indexes != sorted(formal_indexes) or reserve_indexes != sorted(reserve_indexes):
        raise ValueError("R3 source rows must be ordered by sourceRowIndex")

    prior_indexes = {row["sourceRowIndex"] for row in load_jsonl(dataset / "cases.jsonl")}
    overlap = sorted(set(formal_indexes + reserve_indexes).intersection(prior_indexes))
    if overlap:
        raise ValueError(f"R3 contains previously executed source rows: {overlap}")

    source_rows = {
        row["sourceRowIndex"]: row for row in load_jsonl(dataset / "source-annotations.jsonl")
    }
    missing = [index for index in formal_indexes + reserve_indexes if index not in source_rows]
    if missing:
        raise ValueError(f"R3 source rows do not exist: {missing}")

    manifest = json.loads((dataset / "manifest.json").read_text(encoding="utf-8"))
    documents_by_source: dict[str, list[dict[str, Any]]] = defaultdict(list)
    sha_counts = Counter(document["sha256"] for document in manifest["documents"])
    for document in manifest["documents"]:
        documents_by_source[document["sourceDocumentId"]].append(document)

    def build(index: int) -> tuple[dict[str, Any], dict[str, Any]]:
        source = source_rows[index]
        evidence_sources = parse_list(source.get("evidence_sources"), "evidence_sources", index)
        if "Pure-text (Plain-text)" not in evidence_sources:
            raise ValueError(f"source row {index} does not have Pure-text evidence")
        if str(source.get("answer", "")).strip().casefold() == "not answerable":
            raise ValueError(f"source row {index} is not answerable")
        documents = documents_by_source.get(source["doc_id"], [])
        if len(documents) != 1:
            raise ValueError(f"source row {index} maps to {len(documents)} manifest documents")
        document = documents[0]
        if sha_counts[document["sha256"]] != 1:
            raise ValueError(f"source row {index} uses non-unique source content")
        printed_pages = [
            int(page) for page in parse_list(source.get("evidence_pages"), "evidence_pages", index)
        ]
        if not printed_pages:
            raise ValueError(f"source row {index} has no Gold pages")
        mapping = page_mapping(dataset / document["file"], printed_pages)
        case_id = f"mmlongbench-r3-{index:04d}"
        case = {
            "caseId": case_id,
            "sourceRowIndex": index,
            "question": str(source["question"]).strip(),
            "sourceDomain": source["doc_type"],
            "answerFormat": source["answer_format"],
            "referenceAnswer": source["answer"],
            "relevance": [{
                "documentKey": document["documentKey"],
                "displayName": document["displayName"],
                "printedEvidencePages": sorted(set(printed_pages)),
                "physicalEvidencePages": mapping["physicalGoldPages"],
            }],
        }
        return case, {"caseId": case_id, "documentKey": document["documentKey"], **mapping}

    formal_pairs = [build(index) for index in formal_indexes]
    reserve_pairs = [build(index) for index in reserve_indexes]
    mappings = {
        mapping["caseId"]: {key: value for key, value in mapping.items() if key != "caseId"}
        for _, mapping in formal_pairs + reserve_pairs
    }
    return (
        selection,
        [case for case, _ in formal_pairs],
        [case for case, _ in reserve_pairs],
        mappings,
    )


def credential_from_env_or_file(variable: str, env_file: Path) -> str:
    value = os.environ.get(variable, "").strip()
    if not value and env_file.is_file():
        for line in env_file.read_text(encoding="utf-8-sig").splitlines():
            stripped = line.strip()
            if not stripped or stripped.startswith("#") or "=" not in stripped:
                continue
            key, candidate = stripped.split("=", 1)
            if key.strip() == variable:
                value = candidate.strip().strip('"').strip("'")
                break
    if not value:
        raise ValueError(f"credential {variable} is unavailable")
    return value if value.lower().startswith("bearer ") else f"Bearer {value}"


def evaluate(case: dict[str, Any], ranked: list[dict[str, Any]]) -> dict[str, Any]:
    relevance = case["relevance"][0]
    gold_document = relevance["documentKey"]
    gold_pages = set(relevance["physicalEvidencePages"])

    def hit(cutoff: int) -> float:
        return 1.0 if any(row["documentKey"] == gold_document for row in ranked[:cutoff]) else 0.0

    first_rank = next(
        (row["rank"] for row in ranked[:TOP_K] if row["documentKey"] == gold_document), None
    )
    hit_pages = {
        int(item["pageNumber"])
        for row in ranked[:TOP_K]
        if row["documentKey"] == gold_document
        for item in row["evidence"]
        if item.get("pageNumber") is not None and int(item["pageNumber"]) in gold_pages
    }
    return {
        "documentHitAt1": hit(1),
        "documentHitAt3": hit(3),
        "documentHitAt5": hit(5),
        "documentHitAt10": hit(10),
        "anyGoldPageHitAt10": 1.0 if hit_pages else 0.0,
        "evidencePageCoverageAt10": len(hit_pages) / len(gold_pages),
        "allGoldPagesHitAt10": 1.0 if hit_pages == gold_pages else 0.0,
        "reciprocalRankAt10": 1.0 / first_rank if first_rank else 0.0,
        "printedGoldPages": relevance["printedEvidencePages"],
        "physicalGoldPages": sorted(gold_pages),
        "hitPhysicalGoldPages": sorted(hit_pages),
    }


def summarize(rows: list[dict[str, Any]]) -> dict[str, Any]:
    successful = [row for row in rows if row["http"]["ok"]]
    normal = [
        row for row in successful
        if not row["response"].get("degraded") and row["response"].get("executedMode") == MODE
    ]
    summary: dict[str, Any] = {
        "attempts": len(rows),
        "successes": len(successful),
        "normalModeResponses": len(normal),
        "degradedResponses": sum(1 for row in successful if row["response"].get("degraded")),
        "retryCount": sum(max(0, row["http"]["attemptCount"] - 1) for row in rows),
        "latency": latency_summary([row["http"]["latencyMs"] for row in successful]),
        "qualityPopulation": len(normal),
    }
    for metric in METRICS:
        values = [float(row["metrics"][metric]) for row in normal if row.get("metrics")]
        summary[metric] = round(sum(values) / len(values), 6) if values else None
    return summary


def markdown_report(report: dict[str, Any]) -> str:
    metrics = report["metrics"]

    def fmt(value: float | None) -> str:
        return "N/A" if value is None else f"{value:.4f}"

    lines = [
        "# DocQuery N5.2-R3 检索有效集评测报告",
        "",
        f"- 状态：`{report['status']}`",
        f"- 生成时间：`{report['generatedAt']}`",
        "- 80 道首次执行的公开数据集原题；全库检索，不传 `documentIds`",
        f"- HYBRID，topK={TOP_K}；成功 {metrics['successes']}/{metrics['attempts']}，降级 {metrics['degradedResponses']}，重试 {metrics['retryCount']}",
        "- Answer / DeepSeek：未调用",
        "",
        "| Doc Hit@1 | Doc Hit@3 | Doc Hit@5 | Doc Hit@10 | Any Page@10 | Page Coverage@10 | All Pages@10 | MRR@10 | P95 ms |",
        "|---:|---:|---:|---:|---:|---:|---:|---:|---:|",
        f"| {fmt(metrics['documentHitAt1'])} | {fmt(metrics['documentHitAt3'])} | {fmt(metrics['documentHitAt5'])} | {fmt(metrics['documentHitAt10'])} | {fmt(metrics['anyGoldPageHitAt10'])} | {fmt(metrics['evidencePageCoverageAt10'])} | {fmt(metrics['allGoldPagesHitAt10'])} | {fmt(metrics['reciprocalRankAt10'])} | {metrics['latency']['p95Ms'] or 'N/A'} |",
        "",
        "## 分文档类型",
        "",
        "| 类型 | 题数 | Doc Hit@1 | Doc Hit@5 | Doc Hit@10 | Page Coverage@10 |",
        "|---|---:|---:|---:|---:|---:|",
    ]
    for domain, value in report["domainMetrics"].items():
        lines.append(
            f"| {domain} | {value['qualityPopulation']} | {fmt(value['documentHitAt1'])} | "
            f"{fmt(value['documentHitAt5'])} | {fmt(value['documentHitAt10'])} | "
            f"{fmt(value['evidencePageCoverageAt10'])} |"
        )
    lines.extend([
        "",
        f"失败样本共 `{report['failureSampleCount']}` 题，其中 Top10 未找到 Gold 文档 "
        f"`{report['failureBreakdown']['documentMissAt10']}` 题，已找到文档但 Gold 页未完全覆盖 "
        f"`{report['failureBreakdown']['pageIncompleteWithDocumentHit']}` 题。",
        "",
        "## 边界",
        "",
        "R3 只评估当前测试科技租户、评测应用、100 份 READY PDF 和当前索引快照上的顺序 HYBRID 检索。问题来自公开集且在选题时未执行过；不评估 Answer、不可回答题、并发容量、OCR 或复杂视觉语义。",
        "",
    ])
    return "\n".join(lines)


def write_selection_audit(
    output_dir: Path,
    selection_path: Path,
    cases: list[dict[str, Any]],
    reserves: list[dict[str, Any]],
    mappings: dict[str, Any],
) -> None:
    shifted = [
        case_id for case_id, mapping in mappings.items()
        if any(int(label) != physical for label, physical in mapping["printedToPhysical"].items())
    ]
    audit = {
        "schemaVersion": "docquery-n5.2-r3-selection-audit-v1",
        "status": "VALIDATED",
        "formalCases": len(cases),
        "reserveCases": len(reserves),
        "formalDocumentCount": len({case["relevance"][0]["documentKey"] for case in cases}),
        "formalDomainCounts": dict(sorted(Counter(case["sourceDomain"] for case in cases).items())),
        "selectionSha256": sha256_file(selection_path),
        "shiftedPageMappingCases": shifted,
        "formalCaseIds": [case["caseId"] for case in cases],
        "reserveCaseIds": [case["caseId"] for case in reserves],
    }
    output_dir.mkdir(parents=True, exist_ok=True)
    atomic_write_json(output_dir / "selection-audit.json", audit)
    atomic_write_json(output_dir / "page-mapping.json", mappings)
    write_jsonl(output_dir / "cases.jsonl", cases)
    write_jsonl(output_dir / "reserve-cases.jsonl", reserves)


def main() -> int:
    args = parse_args()
    dataset = args.dataset.resolve()
    selection_path = args.selection.resolve()
    output_dir = args.output_dir.resolve()
    selection, cases, reserves, mappings = load_r3_cases(dataset, selection_path)
    write_selection_audit(output_dir, selection_path, cases, reserves, mappings)
    if args.validate_only:
        print(json.dumps({
            "status": "VALIDATED",
            "formalCases": len(cases),
            "reserveCases": len(reserves),
            "outputDir": str(output_dir),
        }, ensure_ascii=False))
        return 0

    manifest_path = dataset / "manifest.json"
    source_path = dataset / "source-annotations.jsonl"
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    name_to_key = {document["displayName"]: document["documentKey"] for document in manifest["documents"]}
    authorization = credential_from_env_or_file(args.token_env, args.env_file.resolve())
    endpoint = (
        f"{args.base_url.rstrip('/')}/api/v1/service/knowledge-bases/"
        f"{args.knowledge_base_id}/retrieve"
    )

    raw_path = output_dir / "raw-results.jsonl"
    rows = load_jsonl(raw_path) if raw_path.exists() else []
    allowed = {(case["caseId"], MODE) for case in cases}
    existing = [(row.get("caseId"), row.get("mode")) for row in rows]
    if any(key not in allowed for key in existing) or len(existing) != len(set(existing)):
        raise ValueError("existing raw-results.jsonl is not a valid resumable R3 result set")
    completed = set(existing)

    for case in cases:
        if (case["caseId"], MODE) in completed:
            continue
        idempotency_key = f"n5.2-r3-valid-v1-{case['caseId']}-hybrid"
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
        print(json.dumps({
            "completed": len(rows),
            "total": len(cases),
            "caseId": case["caseId"],
            "httpStatus": http["httpStatus"],
        }, ensure_ascii=False), flush=True)

    metrics = summarize(rows)
    domain_metrics = {
        domain: summarize([row for row in rows if row["sourceDomain"] == domain])
        for domain in sorted({row["sourceDomain"] for row in rows})
    }
    all_success = len(rows) == 80 and metrics["successes"] == 80
    all_normal = metrics["normalModeResponses"] == 80
    status = (
        "COMPLETED_WITH_REQUEST_FAILURES" if not all_success
        else "COMPLETED_WITH_DEGRADATION" if not all_normal
        else "COMPLETED"
    )
    failures = [
        {
            "caseId": row["caseId"],
            "sourceRowIndex": row["sourceRowIndex"],
            "httpOk": row["http"]["ok"],
            "httpStatus": row["http"]["httpStatus"],
            "degraded": row["response"].get("degraded"),
            "metrics": row.get("metrics", {}),
        }
        for row in rows
        if not row["http"]["ok"]
        or row["response"].get("degraded")
        or float(row.get("metrics", {}).get("documentHitAt10", 0.0)) == 0.0
        or float(row.get("metrics", {}).get("evidencePageCoverageAt10", 0.0)) < 1.0
    ]
    report = {
        "schemaVersion": SCHEMA_VERSION,
        "status": status,
        "generatedAt": dt.datetime.now(dt.timezone.utc).isoformat(),
        "dataset": {
            "version": manifest["datasetVersion"],
            "caseCount": len(cases),
            "reserveCount": len(reserves),
            "sourceDocumentCount": len({case["relevance"][0]["documentKey"] for case in cases}),
            "corpusDocumentCount": manifest["documentCount"],
            "manifestSha256": sha256_file(manifest_path),
            "sourceAnnotationsSha256": sha256_file(source_path),
            "selectionSha256": sha256_file(selection_path),
            "selectionPolicy": selection["selectionPolicy"],
        },
        "configuration": {
            "baseUrl": args.base_url.rstrip("/"),
            "knowledgeBaseId": args.knowledge_base_id,
            "mode": MODE,
            "topK": TOP_K,
            "documentIdsProvided": False,
            "rankingVersion": "retrieve-ranking-v1",
            "answerExecuted": False,
            "credentialEnvironmentVariable": args.token_env,
            "python": platform.python_version(),
            "platform": platform.platform(),
        },
        "usageBoundary": {
            "deepSeekCalls": 0,
            "queryEmbeddingExactTokenUsage": "NOT_AVAILABLE_IN_CURRENT_PUBLIC_API",
            "queryEmbeddingLogicalRequestUpperBound": 80,
        },
        "metrics": metrics,
        "domainMetrics": domain_metrics,
        "failureSampleCount": len(failures),
        "failureBreakdown": {
            "documentMissAt10": sum(
                1 for row in rows
                if float(row.get("metrics", {}).get("documentHitAt10", 0.0)) == 0.0
            ),
            "pageIncompleteWithDocumentHit": sum(
                1 for row in rows
                if float(row.get("metrics", {}).get("documentHitAt10", 0.0)) == 1.0
                and float(row.get("metrics", {}).get("evidencePageCoverageAt10", 0.0)) < 1.0
            ),
        },
        "artifacts": {
            "rawResults": raw_path.name,
            "selectionAudit": "selection-audit.json",
            "cases": "cases.jsonl",
            "reserveCases": "reserve-cases.jsonl",
            "pageMapping": "page-mapping.json",
            "failureSamples": "failure-samples.json",
        },
    }
    atomic_write_json(output_dir / "failure-samples.json", failures)
    atomic_write_json(output_dir / "report.json", report)
    (output_dir / "report.md").write_text(markdown_report(report), encoding="utf-8")
    print(json.dumps({
        "status": status,
        "logicalRequests": len(rows),
        "qualityPopulation": metrics["qualityPopulation"],
        "report": str(output_dir / "report.json"),
    }, ensure_ascii=False))
    return 0 if status == "COMPLETED" else 2


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except ValueError as exc:
        print(f"configuration error: {exc}", file=os.sys.stderr)
        raise SystemExit(2)
