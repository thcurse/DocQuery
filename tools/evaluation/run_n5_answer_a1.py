"""Run the frozen N5.3-A1 20-case Answer PILOT."""

from __future__ import annotations

import argparse
import ast
import datetime as dt
import hashlib
import json
import os
import platform
import re
import time
import unicodedata
import urllib.error
import urllib.request
from collections import Counter
from decimal import Decimal, InvalidOperation
from pathlib import Path
from typing import Any

from n3_eval_common import atomic_write_json, latency_summary, load_jsonl, sha256_file
from run_n5_retrieval_pilot import write_jsonl
from run_n5_retrieval_r3 import page_mapping, parse_list


MODE = "HYBRID"
TOP_K = 10
SCHEMA_VERSION = "docquery-n5.3-a1-answer-pilot-v1"
RETRYABLE_HTTP = {429, 500, 502, 503, 504}
FORMATS = ("Str", "Int", "Float", "List")
SELECTION_SEED = "docquery-n5.3-a1-v1"


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
        "--selection", type=Path, default=dataset / "n5.3-a1-answer-pilot-selection.json"
    )
    parser.add_argument(
        "--output-dir", type=Path, default=dataset / "reports" / "n5.3-a1-answer-pilot"
    )
    parser.add_argument("--timeout-seconds", type=float, default=130.0)
    parser.add_argument("--schema-version", default=SCHEMA_VERSION)
    parser.add_argument("--run-id", default="n5.3-a1-answer-pilot-v1")
    parser.add_argument("--actor-ref", default="n5.3-a1-answer-pilot")
    parser.add_argument("--answer-policy-version", default="answer-policy-v1")
    parser.add_argument(
        "--allow-custom-selection",
        action="store_true",
        help="Allow an explicitly curated 20-case selection, including sourceRowIndexes selections.",
    )
    parser.add_argument("--validate-only", action="store_true")
    return parser.parse_args()


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


def expected_selection(cases: list[dict[str, Any]]) -> list[str]:
    ordered = sorted(
        cases,
        key=lambda case: hashlib.sha256(
            f"{SELECTION_SEED}:{case['caseId']}".encode("utf-8")
        ).hexdigest(),
    )
    counts = Counter()
    documents: set[str] = set()
    selected: list[str] = []
    for case in ordered:
        answer_format = case["answerFormat"]
        document_key = case["relevance"][0]["documentKey"]
        if counts[answer_format] >= 5 or document_key in documents:
            continue
        selected.append(case["caseId"])
        counts[answer_format] += 1
        documents.add(document_key)
    if len(selected) != 20 or any(counts[answer_format] != 5 for answer_format in FORMATS):
        raise ValueError("deterministic Answer selection cannot satisfy frozen quotas")
    return sorted(selected, key=lambda case_id: int(case_id.rsplit("-", 1)[1]))


