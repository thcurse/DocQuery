#!/usr/bin/env python3
"""Run the fixed DocQuery DeepDoc P0 corpus without persisting document text."""

from __future__ import annotations

import argparse
import gc
import hashlib
import json
import os
import statistics
import threading
import time
import traceback
from collections import Counter
from datetime import datetime, timezone
from pathlib import Path

from deepdoc.parser import PdfParser
from pypdf import PdfReader


REPORT_SCHEMA = "docquery-deepdoc-p0-report-v1"
ENGINE_VERSION = "ragflow-v0.26.4"
ENGINE_IMAGE = "infiniflow/ragflow:v0.26.4"


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


class MemorySampler:
    def __init__(self) -> None:
        self.available = False
        self.start_rss = None
        self.end_rss = None
        self.peak_rss = None
        self._stop = threading.Event()
        self._thread = None
        self._process = None
        try:
            import psutil

            self._process = psutil.Process(os.getpid())
            self.available = True
        except Exception:
            pass

    def start(self) -> None:
        if not self.available:
            return
        self.start_rss = self._process.memory_info().rss
        self.peak_rss = self.start_rss

        def sample() -> None:
            while not self._stop.wait(0.2):
                try:
                    self.peak_rss = max(self.peak_rss, self._process.memory_info().rss)
                except Exception:
                    return

        self._thread = threading.Thread(target=sample, daemon=True)
        self._thread.start()

    def finish(self) -> dict:
        if not self.available:
            return {"available": False}
        self.end_rss = self._process.memory_info().rss
        self.peak_rss = max(self.peak_rss, self.end_rss)
        self._stop.set()
        if self._thread is not None:
            self._thread.join(timeout=1)
        return {
            "available": True,
            "rssBeforeBytes": self.start_rss,
            "rssAfterBytes": self.end_rss,
            "peakRssBytes": self.peak_rss,
        }


def pages_for_box(box: dict) -> set[int]:
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


def safe_error_detail(error: BaseException) -> str:
    detail = " ".join(str(error).split())
    return detail[:500]


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

        get_providers = getattr(value, "get_providers", None)
        if callable(get_providers):
            try:
                sessions[path] = [str(item) for item in get_providers()]
            except Exception:
                sessions[path] = ["PROVIDER_INSPECTION_FAILED"]
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


def torch_runtime() -> dict:
    try:
        import torch

        return {
            "installed": True,
            "version": str(torch.__version__),
            "cudaVersion": str(torch.version.cuda),
            "cudaAvailable": bool(torch.cuda.is_available()),
            "deviceCount": int(torch.cuda.device_count()),
            "deviceNames": [
                str(torch.cuda.get_device_name(index))
                for index in range(torch.cuda.device_count())
            ],
        }
    except Exception as error:
        return {
            "installed": False,
            "errorType": type(error).__name__,
            "errorDetail": safe_error_detail(error),
        }


def summarize(results: list[dict], recovery_threshold: int) -> dict:
    parsed = [result for result in results if result["status"] == "PARSED"]
    recovered = [
        result
        for result in parsed
        if result["baselineResult"] in {
            "PDF_COMPLEX_LAYOUT_UNSUPPORTED",
            "PDF_NO_EXTRACTABLE_TEXT",
        }
    ]
    latencies = [result["elapsedMillis"] for result in parsed]
    evidence_required = [result for result in parsed if result["evidencePages"]]
    evidence_complete = [
        result for result in evidence_required if result["evidenceCoverageComplete"]
    ]
    return {
        "attempted": len(results),
        "parsed": len(parsed),
        "failed": len(results) - len(parsed),
        "recoveredPreviouslyRejected": len(recovered),
        "evidenceDocumentsParsed": len(evidence_required),
        "evidenceDocumentsComplete": len(evidence_complete),
        "elapsedMillis": {
            "min": min(latencies) if latencies else None,
            "median": int(statistics.median(latencies)) if latencies else None,
            "max": max(latencies) if latencies else None,
            "total": sum(result["elapsedMillis"] for result in results),
        },
        "p0RecoveryThreshold": recovery_threshold,
        "p0RecoveryThresholdMet": len(recovered) >= recovery_threshold,
    }


