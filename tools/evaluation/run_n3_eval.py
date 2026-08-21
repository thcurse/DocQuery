"""Run n3-eval-v1 against the public DocQuery HTTP contracts."""

from __future__ import annotations

import argparse
import datetime as dt
import json
import math
import platform
import sys
import uuid
from collections import defaultdict
from pathlib import Path
from typing import Any

from n3_eval_common import (
    atomic_write_json,
    authorization_from_env,
    latency_summary,
    load_jsonl,
    normalize_text,
    post_json,
    sha256_file,
)


MODES = ("KEYWORD", "SEMANTIC", "HYBRID")


def parse_args() -> argparse.Namespace:
    root = Path(__file__).resolve().parents[2]
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", default="http://127.0.0.1:8080")
    parser.add_argument("--knowledge-base-id", type=int, required=True)
    parser.add_argument("--token-env", default="DOCQUERY_APPLICATION_CREDENTIAL")
    parser.add_argument("--dataset", type=Path, default=root / "evaluation" / "n3-eval-v1")
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument("--split", choices=("DEVELOPMENT", "ACCEPTANCE", "ALL"), default="DEVELOPMENT")
    parser.add_argument("--top-k", type=int, default=10)
    parser.add_argument("--timeout-seconds", type=float, default=90.0)
    parser.add_argument("--include-answer", action="store_true")
    return parser.parse_args()


def relevant_documents(case: dict[str, Any]) -> set[str]:
    return {item["documentKey"] for item in case["relevance"]}


def anchors(case: dict[str, Any]) -> list[tuple[str, str]]:
    return [
        (item["documentKey"], anchor)
        for item in case["relevance"]
        for anchor in item.get("anchorTexts", [])
    ]


def ranked_results(body: dict[str, Any] | None, name_to_key: dict[str, str]) -> list[dict[str, Any]]:
    output = []
    for result in (body or {}).get("results", []):
        output.append({
            "rank": int(result.get("rank", len(output) + 1)),
            "documentKey": name_to_key.get(result.get("documentName")),
            "documentName": result.get("documentName"),
            "evidenceTexts": [item.get("text", "") for item in result.get("evidence", [])],
            "raw": result,
        })
    return sorted(output, key=lambda item: item["rank"])


def evaluate_retrieve(case: dict[str, Any], ranked: list[dict[str, Any]]) -> dict[str, Any]:
    relevant = relevant_documents(case)
    gold_anchors = anchors(case)
    metrics: dict[str, Any] = {}
    for cutoff in (5, 10):
        top = ranked[:cutoff]
        found_documents = {row["documentKey"] for row in top if row["documentKey"] in relevant}
        metrics[f"documentRecallAt{cutoff}"] = (
            len(found_documents) / len(relevant) if relevant else None
        )
        matched = 0
        for document_key, anchor in gold_anchors:
            expected = normalize_text(anchor)
            if any(
                row["documentKey"] == document_key
                and any(expected in normalize_text(text) for text in row["evidenceTexts"])
                for row in top
            ):
                matched += 1
        metrics[f"anchorRecallAt{cutoff}"] = (
            matched / len(gold_anchors) if gold_anchors else None
        )
    first = next((row["rank"] for row in ranked[:10] if row["documentKey"] in relevant), None)
    metrics["reciprocalRankAt10"] = 1.0 / first if first else (None if not relevant else 0.0)
    gains = [1 if row["documentKey"] in relevant else 0 for row in ranked[:10]]
    dcg = sum(gain / math.log2(index + 2) for index, gain in enumerate(gains))
    ideal = [1] * min(len(relevant), 10)
    idcg = sum(gain / math.log2(index + 2) for index, gain in enumerate(ideal))
    metrics["ndcgAt10"] = dcg / idcg if idcg else None
    return metrics