def load_selection(
    dataset: Path, selection_path: Path, allow_custom_selection: bool = False
) -> tuple[dict[str, Any], list[dict[str, Any]]]:
    selection = json.loads(selection_path.read_text(encoding="utf-8"))
    custom_cases = selection.get("customCases")
    if custom_cases is not None:
        if not allow_custom_selection:
            raise ValueError("customCases selection requires --allow-custom-selection")
        if not isinstance(custom_cases, list) or len(custom_cases) != 20:
            raise ValueError("custom Answer selection must contain exactly 20 cases")

        manifest = json.loads((dataset / "manifest.json").read_text(encoding="utf-8"))
        documents_by_key = {
            document["documentKey"]: document for document in manifest["documents"]
        }
        cases: list[dict[str, Any]] = []
        for index, custom in enumerate(custom_cases):
            if not isinstance(custom, dict):
                raise ValueError(f"custom case {index} must be an object")
            required = {
                "caseId", "question", "sourceDomain", "answerFormat",
                "referenceAnswer", "documentKey", "physicalEvidencePages",
            }
            missing = sorted(required - set(custom))
            if missing:
                raise ValueError(f"custom case {index} is missing fields: {missing}")
            document = documents_by_key.get(custom["documentKey"])
            if document is None:
                raise ValueError(
                    f"custom case {custom['caseId']} has no manifest document"
                )
            physical_pages = custom["physicalEvidencePages"]
            if (
                not isinstance(physical_pages, list)
                or len(physical_pages) != 1
                or not isinstance(physical_pages[0], int)
                or physical_pages[0] < 1
            ):
                raise ValueError(
                    f"custom case {custom['caseId']} must have one physical evidence page"
                )
            if custom["answerFormat"] not in FORMATS:
                raise ValueError(
                    f"custom case {custom['caseId']} has unsupported answer format"
                )
            question = custom["question"]
            if not isinstance(question, str) or not question.strip():
                raise ValueError(f"custom case {custom['caseId']} has no question")
            cases.append({
                "caseId": custom["caseId"],
                "sourceRowIndex": None,
                "question": question.strip(),
                "sourceDomain": custom["sourceDomain"],
                "answerFormat": custom["answerFormat"],
                "referenceAnswer": custom["referenceAnswer"],
                "relevance": [{
                    "documentKey": document["documentKey"],
                    "displayName": document["displayName"],
                    "printedEvidencePages": custom.get("printedEvidencePages", []),
                    "physicalEvidencePages": physical_pages,
                }],
            })
        case_ids = [case["caseId"] for case in cases]
        questions = [case["question"].casefold() for case in cases]
        if len(set(case_ids)) != 20 or len(set(questions)) != 20:
            raise ValueError("custom Answer case IDs and questions must be distinct")
        return selection, cases

    source_row_indexes = selection.get("sourceRowIndexes")
    if source_row_indexes is not None:
        if not allow_custom_selection:
            raise ValueError("sourceRowIndexes selection requires --allow-custom-selection")
        if len(source_row_indexes) != 20 or len(set(source_row_indexes)) != 20:
            raise ValueError("custom Answer selection must contain 20 distinct source rows")

        question_overrides = selection.get("questionOverrides", {})
        if not isinstance(question_overrides, dict):
            raise ValueError("questionOverrides must be an object keyed by source row index")
        selected_keys = {str(source_row_index) for source_row_index in source_row_indexes}
        unknown_override_keys = set(question_overrides) - selected_keys
        if unknown_override_keys:
            raise ValueError(
                "questionOverrides contains rows outside the selection: "
                f"{sorted(unknown_override_keys)}"
            )
        if any(
            not isinstance(question, str) or not question.strip()
            for question in question_overrides.values()
        ):
            raise ValueError("every question override must be a non-empty string")

        source_rows = {
            row["sourceRowIndex"]: row
            for row in load_jsonl(dataset / "source-annotations.jsonl")
        }
        manifest = json.loads((dataset / "manifest.json").read_text(encoding="utf-8"))
        documents_by_source = {
            document["sourceDocumentId"]: document
            for document in manifest["documents"]
        }
        cases: list[dict[str, Any]] = []
        for source_row_index in source_row_indexes:
            source = source_rows.get(source_row_index)
            if source is None:
                raise ValueError(f"source row {source_row_index} does not exist")
            evidence_sources = parse_list(
                source.get("evidence_sources"), "evidence_sources", source_row_index
            )
            if evidence_sources != ["Pure-text (Plain-text)"]:
                raise ValueError(
                    f"source row {source_row_index} is not plain-text-only evidence"
                )
            printed_pages = [
                int(page) for page in parse_list(
                    source.get("evidence_pages"), "evidence_pages", source_row_index
                )
            ]
            if len(set(printed_pages)) != 1:
                raise ValueError(f"source row {source_row_index} is not single-page evidence")
            if str(source.get("answer", "")).strip().casefold() == "not answerable":
                raise ValueError(f"source row {source_row_index} is not answerable")
            document = documents_by_source.get(source["doc_id"])
            if document is None:
                raise ValueError(f"source row {source_row_index} has no manifest document")
            mapping = page_mapping(dataset / document["file"], printed_pages)
            cases.append({
                "caseId": f"mmlongbench-text-{source_row_index:04d}",
                "sourceRowIndex": source_row_index,
                "question": question_overrides.get(
                    str(source_row_index), str(source["question"])
                ).strip(),
                "sourceDomain": source["doc_type"],
                "answerFormat": source["answer_format"],
                "referenceAnswer": source["answer"],
                "relevance": [{
                    "documentKey": document["documentKey"],
                    "displayName": document["displayName"],
                    "printedEvidencePages": sorted(set(printed_pages)),
                    "physicalEvidencePages": mapping["physicalGoldPages"],
                }],
            })
        return selection, cases

    all_cases = load_jsonl(dataset / "reports" / "n5.2-r3-valid" / "cases.jsonl")
    case_by_id = {case["caseId"]: case for case in all_cases}
    ids = selection.get("caseIds", [])
    if not allow_custom_selection and ids != expected_selection(all_cases):
        raise ValueError("A1 selection does not match the frozen deterministic policy")
    if len(ids) != 20 or len(set(ids)) != 20:
        raise ValueError("A1 selection must contain exactly 20 distinct cases")
    cases = [case_by_id[case_id] for case_id in ids]
    formats = Counter(case["answerFormat"] for case in cases)
    if formats != Counter({answer_format: 5 for answer_format in FORMATS}):
        raise ValueError(f"A1 answer-format quotas are invalid: {formats}")
    documents = {case["relevance"][0]["documentKey"] for case in cases}
    if len(documents) != 20:
        raise ValueError("A1 must cover 20 distinct source documents")
    if len({case["sourceDomain"] for case in cases}) != 7:
        raise ValueError("A1 must cover all seven source domains")
    return selection, cases


