#!/usr/bin/env python3
"""DocQuery-owned HTTP boundary around the fixed RAGFlow DeepDoc PDF parser."""

from __future__ import annotations

import gc
import hashlib
import io
import os
import re
import tempfile
import threading
import time
import unicodedata
import uuid
import zipfile
from collections import Counter, defaultdict
from html.parser import HTMLParser
from pathlib import Path
from typing import Any

from flask import Flask, jsonify, request
from pypdf import PdfReader
from werkzeug.exceptions import RequestEntityTooLarge


SCHEMA_VERSION = "docquery-deepdoc-http-v1"
ENGINE_VERSION = "ragflow-v0.26.4"
SHA256_PATTERN = re.compile(r"^[0-9a-f]{64}$")
SUPPORTED_FORMATS = {"PDF", "DOCX", "TXT", "MARKDOWN"}
FORMAT_SUFFIXES = {
    "PDF": ".pdf",
    "DOCX": ".docx",
    "TXT": ".txt",
    "MARKDOWN": ".md",
}


class StableServiceError(RuntimeError):
    def __init__(self, code: str, message: str, retryable: bool, status: int):
        super().__init__(message)
        self.code = code
        self.message = message
        self.retryable = retryable
        self.status = status


def _positive_int(name: str, default: int) -> int:
    try:
        value = int(os.getenv(name, str(default)))
    except ValueError as error:
        raise RuntimeError(f"{name} must be an integer") from error
    if value < 1:
        raise RuntimeError(f"{name} must be positive")
    return value


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _normalized_text_sha256(values: list[str]) -> str:
    return hashlib.sha256(_normalized_text(values).encode("utf-8")).hexdigest()


def _normalized_text(values: list[str]) -> str:
    return " ".join(
        " ".join(str(value).split())
        for value in values
        if str(value).strip()
    )


def _utf16_length(value: str) -> int:
    return len(value.encode("utf-16-le")) // 2


def _safe_request_id(candidate: str | None) -> str:
    if candidate and 1 <= len(candidate) <= 128 and all(
        value.isalnum() or value in "-_." for value in candidate
    ):
        return candidate
    return str(uuid.uuid4())


class _TableGridParser(HTMLParser):
    """Extract visible DeepDoc HTML table cells with stable zero-based coordinates."""

    def __init__(self) -> None:
        super().__init__(convert_charrefs=True)
        self.cells: list[dict[str, Any]] = []
        self._table_depth = 0
        self._row = -1
        self._column = 0
        self._occupied_until: dict[int, int] = {}
        self._cell: dict[str, Any] | None = None

    def handle_starttag(
        self, tag: str, attrs: list[tuple[str, str | None]]
    ) -> None:
        tag = tag.lower()
        if tag == "table":
            self._table_depth += 1
            if self._table_depth == 1:
                self._row = -1
                self._column = 0
                self._occupied_until = {}
            return
        if self._table_depth != 1:
            return
        if tag == "tr":
            self._finish_cell()
            self._row += 1
            self._column = 0
            return
        if tag in {"td", "th"}:
            self._finish_cell()
            if self._row < 0:
                self._row = 0
            while self._occupied_until.get(self._column, -1) >= self._row:
                self._column += 1
            attributes = {name.lower(): value for name, value in attrs}
            row_span = self._positive_span(attributes.get("rowspan"))
            column_span = self._positive_span(attributes.get("colspan"))
            column = self._column
            for occupied in range(column, column + column_span):
                self._occupied_until[occupied] = self._row + row_span - 1
            self._cell = {
                "row": self._row,
                "column": column,
                "rowSpan": row_span,
                "columnSpan": column_span,
                "header": tag == "th",
                "parts": [],
            }
            self._column += column_span
            return
        if self._cell is not None and tag in {"br", "p", "div", "li"}:
            self._cell["parts"].append(" ")

    def handle_startendtag(
        self, tag: str, attrs: list[tuple[str, str | None]]
    ) -> None:
        self.handle_starttag(tag, attrs)

    def handle_endtag(self, tag: str) -> None:
        tag = tag.lower()
        if tag in {"td", "th"} and self._table_depth == 1:
            self._finish_cell()
            return
        if tag == "tr" and self._table_depth == 1:
            self._finish_cell()
            return
        if tag == "table" and self._table_depth > 0:
            if self._table_depth == 1:
                self._finish_cell()
            self._table_depth -= 1

    def handle_data(self, data: str) -> None:
        if self._cell is not None and self._table_depth == 1:
            self._cell["parts"].append(data)

    def close(self) -> None:
        super().close()
        self._finish_cell()

    def _finish_cell(self) -> None:
        if self._cell is None:
            return
        text = " ".join("".join(self._cell.pop("parts")).split())
        self._cell["text"] = text
        self.cells.append(self._cell)
        self._cell = None

    @staticmethod
    def _positive_span(value: str | None) -> int:
        try:
            parsed = int(value or "1")
        except ValueError:
            return 1
        return parsed if 1 <= parsed <= 1000 else 1


def _table_grid_cells(value: str) -> list[dict[str, Any]]:
    if "<table" not in value.casefold():
        return []
    parser = _TableGridParser()
    try:
        parser.feed(value)
        parser.close()
    except (ValueError, TypeError):
        return []
    return [cell for cell in parser.cells if str(cell.get("text") or "").strip()]


def _table_column_count(cells: list[dict[str, Any]]) -> int:
    return max(
        (
            int(cell.get("column") or 0)
            + max(1, int(cell.get("columnSpan") or 1))
            for cell in cells
        ),
        default=0,
    )


def _first_complete_table_row(
    cells: list[dict[str, Any]], column_count: int
) -> int:
    occupied: defaultdict[int, set[int]] = defaultdict(set)
    for cell in cells:
        row = int(cell.get("row") or 0)
        start = int(cell.get("column") or 0)
        span = max(1, int(cell.get("columnSpan") or 1))
        occupied[row].update(range(start, min(column_count, start + span)))
    complete = [row for row, columns in occupied.items() if len(columns) == column_count]
    return min(complete) if complete else min(occupied, default=0)


def collect_onnx_sessions(root: object) -> dict[str, list[str]]:
    sessions: dict[str, list[str]] = {}
    visited: set[int] = set()

    def visit(value: object, path: str, depth: int) -> None:
        if value is None or depth > 5:
            return
        if isinstance(value, (str, bytes, int, float, bool, Path)):
            return
        identity = id(value)
        if identity in visited:
            return
        visited.add(identity)
        providers = getattr(value, "get_providers", None)
        if callable(providers):
            sessions[path] = [str(item) for item in providers()]
            return
        if isinstance(value, dict):
            for name, child in value.items():
                visit(child, f"{path}.{name}", depth + 1)
            return
        if isinstance(value, (list, tuple, set)):
            for index, child in enumerate(value):
                visit(child, f"{path}[{index}]", depth + 1)
            return
        try:
            attributes = vars(value)
        except TypeError:
            return
        for name, child in attributes.items():
            if not name.startswith("__"):
                visit(child, f"{path}.{name}", depth + 1)

    visit(root, "parser", 0)
    return dict(sorted(sessions.items()))