def evaluate_answer(
    case: dict[str, Any], body: dict[str, Any] | None, name_to_key: dict[str, str]
) -> dict[str, Any]:
    body = body or {}
    status = body.get("status")
    expected = "ANSWERED" if case["answerability"] == "ANSWERABLE" else "INSUFFICIENT_EVIDENCE"
    relevant = relevant_documents(case)
    gold_anchors = anchors(case)
    citations = body.get("citations", []) or []
    mechanically_valid = 0
    for citation in citations:
        document_key = name_to_key.get(citation.get("documentName"))
        citation_text = normalize_text(citation.get("text"))
        if any(
            document_key == expected_document
            and normalize_text(anchor) in citation_text
            for expected_document, anchor in gold_anchors
        ):
            mechanically_valid += 1
    fabricated_on_refusal = status == "INSUFFICIENT_EVIDENCE" and bool(citations)
    return {
        "expectedStatus": expected,
        "actualStatus": status,
        "statusCorrect": status == expected,
        "citationCount": len(citations),
        "mechanicallyValidCitationCount": mechanically_valid,
        "hasRelevantDocumentCitation": any(
            name_to_key.get(citation.get("documentName")) in relevant for citation in citations
        ),
        "fabricatedCitationOnRefusal": fabricated_on_refusal,
        "claimsRequireHumanReview": case.get("requiredClaims", []),
    }


def mean(values: list[float | None]) -> float | None:
    present = [value for value in values if value is not None]
    return round(sum(present) / len(present), 6) if present else None