def one_http_attempt(
    url: str,
    authorization: str,
    idempotency_key: str,
    payload: dict[str, Any],
    timeout_seconds: float,
    actor_ref: str,
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
            "X-DocQuery-Trace-Id": f"{actor_ref}:{idempotency_key}",
            "X-DocQuery-Actor-Ref": actor_ref,
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
    actor_ref: str,
) -> dict[str, Any]:
    attempts = []
    for number in (1, 2):
        result = one_http_attempt(
            url, authorization, idempotency_key, payload, timeout_seconds, actor_ref
        )
        attempts.append({key: value for key, value in result.items() if key != "body"})
        retryable = result.get("httpStatus") in RETRYABLE_HTTP or result.get("httpStatus") is None
        if result["ok"] or not retryable or number == 2:
            result["finalAttemptLatencyMs"] = result["latencyMs"]
            result["latencyMs"] = round(sum(item["latencyMs"] for item in attempts), 3)
            result["attemptCount"] = number
            result["attempts"] = attempts
            return result
    raise AssertionError("unreachable")


def normalize(value: str | None) -> str:
    text = re.sub(r"\[E\d+]", " ", value or "", flags=re.IGNORECASE)
    text = unicodedata.normalize("NFKC", text).casefold()
    return re.sub(r"[^\w.%]+", "", text)


def decimals(value: str | None) -> list[Decimal]:
    output = []
    for token in re.findall(r"(?<![\w.])-?\d+(?:\.\d+)?", value or ""):
        try:
            output.append(Decimal(token))
        except InvalidOperation:
            continue
    return output


