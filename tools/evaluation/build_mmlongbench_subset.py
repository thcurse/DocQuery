"""Build DocQuery's frozen MMLongBench-Doc PDF evaluation subset.

The builder uses only the public Hugging Face dataset snapshot pinned below.
It never creates or edits a PDF and never calls a model or paid provider.
"""

from __future__ import annotations

import argparse
import ast
import hashlib
import json
import os
import shutil
import time
import urllib.parse
import urllib.request
from collections import Counter, defaultdict
from concurrent.futures import ThreadPoolExecutor, as_completed
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Iterable


DATASET_VERSION = "mmlongbench-docquery-v1"
SOURCE_DATASET = "MMLongBench-Doc"
SOURCE_REPOSITORY = "yubo2333/MMLongBench-Doc"
SOURCE_REVISION = "2ff6aa9237fc777b6627dc57a486e9225ac5fb86"
SOURCE_ROWS = 1_091
SOURCE_DOCUMENTS = 135
SOURCE_PARQUET_SHA256 = (
    "bcdac3c96669634c34184814cede4fe57cf7ac0f98dde0e85936394f6a56a02d"
)
SOURCE_ANNOTATIONS_SHA256 = (
    "c099b4ff57bf88cbee199d217eda8c653f44c47e1acb1768da3dcc9efa45b1dd"
)
SELECTION_SEED = "docquery-mmlongbench-pure-text-v1"
PURE_TEXT_SOURCE = "Pure-text (Plain-text)"
NOT_ANSWERABLE = "Not answerable"
TARGET_DOCUMENTS = 100

DOMAIN_ORDER = (
    "Academic paper",
    "Administration/Industry file",
    "Brochure",
    "Financial report",
    "Guidebook",
    "Research report / Introduction",
    "Tutorial/Workshop",
)
ANSWERABLE_QUOTAS = {
    "Academic paper": 8,
    "Administration/Industry file": 12,
    "Brochure": 8,
    "Financial report": 8,
    "Guidebook": 10,
    "Research report / Introduction": 10,
    "Tutorial/Workshop": 8,
}
UNANSWERABLE_QUOTAS = {
    "Academic paper": 2,
    "Administration/Industry file": 2,
    "Brochure": 2,
    "Financial report": 2,
    "Guidebook": 3,
    "Research report / Introduction": 3,
    "Tutorial/Workshop": 2,
}
ANSWER_FORMATS = frozenset({"Str", "Int", "Float", "List"})
HF_METADATA_URL = f"https://huggingface.co/api/datasets/{SOURCE_REPOSITORY}"
HF_ROWS_URL = "https://datasets-server.huggingface.co/rows"
PDF_BASE_URL = (
    f"https://huggingface.co/datasets/{SOURCE_REPOSITORY}/resolve/"
    f"{SOURCE_REVISION}/documents"
)


@dataclass(frozen=True)
class SourceRow:
    index: int
    doc_id: str
    doc_type: str
    question: str
    answer: str
    evidence_pages: tuple[int, ...]
    evidence_sources: tuple[str, ...]
    answer_format: str | None

    @property
    def case_id(self) -> str:
        return f"mmlongbench-{self.index:04d}"