def aggregate(rows: list[dict[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {"retrieve": {}, "answer": {}}
    for mode in MODES:
        subset = [row for row in rows if row["operation"] == "RETRIEVE" and row["mode"] == mode]
        result["retrieve"][mode] = {
            "attempts": len(subset),
            "successes": sum(1 for row in subset if row["http"]["ok"]),
            "latency": latency_summary([row["http"]["latencyMs"] for row in subset]),
            **{
                metric: mean([row.get("metrics", {}).get(metric) for row in subset])
                for metric in (
                    "documentRecallAt5", "documentRecallAt10", "anchorRecallAt5",
                    "anchorRecallAt10", "reciprocalRankAt10", "ndcgAt10",
                )
            },
        }
    answers = [row for row in rows if row["operation"] == "ANSWER"]
    if answers:
        answerable = [row for row in answers if row["answerability"] == "ANSWERABLE"]
        unanswerable = [row for row in answers if row["answerability"] == "NOT_ANSWERABLE"]
        result["answer"] = {
            "attempts": len(answers),
            "successes": sum(1 for row in answers if row["http"]["ok"]),
            "latency": latency_summary([row["http"]["latencyMs"] for row in answers]),
            "statusAccuracy": mean([1.0 if row.get("assessment", {}).get("statusCorrect") else 0.0 for row in answers]),
            "answerableStatusAccuracy": mean([1.0 if row.get("assessment", {}).get("statusCorrect") else 0.0 for row in answerable]),
            "controlledRefusalRate": mean([1.0 if row.get("assessment", {}).get("statusCorrect") else 0.0 for row in unanswerable]),
            "fabricatedCitationCount": sum(
                1 for row in answers if row.get("assessment", {}).get("fabricatedCitationOnRefusal")
            ),
            "humanClaimReviewPending": len(answerable),
        }
    return result


def markdown_report(report: dict[str, Any]) -> str:
    lines = [
        "# DocQuery N3 评测报告",
        "",
        f"- runId: `{report['runId']}`",
        f"- status: `{report['status']}`",
        f"- generatedAt: `{report['generatedAt']}`",
        f"- split: `{report['configuration']['split']}`",
        f"- manifestSha256: `{report['dataset']['manifestSha256']}`",
        "",
        "## Retrieve",
        "",
        "| Mode | Success | Recall@5 | Anchor@5 | MRR@10 | nDCG@10 | P95 ms |",
        "|---|---:|---:|---:|---:|---:|---:|",
    ]
    for mode, values in report["metrics"]["retrieve"].items():
        def fmt(value: Any) -> str:
            return "N/A" if value is None else f"{value:.4f}"
        lines.append(
            f"| {mode} | {values['successes']}/{values['attempts']} | "
            f"{fmt(values['documentRecallAt5'])} | {fmt(values['anchorRecallAt5'])} | "
            f"{fmt(values['reciprocalRankAt10'])} | {fmt(values['ndcgAt10'])} | "
            f"{values['latency']['p95Ms'] if values['latency']['p95Ms'] is not None else 'N/A'} |"
        )
    lines.extend(["", "## Answer", ""])
    answer = report["metrics"].get("answer", {})
    if not answer:
        lines.append("未执行；运行时未传 `--include-answer`。")
    else:
        lines.extend([
            f"- 状态准确率：{answer['statusAccuracy']}",
            f"- 不可回答受控拒答率：{answer['controlledRefusalRate']}",
            f"- 拒答时伪造引用：{answer['fabricatedCitationCount']}",
            f"- 待人工 claims 复核：{answer['humanClaimReviewPending']}",
        ])
    lines.extend([
        "",
        "## 解释边界",
        "",
        "该报告只陈述本次目标服务、凭证、KnowledgeBase、模型和索引状态下的结果。若使用 Fake Gateway，结果仅是确定性合同基线，不能解释为真实供应商效果。`raw-results.jsonl` 保留逐题 HTTP 与机械判定，凭证不会写入报告。",
        "",
    ])
    return "\n".join(lines)


def main() -> int:
    args = parse_args()
    if args.top_k < 10:
        raise ValueError("--top-k must be at least 10 for Recall@10/MRR@10")
    dataset = args.dataset.resolve()
    manifest_path = dataset / "manifest.json"
    cases_path = dataset / "cases.jsonl"
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    cases = load_jsonl(cases_path)
    if args.split != "ALL":
        cases = [case for case in cases if case["split"] == args.split]
    authorization = authorization_from_env(args.token_env)
    base_url = args.base_url.rstrip("/")
    endpoint = f"{base_url}/api/v1/service/knowledge-bases/{args.knowledge_base_id}"
    name_to_key = {document["displayName"]: document["documentKey"] for document in manifest["documents"]}
    run_id = str(uuid.uuid4())
    rows: list[dict[str, Any]] = []
    for case in cases:
        for mode in MODES:
            key = f"n3-eval-{run_id}-{case['caseId']}-{mode.lower()}"
            http = post_json(
                f"{endpoint}/retrieve", authorization, key,
                {"query": case["question"], "mode": mode, "topK": args.top_k},
                args.timeout_seconds,
            )
            ranked = ranked_results(http.get("body"), name_to_key) if http["ok"] else []
            rows.append({
                "caseId": case["caseId"], "split": case["split"], "kind": case["kind"],
                "answerability": case["answerability"], "operation": "RETRIEVE", "mode": mode,
                "http": http, "metrics": evaluate_retrieve(case, ranked) if http["ok"] else {},
            })
        if args.include_answer:
            key = f"n3-eval-{run_id}-{case['caseId']}-answer"
            http = post_json(
                f"{endpoint}/answer", authorization, key,
                {"query": case["question"], "mode": "HYBRID", "topK": args.top_k},
                args.timeout_seconds,
            )
            rows.append({
                "caseId": case["caseId"], "split": case["split"], "kind": case["kind"],
                "answerability": case["answerability"], "operation": "ANSWER", "mode": "HYBRID",
                "http": http,
                "assessment": evaluate_answer(case, http.get("body"), name_to_key) if http["ok"] else {},
            })

    output = args.output_dir.resolve()
    output.mkdir(parents=True, exist_ok=True)
    raw_path = output / "raw-results.jsonl"
    raw_path.write_text(
        "".join(json.dumps(row, ensure_ascii=False) + "\n" for row in rows), encoding="utf-8"
    )
    all_success = all(row["http"]["ok"] for row in rows)
    report = {
        "schemaVersion": "n3-eval-report-v1",
        "runId": run_id,
        "status": "COMPLETED" if all_success else "COMPLETED_WITH_REQUEST_FAILURES",
        "generatedAt": dt.datetime.now(dt.timezone.utc).isoformat(),
        "dataset": {
            "version": manifest["datasetVersion"],
            "manifestSha256": sha256_file(manifest_path),
            "casesSha256": sha256_file(cases_path),
            "caseCount": len(cases),
        },
        "configuration": {
            "baseUrl": base_url,
            "knowledgeBaseId": args.knowledge_base_id,
            "split": args.split,
            "topK": args.top_k,
            "includeAnswer": args.include_answer,
            "credentialEnvironmentVariable": args.token_env,
            "python": platform.python_version(),
            "platform": platform.platform(),
        },
        "metrics": aggregate(rows),
        "artifacts": {"rawResults": raw_path.name},
    }
    atomic_write_json(output / "report.json", report)
    (output / "report.md").write_text(markdown_report(report), encoding="utf-8")
    print(json.dumps({
        "status": report["status"], "runId": run_id,
        "report": str(output / "report.json"), "attempts": len(rows),
    }, ensure_ascii=False))
    return 0 if all_success else 2


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except ValueError as exc:
        print(f"configuration error: {exc}", file=sys.stderr)
        raise SystemExit(2)