class DeepDocRuntime:
    """One initialized model and a bounded in-process execution gate."""

    def __init__(
        self,
        parsers: dict[str, object],
        sessions: dict[str, list[str]],
        max_pdf_pages: int,
        max_blocks: int,
        max_docx_expanded_bytes: int = 200 * 1024 * 1024,
        parse_concurrency: int = 1,
    ) -> None:
        self.parsers = parsers
        self.sessions = sessions
        self.max_pdf_pages = max_pdf_pages
        self.max_blocks = max_blocks
        self.max_docx_expanded_bytes = max_docx_expanded_bytes
        self.parse_concurrency = parse_concurrency
        self._slots = threading.Semaphore(parse_concurrency)
        self._activity_lock = threading.Lock()
        self._active = 0
        self._max_observed_active = 0

    @property
    def max_observed_active(self) -> int:
        with self._activity_lock:
            return self._max_observed_active

    def parse(
        self,
        path: Path,
        expected_sha256: str,
        source_format: str,
    ) -> dict[str, Any]:
        if _sha256(path) != expected_sha256:
            raise StableServiceError(
                "SOURCE_OBJECT_INTEGRITY_MISMATCH",
                "Uploaded source does not match the accepted digest",
                False,
                400,
            )
        if source_format not in SUPPORTED_FORMATS:
            raise StableServiceError(
                "DOCUMENT_FORMAT_UNSUPPORTED",
                "Requested document format is unsupported",
                False,
                415,
            )
        inspection = self._inspect_source(path, source_format)

        started = time.perf_counter()
        self._slots.acquire()
        try:
            with self._activity_lock:
                self._active += 1
                self._max_observed_active = max(
                    self._max_observed_active, self._active
                )
            try:
                if source_format == "PDF":
                    page_count = int(inspection)
                    boxes = self.parsers["PDF"].parse_into_bboxes(
                        str(path), zoomin=3, from_page=0, to_page=page_count
                    )
                    blocks, warnings = self._map_pdf_boxes(
                        boxes,
                        page_count,
                        getattr(self.parsers["PDF"], "outlines", []),
                        path,
                    )
                elif source_format == "DOCX":
                    page_count = None
                    blocks, warnings = self._parse_docx(path)
                elif source_format == "TXT":
                    page_count = None
                    blocks, warnings = self._parse_txt(path, str(inspection))
                else:
                    page_count = None
                    blocks, warnings = self._parse_markdown(str(inspection))
            except Exception as error:
                if isinstance(error, StableServiceError):
                    raise
                raise StableServiceError(
                    "DEEPDOC_PARSER_UNAVAILABLE",
                    "DeepDoc parser could not complete the document",
                    True,
                    503,
                ) from error
            finally:
                with self._activity_lock:
                    self._active -= 1
        finally:
            self._slots.release()
            gc.collect()

        if not blocks:
            raise StableServiceError(
                "PDF_NO_EXTRACTABLE_TEXT"
                if source_format == "PDF"
                else "DOCUMENT_CORRUPT",
                "DeepDoc returned no nonblank positioned blocks",
                False,
                422,
            )
        return {
            "schemaVersion": SCHEMA_VERSION,
            "sourceSha256": expected_sha256,
            "sourceFormat": source_format,
            "pageCount": page_count,
            "elapsedMillis": int((time.perf_counter() - started) * 1000),
            "blockCount": len(blocks),
            "blocks": blocks,
            "warnings": warnings,
        }

    def _inspect_source(self, path: Path, source_format: str) -> int | str | None:
        if source_format == "PDF":
            return self._inspect_pdf(path)
        if source_format == "DOCX":
            self._inspect_docx(path)
            return None
        return self._read_utf8(path)

    def _inspect_pdf(self, path: Path) -> int:
        try:
            with path.open("rb") as stream:
                if stream.read(5) != b"%PDF-":
                    raise StableServiceError(
                        "DOCUMENT_FORMAT_MISMATCH",
                        "Uploaded source is not a PDF document",
                        False,
                        415,
                    )
            reader = PdfReader(str(path))
            if reader.is_encrypted:
                try:
                    unlocked = bool(reader.decrypt(""))
                except Exception:
                    unlocked = False
                if not unlocked:
                    raise StableServiceError(
                        "ENCRYPTED_DOCUMENT_UNSUPPORTED",
                        "Password-protected PDF is unsupported",
                        False,
                        422,
                    )
            page_count = len(reader.pages)
        except StableServiceError:
            raise
        except Exception as error:
            raise StableServiceError(
                "DOCUMENT_CORRUPT",
                "PDF document could not be inspected",
                False,
                422,
            ) from error
        if page_count < 1:
            raise StableServiceError(
                "PDF_NO_EXTRACTABLE_TEXT",
                "PDF contains no pages",
                False,
                422,
            )
        if page_count > self.max_pdf_pages:
            raise StableServiceError(
                "DOCUMENT_PARSE_LIMIT_EXCEEDED",
                "PDF page count exceeds configured limit",
                False,
                422,
            )
        return page_count

    def _inspect_docx(self, path: Path) -> None:
        try:
            with path.open("rb") as stream:
                header = stream.read(8)
            if header.startswith(bytes.fromhex("d0cf11e0")):
                raise StableServiceError(
                    "ENCRYPTED_DOCUMENT_UNSUPPORTED",
                    "Encrypted Office document is unsupported",
                    False,
                    422,
                )
            if not header.startswith(b"PK"):
                raise StableServiceError(
                    "DOCUMENT_FORMAT_MISMATCH",
                    "Uploaded source is not a DOCX OOXML package",
                    False,
                    415,
                )
            expanded_bytes = 0
            with zipfile.ZipFile(path) as archive:
                names = set(archive.namelist())
                if len(names) > 10_000:
                    self._limit_exceeded("DOCX contains too many ZIP entries")
                for item in archive.infolist():
                    expanded_bytes += max(0, item.file_size)
                    if expanded_bytes > self.max_docx_expanded_bytes:
                        self._limit_exceeded("DOCX expanded content exceeds configured limit")
                if "[Content_Types].xml" not in names or "word/document.xml" not in names:
                    raise StableServiceError(
                        "DOCUMENT_FORMAT_MISMATCH",
                        "ZIP package is not a DOCX document",
                        False,
                        415,
                    )
        except StableServiceError:
            raise
        except (OSError, zipfile.BadZipFile) as error:
            raise StableServiceError(
                "DOCUMENT_CORRUPT",
                "DOCX package could not be inspected",
                False,
                422,
            ) from error

    def _read_utf8(self, path: Path) -> str:
        try:
            text = path.read_bytes().decode("utf-8")
        except (OSError, UnicodeDecodeError) as error:
            raise StableServiceError(
                "TEXT_ENCODING_UNSUPPORTED",
                "Text document is not valid UTF-8",
                False,
                422,
            ) from error
        if text.startswith("\ufeff"):
            text = text[1:]
        if "\x00" in text:
            raise StableServiceError(
                "TEXT_ENCODING_UNSUPPORTED",
                "Text document is not valid UTF-8",
                False,
                422,
            )
        return text.replace("\r\n", "\n").replace("\r", "\n")

    def _limit_exceeded(self, message: str) -> None:
        raise StableServiceError(
            "DOCUMENT_PARSE_LIMIT_EXCEEDED",
            message,
            False,
            422,
        )

    def _map_pdf_boxes(
        self,
        boxes: list[dict[str, Any]],
        page_count: int,
        outlines: list[tuple[Any, Any, Any]] | None = None,
        pdf_path: Path | None = None,
    ) -> tuple[list[dict[str, Any]], list[dict[str, Any]]]:
        boxes = [
            dict(box)
            for box in boxes
            if str(box.get("text") or "").strip()
        ]
        boxes, merged_title_fragments = self._merge_pdf_title_fragments(
            boxes, outlines or []
        )
        boxes, reconstructed_inline_headings = (
            self._reconstruct_pdf_inline_numbered_headings(boxes)
        )
        boxes, split_numbered_headings = self._split_pdf_numbered_heading_prefixes(
            boxes
        )
        boxes, reordered_numbered_titles = self._normalize_pdf_numbered_title_order(
            boxes
        )
        structured_tables = {
            index: _table_grid_cells(str(box.get("text") or ""))
            for index, box in enumerate(boxes)
            if str(box.get("layout_type") or "").lower() == "table"
        }
        digital_tables, table_covered_boxes = self._pdf_digital_table_cells(
            pdf_path,
            boxes,
            structured_tables,
        )
        structured_tables.update(digital_tables)
        table_contexts = self._pdf_table_contexts(boxes, structured_tables)
        source_text_values: list[str] = []
        for index, box in enumerate(boxes):
            if index in table_covered_boxes:
                continue
            cells = structured_tables.get(index) or []
            if cells:
                source_text_values.extend(str(cell["text"]) for cell in cells)
            else:
                source_text_values.append(str(box.get("text") or ""))
        source_text_sha256 = _normalized_text_sha256(source_text_values)
        blocks: list[dict[str, Any]] = []
        page_ordinals: defaultdict[int, int] = defaultdict(int)
        page_offsets: defaultdict[int, int] = defaultdict(int)
        layout_counts: Counter[str] = Counter()
        multi_page_blocks = 0
        structured_table_blocks = 0
        structured_table_cells = 0
        flattened_table_blocks = 0
        document_title_emitted = False
        title_decisions, title_stats = self._govern_pdf_title_candidates(
            boxes, outlines or []
        )

        for box_index, box in enumerate(boxes):
            if box_index in table_covered_boxes:
                continue
            text = str(box.get("text") or "").strip()
            if not text:
                continue
            pages = self._pages(box)
            if not pages:
                raise StableServiceError(
                    "DEEPDOC_POSITION_MISSING",
                    "DeepDoc returned a text block without a physical page",
                    False,
                    422,
                )
            page_number = min(pages)
            if page_number < 1 or page_number > page_count:
                raise StableServiceError(
                    "DEEPDOC_POSITION_INVALID",
                    "DeepDoc returned a page outside the source document",
                    False,
                    422,
                )
            if len(pages) > 1:
                multi_page_blocks += 1
            if len(blocks) >= self.max_blocks:
                raise StableServiceError(
                    "DOCUMENT_PARSE_LIMIT_EXCEEDED",
                    "DeepDoc returned too many blocks",
                    False,
                    422,
                )

            layout_type = str(box.get("layout_type") or "unknown").lower()
            layout_counts[layout_type] += 1
            table_cells = structured_tables.get(box_index) or []
            if layout_type == "table" and table_cells:
                structured_table_blocks += 1
                table_context = table_contexts[box_index]
                for cell in table_cells:
                    cell_text = str(cell["text"])
                    if len(blocks) >= self.max_blocks:
                        raise StableServiceError(
                            "DOCUMENT_PARSE_LIMIT_EXCEEDED",
                            "DeepDoc returned too many blocks",
                            False,
                            422,
                        )
                    ordinal = page_ordinals[page_number]
                    start = page_offsets[page_number]
                    end = start + _utf16_length(cell_text)
                    table_row = int(cell["row"])
                    table_column = int(cell["column"])
                    column_header = (
                        table_context["columnHeaders"].get(table_column)
                        if table_row >= int(table_context["headerStartRow"])
                        else None
                    )
                    blocks.append(
                        {
                            "kind": "TABLE_CELL",
                            "text": cell_text,
                            "sourceType": "PDF",
                            "pageNumber": page_number,
                            "pageBlockOrdinal": ordinal,
                            "pageCharacterStart": start,
                            "pageCharacterEnd": end,
                            "tableRow": table_row,
                            "tableColumn": table_column,
                            "tableRowSpan": int(cell.get("rowSpan") or 1),
                            "tableColumnSpan": int(cell.get("columnSpan") or 1),
                            "tableId": str(table_context["tableId"]),
                            "tableGroupId": str(table_context["tableGroupId"]),
                            "tableContinuationOf": table_context["continuationOf"],
                            "tableCaption": table_context["caption"],
                            "tableColumnHeader": column_header,
                            "tableRowHeader": table_context["rowHeaders"].get(
                                table_row
                            ),
                            "tableCellHeading": self._table_cell_heading(
                                cell,
                                column_header,
                            ),
                            "headingLevel": None,
                            "detectionSource": None,
                            "documentTitle": False,
                        }
                    )
                    structured_table_cells += 1
                    page_ordinals[page_number] += 1
                    page_offsets[page_number] = end + 2
                continue
            if layout_type == "table":
                flattened_table_blocks += 1
            kind = {
                "text": "PARAGRAPH",
                "table": "TABLE_CELL",
                "figure": "RAW_TEXT",
                "title": "HEADING",
            }.get(layout_type, "RAW_TEXT")
            document_title = False
            heading_level = None
            detection_source = None
            if layout_type == "title":
                decision = title_decisions.get(box_index, {})
                if decision.get("documentTitle"):
                    kind = "TITLE"
                    document_title = True
                    document_title_emitted = True
                    detection_source = "DEEPDOC_DOCUMENT_TITLE_CANDIDATE"
                elif decision.get("accepted"):
                    kind = "HEADING"
                    heading_level = int(decision.get("headingLevel") or 1)
                    detection_source = str(decision.get("detectionSource"))
                else:
                    # DeepDoc title 只是视觉候选。治理失败时保留全文和位置，
                    # 仅降级为正文，绝不删除或静默丢失证据。
                    kind = "PARAGRAPH"
                    detection_source = "DEEPDOC_LAYOUT_TITLE_DOWNGRADED"

            ordinal = page_ordinals[page_number]
            start = page_offsets[page_number]
            end = start + _utf16_length(text)
            blocks.append(
                {
                    "kind": kind,
                    "text": text,
                    "sourceType": "PDF",
                    "pageNumber": page_number,
                    "pageBlockOrdinal": ordinal,
                    "pageCharacterStart": start,
                    "pageCharacterEnd": end,
                    "headingLevel": heading_level,
                    "detectionSource": detection_source,
                    "documentTitle": document_title,
                }
            )
            page_ordinals[page_number] += 1
            page_offsets[page_number] = end + 2

        warnings: list[dict[str, Any]] = []
        if reconstructed_inline_headings:
            warnings.append(
                {
                    "code": "DEEPDOC_INLINE_NUMBERED_HEADING_RECONSTRUCTED",
                    "message": (
                        f"Reconstructed {reconstructed_inline_headings} numbered "
                        "headings from same-row DeepDoc fragments"
                    ),
                    "pageNumber": None,
                }
            )
        if reordered_numbered_titles:
            warnings.append(
                {
                    "code": "DEEPDOC_NUMBERED_TITLE_ORDER_NORMALIZED",
                    "message": (
                        f"Normalized {reordered_numbered_titles} numbered title candidates "
                        "whose visual number was returned after the title text"
                    ),
                    "pageNumber": None,
                }
            )
        if split_numbered_headings:
            warnings.append(
                {
                    "code": "DEEPDOC_NUMBERED_PARAGRAPH_HEADING_SPLIT",
                    "message": (
                        f"Split {split_numbered_headings} high-confidence numbered "
                        "heading prefixes from their following body text"
                    ),
                    "pageNumber": None,
                }
            )
        output_text_sha256 = _normalized_text_sha256(
            [str(block.get("text") or "") for block in blocks]
        )
        if output_text_sha256 != source_text_sha256:
            raise StableServiceError(
                "DEEPDOC_TITLE_GOVERNANCE_TEXT_MISMATCH",
                "Title governance changed or dropped source text",
                False,
                500,
            )
        warnings.append(
            {
                "code": "DEEPDOC_TITLE_GOVERNANCE_TEXT_PRESERVED",
                "message": (
                    "Title governance preserved normalized source text SHA-256 "
                    + output_text_sha256
                ),
                "pageNumber": None,
            }
        )
        if merged_title_fragments:
            warnings.append(
                {
                    "code": "DEEPDOC_TITLE_FRAGMENTS_MERGED",
                    "message": (
                        f"Merged {merged_title_fragments} adjacent DeepDoc title fragments "
                        "without dropping text"
                    ),
                    "pageNumber": None,
                }
            )
        governed_candidates = int(title_stats.get("candidates", 0))
        accepted_headings = int(title_stats.get("accepted", 0))
        downgraded_headings = int(title_stats.get("downgraded", 0))
        if governed_candidates:
            warnings.append(
                {
                    "code": "DEEPDOC_TITLE_CANDIDATES_GOVERNED",
                    "message": (
                        f"DeepDoc title candidates={governed_candidates}; "
                        f"accepted headings={accepted_headings}; "
                        f"downgraded to paragraph={downgraded_headings}"
                    ),
                    "pageNumber": None,
                }
            )
        reason_codes = {
            "running-header-footer": "DEEPDOC_RUNNING_TITLE_DOWNGRADED",
            "repeated-label": "DEEPDOC_REPEATED_TITLE_DOWNGRADED",
            "empty-section": "DEEPDOC_EMPTY_TITLE_DOWNGRADED",
            "title-too-long": "DEEPDOC_LONG_TITLE_DOWNGRADED",
            "weak-visual-title": "DEEPDOC_WEAK_VISUAL_TITLE_DOWNGRADED",
        }
        for reason, code in reason_codes.items():
            count = int(title_stats.get(reason, 0))
            if count:
                warnings.append(
                    {
                        "code": code,
                        "message": f"Downgraded {count} DeepDoc title candidates: {reason}",
                        "pageNumber": None,
                    }
                )
        accepted_without_outline = int(title_stats.get("accepted-structure", 0)) + int(
            title_stats.get("accepted-visual", 0)
        )
        if accepted_without_outline:
            warnings.append(
                {
                    "code": "DEEPDOC_HEADING_LEVEL_CONSERVATIVE",
                    "message": (
                        f"{accepted_without_outline} accepted visual headings use "
                        "a conservative inferred level"
                    ),
                    "pageNumber": None,
                }
            )
        if structured_table_blocks:
            warnings.append(
                {
                    "code": "DEEPDOC_TABLE_CELLS_STRUCTURED",
                    "message": (
                        f"Expanded {structured_table_blocks} DeepDoc HTML table blocks "
                        f"into {structured_table_cells} cells with row and column coordinates"
                    ),
                    "pageNumber": None,
                }
            )
        if digital_tables:
            warnings.append(
                {
                    "code": "DEEPDOC_DIGITAL_TABLE_ORDER_PRESERVED",
                    "message": (
                        f"Preferred the PDF text layer for {len(digital_tables)} "
                        "high-confidence tables and preserved their physical row/column order"
                    ),
                    "pageNumber": None,
                }
            )
        bound_table_headers = sum(
            1 for context in table_contexts.values() if context["columnHeaders"]
        )
        if bound_table_headers:
            warnings.append(
                {
                    "code": "DEEPDOC_TABLE_COLUMN_HEADERS_BOUND",
                    "message": (
                        f"Bound column headers for {bound_table_headers} structured "
                        "tables using explicit HTML headers or high-confidence PDF layout"
                    ),
                    "pageNumber": None,
                }
            )
        bound_table_relations = sum(
            1
            for context in table_contexts.values()
            if context["caption"]
            or context["rowHeaders"]
            or any(
                str(cell.get("headingText") or "").strip()
                for cell in structured_tables.get(context["tableIndex"], [])
            )
        )
        if bound_table_relations:
            warnings.append(
                {
                    "code": "DEEPDOC_TABLE_RELATIONS_BOUND",
                    "message": (
                        f"Bound captions, row labels, or item headings for "
                        f"{bound_table_relations} structured tables without "
                        "promoting them to document headings"
                    ),
                    "pageNumber": None,
                }
            )
        if flattened_table_blocks:
            warnings.append(
                {
                    "code": "DEEPDOC_TABLE_STRUCTURE_FLATTENED",
                    "message": (
                        f"Retained {flattened_table_blocks} non-HTML DeepDoc table blocks "
                        "without a cell grid"
                    ),
                    "pageNumber": None,
                }
            )
        if layout_counts["figure"]:
            warnings.append(
                {
                    "code": "DEEPDOC_FIGURE_TEXT_ONLY",
                    "message": "DeepDoc figure blocks retain text without image semantics",
                    "pageNumber": None,
                }
            )
        if multi_page_blocks:
            warnings.append(
                {
                    "code": "DEEPDOC_MULTI_PAGE_BLOCK_FIRST_PAGE",
                    "message": "A multi-page DeepDoc block uses its first physical page",
                    "pageNumber": None,
                }
            )
        return blocks, warnings

    def _pdf_digital_table_cells(
        self,
        pdf_path: Path | None,
        boxes: list[dict[str, Any]],
        structured_tables: dict[int, list[dict[str, Any]]],
    ) -> tuple[dict[int, list[dict[str, Any]]], set[int]]:
        """Prefer a trustworthy PDF text-layer table without duplicating its children.

        DeepDoc's TSR is retained as the table detector and fallback.  For a digital
        PDF, pdfplumber can often recover the same physical table with its original
        reading order, including visual headings that DeepDoc emitted as separate
        title boxes.  The replacement is deliberately conservative: the table bounds
        must strongly overlap, the column counts must agree, and the digital text
        must cover most of the DeepDoc table text.
        """
        if pdf_path is None or not structured_tables:
            return {}, set()
        try:
            import pdfplumber
        except ImportError:
            return {}, set()

        replacements: dict[int, list[dict[str, Any]]] = {}
        covered_boxes: set[int] = set()
        try:
            with pdfplumber.open(str(pdf_path)) as document:
                page_tables: dict[int, list[Any]] = {}
                used_tables: defaultdict[int, set[int]] = defaultdict(set)
                for table_index, deepdoc_cells in structured_tables.items():
                    position = self._first_pdf_position(boxes[table_index])
                    if position is None:
                        continue
                    page, left, right, top, bottom = position
                    if page < 1 or page > len(document.pages):
                        continue
                    if page not in page_tables:
                        page_tables[page] = list(document.pages[page - 1].find_tables())
                    candidates = page_tables[page]
                    best_index = None
                    best_overlap = 0.0
                    for candidate_index, candidate in enumerate(candidates):
                        if candidate_index in used_tables[page]:
                            continue
                        overlap = self._pdf_bbox_iou(
                            (left, top, right, bottom),
                            tuple(float(value) for value in candidate.bbox),
                        )
                        if overlap > best_overlap:
                            best_overlap = overlap
                            best_index = candidate_index
                    column_count = _table_column_count(deepdoc_cells)
                    digital_cells: list[dict[str, Any]] = []
                    selected_candidate = None
                    if best_index is not None and best_overlap >= 0.75:
                        candidate = candidates[best_index]
                        matrix = candidate.extract() or []
                        candidate_cells = self._pdf_table_candidate_cells(
                            document.pages[page - 1],
                            candidate,
                            matrix,
                        )
                        if (
                            column_count >= 1
                            and _table_column_count(candidate_cells) == column_count
                            and self._pdf_digital_table_text_covers(
                                deepdoc_cells, candidate_cells
                            )
                        ):
                            digital_cells = candidate_cells
                            selected_candidate = best_index
                    if not digital_cells:
                        digital_cells = self._pdf_relational_table_cells(
                            document.pages[page - 1],
                            (left, top, right, bottom),
                            deepdoc_cells,
                        )
                    if not digital_cells:
                        continue
                    replacements[table_index] = digital_cells
                    if selected_candidate is not None:
                        used_tables[page].add(selected_candidate)
                    digital_text = self._pdf_match_text(
                        "\n".join(str(cell["text"]) for cell in digital_cells)
                    )
                    for box_index, box in enumerate(boxes):
                        if box_index == table_index:
                            continue
                        box_position = self._first_pdf_position(box)
                        if box_position is None or box_position[0] != page:
                            continue
                        _, box_left, box_right, box_top, box_bottom = box_position
                        center_x = (box_left + box_right) / 2
                        center_y = (box_top + box_bottom) / 2
                        box_text = self._pdf_match_text(str(box.get("text") or ""))
                        if (
                            left <= center_x <= right
                            and top <= center_y <= bottom
                            and len(box_text) >= 3
                            and box_text in digital_text
                        ):
                            covered_boxes.add(box_index)
        except Exception:
            # The DeepDoc HTML table remains the complete, deterministic fallback.
            return {}, set()
        return replacements, covered_boxes

    @classmethod
    def _pdf_relational_table_cells(
        cls,
        page: Any,
        bbox: tuple[float, float, float, float],
        deepdoc_cells: list[dict[str, Any]],
    ) -> list[dict[str, Any]]:
        """Repair a multi-row two-column grid from trustworthy PDF word geometry.

        This fallback is intentionally narrower than generic table guessing.  DeepDoc
        must already have detected a multi-row, two-column table.  The PDF text layer
        must then expose one stable vertical separator shared by several physical
        lines, and the reconstructed text must substantially cover DeepDoc's text.
        Independent newspaper/page columns and one-row layout tables therefore never
        enter this path.
        """
        column_count = _table_column_count(deepdoc_cells)
        source_rows = {int(cell.get("row") or 0) for cell in deepdoc_cells}
        if column_count != 2 or len(source_rows) < 3:
            return []
        try:
            words = page.crop(bbox).extract_words(extra_attrs=["fontname", "size"])
        except Exception:
            return []
        lines = cls._pdf_table_visual_lines(words or [])
        if len(lines) < 4:
            return []
        split = cls._pdf_two_column_separator(lines, bbox)
        if split is None:
            return []

        cells: list[dict[str, Any]] = []
        paired_rows = 0
        for row, line in enumerate(lines):
            left_words: list[dict[str, Any]] = []
            right_words: list[dict[str, Any]] = []
            crosses_separator = False
            for word in line["words"]:
                x0 = float(word.get("x0") or 0)
                x1 = float(word.get("x1") or x0)
                if x0 < split < x1:
                    crosses_separator = True
                    break
                (left_words if (x0 + x1) / 2 < split else right_words).append(word)
            if crosses_separator:
                return []
            texts = [
                cls._pdf_words_text(left_words),
                cls._pdf_words_text(right_words),
            ]
            if texts[0] and texts[1]:
                paired_rows += 1
            for column, text in enumerate(texts):
                if text:
                    cells.append(
                        {
                            "row": row,
                            "column": column,
                            "rowSpan": 1,
                            "columnSpan": 1,
                            "header": False,
                            "text": text,
                            "geometryReconstructed": True,
                        }
                    )
        if paired_rows < max(3, (len(lines) + 2) // 3):
            return []
        if not cls._pdf_digital_table_text_covers(deepdoc_cells, cells):
            return []
        return cells

    @staticmethod
    def _pdf_table_visual_lines(words: list[dict[str, Any]]) -> list[dict[str, Any]]:
        lines: list[dict[str, Any]] = []
        for word in sorted(
            words,
            key=lambda item: (
                float(item.get("top") or 0),
                float(item.get("x0") or 0),
            ),
        ):
            text = str(word.get("text") or "").strip()
            if not text:
                continue
            top = float(word.get("top") or 0)
            if not lines or abs(top - float(lines[-1]["top"])) > 2.0:
                lines.append({"top": top, "words": [word]})
            else:
                lines[-1]["words"].append(word)
        for line in lines:
            line["words"].sort(key=lambda item: float(item.get("x0") or 0))
        return lines

    @staticmethod
    def _pdf_two_column_separator(
        lines: list[dict[str, Any]],
        bbox: tuple[float, float, float, float],
    ) -> float | None:
        left, _top, right, _bottom = bbox
        width = right - left
        if width <= 0:
            return None
        minimum_gap = max(18.0, width * 0.08)
        gaps: list[tuple[float, float]] = []
        for line in lines:
            words = line["words"]
            for first, second in zip(words, words[1:]):
                gap_left = float(first.get("x1") or first.get("x0") or 0)
                gap_right = float(second.get("x0") or 0)
                if gap_right - gap_left >= minimum_gap:
                    gaps.append((gap_left, gap_right))
        if not gaps:
            return None
        candidates = sorted(
            {
                (gap_left + gap_right) / 2
                for gap_left, gap_right in gaps
            }
            | {gap_left for gap_left, _gap_right in gaps}
            | {gap_right for _gap_left, gap_right in gaps}
        )
        candidates = [
            value
            for value in candidates
            if left + width * 0.18 <= value <= right - width * 0.18
        ]
        if not candidates:
            return None
        scored = [
            (
                sum(1 for gap_left, gap_right in gaps if gap_left <= value <= gap_right),
                sum(
                    gap_right - gap_left
                    for gap_left, gap_right in gaps
                    if gap_left <= value <= gap_right
                ),
                value,
            )
            for value in candidates
        ]
        coverage, _gap_width, separator = max(scored)
        return separator if coverage >= max(3, (len(lines) + 2) // 3) else None

    @staticmethod
    def _pdf_words_text(words: list[dict[str, Any]]) -> str:
        return " ".join(
            str(word.get("text") or "").strip()
            for word in sorted(words, key=lambda item: float(item.get("x0") or 0))
            if str(word.get("text") or "").strip()
        )

    @staticmethod
    def _pdf_table_matrix_cells(matrix: list[list[Any]]) -> list[dict[str, Any]]:
        cells: list[dict[str, Any]] = []
        for row, values in enumerate(matrix):
            for column, value in enumerate(values or []):
                lines = [" ".join(line.split()) for line in str(value or "").splitlines()]
                text = "\n".join(line for line in lines if line)
                if not text:
                    continue
                cells.append(
                    {
                        "row": row,
                        "column": column,
                        "rowSpan": 1,
                        "columnSpan": 1,
                        "header": False,
                        "text": text,
                    }
                )
        return cells

    @classmethod
    def _pdf_table_candidate_cells(
        cls,
        page: Any,
        candidate: Any,
        matrix: list[list[Any]],
    ) -> list[dict[str, Any]]:
        """Split a digital table cell at physical bold-title boundaries."""
        result: list[dict[str, Any]] = []
        candidate_rows = list(getattr(candidate, "rows", []) or [])
        for row, values in enumerate(matrix):
            row_cells = (
                list(getattr(candidate_rows[row], "cells", []) or [])
                if row < len(candidate_rows)
                else []
            )
            for column, value in enumerate(values or []):
                fallback = cls._pdf_table_matrix_cells([[value]])
                if not fallback:
                    continue
                bbox = row_cells[column] if column < len(row_cells) else None
                if bbox is None:
                    fallback[0]["row"] = row
                    fallback[0]["column"] = column
                    result.extend(fallback)
                    continue
                try:
                    words = page.crop(tuple(float(item) for item in bbox)).extract_words(
                        extra_attrs=["fontname", "size"]
                    )
                except Exception:
                    words = []
                lines = cls._pdf_table_word_lines(words or [])
                segments = cls._pdf_table_line_cells(lines, row, column)
                if segments:
                    result.extend(segments)
                else:
                    fallback[0]["row"] = row
                    fallback[0]["column"] = column
                    result.extend(fallback)
        return result

    @staticmethod
    def _pdf_table_word_lines(
        words: list[dict[str, Any]],
    ) -> list[tuple[str, bool, bool]]:
        grouped: list[dict[str, Any]] = []
        for word in sorted(
            words,
            key=lambda item: (
                float(item.get("top") or 0),
                float(item.get("x0") or 0),
            ),
        ):
            text = str(word.get("text") or "").strip()
            if not text:
                continue
            top = float(word.get("top") or 0)
            if not grouped or abs(top - float(grouped[-1]["top"])) > 2.0:
                grouped.append({"top": top, "words": [word]})
            else:
                grouped[-1]["words"].append(word)
        lines: list[tuple[str, bool, bool]] = []
        for group in grouped:
            line_words = sorted(
                group["words"], key=lambda item: float(item.get("x0") or 0)
            )
            text = " ".join(
                str(word.get("text") or "").strip()
                for word in line_words
                if str(word.get("text") or "").strip()
            )
            if not text:
                continue
            bold = [
                "bold" in str(word.get("fontname") or "").casefold()
                for word in line_words
            ]
            leading_bold = bool(bold and bold[0])
            mixed_body = leading_bold and any(not value for value in bold[1:])
            lines.append((text, leading_bold, mixed_body))
        return lines

    @staticmethod
    def _pdf_table_line_cells(
        lines: list[tuple[str, bool, bool]],
        row: int,
        column: int,
    ) -> list[dict[str, Any]]:
        segments: list[tuple[list[str], list[str]]] = []
        current: list[str] = []
        current_heading: list[str] = []
        body_seen = False
        for text, heading, mixed_body in lines:
            normalized = " ".join(str(text).split())
            if not normalized:
                continue
            if heading and current and body_seen:
                segments.append((current, current_heading))
                current = []
                current_heading = []
                body_seen = False
            current.append(normalized)
            if heading and not body_seen:
                current_heading.append(normalized)
            if not heading or mixed_body:
                body_seen = True
        if current:
            segments.append((current, current_heading))
        return [
            {
                "row": row,
                "column": column,
                "rowSpan": 1,
                "columnSpan": 1,
                "header": False,
                "text": "\n".join(segment),
                "headingText": "\n".join(heading) or None,
            }
            for segment, heading in segments
            if segment
        ]

    @staticmethod
    def _pdf_bbox_iou(
        first: tuple[float, float, float, float],
        second: tuple[float, float, float, float],
    ) -> float:
        left = max(first[0], second[0])
        top = max(first[1], second[1])
        right = min(first[2], second[2])
        bottom = min(first[3], second[3])
        intersection = max(0.0, right - left) * max(0.0, bottom - top)
        first_area = max(0.0, first[2] - first[0]) * max(0.0, first[3] - first[1])
        second_area = max(0.0, second[2] - second[0]) * max(0.0, second[3] - second[1])
        union = first_area + second_area - intersection
        return intersection / union if union > 0 else 0.0

    @classmethod
    def _pdf_digital_table_text_covers(
        cls,
        deepdoc_cells: list[dict[str, Any]],
        digital_cells: list[dict[str, Any]],
    ) -> bool:
        deepdoc_value = " ".join(
            str(cell.get("text") or "") for cell in deepdoc_cells
        )
        digital_value = " ".join(
            str(cell.get("text") or "") for cell in digital_cells
        )
        deepdoc_text = cls._pdf_match_text(deepdoc_value)
        digital_text = cls._pdf_match_text(digital_value)
        if not digital_text:
            return False
        if not deepdoc_text:
            return True
        if len(digital_text) < max(1, int(len(deepdoc_text) * 0.75)):
            return False
        source_tokens = Counter(cls._pdf_match_tokens(deepdoc_value))
        candidate_tokens = Counter(cls._pdf_match_tokens(digital_value))
        source_weight = sum(len(token) * count for token, count in source_tokens.items())
        covered_weight = sum(
            len(token) * min(count, candidate_tokens[token])
            for token, count in source_tokens.items()
        )
        return source_weight == 0 or covered_weight / source_weight >= 0.72

    @staticmethod
    def _pdf_match_text(value: str) -> str:
        normalized = unicodedata.normalize("NFKC", value).casefold()
        return "".join(character for character in normalized if character.isalnum())

    @staticmethod
    def _pdf_match_tokens(value: str) -> list[str]:
        normalized = unicodedata.normalize("NFKC", value).casefold()
        return re.findall(r"[^\W_]+", normalized, flags=re.UNICODE)

    def _pdf_table_contexts(
        self,
        boxes: list[dict[str, Any]],
        structured_tables: dict[int, list[dict[str, Any]]],
    ) -> dict[int, dict[str, Any]]:
        """Assign conservative table roles without creating document headings."""
        table_ordinals: defaultdict[int, int] = defaultdict(int)
        contexts: dict[int, dict[str, Any]] = {}
        for table_index, cells in structured_tables.items():
            position = self._first_pdf_position(boxes[table_index])
            if position is None:
                continue
            page, left, right, top, bottom = position
            ordinal = table_ordinals[page]
            table_ordinals[page] += 1
            column_count = _table_column_count(cells)
            headers = self._html_table_column_headers(cells, column_count)
            header_start_row = 0
            if not headers:
                headers = self._layout_table_column_headers(
                    boxes,
                    table_index,
                    page,
                    left,
                    right,
                    top,
                    bottom,
                    column_count,
                )
                header_start_row = _first_complete_table_row(cells, column_count)
            table_id = f"pdf:p{page}:t{ordinal}"
            layout_mode = self._table_layout_mode(cells, column_count)
            contexts[table_index] = {
                "tableIndex": table_index,
                "tableId": table_id,
                "tableGroupId": table_id,
                "continuationOf": None,
                "page": page,
                "top": top,
                "bottom": bottom,
                "columnCount": column_count,
                "columnHeaders": headers,
                "headerStartRow": header_start_row,
                "caption": self._layout_table_caption(
                    boxes,
                    table_index,
                    page,
                    left,
                    right,
                    top,
                ),
                "layoutMode": layout_mode,
                "rowHeaders": self._table_row_headers(cells, layout_mode),
            }
        self._link_pdf_table_continuations(boxes, contexts)
        return contexts

    def _html_table_column_headers(
        self,
        cells: list[dict[str, Any]],
        column_count: int,
    ) -> dict[int, str]:
        if column_count < 1 or not cells:
            return {}
        first_row = min(int(cell.get("row") or 0) for cell in cells)
        header_cells = [
            cell
            for cell in cells
            if cell.get("header") and int(cell.get("row") or 0) == first_row
        ]
        headers: dict[int, str] = {}
        for cell in header_cells:
            text = str(cell.get("text") or "").strip()
            if not text:
                continue
            start = int(cell.get("column") or 0)
            span = max(1, int(cell.get("columnSpan") or 1))
            for column in range(start, min(column_count, start + span)):
                headers.setdefault(column, text)
        return headers if len(headers) == column_count else {}

    @staticmethod
    def _table_layout_mode(
        cells: list[dict[str, Any]],
        column_count: int,
    ) -> str:
        occupied: defaultdict[int, set[int]] = defaultdict(set)
        for cell in cells:
            row = int(cell.get("row") or 0)
            start = int(cell.get("column") or 0)
            span = max(1, int(cell.get("columnSpan") or 1))
            occupied[row].update(range(start, min(column_count, start + span)))
        if len(occupied) < 2 or column_count < 2:
            return "COLUMN_FLOW"
        complete_rows = sum(
            1 for columns in occupied.values() if len(columns) == column_count
        )
        return (
            "ROW"
            if complete_rows >= max(1, (len(occupied) + 2) // 3)
            else "COLUMN_FLOW"
        )

    @staticmethod
    def _table_row_headers(
        cells: list[dict[str, Any]],
        layout_mode: str,
    ) -> dict[int, str]:
        if layout_mode != "ROW":
            return {}
        row_headers: dict[int, str] = {}
        for cell in cells:
            row = int(cell.get("row") or 0)
            column = int(cell.get("column") or 0)
            if column != 0 or cell.get("header"):
                continue
            text = " ".join(str(cell.get("text") or "").split())
            if text:
                row_headers.setdefault(row, text)
        return row_headers

    def _layout_table_caption(
        self,
        boxes: list[dict[str, Any]],
        table_index: int,
        page: int,
        left: float,
        right: float,
        top: float,
    ) -> str | None:
        candidates: list[tuple[float, float, str]] = []
        table_width = max(1.0, right - left)
        for index, box in enumerate(boxes):
            if index == table_index:
                continue
            layout_type = str(box.get("layout_type") or "").lower()
            if layout_type not in {"text", "title"}:
                continue
            text = " ".join(str(box.get("text") or "").split())
            if not text or len(text) > 240:
                continue
            position = self._first_pdf_position(box)
            if position is None:
                continue
            candidate_page, candidate_left, candidate_right, candidate_top, candidate_bottom = position
            overlap = max(0.0, min(right, candidate_right) - max(left, candidate_left))
            candidate_width = max(1.0, candidate_right - candidate_left)
            if (
                candidate_page != page
                or candidate_bottom > top + 2
                or top - candidate_bottom > 48
                or overlap / min(table_width, candidate_width) < 0.35
            ):
                continue
            candidates.append((candidate_top, candidate_bottom, text))
        if not candidates:
            return None
        selected: list[tuple[float, float, str]] = []
        for candidate in sorted(candidates, key=lambda item: item[1], reverse=True):
            if not selected:
                selected.append(candidate)
                continue
            if len(selected) >= 2 or selected[-1][0] - candidate[1] > 18:
                break
            selected.append(candidate)
        return "\n".join(item[2] for item in reversed(selected)) or None

    @staticmethod
    def _table_cell_heading(
        cell: dict[str, Any],
        column_header: str | None,
    ) -> str | None:
        heading = str(cell.get("headingText") or "").strip()
        if not heading:
            return None
        lines = [" ".join(line.split()) for line in heading.splitlines() if line.strip()]
        header_lines = [
            " ".join(line.split())
            for line in str(column_header or "").splitlines()
            if line.strip()
        ]
        while header_lines and lines[: len(header_lines)] == header_lines:
            lines = lines[len(header_lines) :]
        return "\n".join(lines) or None

    def _link_pdf_table_continuations(
        self,
        boxes: list[dict[str, Any]],
        contexts: dict[int, dict[str, Any]],
    ) -> None:
        by_page: defaultdict[int, list[dict[str, Any]]] = defaultdict(list)
        page_bottoms: defaultdict[int, float] = defaultdict(float)
        for box in boxes:
            position = self._first_pdf_position(box)
            if position is not None:
                page_bottoms[position[0]] = max(page_bottoms[position[0]], position[4])
        for context in contexts.values():
            by_page[int(context["page"])].append(context)
        for values in by_page.values():
            values.sort(key=lambda item: (float(item["top"]), item["tableId"]))
        for page in sorted(by_page):
            if page - 1 not in by_page:
                continue
            previous = by_page[page - 1][-1]
            current = by_page[page][0]
            if int(previous["columnCount"]) != int(current["columnCount"]):
                continue
            previous_extent = max(1.0, page_bottoms[page - 1])
            current_extent = max(1.0, page_bottoms[page])
            if (
                float(previous["bottom"]) < previous_extent * 0.8
                or float(current["top"]) > current_extent * 0.25
            ):
                continue
            previous_headers = self._table_context_signature(
                previous["columnHeaders"].values()
            )
            current_headers = self._table_context_signature(
                current["columnHeaders"].values()
            )
            previous_caption = self._table_context_signature([previous["caption"]])
            current_caption = self._table_context_signature([current["caption"]])
            explicit_continuation = "continu" in current_caption
            repeated_structure = bool(
                previous_headers
                and previous_headers == current_headers
                or previous_caption
                and previous_caption == current_caption
            )
            if not explicit_continuation and not repeated_structure:
                continue
            current["tableGroupId"] = previous["tableGroupId"]
            current["continuationOf"] = previous["tableId"]

    @staticmethod
    def _table_context_signature(values: Any) -> str:
        return "|".join(
            "".join(
                character
                for character in unicodedata.normalize("NFKC", str(value or "")).casefold()
                if character.isalnum()
            )
            for value in values
            if str(value or "").strip()
        )

    def _layout_table_column_headers(
        self,
        boxes: list[dict[str, Any]],
        table_index: int,
        page: int,
        left: float,
        right: float,
        top: float,
        bottom: float,
        column_count: int,
    ) -> dict[int, str]:
        if column_count < 2 or right <= left or bottom <= top:
            return {}
        candidates: defaultdict[int, list[tuple[float, float, str]]] = defaultdict(list)
        width = right - left
        for index, box in enumerate(boxes):
            if index == table_index or str(box.get("layout_type") or "").lower() != "title":
                continue
            text = " ".join(str(box.get("text") or "").split())
            if not text or len(text) > 120 or len(text.split()) > 12:
                continue
            position = self._first_pdf_position(box)
            if position is None:
                continue
            candidate_page, candidate_left, candidate_right, candidate_top, candidate_bottom = position
            center_x = (candidate_left + candidate_right) / 2
            if (
                candidate_page != page
                or center_x < left
                or center_x > right
                or candidate_top < top - 2
                or candidate_bottom > bottom + 2
            ):
                continue
            column = min(
                column_count - 1,
                max(0, int((center_x - left) / width * column_count)),
            )
            candidates[column].append((candidate_top, candidate_bottom, text))
        if len(candidates) != column_count:
            return {}
        selected = {column: min(values) for column, values in candidates.items()}
        centers = [(value[0] + value[1]) / 2 for value in selected.values()]
        alignment_tolerance = max(24.0, min(80.0, (bottom - top) * 0.08))
        if max(centers) - min(centers) > alignment_tolerance:
            return {}
        return {column: value[2] for column, value in selected.items()}

    def _reconstruct_pdf_inline_numbered_headings(
        self,
        boxes: list[dict[str, Any]],
    ) -> tuple[list[dict[str, Any]], int]:
        """Repair only high-confidence number/title fragments on one visual row.

        DeepDoc occasionally returns a hierarchical section number as one box and
        the short title (sometimes followed by body text) as the next ``text`` box.
        Requiring the number to be hierarchical, the phrase to look like a title,
        and both boxes to overlap vertically keeps ordinary numbered list items out.
        """
        source_hash = _normalized_text_sha256(
            [str(box.get("text") or "") for box in boxes]
        )
        result: list[dict[str, Any]] = []
        reconstructed = 0
        index = 0
        while index < len(boxes):
            current = dict(boxes[index])
            current_text = str(current.get("text") or "").strip()
            if index + 1 < len(boxes):
                following = dict(boxes[index + 1])
                following_text = str(following.get("text") or "").strip()
                phrase_and_body = self._pdf_heading_phrase_and_body(current_text)
                current_position = self._first_pdf_position(current)
                following_position = self._first_pdf_position(following)
                if (
                    str(current.get("layout_type") or "").lower() in {"text", "title"}
                    and str(following.get("layout_type") or "").lower() == "title"
                    and self._is_hierarchical_number_only_pdf_title(following_text)
                    and phrase_and_body is not None
                    and not phrase_and_body[1]
                    and self._can_merge_inline_numbered_pdf_title(
                        following_position,
                        current_position,
                        phrase_and_body[0],
                    )
                ):
                    heading = self._joined_pdf_boxes(current, following)
                    heading["text"] = f"{following_text} {phrase_and_body[0]}"
                    heading["layout_type"] = "title"
                    result.append(heading)
                    reconstructed += 1
                    index += 2
                    continue
            if (
                index + 1 >= len(boxes)
                or not self._is_hierarchical_number_only_pdf_title(current_text)
            ):
                result.append(current)
                index += 1
                continue

            following = dict(boxes[index + 1])
            if str(following.get("layout_type") or "").lower() not in {"text", "title"}:
                result.append(current)
                index += 1
                continue
            following_text = str(following.get("text") or "").strip()
            phrase_and_body = self._pdf_heading_phrase_and_body(following_text)
            if phrase_and_body is None:
                result.append(current)
                index += 1
                continue

            phrase, body = phrase_and_body
            current_layout = str(current.get("layout_type") or "").lower()
            geometry_matches = self._can_merge_inline_numbered_pdf_title(
                current_position,
                following_position,
                phrase,
            )
            conservative_sequence_fallback = (
                self._pdf_boxes_share_page(current, following)
                and (
                    (
                        current_layout == "title"
                        and str(following.get("layout_type") or "").lower()
                        == "text"
                        and not body
                    )
                    or (current_layout == "text" and bool(body))
                )
            )
            if not geometry_matches and not conservative_sequence_fallback:
                result.append(current)
                index += 1
                continue
            heading = self._joined_pdf_boxes(current, following)
            heading["text"] = f"{current_text} {phrase}"
            heading["layout_type"] = "title"
            result.append(heading)
            if body:
                following["text"] = body
                result.append(following)
            reconstructed += 1
            index += 2

        output_hash = _normalized_text_sha256(
            [str(box.get("text") or "") for box in result]
        )
        source_characters = Counter(
            character
            for character in unicodedata.normalize(
                "NFKC", "".join(str(box.get("text") or "") for box in boxes)
            )
            if not character.isspace()
        )
        output_characters = Counter(
            character
            for character in unicodedata.normalize(
                "NFKC", "".join(str(box.get("text") or "") for box in result)
            )
            if not character.isspace()
        )
        if output_hash != source_hash and output_characters != source_characters:
            raise StableServiceError(
                "DEEPDOC_TITLE_GOVERNANCE_TEXT_MISMATCH",
                "Inline numbered heading reconstruction changed or dropped source text",
                False,
                500,
            )
        return result, reconstructed

    def _pdf_heading_phrase_and_body(self, value: str) -> tuple[str, str] | None:
        text = unicodedata.normalize("NFKC", value).strip()
        if self._is_plausible_pdf_heading_phrase(text):
            return text, ""
        match = re.fullmatch(r"(.{1,120}?[.!?])\s+(.+)", text, re.DOTALL)
        if not match or not self._is_plausible_pdf_heading_phrase(match.group(1)):
            return None
        return match.group(1).strip(), match.group(2).strip()

    def _joined_pdf_boxes(
        self,
        first: dict[str, Any],
        second: dict[str, Any],
    ) -> dict[str, Any]:
        joined = dict(first)
        first_position = self._first_pdf_position(first)
        second_position = self._first_pdf_position(second)
        if first_position is None or second_position is None:
            if first_position is None and second.get("positions"):
                joined["positions"] = list(second["positions"])
            if joined.get("page_number") is None and second.get("page_number") is not None:
                joined["page_number"] = second["page_number"]
            return joined
        page, left, right, top, bottom = first_position
        _page2, left2, right2, top2, bottom2 = second_position
        joined["positions"] = [[
            page,
            min(left, left2),
            max(right, right2),
            min(top, top2),
            max(bottom, bottom2),
        ]]
        joined["x0"] = min(left, left2)
        joined["x1"] = max(right, right2)
        joined["top"] = min(top, top2)
        joined["bottom"] = max(bottom, bottom2)
        return joined

    def _pdf_boxes_share_page(
        self,
        first: dict[str, Any],
        second: dict[str, Any],
    ) -> bool:
        return bool(self._pages(first) & self._pages(second))

    def _normalize_pdf_numbered_title_order(
        self,
        boxes: list[dict[str, Any]],
    ) -> tuple[list[dict[str, Any]], int]:
        normalized: list[dict[str, Any]] = []
        reordered = 0
        for original in boxes:
            box = dict(original)
            text = str(box.get("text") or "").strip()
            if str(box.get("layout_type") or "").lower() != "title" or not text:
                normalized.append(box)
                continue
            leading = re.fullmatch(
                r"(\d+(?:\.\d+){1,5}\.)(\S.{0,118})",
                unicodedata.normalize("NFKC", text),
            )
            if leading and self._is_plausible_pdf_heading_phrase(leading.group(2)):
                box["text"] = f"{leading.group(1)} {leading.group(2).strip()}"
                normalized.append(box)
                reordered += 1
                continue
            trailing = re.fullmatch(
                r"(.{2,120}?)(\d+(?:\.\d+){1,5}\.)",
                unicodedata.normalize("NFKC", text),
            )
            if trailing and self._is_plausible_pdf_heading_phrase(trailing.group(1)):
                candidate = f"{trailing.group(2)} {trailing.group(1).strip()}"
                if sorted(character for character in candidate if not character.isspace()) \
                        != sorted(character for character in text if not character.isspace()):
                    raise StableServiceError(
                        "DEEPDOC_TITLE_GOVERNANCE_TEXT_MISMATCH",
                        "Numbered title order normalization changed source characters",
                        False,
                        500,
                    )
                box["text"] = candidate
                reordered += 1
            normalized.append(box)
        return normalized, reordered

    def _split_pdf_numbered_heading_prefixes(
        self,
        boxes: list[dict[str, Any]],
    ) -> tuple[list[dict[str, Any]], int]:
        result: list[dict[str, Any]] = []
        split_count = 0
        for original in boxes:
            text = str(original.get("text") or "").strip()
            if str(original.get("layout_type") or "").lower() != "text":
                result.append(dict(original))
                continue
            match = re.fullmatch(
                r"(\d+(?:\.\d+){1,5}\.)\s*(.{1,120}?[.!?])\s+(.+)",
                text,
                re.DOTALL,
            )
            if not match or not self._is_plausible_pdf_heading_phrase(match.group(2)):
                result.append(dict(original))
                continue
            heading = dict(original)
            heading["text"] = f"{match.group(1)} {match.group(2).strip()}"
            heading["layout_type"] = "title"
            body = dict(original)
            body["text"] = match.group(3).strip()
            result.extend((heading, body))
            split_count += 1
        return result, split_count

    def _is_plausible_pdf_heading_phrase(self, value: str) -> bool:
        text = unicodedata.normalize("NFKC", value).strip()
        if not text or len(text) > 120 or "\n" in text:
            return False
        words = re.findall(r"[A-Za-z][A-Za-z'’-]*", text)
        if not 1 <= len(words) <= 12:
            return False
        insignificant = {
            "a", "an", "and", "as", "at", "by", "for", "from", "in",
            "of", "on", "or", "the", "to", "with",
        }
        significant = [word for word in words if word.casefold() not in insignificant]
        if not significant:
            return False
        titled = sum(1 for word in significant if word[0].isupper())
        return titled / len(significant) >= 0.75

    def _merge_pdf_title_fragments(
        self,
        boxes: list[dict[str, Any]],
        outlines: list[tuple[Any, Any, Any]],
    ) -> tuple[list[dict[str, Any]], int]:
        outline_keys = {
            (int(item[2]), self._pdf_title_key(str(item[0]), False))
            for item in outlines
            if isinstance(item, (list, tuple))
            and len(item) >= 3
            and str(item[2]).isdigit()
        }
        merged: list[dict[str, Any]] = []
        merged_fragments = 0
        index = 0
        while index < len(boxes):
            current = dict(boxes[index])
            if str(current.get("layout_type") or "").lower() != "title":
                merged.append(current)
                index += 1
                continue
            while index + 1 < len(boxes):
                following = boxes[index + 1]
                if not self._can_merge_pdf_title_fragments(
                    current, following, outline_keys
                ):
                    break
                first_position = self._first_pdf_position(current)
                second_position = self._first_pdf_position(following)
                assert first_position is not None and second_position is not None
                page, left, right, top, bottom = first_position
                _page2, left2, right2, top2, bottom2 = second_position
                current["text"] = (
                    str(current.get("text") or "").strip()
                    + " "
                    + str(following.get("text") or "").strip()
                )
                current["positions"] = [[
                    page,
                    min(left, left2),
                    max(right, right2),
                    min(top, top2),
                    max(bottom, bottom2),
                ]]
                current["x0"] = min(left, left2)
                current["x1"] = max(right, right2)
                current["top"] = min(top, top2)
                current["bottom"] = max(bottom, bottom2)
                index += 1
                merged_fragments += 1
            merged.append(current)
            index += 1
        return merged, merged_fragments

    def _can_merge_pdf_title_fragments(
        self,
        first: dict[str, Any],
        second: dict[str, Any],
        outline_keys: set[tuple[int, str]],
    ) -> bool:
        if str(second.get("layout_type") or "").lower() != "title":
            return False
        first_text = str(first.get("text") or "").strip()
        second_text = str(second.get("text") or "").strip()
        if not first_text or not second_text:
            return False
        first_position = self._first_pdf_position(first)
        second_position = self._first_pdf_position(second)
        if first_position is None or second_position is None:
            return False
        if self._is_number_only_pdf_title(first_text):
            return self._can_merge_inline_numbered_pdf_title(
                first_position,
                second_position,
                second_text,
            )
        if self._strong_pdf_heading_level(first_text) is not None:
            return False
        if self._strong_pdf_heading_level(second_text) is not None:
            return False
        page, left, right, top, bottom = first_position
        page2, left2, right2, top2, bottom2 = second_position
        if page != page2:
            return False
        if (page, self._pdf_title_key(first_text, False)) in outline_keys:
            return False
        if (page2, self._pdf_title_key(second_text, False)) in outline_keys:
            return False
        first_height = max(1.0, bottom - top)
        second_height = max(1.0, bottom2 - top2)
        vertical_gap = top2 - bottom
        if vertical_gap < -max(first_height, second_height) * 0.25:
            return False
        if vertical_gap > max(3.0, min(first_height, second_height) * 0.6):
            return False
        first_width = max(1.0, right - left)
        second_width = max(1.0, right2 - left2)
        overlap = max(0.0, min(right, right2) - max(left, left2))
        aligned = overlap / min(first_width, second_width) >= 0.2 or abs(left - left2) <= 12
        return aligned

    def _is_number_only_pdf_title(self, value: str) -> bool:
        text = unicodedata.normalize("NFKC", value).strip()
        return re.fullmatch(
            r"(?:\d+(?:\.\d+){0,5}|[IVXLCDM]+)[.、)）:]?",
            text,
            re.IGNORECASE,
        ) is not None

    def _is_hierarchical_number_only_pdf_title(self, value: str) -> bool:
        text = unicodedata.normalize("NFKC", value).strip()
        return re.fullmatch(r"\d+(?:\.\d+){1,5}\.", text) is not None

    def _can_merge_inline_numbered_pdf_title(
        self,
        first_position: tuple[int, float, float, float, float] | None,
        second_position: tuple[int, float, float, float, float] | None,
        second_text: str,
    ) -> bool:
        if first_position is None or second_position is None:
            return False
        if not second_text or len(second_text) > 120:
            return False
        if self._is_number_only_pdf_title(second_text):
            return False
        page, left, right, top, bottom = first_position
        page2, left2, _right2, top2, bottom2 = second_position
        if page != page2:
            return False
        first_height = max(1.0, bottom - top)
        second_height = max(1.0, bottom2 - top2)
        vertical_overlap = max(0.0, min(bottom, bottom2) - max(top, top2))
        if vertical_overlap / min(first_height, second_height) < 0.6:
            return False
        if left2 <= left and abs(left2 - left) > 12:
            return False
        horizontal_gap = left2 - right
        return -min(first_height, second_height) * 0.6 <= horizontal_gap <= max(
            24.0,
            max(first_height, second_height) * 4.0,
        )

    def _govern_pdf_title_candidates(
        self,
        boxes: list[dict[str, Any]],
        outlines: list[tuple[Any, Any, Any]],
    ) -> tuple[dict[int, dict[str, Any]], Counter[str]]:
        title_indices = [
            index
            for index, box in enumerate(boxes)
            if str(box.get("layout_type") or "").lower() == "title"
            and str(box.get("text") or "").strip()
        ]
        decisions: dict[int, dict[str, Any]] = {}
        stats: Counter[str] = Counter()
        if not title_indices:
            return decisions, stats

        first_page_title = next(
            (
                index
                for index in title_indices
                if min(self._pages(boxes[index]) or {0}) == 1
            ),
            None,
        )
        if first_page_title is not None:
            decisions[first_page_title] = {"documentTitle": True}
            stats["document-title"] += 1

        candidates = [index for index in title_indices if index != first_page_title]
        stats["candidates"] = len(candidates)
        if not candidates:
            return decisions, stats

        outline_map: dict[tuple[int, str], int] = {}
        for item in outlines:
            if not isinstance(item, (list, tuple)) or len(item) < 3:
                continue
            try:
                title, depth, page_number = item[0], int(item[1]), int(item[2])
            except (TypeError, ValueError):
                continue
            key = self._pdf_title_key(str(title), replace_numbers=False)
            if key:
                outline_map[(page_number, key)] = max(1, min(6, depth + 1))

        label_counts: Counter[str] = Counter(
            self._pdf_title_key(str(boxes[index].get("text") or ""), True)
            for index in candidates
        )
        page_bounds = self._pdf_page_vertical_bounds(boxes)
        running_indices = self._running_title_indices(
            boxes, candidates, page_bounds
        )
        trusted_structure_indices = {
            index
            for index in candidates
            if self._pdf_outline_level(boxes[index], outline_map) is not None
            or self._strong_pdf_heading_level(
                str(boxes[index].get("text") or "").strip()
            ) is not None
        }
        structure_rich_document = len(trusted_structure_indices) >= 4
        if structure_rich_document:
            stats["structure-rich-document"] = 1

        for candidate_offset, index in enumerate(candidates):
            box = boxes[index]
            text = str(box.get("text") or "").strip()
            pages = self._pages(box)
            page_number = min(pages or {0})
            outline_level = self._pdf_outline_level(box, outline_map)
            strong_level = self._strong_pdf_heading_level(text)
            next_title_index = (
                candidates[candidate_offset + 1]
                if candidate_offset + 1 < len(candidates)
                else len(boxes)
            )
            body_blocks = [
                following
                for following in boxes[index + 1 : next_title_index]
                if str(following.get("text") or "").strip()
                and str(following.get("layout_type") or "").lower() != "title"
            ]
            body_chars = sum(
                len(str(following.get("text") or "").strip())
                for following in body_blocks
            )
            label_key = self._pdf_title_key(text, replace_numbers=True)

            if outline_level is not None:
                self._accept_pdf_title(
                    decisions,
                    stats,
                    index,
                    outline_level,
                    "PDF_OUTLINE_MATCH",
                    "accepted-outline",
                )
            elif strong_level is not None:
                self._accept_pdf_title(
                    decisions,
                    stats,
                    index,
                    strong_level,
                    "DEEPDOC_NUMBERED_TITLE",
                    "accepted-structure",
                )
            elif index in running_indices:
                self._downgrade_pdf_title(decisions, stats, index, "running-header-footer")
            elif label_key and label_counts[label_key] >= 2:
                self._downgrade_pdf_title(decisions, stats, index, "repeated-label")
            elif len(text) > 120:
                self._downgrade_pdf_title(decisions, stats, index, "title-too-long")
            elif self._is_chapter_level_visual_title(
                text, allow_uppercase=not structure_rich_document
            ):
                self._accept_pdf_title(
                    decisions,
                    stats,
                    index,
                    1,
                    "DEEPDOC_CHAPTER_TITLE_VERIFIED",
                    "accepted-visual",
                )
            elif not body_blocks or body_chars < 40:
                self._downgrade_pdf_title(decisions, stats, index, "empty-section")
            else:
                self._downgrade_pdf_title(decisions, stats, index, "weak-visual-title")
        return decisions, stats

    def _accept_pdf_title(
        self,
        decisions: dict[int, dict[str, Any]],
        stats: Counter[str],
        index: int,
        level: int,
        source: str,
        reason: str,
    ) -> None:
        decisions[index] = {
            "accepted": True,
            "headingLevel": max(1, min(6, level)),
            "detectionSource": source,
        }
        stats["accepted"] += 1
        stats[reason] += 1

    def _downgrade_pdf_title(
        self,
        decisions: dict[int, dict[str, Any]],
        stats: Counter[str],
        index: int,
        reason: str,
    ) -> None:
        decisions[index] = {"accepted": False, "reason": reason}
        stats["downgraded"] += 1
        stats[reason] += 1

    def _pdf_title_key(self, value: str, replace_numbers: bool) -> str:
        normalized = unicodedata.normalize("NFKC", value).casefold()
        if replace_numbers:
            normalized = re.sub(r"\d+(?:\.\d+)*", "#", normalized)
        return "".join(
            character
            for character in normalized
            if character.isalnum() or character == "#"
        )

    def _strong_pdf_heading_level(self, value: str) -> int | None:
        text = unicodedata.normalize("NFKC", value).strip()
        numeric = re.match(
            r"^(\d+(?:\.\d+){0,5})[.、)）:](?:\s*\S.*)?$", text
        )
        if numeric:
            return min(6, numeric.group(1).count(".") + 1)
        if re.match(
            r"^[IVXLCDM]+[.、)）:](?:\s*\S.*)?$", text, re.IGNORECASE
        ):
            return 1
        if re.match(r"^[一二三四五六七八九十百]+[、.．]\s*\S+", text):
            return 1
        if re.match(
            r"^(chapter|part|section|unit|appendix)\s*"
            r"(\d+|[ivxlcdm]+|[a-z])\s*[:：.、\-—]\s*\S+",
            text,
            re.IGNORECASE,
        ):
            return 1
        if re.match(
            r"^semester\s*\d+\s*[:：]\s*quarter\s*\d+",
            text,
            re.IGNORECASE,
        ):
            return 1
        return None

    def _pdf_outline_level(
        self,
        box: dict[str, Any],
        outline_map: dict[tuple[int, str], int],
    ) -> int | None:
        pages = self._pages(box)
        page_number = min(pages or {0})
        return outline_map.get(
            (
                page_number,
                self._pdf_title_key(
                    str(box.get("text") or "").strip(),
                    replace_numbers=False,
                ),
            )
        )

    def _is_chapter_level_visual_title(
        self, value: str, *, allow_uppercase: bool = True
    ) -> bool:
        text = unicodedata.normalize("NFKC", value).strip()
        if not text or len(text) > 100:
            return False
        normalized = re.sub(r"[\s:：.。!！?？\-_—]+", "", text).casefold()
        canonical_sections = {
            "abstract",
            "introduction",
            "background",
            "overview",
            "method",
            "methods",
            "methodology",
            "result",
            "results",
            "discussion",
            "conclusion",
            "conclusions",
            "reference",
            "references",
            "appendix",
            "摘要",
            "引言",
            "背景",
            "概述",
            "方法",
            "结果",
            "讨论",
            "结论",
            "参考文献",
            "附录",
        }
        if normalized in canonical_sections:
            return True
        if not allow_uppercase:
            return False
        cased_letters = [character for character in text if character.isalpha() and character.isascii()]
        if len(cased_letters) < 4:
            return False
        uppercase = sum(1 for character in cased_letters if character.isupper())
        return uppercase / len(cased_letters) >= 0.9

    def _pdf_page_vertical_bounds(
        self, boxes: list[dict[str, Any]]
    ) -> dict[int, tuple[float, float]]:
        values: defaultdict[int, list[float]] = defaultdict(list)
        for box in boxes:
            position = self._first_pdf_position(box)
            if position is None:
                continue
            page_number, _left, _right, top, bottom = position
            values[page_number].extend([top, bottom])
        return {
            page: (min(coordinates), max(coordinates))
            for page, coordinates in values.items()
            if coordinates
        }

    def _running_title_indices(
        self,
        boxes: list[dict[str, Any]],
        candidates: list[int],
        page_bounds: dict[int, tuple[float, float]],
    ) -> set[int]:
        bands: defaultdict[tuple[str, str], list[int]] = defaultdict(list)
        for index in candidates:
            position = self._first_pdf_position(boxes[index])
            if position is None:
                continue
            page_number, _left, _right, top, bottom = position
            bounds = page_bounds.get(page_number)
            if bounds is None or bounds[1] <= bounds[0]:
                continue
            span = bounds[1] - bounds[0]
            band = None
            if top <= bounds[0] + span * 0.12:
                band = "top"
            elif bottom >= bounds[1] - span * 0.12:
                band = "bottom"
            if band:
                key = self._pdf_title_key(
                    str(boxes[index].get("text") or ""),
                    replace_numbers=True,
                )
                if key:
                    bands[(key, band)].append(index)
        return {
            index
            for indices in bands.values()
            if len(indices) >= 2
            for index in indices
        }

    def _first_pdf_position(
        self, box: dict[str, Any]
    ) -> tuple[int, float, float, float, float] | None:
        for position in box.get("positions") or []:
            if not isinstance(position, (list, tuple)) or len(position) < 5:
                continue
            try:
                return (
                    int(position[0]),
                    float(position[1]),
                    float(position[2]),
                    float(position[3]),
                    float(position[4]),
                )
            except (TypeError, ValueError):
                continue
        return None

    def _parse_docx(
        self, path: Path
    ) -> tuple[list[dict[str, Any]], list[dict[str, Any]]]:
        parser = self.parsers["DOCX"]
        sections, tables = parser(str(path))
        paragraph_positions, table_positions, exact = self._docx_positions(parser)
        blocks: list[dict[str, Any]] = []
        warnings: list[dict[str, Any]] = []
        document_title_emitted = False
        flattened_title = False

        for ordinal, section in enumerate(sections):
            text = str(section[0] if section else "").strip()
            if not text:
                continue
            style = str(section[1] if len(section) > 1 else "")
            normalized_style = style.strip().lower()
            heading = re.search(r"(?:heading|标题)\s*([1-6])", style, re.I)
            kind = "PARAGRAPH"
            heading_level = None
            detection_source = None
            document_title = False
            if normalized_style in {"title", "文档标题"} and not document_title_emitted:
                kind = "TITLE"
                document_title = True
                document_title_emitted = True
                detection_source = "DEEPDOC_DOCX_STYLE"
            elif normalized_style in {"title", "文档标题"}:
                kind = "HEADING"
                heading_level = 1
                detection_source = "DEEPDOC_DOCX_STYLE"
                flattened_title = True
            elif heading:
                kind = "HEADING"
                heading_level = int(heading.group(1))
                detection_source = "DEEPDOC_DOCX_STYLE"
            elif "list" in normalized_style or "列表" in normalized_style:
                kind = "LIST_ITEM"
            blocks.append(
                self._non_pdf_block(
                    kind,
                    text,
                    "DOCX",
                    body_element_index=paragraph_positions.get(ordinal, ordinal),
                    heading_level=heading_level,
                    detection_source=detection_source,
                    document_title=document_title,
                )
            )

        for table_ordinal, table in enumerate(tables):
            body_index = table_positions.get(
                table_ordinal,
                len(sections) + table_ordinal,
            )
            values = table if isinstance(table, list) else [table]
            if not any(str(value or "").strip() for value in values):
                # RAGFlow keeps an empty list placeholder for tables with <2 rows.
                values = self._docx_empty_table_cells(parser, table_ordinal, len(tables))
            for value in values:
                text = str(value or "").strip()
                if text:
                    blocks.append(
                        self._non_pdf_block(
                            "TABLE_CELL",
                            text,
                            "DOCX",
                            body_element_index=body_index,
                        )
                    )
        if exact:
            # Stable sorting restores body order and preserves each table's cell order.
            blocks.sort(key=lambda block: block["bodyElementIndex"])
        if tables:
            warnings.append(
                {
                    "code": "DEEPDOC_TABLE_STRUCTURE_FLATTENED",
                    "message": "DeepDoc DOCX tables retain text without a cell grid",
                    "pageNumber": None,
                }
            )
        if flattened_title:
            warnings.append(
                {
                    "code": "DEEPDOC_DOCUMENT_TITLE_FLATTENED",
                    "message": "Additional DOCX title styles use heading level one",
                    "pageNumber": None,
                }
            )
        if not exact:
            warnings.append(
                {
                    "code": "DEEPDOC_DOCX_POSITION_LOGICAL",
                    "message": "DOCX positions use deterministic DeepDoc logical order",
                    "pageNumber": None,
                }
            )
        return self._bounded(blocks), warnings

    def _docx_empty_table_cells(
        self, parser: object, table_ordinal: int, table_count: int
    ) -> list[str]:
        try:
            source_tables = parser.doc.tables
            # Only use ordinal correspondence when all source tables are represented.
            if len(source_tables) != table_count:
                return []
            source_table = source_tables[table_ordinal]
        except (AttributeError, IndexError, TypeError):
            return []

        values: list[str] = []
        seen_cells = set()
        for row in source_table.rows:
            for cell in row.cells:
                # Merged grid positions may expose the same physical XML cell repeatedly.
                if cell._tc in seen_cells:
                    continue
                seen_cells.add(cell._tc)
                text = cell.text.strip()
                if text:
                    values.append(text)
        return values

    def _docx_positions(
        self, parser: object
    ) -> tuple[dict[int, int], dict[int, int], bool]:
        try:
            document = parser.doc
            children = [
                child
                for child in document.element.body.iterchildren()
                if str(child.tag).endswith("}p") or str(child.tag).endswith("}tbl")
            ]
            by_identity = {id(child): index for index, child in enumerate(children)}
            paragraphs = {
                index: by_identity[id(paragraph._p)]
                for index, paragraph in enumerate(document.paragraphs)
            }
            tables = {
                index: by_identity[id(table._tbl)]
                for index, table in enumerate(document.tables)
            }
            return paragraphs, tables, True
        except (AttributeError, KeyError):
            return {}, {}, False

    def _parse_txt(
        self, path: Path, source_text: str
    ) -> tuple[list[dict[str, Any]], list[dict[str, Any]]]:
        sections = self.parsers["TXT"](
            str(path), chunk_token_num=128, delimiter="\n"
        )
        source_lines = source_text.split("\n")
        line_cursor = 0
        blocks: list[dict[str, Any]] = []
        for section in sections:
            text = str(section[0] if section else "").strip()
            if not text:
                continue
            start_line, end_line, line_cursor = self._locate_text_lines(
                source_lines, text, line_cursor
            )
            blocks.append(
                self._non_pdf_block(
                    "PARAGRAPH",
                    text,
                    "TXT",
                    start_line=start_line,
                    end_line=end_line,
                )
            )
        return self._bounded(blocks), []

    def _locate_text_lines(
        self, source_lines: list[str], text: str, cursor: int
    ) -> tuple[int, int, int]:
        target_lines = text.split("\n")
        positions: list[int] = []
        next_source = cursor
        for target in target_lines:
            while next_source < len(source_lines) and source_lines[next_source] != target:
                next_source += 1
            if next_source >= len(source_lines):
                raise StableServiceError(
                    "DEEPDOC_POSITION_MISSING",
                    "DeepDoc TXT block could not be bound to source lines",
                    False,
                    422,
                )
            positions.append(next_source)
            next_source += 1
        return positions[0] + 1, positions[-1] + 1, next_source

    def _parse_markdown(
        self, source_text: str
    ) -> tuple[list[dict[str, Any]], list[dict[str, Any]]]:
        extractor = self.parsers["MARKDOWN"](source_text)
        elements = extractor.extract_elements(include_meta=True)
        blocks: list[dict[str, Any]] = []
        for element in elements:
            element_type = str(element.get("type") or "text_block")
            text = str(element.get("content") or "").strip()
            if not text:
                continue
            kind = {
                "header": "HEADING",
                "code_block": "CODE_BLOCK",
                "list_block": "LIST_ITEM",
                "blockquote": "QUOTE",
                "text_block": "PARAGRAPH",
            }.get(element_type, "RAW_TEXT")
            heading_level = None
            detection_source = None
            if element_type == "header":
                header = re.match(r"^(#{1,6})\s+(.*)$", text, re.S)
                if not header or not header.group(2).strip():
                    continue
                heading_level = len(header.group(1))
                text = header.group(2).strip()
                detection_source = "DEEPDOC_MARKDOWN_HEADING"
            if element_type == "code_block":
                text = self._strip_markdown_fence(text)
            if element_type == "blockquote":
                text = "\n".join(
                    line.lstrip()[1:].lstrip()
                    if line.lstrip().startswith(">")
                    else line
                    for line in text.split("\n")
                ).strip()
            if not text:
                continue
            blocks.append(
                self._non_pdf_block(
                    kind,
                    text,
                    "MARKDOWN",
                    start_line=int(element["start_line"]) + 1,
                    start_column=1,
                    end_line=int(element["end_line"]) + 1,
                    end_column=len(source_text.split("\n")[int(element["end_line"])]) + 1,
                    heading_level=heading_level,
                    detection_source=detection_source,
                )
            )
        return self._bounded(blocks), []

    def _strip_markdown_fence(self, text: str) -> str:
        lines = text.split("\n")
        if lines and re.match(r"^[ \t]{0,3}(`{3,}|~{3,})", lines[0]):
            lines = lines[1:]
        if lines and re.match(r"^[ \t]{0,3}(`{3,}|~{3,})\s*$", lines[-1]):
            lines = lines[:-1]
        return "\n".join(lines).strip("\n")

    def _non_pdf_block(
        self,
        kind: str,
        text: str,
        source_type: str,
        *,
        body_element_index: int | None = None,
        start_line: int | None = None,
        start_column: int | None = None,
        end_line: int | None = None,
        end_column: int | None = None,
        heading_level: int | None = None,
        detection_source: str | None = None,
        document_title: bool = False,
    ) -> dict[str, Any]:
        return {
            "kind": kind,
            "text": text,
            "sourceType": source_type,
            "bodyElementIndex": body_element_index,
            "startLine": start_line,
            "startColumn": start_column,
            "endLine": end_line,
            "endColumn": end_column,
            "headingLevel": heading_level,
            "detectionSource": detection_source,
            "documentTitle": document_title,
        }

    def _bounded(self, blocks: list[dict[str, Any]]) -> list[dict[str, Any]]:
        if len(blocks) > self.max_blocks:
            self._limit_exceeded("DeepDoc returned too many blocks")
        return blocks

    def _pages(self, box: dict[str, Any]) -> set[int]:
        pages: set[int] = set()
        for position in box.get("positions") or []:
            if isinstance(position, (list, tuple)) and position:
                try:
                    pages.add(int(position[0]))
                except (TypeError, ValueError):
                    pass
        if not pages and box.get("page_number") is not None:
            try:
                pages.add(int(box["page_number"]))
            except (TypeError, ValueError):
                pass
        return {page for page in pages if page > 0}


def create_app(runtime: DeepDocRuntime, max_upload_bytes: int) -> Flask:
    app = Flask(__name__)
    app.config["MAX_CONTENT_LENGTH"] = max_upload_bytes

    @app.errorhandler(RequestEntityTooLarge)
    def too_large(_error: RequestEntityTooLarge):
        return _error_response(
            StableServiceError(
                "DOCUMENT_PARSE_LIMIT_EXCEEDED",
                "Uploaded document exceeds the configured HTTP limit",
                False,
                413,
            ),
            _safe_request_id(request.headers.get("X-Request-ID")),
        )

    @app.get("/health/live")
    def live():
        return jsonify({"status": "UP", "schemaVersion": SCHEMA_VERSION})

    @app.get("/health/ready")
    def ready():
        return jsonify(
            {
                "status": "READY",
                "schemaVersion": SCHEMA_VERSION,
                "engineVersion": ENGINE_VERSION,
                "supportedFormats": sorted(SUPPORTED_FORMATS),
                "parseConcurrency": runtime.parse_concurrency,
                "maxObservedParseConcurrency": runtime.max_observed_active,
                "onnxSessions": runtime.sessions,
            }
        )

    @app.post("/v1/parse")
    def parse():
        request_id = _safe_request_id(request.headers.get("X-Request-ID"))
        expected_sha256 = str(request.headers.get("X-Source-SHA256") or "").lower()
        if not SHA256_PATTERN.fullmatch(expected_sha256):
            return _error_response(
                StableServiceError(
                    "SOURCE_DIGEST_REQUIRED",
                    "X-Source-SHA256 must contain a lowercase SHA-256 digest",
                    False,
                    400,
                ),
                request_id,
            )
        source_format = str(request.headers.get("X-Source-Format") or "").upper()
        if source_format not in SUPPORTED_FORMATS:
            return _error_response(
                StableServiceError(
                    "DOCUMENT_FORMAT_UNSUPPORTED",
                    "X-Source-Format must identify a supported document format",
                    False,
                    415,
                ),
                request_id,
            )
        upload = request.files.get("file")
        if upload is None:
            return _error_response(
                StableServiceError(
                    "SOURCE_FILE_REQUIRED",
                    "Multipart field file is required",
                    False,
                    400,
                ),
                request_id,
            )

        path: Path | None = None
        try:
            with tempfile.NamedTemporaryFile(
                delete=False,
                suffix=FORMAT_SUFFIXES[source_format],
            ) as temporary:
                path = Path(temporary.name)
                upload.save(temporary)
            result = runtime.parse(path, expected_sha256, source_format)
            result["requestId"] = request_id
            return jsonify(result)
        except StableServiceError as error:
            return _error_response(error, request_id)
        except Exception:
            return _error_response(
                StableServiceError(
                    "DEEPDOC_SERVICE_UNAVAILABLE",
                    "DeepDoc service could not complete the request",
                    True,
                    503,
                ),
                request_id,
            )
        finally:
            if path is not None:
                try:
                    path.unlink(missing_ok=True)
                except OSError:
                    pass

    return app


def _error_response(error: StableServiceError, request_id: str):
    return (
        jsonify(
            {
                "schemaVersion": SCHEMA_VERSION,
                "requestId": request_id,
                "error": {
                    "code": error.code,
                    "message": error.message,
                    "retryable": error.retryable,
                },
            }
        ),
        error.status,
    )


def build_runtime() -> DeepDocRuntime:
    from deepdoc.parser import DocxParser, MarkdownElementExtractor, PdfParser, TxtParser

    pdf_parser = PdfParser()
    sessions = collect_onnx_sessions(pdf_parser)
    required_provider = os.getenv("REQUIRE_ONNX_PROVIDER", "").strip()
    active = {
        provider
        for providers in sessions.values()
        for provider in providers
    }
    if required_provider and required_provider not in active:
        raise RuntimeError("Required ONNX provider is unavailable")
    concurrency = _positive_int("PARSER_MAX_CONCURRENCY", 1)
    if concurrency != 1:
        raise RuntimeError("P1 requires PARSER_MAX_CONCURRENCY=1")
    return DeepDocRuntime(
        parsers={
            "PDF": pdf_parser,
            "DOCX": DocxParser(),
            "TXT": TxtParser(),
            "MARKDOWN": MarkdownElementExtractor,
        },
        sessions=sessions,
        max_pdf_pages=_positive_int("PARSER_MAX_PDF_PAGES", 2000),
        max_blocks=_positive_int("PARSER_MAX_BLOCKS", 200000),
        max_docx_expanded_bytes=_positive_int(
            "PARSER_MAX_DOCX_EXPANDED_BYTES", 200 * 1024 * 1024
        ),
        parse_concurrency=concurrency,
    )


def main() -> int:
    runtime = build_runtime()
    app = create_app(
        runtime,
        _positive_int("PARSER_MAX_REQUEST_BYTES", 51 * 1024 * 1024),
    )
    app.run(
        host=os.getenv("PARSER_HTTP_HOST", "0.0.0.0"),
        port=_positive_int("PARSER_HTTP_PORT", 8080),
        threaded=True,
        use_reloader=False,
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