def automatic_answer_assessment(case: dict[str, Any], answer: str | None) -> dict[str, Any]:
    answer_format = case["answerFormat"]
    reference = str(case["referenceAnswer"])
    if not answer:
        return {
            "automaticReferenceMatch": False,
            "referenceItemRecall": 0.0 if answer_format == "List" else None,
            "referenceOrderMatch": False if answer_format == "List" else None,
        }
    if answer_format in {"Int", "Float"}:
        expected = decimals(reference)
        actual = decimals(answer)
        matched = bool(expected) and expected[0] in actual
        return {
            "automaticReferenceMatch": matched,
            "referenceItemRecall": None,
            "referenceOrderMatch": None,
        }
    if answer_format == "Str":
        expected = normalize(reference)
        actual = normalize(answer)
        matched = bool(expected) and (expected in actual or actual in expected)
        return {
            "automaticReferenceMatch": matched,
            "referenceItemRecall": None,
            "referenceOrderMatch": None,
        }
    try:
        expected_items = ast.literal_eval(reference)
    except (ValueError, SyntaxError) as exc:
        raise ValueError(f"invalid List reference for {case['caseId']}") from exc
    if not isinstance(expected_items, list) or not expected_items:
        raise ValueError(f"invalid List reference for {case['caseId']}")
    actual = normalize(answer)
    item_positions = [actual.find(normalize(str(item))) for item in expected_items]
    matched_items = sum(position >= 0 for position in item_positions)
    order_match = matched_items == len(expected_items) and item_positions == sorted(item_positions)
    return {
        "automaticReferenceMatch": matched_items == len(expected_items),
        "referenceItemRecall": matched_items / len(expected_items),
        "referenceOrderMatch": order_match,
    }


def safe_response(body: dict[str, Any] | None, name_to_key: dict[str, str]) -> dict[str, Any]:
    body = body or {}
    citations = []
    for citation in body.get("citations", []) or []:
        position = citation.get("sourcePosition") or {}
        citations.append({
            "citationIndex": citation.get("citationIndex"),
            "documentKey": name_to_key.get(citation.get("documentName")),
            "documentName": citation.get("documentName"),
            "headingPath": citation.get("headingPath") or [],
            "blockId": citation.get("blockId"),
            "text": citation.get("text"),
            "truncated": bool(citation.get("truncated")),
            "pageNumber": position.get("pageNumber"),
        })
    return {
        "queryExecutionId": body.get("queryExecutionId"),
        "status": body.get("status"),
        "answer": body.get("answer"),
        "requestedMode": body.get("requestedMode"),
        "executedMode": body.get("executedMode"),
        "degraded": bool(body.get("degraded")),
        "degradationReason": body.get("degradationReason"),
        "citations": citations,
    }


def evaluate(case: dict[str, Any], response: dict[str, Any]) -> dict[str, Any]:
    relevance = case["relevance"][0]
    gold_document = relevance["documentKey"]
    gold_pages = set(relevance["physicalEvidencePages"])
    citations = response["citations"]
    relevant_citations = [citation for citation in citations if citation["documentKey"] == gold_document]
    hit_pages = {
        int(citation["pageNumber"])
        for citation in relevant_citations
        if citation.get("pageNumber") is not None and int(citation["pageNumber"]) in gold_pages
    }
    return {
        "answered": response.get("status") == "ANSWERED",
        "controlledRefusal": response.get("status") == "INSUFFICIENT_EVIDENCE",
        "citationCount": len(citations),
        "hasRelevantDocumentCitation": bool(relevant_citations),
        "anyGoldPageCitation": bool(hit_pages),
        "goldPageCitationCoverage": len(hit_pages) / len(gold_pages),
        "allGoldPagesCited": hit_pages == gold_pages,
        "printedGoldPages": relevance["printedEvidencePages"],
        "physicalGoldPages": sorted(gold_pages),
        "citedPhysicalGoldPages": sorted(hit_pages),
        **automatic_answer_assessment(case, response.get("answer")),
    }


def mean_boolean(rows: list[dict[str, Any]], field: str) -> float | None:
    values = [row["metrics"].get(field) for row in rows if row.get("metrics")]
    values = [value for value in values if isinstance(value, bool)]
    return round(sum(1.0 if value else 0.0 for value in values) / len(values), 6) if values else None


def mean_numeric(rows: list[dict[str, Any]], field: str) -> float | None:
    values = [row["metrics"].get(field) for row in rows if row.get("metrics")]
    values = [float(value) for value in values if isinstance(value, (int, float)) and not isinstance(value, bool)]
    return round(sum(values) / len(values), 6) if values else None