def write_report(path: Path, report: dict) -> None:
    recovery_threshold = int(report["acceptance"]["recoveryThreshold"])
    report["summary"] = summarize(report["results"], recovery_threshold)
    report["updatedAt"] = utc_now()
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(path.suffix + ".tmp")
    temporary.write_text(
        json.dumps(report, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    temporary.replace(path)


def parse_document(parser: PdfParser, source: Path, item: dict) -> dict:
    started = time.perf_counter()
    sampler = MemorySampler()
    result = {
        "documentKey": item["documentKey"],
        "displayName": item["displayName"],
        "sourceDomain": item["sourceDomain"],
        "role": item["role"],
        "caseIds": item["caseIds"],
        "baselineResult": item["baselineResult"],
        "sha256": item["sha256"],
        "bytes": item["bytes"],
        "evidencePages": item["evidencePages"],
        "status": "FAILED",
    }
    try:
        if not source.is_file():
            raise RuntimeError("SOURCE_MISSING")
        if source.stat().st_size != item["bytes"] or sha256(source) != item["sha256"]:
            raise RuntimeError("SOURCE_INTEGRITY_MISMATCH")

        reader = PdfReader(str(source))
        page_count = len(reader.pages)
        result["pageCount"] = page_count
        sampler.start()
        boxes = parser.parse_into_bboxes(
            str(source),
            zoomin=3,
            from_page=0,
            to_page=page_count,
        )

        layout_counts: Counter[str] = Counter()
        pages_with_text: set[int] = set()
        block_count = 0
        text_length = 0
        boxes_with_positions = 0
        for box in boxes:
            text = str(box.get("text") or "").strip()
            if not text:
                continue
            block_count += 1
            text_length += len(text)
            layout_counts[str(box.get("layout_type") or "unknown")] += 1
            pages = pages_for_box(box)
            if pages:
                boxes_with_positions += 1
                pages_with_text.update(pages)

        if block_count == 0:
            raise RuntimeError("NO_NONBLANK_BLOCKS")

        evidence_pages = set(item["evidencePages"])
        evidence_pages_with_text = sorted(evidence_pages & pages_with_text)
        result.update(
            {
                "status": "PARSED",
                "blockCount": block_count,
                "textLength": text_length,
                "boxesWithPositions": boxes_with_positions,
                "layoutTypeCounts": dict(sorted(layout_counts.items())),
                "pagesWithTextCount": len(pages_with_text),
                "evidencePagesWithText": evidence_pages_with_text,
                "evidenceCoverageComplete": evidence_pages.issubset(pages_with_text),
            }
        )
    except Exception as error:
        result["errorType"] = type(error).__name__
        result["errorDetail"] = safe_error_detail(error)
        result["traceTail"] = traceback.format_exc(limit=2).splitlines()[-1][:500]
    finally:
        result["memory"] = sampler.finish()
        result["elapsedMillis"] = int((time.perf_counter() - started) * 1000)
        gc.collect()
    return result


def main() -> int:
    argument_parser = argparse.ArgumentParser()
    argument_parser.add_argument("--sample", required=True, type=Path)
    argument_parser.add_argument("--input-dir", required=True, type=Path)
    argument_parser.add_argument("--output", required=True, type=Path)
    args = argument_parser.parse_args()

    sample = json.loads(args.sample.read_text(encoding="utf-8"))
    report = {
        "schemaVersion": REPORT_SCHEMA,
        "sampleVersion": sample["sampleVersion"],
        "datasetVersion": sample["datasetVersion"],
        "engine": {
            "name": "RAGFlow DeepDoc PdfParser",
            "version": ENGINE_VERSION,
            "image": ENGINE_IMAGE,
            "device": os.getenv("DEVICE", ""),
            "cudaVisibleDevices": os.getenv("CUDA_VISIBLE_DEVICES", ""),
            "network": "none",
            "tableAutoRotate": os.getenv("TABLE_AUTO_ROTATE", ""),
            "pageBatchSize": os.getenv("PDF_PARSER_PAGE_BATCH_SIZE", ""),
            "requiredOnnxProvider": os.getenv("REQUIRE_ONNX_PROVIDER", ""),
        },
        "acceptance": sample.get("acceptance", {"recoveryThreshold": 6}),
        "startedAt": utc_now(),
        "completed": False,
        "results": [],
    }
    write_report(args.output, report)

    initialization_started = time.perf_counter()
    try:
        parser = PdfParser()
    except Exception as error:
        report["initializationError"] = {
            "type": type(error).__name__,
            "detail": safe_error_detail(error),
        }
        report["modelInitializationMillis"] = int(
            (time.perf_counter() - initialization_started) * 1000
        )
        write_report(args.output, report)
        return 2

    report["modelInitializationMillis"] = int(
        (time.perf_counter() - initialization_started) * 1000
    )
    report["runtime"] = {
        "torch": torch_runtime(),
        "onnxSessions": collect_onnx_sessions(parser),
    }
    required_provider = report["engine"]["requiredOnnxProvider"]
    active_providers = {
        provider
        for providers in report["runtime"]["onnxSessions"].values()
        for provider in providers
    }
    if required_provider and required_provider not in active_providers:
        report["initializationError"] = {
            "type": "RequiredOnnxProviderUnavailable",
            "detail": (
                f"required={required_provider}; "
                f"active={sorted(active_providers)}"
            ),
        }
        write_report(args.output, report)
        return 3
    write_report(args.output, report)

    for item in sample["documents"]:
        source = args.input_dir / item["file"]
        result = parse_document(parser, source, item)
        report["results"].append(result)
        write_report(args.output, report)
        print(
            json.dumps(
                {
                    "document": item["displayName"],
                    "status": result["status"],
                    "elapsedMillis": result["elapsedMillis"],
                    "blockCount": result.get("blockCount"),
                    "evidenceCoverageComplete": result.get(
                        "evidenceCoverageComplete"
                    ),
                    "error": result.get("errorDetail"),
                },
                ensure_ascii=False,
            ),
            flush=True,
        )

    report["completed"] = True
    report["completedAt"] = utc_now()
    write_report(args.output, report)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
