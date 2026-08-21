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
    normalized = " ".join(
        " ".join(str(value).split())
        for value in values
        if str(value).strip()
    )
    return hashlib.sha256(normalized.encode("utf-8")).hexdigest()


def _utf16_length(value: str) -> int:
    return len(value.encode("utf-16-le")) // 2


def _safe_request_id(candidate: str | None) -> str:
    if candidate and 1 <= len(candidate) <= 128 and all(
        value.isalnum() or value in "-_." for value in candidate
    ):
        return candidate
    return str(uuid.uuid4())


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
    ) -> tuple[list[dict[str, Any]], list[dict[str, Any]]]:
        source_text_sha256 = _normalized_text_sha256(
            [str(box.get("text") or "") for box in boxes]
        )
        boxes, merged_title_fragments = self._merge_pdf_title_fragments(
            boxes, outlines or []
        )
        blocks: list[dict[str, Any]] = []
        page_ordinals: defaultdict[int, int] = defaultdict(int)
        page_offsets: defaultdict[int, int] = defaultdict(int)
        layout_counts: Counter[str] = Counter()
        multi_page_blocks = 0
        document_title_emitted = False
        title_decisions, title_stats = self._govern_pdf_title_candidates(
            boxes, outlines or []
        )

        for box_index, box in enumerate(boxes):
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
        if layout_counts["table"]:
            warnings.append(
                {
                    "code": "DEEPDOC_TABLE_STRUCTURE_FLATTENED",
                    "message": "DeepDoc table blocks retain text without a cell grid",
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
        if self._strong_pdf_heading_level(first_text) is not None:
            return False
        if self._strong_pdf_heading_level(second_text) is not None:
            return False
        first_position = self._first_pdf_position(first)
        second_position = self._first_pdf_position(second)
        if first_position is None or second_position is None:
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