def summarize(rows: list[dict[str, Any]]) -> dict[str, Any]:
    successful = [row for row in rows if row["http"]["ok"]]
    normal = [
        row for row in successful
        if not row["response"].get("degraded") and row["response"].get("executedMode") == MODE
    ]
    relevant_document_count = sum(
        1 for row in normal if row["metrics"].get("hasRelevantDocumentCitation") is True
    )
    any_gold_page_count = sum(
        1 for row in normal if row["metrics"].get("anyGoldPageCitation") is True
    )
    answered_count = sum(1 for row in normal if row["metrics"].get("answered") is True)
    refusal_count = sum(
        1 for row in normal if row["metrics"].get("controlledRefusal") is True
    )
    automatic_match_count = sum(
        1 for row in normal if row["metrics"].get("automaticReferenceMatch") is True
    )
    all_gold_pages_count = sum(
        1 for row in normal if row["metrics"].get("allGoldPagesCited") is True
    )
    end_to_end_denominator = len(rows)
    return {
        "attempts": len(rows),
        "successes": len(successful),
        "normalModeResponses": len(normal),
        "degradedResponses": sum(1 for row in successful if row["response"].get("degraded")),
        "retryCount": sum(max(0, row["http"]["attemptCount"] - 1) for row in rows),
        "latency": latency_summary([row["http"]["latencyMs"] for row in successful]),
        "metricPopulation": "ALL_20_LOGICAL_REQUESTS; request failures score zero",
        "answeredRate": round(answered_count / end_to_end_denominator, 6),
        "controlledRefusalRate": round(refusal_count / end_to_end_denominator, 6),
        "automaticReferenceMatchRate": round(automatic_match_count / end_to_end_denominator, 6),
        "relevantDocumentCitationRate": round(relevant_document_count / end_to_end_denominator, 6),
        "anyGoldPageCitationRate": round(any_gold_page_count / end_to_end_denominator, 6),
        "goldPageCitationCoverage": round(
            sum(float(row.get("metrics", {}).get("goldPageCitationCoverage", 0.0)) for row in rows)
            / end_to_end_denominator,
            6,
        ),
        "allGoldPagesCitedRate": round(all_gold_pages_count / end_to_end_denominator, 6),
        "relevantDocumentCitationCount": relevant_document_count,
        "anyGoldPageCitationCount": any_gold_page_count,
        "successfulResponseDiagnostics": {
            "population": len(normal),
            "answeredRate": mean_boolean(normal, "answered"),
            "automaticReferenceMatchRate": mean_boolean(normal, "automaticReferenceMatch"),
            "relevantDocumentCitationRate": mean_boolean(normal, "hasRelevantDocumentCitation"),
            "anyGoldPageCitationRate": mean_boolean(normal, "anyGoldPageCitation"),
            "goldPageCitationCoverage": mean_numeric(normal, "goldPageCitationCoverage"),
        },
        "manualAdjudication": "PENDING",
    }


def manual_summary(path: Path, rows: list[dict[str, Any]]) -> dict[str, Any] | None:
    if not path.is_file():
        return None
    value = json.loads(path.read_text(encoding="utf-8"))
    decisions = value.get("decisions", [])
    row_ids = {row["caseId"] for row in rows}
    decision_ids = [decision.get("caseId") for decision in decisions]
    if len(decisions) != 20 or set(decision_ids) != row_ids or len(set(decision_ids)) != 20:
        raise ValueError("manual adjudication must cover every A1 case exactly once")
    correct = sum(1 for decision in decisions if decision.get("correct") is True)
    unsupported = sum(
        1 for decision in decisions if decision.get("citationSupport") == "UNSUPPORTED"
    )
    issues = Counter(
        decision["datasetIssue"] for decision in decisions if decision.get("datasetIssue")
    )
    return {
        "status": "COMPLETED",
        "correctAnswers": correct,
        "accuracy": round(correct / len(decisions), 6),
        "unsupportedCitations": unsupported,
        "datasetIssueCounts": dict(sorted(issues.items())),
        "artifact": path.name,
    }


