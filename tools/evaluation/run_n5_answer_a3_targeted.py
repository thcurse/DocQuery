"""Run the five-case N5.3-A3 targeted Answer revalidation."""

from __future__ import annotations

import argparse
import datetime as dt
import json
from collections import Counter
from pathlib import Path
from typing import Any

from n3_eval_common import atomic_write_json, latency_summary, load_jsonl
from run_n5_answer_a1 import (
    MODE,
    TOP_K,
    credential_from_env_or_file,
    evaluate,
    post_with_one_retry,
    safe_response,
)
from run_n5_retrieval_pilot import write_jsonl


CASE_IDS = (
    "mmlongbench-r3-0562",
    "mmlongbench-r3-0607",
    "mmlongbench-r3-0831",
    "mmlongbench-r3-0937",
    "mmlongbench-r3-1032",
)
SCHEMA_VERSION = "docquery-n5.3-a3-targeted-revalidation-v1"


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
        "--output-dir",
        type=Path,
        default=dataset / "reports" / "n5.3-a3-targeted-revalidation",
    )
    parser.add_argument("--timeout-seconds", type=float, default=330.0)
    parser.add_argument("--run-id", default="n5.3-a3-targeted-v1")
    parser.add_argument("--actor-ref", default="n5.3-a3-targeted-revalidation")
    parser.add_argument("--schema-version", default=SCHEMA_VERSION)
    parser.add_argument("--answer-policy-version", default="answer-policy-v4")
    parser.add_argument(
        "--case-ids",
        nargs="+",
        choices=CASE_IDS,
        default=list(CASE_IDS),
        help="Frozen targeted case IDs to run; defaults to all five.",
    )
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    dataset = args.dataset.resolve()
    output_dir = args.output_dir.resolve()
    output_dir.mkdir(parents=True, exist_ok=True)
    selected_case_ids = tuple(args.case_ids)

    source_cases = load_jsonl(dataset / "reports" / "n5.3-a2-answer-agent-v2" / "cases.jsonl")
    case_by_id = {case["caseId"]: case for case in source_cases}
    if any(case_id not in case_by_id for case_id in selected_case_ids):
        raise ValueError("A3 targeted case is missing from the frozen A2 cases")
    cases = [case_by_id[case_id] for case_id in selected_case_ids]
    write_jsonl(output_dir / "cases.jsonl", cases)

    manifest = json.loads((dataset / "manifest.json").read_text(encoding="utf-8"))
    name_to_key = {
        document["displayName"]: document["documentKey"]
        for document in manifest["documents"]
    }
    authorization = credential_from_env_or_file(args.token_env, args.env_file.resolve())
    endpoint = (
        f"{args.base_url.rstrip('/')}/api/v1/service/knowledge-bases/"
        f"{args.knowledge_base_id}/answer"
    )
    raw_path = output_dir / "raw-results.jsonl"
    rows = load_jsonl(raw_path) if raw_path.exists() else []
    existing = [row.get("caseId") for row in rows]
    if (len(existing) != len(set(existing))
            or any(case_id not in selected_case_ids for case_id in existing)):
        raise ValueError("existing A3 raw results are not resumable")
    completed = set(existing)

    for case in cases:
        if case["caseId"] in completed:
            continue
        idempotency_key = f"{args.run_id}-{case['caseId']}"
        http = post_with_one_retry(
            endpoint,
            authorization,
            idempotency_key,
            {"query": case["question"], "mode": MODE, "topK": TOP_K},
            args.timeout_seconds,
            args.actor_ref,
        )
        body = http.pop("body", None)
        response = safe_response(body, name_to_key) if http["ok"] else {
            "status": None,
            "answer": None,
            "citations": [],
            "degraded": False,
            "errorCode": (body or {}).get("code") or (body or {}).get("errorCode"),
        }
        rows.append({
            "schemaVersion": args.schema_version,
            "caseId": case["caseId"],
            "sourceRowIndex": case["sourceRowIndex"],
            "sourceDomain": case["sourceDomain"],
            "answerFormat": case["answerFormat"],
            "referenceAnswer": case["referenceAnswer"],
            "question": case["question"],
            "mode": MODE,
            "topK": TOP_K,
            "http": http,
            "response": response,
            "metrics": evaluate(case, response) if http["ok"] else {},
        })
        write_jsonl(raw_path, rows)
        print(json.dumps({
            "completed": len(rows),
            "total": len(cases),
            "caseId": case["caseId"],
            "httpStatus": http["httpStatus"],
            "answerStatus": response.get("status"),
            "latencyMs": http["latencyMs"],
        }, ensure_ascii=False), flush=True)

    successes = [row for row in rows if row["http"]["ok"]]
    error_counts = Counter(
        row["response"].get("errorCode")
        for row in rows
        if not row["http"]["ok"] and row["response"].get("errorCode")
    )
    blocked_provider = (
        not successes
        and len(rows) == len(cases)
        and error_counts == Counter({"ANSWER_MODEL_UNAVAILABLE": len(cases)})
    )
    manual_path = output_dir / "manual-adjudication.json"
    manual_review: str | dict[str, Any] = "PENDING"
    if manual_path.is_file():
        manual = json.loads(manual_path.read_text(encoding="utf-8"))
        decisions = manual.get("decisions", [])
        decision_ids = [decision.get("caseId") for decision in decisions]
        if (len(decisions) != len(cases)
                or set(decision_ids) != set(selected_case_ids)
                or len(set(decision_ids)) != len(cases)):
            raise ValueError("manual adjudication must cover every targeted case exactly once")
        manual_review = {
            "status": "COMPLETED",
            "correctAnswers": sum(
                decision.get("correct") is True for decision in decisions
            ),
            "unsupportedCitations": sum(
                decision.get("citationSupport") == "UNSUPPORTED"
                for decision in decisions
            ),
            "artifact": manual_path.name,
        }
    status = (
        "BLOCKED_PROVIDER"
        if blocked_provider
        else "COMPLETED_PENDING_REVIEW"
        if len(successes) == len(cases)
        else "COMPLETED_WITH_FAILURES"
    )
    if isinstance(manual_review, dict):
        status = (
            "COMPLETED_PASSED_REVALIDATION"
            if len(successes) == len(cases)
            and manual_review["correctAnswers"] == len(cases)
            and manual_review["unsupportedCitations"] == 0
            else "COMPLETED_FAILED_REVALIDATION"
        )
    report: dict[str, Any] = {
        "schemaVersion": args.schema_version,
        "status": status,
        "generatedAt": dt.datetime.now(dt.timezone.utc).isoformat(),
        "scope": {
            "kind": "KNOWN_FAILURE_TARGETED_REVALIDATION",
            "caseIds": list(selected_case_ids),
            "caseCount": len(cases),
            "documentIdsProvided": False,
            "mode": MODE,
            "topK": TOP_K,
            "answerPolicyVersion": args.answer_policy_version,
        },
        "metrics": {
            "successes": len(successes),
            "attempts": len(rows),
            "answered": sum(row["response"].get("status") == "ANSWERED" for row in successes),
            "controlledRefusals": sum(
                row["response"].get("status") == "INSUFFICIENT_EVIDENCE"
                for row in successes
            ),
            "automaticReferenceMatches": sum(
                row["metrics"].get("automaticReferenceMatch") is True for row in successes
            ),
            "relevantDocumentCitations": sum(
                row["metrics"].get("hasRelevantDocumentCitation") is True for row in successes
            ),
            "anyGoldPageCitations": sum(
                row["metrics"].get("anyGoldPageCitation") is True for row in successes
            ),
            "goldPageCitationCoverage": round(sum(
                float(row["metrics"].get("goldPageCitationCoverage", 0.0))
                for row in successes
            ) / len(cases), 6),
            "allGoldPagesCited": sum(
                row["metrics"].get("allGoldPagesCited") is True for row in successes
            ),
            "latency": latency_summary([row["http"]["latencyMs"] for row in successes]),
            "errorCounts": dict(sorted(error_counts.items())),
            "manualReview": manual_review,
        },
        "usageBoundary": {
            "paidLogicalRequestUpperBound": len(cases),
            "exactProviderTokenUsage": "NOT_AVAILABLE_IN_CURRENT_ANSWER_CONTRACT",
            "exactCost": "NOT_AVAILABLE",
        },
        "run": {"runId": args.run_id, "actorRef": args.actor_ref},
        "artifacts": {
            "cases": "cases.jsonl",
            "rawResults": "raw-results.jsonl",
            "manualAdjudication": manual_path.name if manual_path.is_file() else None,
            "auditSummary": "audit-summary.json"
                    if (output_dir / "audit-summary.json").is_file() else None,
        },
    }
    atomic_write_json(output_dir / "report.json", report)
    print(json.dumps({"status": report["status"], "report": str(output_dir / "report.json")}, ensure_ascii=False))
    return 0 if len(successes) == len(cases) else 2


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except ValueError as exc:
        print(f"configuration error: {exc}", file=__import__("sys").stderr)
        raise SystemExit(2)
