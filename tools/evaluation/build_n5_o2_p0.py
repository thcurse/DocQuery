"""Build the N5.2-O2-P0 corrected diagnostic from frozen R1/R2 responses."""

from __future__ import annotations

import json
from collections import defaultdict
from pathlib import Path
from typing import Any

from pypdf import PdfReader

from n3_eval_common import atomic_write_json, load_jsonl, sha256_file


SCHEMA_VERSION = "docquery-n5.2-o2-p0-v1"
METRICS = (
    "documentRecallAt1",
    "documentRecallAt5",
    "documentRecallAt10",
    "evidencePageRecallAt10",
    "evidencePageCoverageAt10",
    "allEvidencePagesHitAt10",
    "reciprocalRankAt10",
)


def mean(rows: list[dict[str, Any]], metric: str) -> float:
    return round(sum(float(row["metrics"][metric]) for row in rows) / len(rows), 6)


def summarize(rows: list[dict[str, Any]]) -> dict[str, Any]:
    return {"cases": len(rows), **{metric: mean(rows, metric) for metric in METRICS}}


def page_mapping(pdf_path: Path, printed_pages: list[int]) -> dict[str, Any]:
    reader = PdfReader(pdf_path)
    labels = reader.page_labels
    printed_to_physical: dict[str, int] = {}
    for printed in sorted(set(printed_pages)):
        matches = [index + 1 for index, label in enumerate(labels) if str(label) == str(printed)]
        if len(matches) != 1:
            raise ValueError(
                f"Gold page label {printed} is not unique in {pdf_path.name}: {matches}"
            )
        printed_to_physical[str(printed)] = matches[0]
    return {
        "source": "PDF_PAGE_LABELS",
        "pdfPageCount": len(reader.pages),
        "printedToPhysical": printed_to_physical,
        "physicalGoldPages": sorted(set(printed_to_physical.values())),
    }


def corrected_metrics(
    case: dict[str, Any],
    row: dict[str, Any],
    key_to_sha: dict[str, str],
    mapping: dict[str, Any],
) -> dict[str, Any]:
    gold_key = case["relevance"][0]["documentKey"]
    gold_sha = key_to_sha[gold_key]
    results = row["response"]["results"]
    equivalent = [
        result for result in results
        if key_to_sha.get(result["documentKey"]) == gold_sha
    ]

    def hit_at(k: int) -> float:
        return 1.0 if any(result["rank"] <= k for result in equivalent) else 0.0

    first_rank = min((result["rank"] for result in equivalent), default=None)
    gold_pages = set(mapping["physicalGoldPages"])
    hit_pages = {
        evidence["pageNumber"]
        for result in equivalent
        if result["rank"] <= 10
        for evidence in result["evidence"]
        if evidence.get("pageNumber") in gold_pages
    }
    return {
        "documentRecallAt1": hit_at(1),
        "documentRecallAt5": hit_at(5),
        "documentRecallAt10": hit_at(10),
        "evidencePageRecallAt10": 1.0 if hit_pages else 0.0,
        "evidencePageCoverageAt10": len(hit_pages) / len(gold_pages),
        "allEvidencePagesHitAt10": 1.0 if hit_pages == gold_pages else 0.0,
        "reciprocalRankAt10": 1.0 / first_rank if first_rank is not None and first_rank <= 10 else 0.0,
        "printedGoldPages": sorted(set(case["relevance"][0]["evidencePages"])),
        "physicalGoldPages": sorted(gold_pages),
        "hitPhysicalGoldPages": sorted(hit_pages),
        "goldContentSha256": gold_sha,
    }