def markdown_report(report: dict[str, Any]) -> str:
    metrics = report["metrics"]

    def fmt(value: Any) -> str:
        return "N/A" if value is None else f"{value:.4f}"

    lines = [
        "# DocQuery N5.3 Answer 评测报告",
        "",
        f"- 状态：`{report['status']}`",
        f"- 生成时间：`{report['generatedAt']}`",
        f"- 20 道选定的 Answer 公开数据集原题；{report['dataset']['sourceDocumentCount']} 份文档；不传 `documentIds`",
        f"- HYBRID，topK={TOP_K}；成功 {metrics['successes']}/{metrics['attempts']}，降级 {metrics['degradedResponses']}，重试 {metrics['retryCount']}",
        "- 精确 Token / 费用：`NOT_AVAILABLE_IN_CURRENT_ANSWER_CONTRACT`",
        "",
        "| Answered | 自动参考答案匹配 | 正确文档引用 | 任意 Gold 页引用 | Gold 页覆盖率 | 全部 Gold 页引用 | 成功响应 P95 ms |",
        "|---:|---:|---:|---:|---:|---:|---:|",
        f"| {fmt(metrics['answeredRate'])} | {fmt(metrics['automaticReferenceMatchRate'])} | {fmt(metrics['relevantDocumentCitationRate'])} | {fmt(metrics['anyGoldPageCitationRate'])} | {fmt(metrics['goldPageCitationCoverage'])} | {fmt(metrics['allGoldPagesCitedRate'])} | {metrics['latency']['p95Ms'] or 'N/A'} |",
        "",
        "以上质量指标以全部 20 个逻辑请求为分母，请求失败计 0；自动答案匹配只用于初筛，最终答案正确性和引用语义支持度以人工复核产物为准。",
        "",
    ]
    manual = metrics.get("manualAdjudication")
    if isinstance(manual, dict):
        lines.extend([
            "## 人工复核与验收",
            "",
            f"- 按 PDF 原文纠错后的正确答案：{manual['correctAnswers']}/20（{manual['accuracy']:.4f}）",
            f"- 无法由引用完整支持的回答：{manual['unsupportedCitations']} 题",
            f"- 数据集问题：{json.dumps(manual['datasetIssueCounts'], ensure_ascii=False)}",
            f"- 验收：`{'PASS' if report['acceptance']['passed'] else 'FAILED'}`",
            "",
        ])
    return "\n".join(lines)