def sha256_bytes(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def document_key(doc_id: str) -> str:
    return f"mmlongbench-{sha256_bytes(doc_id.encode('utf-8'))[:20]}"


def selection_score(label: str, *parts: object) -> str:
    material = "|".join((SELECTION_SEED, label, *(str(part) for part in parts)))
    return sha256_bytes(material.encode("utf-8"))


def _literal_list(value: str, element_type: type) -> tuple[Any, ...]:
    try:
        parsed = ast.literal_eval(value)
    except (SyntaxError, ValueError) as exc:
        raise ValueError(f"invalid source list literal: {value!r}") from exc
    if not isinstance(parsed, list) or any(not isinstance(item, element_type) for item in parsed):
        raise ValueError(f"invalid source list literal: {value!r}")
    return tuple(parsed)


def source_row(value: dict[str, Any]) -> SourceRow:
    required_strings = ("doc_id", "doc_type", "question", "answer")
    if any(not isinstance(value.get(field), str) or not value[field].strip()
           for field in required_strings):
        raise ValueError(f"source row has missing text fields: {value!r}")
    index = value.get("sourceRowIndex")
    if isinstance(index, bool) or not isinstance(index, int) or index < 0:
        raise ValueError(f"source row has invalid index: {index!r}")
    answer_format = value.get("answer_format")
    if answer_format is not None and not isinstance(answer_format, str):
        raise ValueError(f"source row has invalid answer format: {answer_format!r}")
    if answer_format == "None":
        answer_format = None
    return SourceRow(
        index=index,
        doc_id=value["doc_id"],
        doc_type=value["doc_type"],
        question=value["question"],
        answer=value["answer"],
        evidence_pages=_literal_list(value.get("evidence_pages", "[]"), int),
        evidence_sources=_literal_list(value.get("evidence_sources", "[]"), str),
        answer_format=answer_format,
    )


def canonical_source_value(index: int, row: dict[str, Any]) -> dict[str, Any]:
    return {
        "sourceRowIndex": index,
        "doc_id": row.get("doc_id"),
        "doc_type": row.get("doc_type"),
        "question": row.get("question"),
        "answer": row.get("answer"),
        "evidence_pages": row.get("evidence_pages"),
        "evidence_sources": row.get("evidence_sources"),
        "answer_format": row.get("answer_format"),
    }


def _request_json(url: str, timeout_seconds: float = 60.0) -> dict[str, Any]:
    request = urllib.request.Request(
        url,
        headers={"User-Agent": "DocQuery-N5.1-public-evaluation/1.0"},
    )
    with urllib.request.urlopen(request, timeout=timeout_seconds) as response:
        return json.loads(response.read().decode("utf-8"))


def fetch_source_snapshot(output: Path) -> None:
    metadata = _request_json(HF_METADATA_URL)
    if metadata.get("sha") != SOURCE_REVISION:
        raise ValueError(
            "Hugging Face dataset head changed; update only after reviewing the new snapshot: "
            f"expected {SOURCE_REVISION}, got {metadata.get('sha')}"
        )
    if metadata.get("private") is not False or metadata.get("gated") not in (False, None):
        raise ValueError("source dataset is no longer public and ungated")

    rows: list[dict[str, Any]] = []
    for offset in range(0, SOURCE_ROWS, 100):
        query = urllib.parse.urlencode({
            "dataset": SOURCE_REPOSITORY,
            "config": "default",
            "split": "train",
            "offset": offset,
            "length": min(100, SOURCE_ROWS - offset),
        })
        payload = _request_json(f"{HF_ROWS_URL}?{query}")
        if payload.get("num_rows_total") != SOURCE_ROWS:
            raise ValueError(
                f"unexpected source row count: {payload.get('num_rows_total')}"
            )
        for item in payload.get("rows", []):
            rows.append(canonical_source_value(item["row_idx"], item["row"]))

    parsed = [source_row(row) for row in rows]
    validate_source_rows(parsed)
    output.parent.mkdir(parents=True, exist_ok=True)
    temporary = output.with_suffix(output.suffix + ".tmp")
    temporary.write_text(
        "".join(
            json.dumps(row, ensure_ascii=False, separators=(",", ":")) + "\n"
            for row in rows
        ),
        encoding="utf-8",
        newline="\n",
    )
    if sha256_file(temporary) != SOURCE_ANNOTATIONS_SHA256:
        temporary.unlink(missing_ok=True)
        raise ValueError("source annotations do not match the frozen canonical snapshot")
    temporary.replace(output)


def read_source_snapshot(path: Path) -> list[SourceRow]:
    rows = [
        source_row(json.loads(line))
        for line in path.read_text(encoding="utf-8").splitlines()
        if line.strip()
    ]
    validate_source_rows(rows)
    return rows


def validate_source_rows(rows: list[SourceRow]) -> None:
    if len(rows) != SOURCE_ROWS:
        raise ValueError(f"expected {SOURCE_ROWS} source rows, got {len(rows)}")
    indexes = [row.index for row in rows]
    if indexes != list(range(SOURCE_ROWS)):
        raise ValueError("source row indexes are missing, duplicated, or reordered")
    documents = {row.doc_id for row in rows}
    if len(documents) != SOURCE_DOCUMENTS:
        raise ValueError(
            f"expected {SOURCE_DOCUMENTS} source documents, got {len(documents)}"
        )
    domains = {row.doc_type for row in rows}
    if domains != set(DOMAIN_ORDER):
        raise ValueError(f"unexpected source domains: {sorted(domains)}")


def is_answerable_candidate(row: SourceRow) -> bool:
    return (
        row.answer != NOT_ANSWERABLE
        and row.answer_format in ANSWER_FORMATS
        and bool(row.evidence_pages)
        and row.evidence_sources == (PURE_TEXT_SOURCE,)
    )


def is_unanswerable_candidate(row: SourceRow) -> bool:
    return (
        row.answer == NOT_ANSWERABLE
        and not row.evidence_pages
        and not row.evidence_sources
        and row.answer_format is None
    )


def _select_domain_cases(
    candidates: Iterable[SourceRow],
    quota: int,
    label: str,
    used_documents: set[str],
) -> list[SourceRow]:
    ordered = sorted(
        candidates,
        key=lambda row: (
            selection_score(label, row.doc_type, row.index, row.doc_id, row.question),
            row.index,
        ),
    )
    selected: list[SourceRow] = []
    selected_indexes: set[int] = set()
    for prefer_new_document in (True, False):
        for row in ordered:
            if row.index in selected_indexes:
                continue
            if prefer_new_document and row.doc_id in used_documents:
                continue
            selected.append(row)
            selected_indexes.add(row.index)
            used_documents.add(row.doc_id)
            if len(selected) == quota:
                return selected
    raise ValueError(f"insufficient {label}/{ordered[0].doc_type if ordered else '?'} cases")


def select_cases(rows: list[SourceRow]) -> list[SourceRow]:
    by_domain: dict[str, list[SourceRow]] = defaultdict(list)
    for row in rows:
        by_domain[row.doc_type].append(row)

    selected: list[SourceRow] = []
    used_documents: set[str] = set()
    for domain in DOMAIN_ORDER:
        selected.extend(_select_domain_cases(
            (row for row in by_domain[domain] if is_answerable_candidate(row)),
            ANSWERABLE_QUOTAS[domain],
            "answerable",
            used_documents,
        ))
    for domain in DOMAIN_ORDER:
        selected.extend(_select_domain_cases(
            (row for row in by_domain[domain] if is_unanswerable_candidate(row)),
            UNANSWERABLE_QUOTAS[domain],
            "unanswerable",
            used_documents,
        ))

    if len(selected) != 80 or len({row.index for row in selected}) != 80:
        raise ValueError("selection did not produce 80 unique cases")
    return sorted(selected, key=lambda row: row.case_id)


def select_documents(rows: list[SourceRow], cases: list[SourceRow]) -> list[str]:
    all_documents = sorted({row.doc_id for row in rows})
    selected = {row.doc_id for row in cases}
    for doc_id in sorted(
        (doc_id for doc_id in all_documents if doc_id not in selected),
        key=lambda value: (selection_score("distractor", value), value),
    ):
        selected.add(doc_id)
        if len(selected) == TARGET_DOCUMENTS:
            break
    if len(selected) != TARGET_DOCUMENTS:
        raise ValueError(f"expected {TARGET_DOCUMENTS} documents, got {len(selected)}")
    return sorted(selected, key=lambda value: (document_key(value), value))


def pdf_url(doc_id: str) -> str:
    return f"{PDF_BASE_URL}/{urllib.parse.quote(doc_id, safe='')}"


def _valid_pdf_header(path: Path) -> bool:
    if not path.is_file() or path.stat().st_size < 8:
        return False
    with path.open("rb") as stream:
        return stream.read(5) == b"%PDF-"


def download_pdf(doc_id: str, destination: Path, retries: int = 3) -> None:
    if _valid_pdf_header(destination):
        return
    destination.parent.mkdir(parents=True, exist_ok=True)
    temporary = destination.with_suffix(destination.suffix + ".part")
    for attempt in range(1, retries + 1):
        try:
            request = urllib.request.Request(
                pdf_url(doc_id),
                headers={"User-Agent": "DocQuery-N5.1-public-evaluation/1.0"},
            )
            with urllib.request.urlopen(request, timeout=180) as response, \
                    temporary.open("wb") as output:
                shutil.copyfileobj(response, output, length=1024 * 1024)
            if not _valid_pdf_header(temporary):
                raise ValueError(f"downloaded file is not a PDF: {doc_id}")
            temporary.replace(destination)
            return
        except Exception:
            if attempt == retries:
                raise
            time.sleep(attempt)


def download_documents(doc_ids: list[str], cache: Path, workers: int) -> None:
    cache.mkdir(parents=True, exist_ok=True)
    with ThreadPoolExecutor(max_workers=workers) as executor:
        futures = {
            executor.submit(download_pdf, doc_id, cache / doc_id): doc_id
            for doc_id in doc_ids
        }
        for future in as_completed(futures):
            doc_id = futures[future]
            try:
                future.result()
            except Exception as exc:
                raise RuntimeError(f"failed to download {doc_id}: {exc}") from exc


def _answer_claims(row: SourceRow) -> list[str]:
    if row.answer_format != "List":
        return []
    try:
        parsed = ast.literal_eval(row.answer)
    except (SyntaxError, ValueError):
        return []
    if not isinstance(parsed, list):
        return []
    return [str(item) for item in parsed if str(item).strip()]


def build_case(row: SourceRow) -> dict[str, Any]:
    answerable = is_answerable_candidate(row)
    key = document_key(row.doc_id)
    relevance = []
    if answerable:
        relevance.append({
            "documentKey": key,
            "evidencePages": list(row.evidence_pages),
            "evidenceSources": list(row.evidence_sources),
            "anchorTexts": [],
        })
    return {
        "caseId": row.case_id,
        "split": "ACCEPTANCE",
        "kind": "PAGE_EVIDENCE" if answerable else "CONTROLLED_REFUSAL",
        "question": row.question,
        "questionType": row.answer_format or "NONE",
        "answerability": "ANSWERABLE" if answerable else "UNANSWERABLE",
        "sourceDataset": SOURCE_DATASET,
        "sourceRowIndex": row.index,
        "sourceDocumentKey": key,
        "sourceDocumentId": row.doc_id,
        "sourceDomain": row.doc_type,
        "answerFormat": row.answer_format,
        "referenceAnswer": row.answer,
        "goldAnswers": [row.answer] if answerable else [],
        "evidenceQualification": "MMLONGBENCH_EXPERT_ANNOTATED_PAGES",
        "relevance": relevance,
        "requiredClaims": _answer_claims(row) if answerable else [],
    }


def _link_or_copy(source: Path, destination: Path) -> None:
    try:
        os.link(source, destination)
    except OSError:
        shutil.copy2(source, destination)


def write_json(path: Path, value: object) -> None:
    path.write_text(
        json.dumps(value, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
        newline="\n",
    )


def write_dataset(
    output: Path,
    source_snapshot: Path,
    pdf_cache: Path,
    rows: list[SourceRow],
    cases: list[SourceRow],
    doc_ids: list[str],
) -> None:
    if output.exists() and any(output.iterdir()):
        raise ValueError(f"output directory must be absent or empty: {output}")
    corpus = output / "corpus"
    corpus.mkdir(parents=True, exist_ok=True)
    snapshot_output = output / "source-annotations.jsonl"
    shutil.copyfile(source_snapshot, snapshot_output)

    case_values = [build_case(row) for row in cases]
    cases_path = output / "cases.jsonl"
    cases_path.write_text(
        "".join(
            json.dumps(case, ensure_ascii=False, separators=(",", ":")) + "\n"
            for case in case_values
        ),
        encoding="utf-8",
        newline="\n",
    )

    rows_by_document: dict[str, list[SourceRow]] = defaultdict(list)
    for row in rows:
        rows_by_document[row.doc_id].append(row)
    cases_by_document: dict[str, list[SourceRow]] = defaultdict(list)
    for row in cases:
        cases_by_document[row.doc_id].append(row)

    manifest_documents: list[dict[str, Any]] = []
    corpus_checksums: list[str] = []
    for doc_id in doc_ids:
        source = pdf_cache / doc_id
        if not _valid_pdf_header(source):
            raise ValueError(f"missing or invalid source PDF: {source}")
        destination = corpus / doc_id
        _link_or_copy(source, destination)
        digest = sha256_file(destination)
        domain_counts = Counter(row.doc_type for row in rows_by_document[doc_id])
        if len(domain_counts) != 1:
            raise ValueError(f"document has ambiguous domain: {doc_id}")
        document_cases = sorted(cases_by_document.get(doc_id, []), key=lambda row: row.case_id)
        role = "CASE_SOURCE" if document_cases else "DISTRACTOR"
        manifest_documents.append({
            "documentKey": document_key(doc_id),
            "file": f"corpus/{doc_id}",
            "format": "PDF",
            "displayName": doc_id,
            "sourceDocumentId": doc_id,
            "sourceDomain": next(iter(domain_counts)),
            "sourceUrl": pdf_url(doc_id),
            "sha256": digest,
            "bytes": destination.stat().st_size,
            "role": role,
            "caseIds": [row.case_id for row in document_cases],
        })
        corpus_checksums.append(f"{digest}  corpus/{doc_id}\n")

    corpus_checksums_path = output / "corpus-checksums.sha256"
    corpus_checksums_path.write_text("".join(corpus_checksums), encoding="ascii", newline="\n")
    answerability_counts = Counter(case["answerability"] for case in case_values)
    domain_counts = Counter(case["sourceDomain"] for case in case_values)
    format_counts = Counter(case["answerFormat"] or "NONE" for case in case_values)
    role_counts = Counter(document["role"] for document in manifest_documents)

    manifest = {
        "datasetVersion": DATASET_VERSION,
        "sourceDataset": SOURCE_DATASET,
        "sourceRepository": SOURCE_REPOSITORY,
        "sourceRevision": SOURCE_REVISION,
        "sourceParquetSha256": SOURCE_PARQUET_SHA256,
        "sourceRows": SOURCE_ROWS,
        "sourceDocuments": SOURCE_DOCUMENTS,
        "license": "CC-BY-NC-4.0 research use only",
        "officialLeaderboardComparable": False,
        "selectionSeed": SELECTION_SEED,
        "selection": {
            "answerableQuotasByDomain": ANSWERABLE_QUOTAS,
            "unanswerableQuotasByDomain": UNANSWERABLE_QUOTAS,
            "answerableEvidenceSources": [PURE_TEXT_SOURCE],
            "excludedEvidenceSources": ["Generalized-text (Layout)", "Table", "Chart", "Figure"],
            "documentTarget": TARGET_DOCUMENTS,
            "preferDistinctCaseDocuments": True,
            "remainingDocuments": "DETERMINISTIC_SAME_DATASET_DISTRACTORS",
        },
        "documentCount": len(manifest_documents),
        "caseSourceDocumentCount": role_counts["CASE_SOURCE"],
        "distractorDocumentCount": role_counts["DISTRACTOR"],
        "caseCount": len(case_values),
        "answerableCaseCount": answerability_counts["ANSWERABLE"],
        "unanswerableCaseCount": answerability_counts["UNANSWERABLE"],
        "caseDomainCounts": dict(sorted(domain_counts.items())),
        "caseAnswerFormatCounts": dict(sorted(format_counts.items())),
        "sourceAnnotationsSha256": sha256_file(snapshot_output),
        "casesSha256": sha256_file(cases_path),
        "corpusChecksumsSha256": sha256_file(corpus_checksums_path),
        "documents": manifest_documents,
    }
    write_json(output / "manifest.json", manifest)

    metadata_paths = [snapshot_output, cases_path, corpus_checksums_path]
    (output / "checksums.sha256").write_text(
        "".join(
            f"{sha256_file(path)}  {path.relative_to(output).as_posix()}\n"
            for path in metadata_paths
        ),
        encoding="ascii",
        newline="\n",
    )
    (output / "README.md").write_text(
        "# mmlongbench-docquery-v1\n\n"
        "这是 MMLongBench-Doc 的 DocQuery 确定性非商业评测子集：100 份真实原始 PDF、"
        "64 个纯文本证据可回答问题和 16 个不可回答问题。PDF 均来自上游锁定快照，"
        "DocQuery 不生成、不转换、不改写这些文件。\n\n"
        "正式来源：[MMLongBench-Doc 官方仓库](https://github.com/mayubo2333/MMLongBench-Doc)。"
        "上游数据仅限研究用途并采用 CC BY-NC 4.0；本产物只用于 DocQuery 的非商业评测与"
        "秋招展示，不得作为商用语料重新分发。\n\n"
        "本子集只选择官方标为 `Pure-text (Plain-text)` 的可回答题，排除依赖图片、图表、"
        "表格或版面坐标的问题；不可回答题保留官方空证据标注。它不是官方完整榜单结果，"
        "也不证明 OCR、扫描件、复杂表格、视觉问答或 DOCX 质量。\n\n"
        "由于上游纯文本可回答题只覆盖 60 份不同 PDF，80 个 case 不强制一题一文档。"
        "选择器优先扩大 case 文档覆盖面，再从同一公开数据集确定性补足到 100 份 PDF；"
        "实际 CASE_SOURCE 与 DISTRACTOR 数量记录在 manifest。\n\n"
        "复现（不调用模型或供应商）：\n\n"
        "```powershell\n"
        "python tools/evaluation/build_mmlongbench_subset.py all `\n"
        "  --cache tmp/pdfs/mmlongbench-source `\n"
        "  --output evaluation/mmlongbench-docquery-v1\n"
        "```\n\n"
        "`source-annotations.jsonl` 保存1091条官方标注的规范化快照；PDF 校验和位于"
        "`corpus-checksums.sha256`。上传时保留原始文件名，评测器按 manifest 的"
        "`displayName` 映射稳定 documentKey。\n",
        encoding="utf-8",
        newline="\n",
    )


def build_all(cache: Path, output: Path, workers: int) -> None:
    snapshot = cache / "source-annotations.jsonl"
    if not snapshot.exists():
        fetch_source_snapshot(snapshot)
    if sha256_file(snapshot) != SOURCE_ANNOTATIONS_SHA256:
        raise ValueError("cached source annotations do not match the frozen snapshot")
    rows = read_source_snapshot(snapshot)
    cases = select_cases(rows)
    documents = select_documents(rows, cases)
    pdf_cache = cache / "pdf"
    download_documents(documents, pdf_cache, workers)
    write_dataset(output, snapshot, pdf_cache, rows, cases, documents)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    subparsers = parser.add_subparsers(dest="command", required=True)

    fetch = subparsers.add_parser("fetch-annotations")
    fetch.add_argument("--output", type=Path, required=True)

    build = subparsers.add_parser("all")
    build.add_argument("--cache", type=Path, required=True)
    build.add_argument("--output", type=Path, required=True)
    build.add_argument("--workers", type=int, default=6)

    args = parser.parse_args()
    if args.command == "fetch-annotations":
        fetch_source_snapshot(args.output)
    else:
        if args.workers < 1 or args.workers > 16:
            parser.error("--workers must be between 1 and 16")
        build_all(args.cache, args.output, args.workers)


if __name__ == "__main__":
    main()