def main() -> int:
    root = Path(__file__).resolve().parents[2]
    dataset = root / "evaluation" / "mmlongbench-docquery-v1"
    selection_path = dataset / "n5.2-o2-p0-selection.json"
    output_dir = dataset / "reports" / "n5.2-o2-p0"
    selection = json.loads(selection_path.read_text(encoding="utf-8"))
    selected_ids = selection["caseIds"]
    excluded_ids = [case_id for values in selection["excluded"].values() for case_id in values]

    all_cases = load_jsonl(dataset / "cases.jsonl")
    answerable = [case for case in all_cases if case["answerability"] == "ANSWERABLE"]
    answerable_ids = {case["caseId"] for case in answerable}
    if set(selected_ids).intersection(excluded_ids):
        raise ValueError("selected and excluded cases overlap")
    if set(selected_ids).union(excluded_ids) != answerable_ids:
        raise ValueError("selection must partition all ANSWERABLE cases")
    if len(selected_ids) != 25 or len(set(selected_ids)) != 25:
        raise ValueError("O2-P0 selection must contain 25 distinct cases")
    case_by_id = {case["caseId"]: case for case in answerable}

    manifest_path = dataset / "manifest.json"
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    document_by_key = {document["documentKey"]: document for document in manifest["documents"]}
    key_to_sha = {key: document["sha256"] for key, document in document_by_key.items()}
    duplicates: dict[str, list[str]] = defaultdict(list)
    for document in manifest["documents"]:
        duplicates[document["sha256"]].append(document["displayName"])
    duplicate_groups = [
        {"sha256": digest, "documents": names}
        for digest, names in sorted(duplicates.items()) if len(names) > 1
    ]

    r1_path = dataset / "reports" / "n5.2-r1-pilot" / "raw-results.jsonl"
    r2_path = dataset / "reports" / "n5.2-r2-heldout" / "raw-results.jsonl"
    source_rows = [
        row for row in load_jsonl(r1_path) + load_jsonl(r2_path)
        if row.get("mode") == "HYBRID"
    ]
    row_by_id = {row["caseId"]: row for row in source_rows}
    if any(case_id not in row_by_id for case_id in selected_ids):
        raise ValueError("frozen HYBRID results are incomplete")

    mappings: dict[str, Any] = {}
    corrected_rows: list[dict[str, Any]] = []
    original_rows: list[dict[str, Any]] = []
    for case_id in selected_ids:
        case = case_by_id[case_id]
        document = document_by_key[case["relevance"][0]["documentKey"]]
        mapping = page_mapping(
            dataset / document["file"],
            case["relevance"][0]["evidencePages"],
        )
        mappings[case_id] = {
            "documentKey": document["documentKey"],
            "displayName": document["displayName"],
            **mapping,
        }
        source_row = row_by_id[case_id]
        original_rows.append(source_row)
        corrected_rows.append({
            "caseId": case_id,
            "question": case["question"],
            "metrics": corrected_metrics(case, source_row, key_to_sha, mapping),
        })

    original = summarize(original_rows)
    corrected = summarize(corrected_rows)
    original_by_id = {row["caseId"]: row for row in original_rows}
    corrected_by_id = {row["caseId"]: row for row in corrected_rows}
    shifted = [
        case_id for case_id, mapping in mappings.items()
        if any(int(printed) != physical for printed, physical in mapping["printedToPhysical"].items())
    ]
    page_score_changes = []
    for case_id in shifted:
        before = original_by_id[case_id]["metrics"]
        after = corrected_by_id[case_id]["metrics"]
        if (
            float(before["evidencePageCoverageAt10"])
            != float(after["evidencePageCoverageAt10"])
            or float(before["evidencePageRecallAt10"])
            != float(after["evidencePageRecallAt10"])
        ):
            page_score_changes.append({
                "caseId": case_id,
                "printedToPhysical": mappings[case_id]["printedToPhysical"],
                "beforeAnyPageHitAt10": before["evidencePageRecallAt10"],
                "afterAnyPageHitAt10": after["evidencePageRecallAt10"],
                "beforePageCoverageAt10": before["evidencePageCoverageAt10"],
                "afterPageCoverageAt10": after["evidencePageCoverageAt10"],
            })
    report = {
        "schemaVersion": SCHEMA_VERSION,
        "status": "COMPLETED",
        "diagnosticOnly": True,
        "paidRequests": 0,
        "selection": {
            "selected": len(selected_ids),
            "excluded": len(excluded_ids),
            "selectionBasedOnRetrievalResults": False,
            "selectionSha256": sha256_file(selection_path),
        },
        "pageMapping": {
            "method": "PDF Page Labels mapped to one-based physical PDF pages",
            "mappedCases": len(mappings),
            "shiftedCaseIds": shifted,
            "scoreChanges": page_score_changes,
            "artifact": "page-mapping.json",
        },
        "duplicateContentGroups": duplicate_groups,
        "metrics": {
            "frozenOriginalScoring": original,
            "correctedContentEquivalentAndPhysicalPageScoring": corrected,
        },
        "boundary": (
            "This is a post-result, question-text-curated diagnostic subset. "
            "It is not a new held-out score and does not replace the frozen R2 report."
        ),
        "sources": {
            "manifestSha256": sha256_file(manifest_path),
            "r1RawSha256": sha256_file(r1_path),
            "r2RawSha256": sha256_file(r2_path),
        },
    }
    output_dir.mkdir(parents=True, exist_ok=True)
    atomic_write_json(output_dir / "page-mapping.json", mappings)
    atomic_write_json(output_dir / "corrected-results.json", corrected_rows)
    atomic_write_json(output_dir / "report.json", report)
    (output_dir / "report.md").write_text(
        "\n".join([
            "# DocQuery N5.2-O2-P0 评测口径修正报告",
            "",
            "- 状态：`COMPLETED`",
            "- 25题只按问题文本的可定位性筛选，不改写、不生成问题",
            "- 使用冻结的 R1/R2 HYBRID 原始响应重新计分，付费请求：0",
            "- 相同 SHA-256 文档按同一内容来源计分",
            "- Gold 印刷页通过 PDF Page Labels 映射到物理页",
            "- 本报告是已观察结果后的诊断子集，不替代 R2 held-out 报告",
            "",
            "| 口径 | Doc Hit@5 | Doc Hit@10 | Page Coverage@10 | Any Page Hit@10 | MRR@10 |",
            "|---|---:|---:|---:|---:|---:|",
            f"| 原冻结算法 | {original['documentRecallAt5']:.4f} | {original['documentRecallAt10']:.4f} | {original['evidencePageCoverageAt10']:.4f} | {original['evidencePageRecallAt10']:.4f} | {original['reciprocalRankAt10']:.4f} |",
            f"| 修正口径 | {corrected['documentRecallAt5']:.4f} | {corrected['documentRecallAt10']:.4f} | {corrected['evidencePageCoverageAt10']:.4f} | {corrected['evidencePageRecallAt10']:.4f} | {corrected['reciprocalRankAt10']:.4f} |",
            "",
            f"发生非恒等页码映射的入选题：{', '.join(shifted) or '无'}。",
            "",
            "页码修正逐题影响：",
            "",
            *[
                f"- `{change['caseId']}`：Any Page Hit {change['beforeAnyPageHitAt10']:.0f} → {change['afterAnyPageHitAt10']:.0f}，Page Coverage {change['beforePageCoverageAt10']:.4f} → {change['afterPageCoverageAt10']:.4f}，映射 {change['printedToPhysical']}"
                for change in page_score_changes
            ],
            "",
        ]),
        encoding="utf-8",
    )
    print(json.dumps({"status": "COMPLETED", "selected": len(selected_ids), "report": str(output_dir / "report.json")}, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except ValueError as exc:
        print(f"configuration error: {exc}", file=__import__("sys").stderr)
        raise SystemExit(2)