def main() -> int:
    args = parse_args()
    dataset = args.dataset.resolve()
    selection_path = args.selection.resolve()
    output_dir = args.output_dir.resolve()
    selection, cases = load_selection(
        dataset, selection_path, allow_custom_selection=args.allow_custom_selection
    )
    output_dir.mkdir(parents=True, exist_ok=True)
    selection_audit = {
        "schemaVersion": "docquery-n5.3-a1-selection-audit-v1",
        "status": "VALIDATED",
        "caseCount": len(cases),
        "documentCount": len({case["relevance"][0]["documentKey"] for case in cases}),
        "answerFormatCounts": dict(sorted(Counter(case["answerFormat"] for case in cases).items())),
        "domainCounts": dict(sorted(Counter(case["sourceDomain"] for case in cases).items())),
        "selectionSha256": sha256_file(selection_path),
        "caseIds": [case["caseId"] for case in cases],
    }
    atomic_write_json(output_dir / "selection-audit.json", selection_audit)
    write_jsonl(output_dir / "cases.jsonl", cases)
    if args.validate_only:
        print(json.dumps(selection_audit, ensure_ascii=False))
        return 0

    manifest_path = dataset / "manifest.json"
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    name_to_key = {document["displayName"]: document["documentKey"] for document in manifest["documents"]}
    authorization = credential_from_env_or_file(args.token_env, args.env_file.resolve())
    endpoint = (
        f"{args.base_url.rstrip('/')}/api/v1/service/knowledge-bases/"
        f"{args.knowledge_base_id}/answer"
    )
    raw_path = output_dir / "raw-results.jsonl"
    rows = load_jsonl(raw_path) if raw_path.exists() else []
    allowed = {case["caseId"] for case in cases}
    existing = [row.get("caseId") for row in rows]
    if any(case_id not in allowed for case_id in existing) or len(existing) != len(set(existing)):
        raise ValueError("existing raw-results.jsonl is not a valid resumable A1 result set")
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
        }, ensure_ascii=False), flush=True)

    metrics = summarize(rows)
    manual = manual_summary(output_dir / "manual-adjudication.json", rows)
    if manual is not None:
        metrics["manualAdjudication"] = manual
    all_success = len(rows) == 20 and metrics["successes"] == 20
    all_normal = metrics["normalModeResponses"] == 20
    status = (
        "COMPLETED_WITH_REQUEST_FAILURES" if not all_success
        else "COMPLETED_WITH_DEGRADATION" if not all_normal
        else "COMPLETED_PENDING_MANUAL_REVIEW"
    )
    thresholds = {
        "manualCorrectAnswers": 14,
        "relevantDocumentCitations": 17,
        "anyGoldPageCitations": 13,
        "unsupportedCitations": 0,
    }
    acceptance = {
        "manualCorrectAnswers": manual["correctAnswers"] if manual else None,
        "relevantDocumentCitations": metrics["relevantDocumentCitationCount"],
        "anyGoldPageCitations": metrics["anyGoldPageCitationCount"],
        "unsupportedCitations": manual["unsupportedCitations"] if manual else None,
    }
    acceptance["passed"] = bool(
        manual
        and acceptance["manualCorrectAnswers"] >= thresholds["manualCorrectAnswers"]
        and acceptance["relevantDocumentCitations"] >= thresholds["relevantDocumentCitations"]
        and acceptance["anyGoldPageCitations"] >= thresholds["anyGoldPageCitations"]
        and acceptance["unsupportedCitations"] <= thresholds["unsupportedCitations"]
        and all_success
        and all_normal
    )
    if manual is not None:
        status = "COMPLETED" if acceptance["passed"] else "COMPLETED_FAILED_ACCEPTANCE"
    report = {
        "schemaVersion": args.schema_version,
        "status": status,
        "generatedAt": dt.datetime.now(dt.timezone.utc).isoformat(),
        "dataset": {
            "version": manifest["datasetVersion"],
            "caseCount": len(cases),
            "sourceDocumentCount": len({
                case["relevance"][0]["documentKey"] for case in cases
            }),
            "manifestSha256": sha256_file(manifest_path),
            "r3CasesSha256": sha256_file(dataset / "reports" / "n5.2-r3-valid" / "cases.jsonl"),
            "selectionSha256": sha256_file(selection_path),
            "selectionPolicy": selection["selectionPolicy"],
        },
        "configuration": {
            "baseUrl": args.base_url.rstrip("/"),
            "knowledgeBaseId": args.knowledge_base_id,
            "mode": MODE,
            "topK": TOP_K,
            "documentIdsProvided": False,
            "answerPolicyVersion": args.answer_policy_version,
            "runId": args.run_id,
            "actorRef": args.actor_ref,
            "credentialEnvironmentVariable": args.token_env,
            "python": platform.python_version(),
            "platform": platform.platform(),
        },
        "usageBoundary": {
            "exactProviderTokenUsage": "NOT_AVAILABLE_IN_CURRENT_ANSWER_CONTRACT",
            "exactCost": "NOT_AVAILABLE",
            "answerLogicalRequestUpperBound": 20,
        },
        "metrics": metrics,
        "acceptanceThresholds": thresholds,
        "acceptance": acceptance,
        "artifacts": {
            "rawResults": raw_path.name,
            "cases": "cases.jsonl",
            "selectionAudit": "selection-audit.json",
            "manualAdjudication": "manual-adjudication.json",
            "auditSummary": "audit-summary.json",
        },
    }
    atomic_write_json(output_dir / "report.json", report)
    (output_dir / "report.md").write_text(markdown_report(report), encoding="utf-8")
    print(json.dumps({
        "status": status,
        "logicalRequests": len(rows),
        "report": str(output_dir / "report.json"),
    }, ensure_ascii=False))
    return 0 if all_success and all_normal else 2


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except ValueError as exc:
        print(f"configuration error: {exc}", file=os.sys.stderr)
        raise SystemExit(2)
