import hashlib
import io
import threading
import time
import unittest
import zipfile

from pypdf import PdfWriter

from deepdoc_http_service import DeepDocRuntime, create_app


def pdf_bytes() -> bytes:
    output = io.BytesIO()
    writer = PdfWriter()
    writer.add_blank_page(width=100, height=100)
    writer.write(output)
    return output.getvalue()


def encrypted_pdf_bytes(password: str) -> bytes:
    output = io.BytesIO()
    writer = PdfWriter()
    writer.add_blank_page(width=100, height=100)
    writer.encrypt(password)
    writer.write(output)
    return output.getvalue()


def docx_bytes() -> bytes:
    output = io.BytesIO()
    with zipfile.ZipFile(output, "w") as archive:
        archive.writestr("[Content_Types].xml", "<Types/>")
        archive.writestr("word/document.xml", "<document/>")
    return output.getvalue()


class FakeParser:
    def __init__(self) -> None:
        self.active = 0
        self.max_active = 0
        self.lock = threading.Lock()
        self.outlines = []

    def parse_into_bboxes(self, *_args, **_kwargs):
        with self.lock:
            self.active += 1
            self.max_active = max(self.max_active, self.active)
        try:
            time.sleep(0.08)
            return [
                {
                    "text": "Document title",
                    "layout_type": "title",
                    "positions": [[1, 1, 2, 3, 4]],
                },
                {
                    "text": "A😀B",
                    "layout_type": "text",
                    "positions": [[1, 1, 2, 5, 6]],
                },
                {
                    "text": "a | b",
                    "layout_type": "table",
                    "positions": [[1, 1, 2, 7, 8]],
                },
            ]
        finally:
            with self.lock:
                self.active -= 1


class FakeDocxParser:
    def __call__(self, _path):
        return (
            [
                ("Document title", "Title"),
                ("Section", "Heading 2"),
                ("Body", "Normal"),
            ],
            [["Name: A;Value: 1"]],
        )


class FakeTxtParser:
    def __call__(self, _path, **_kwargs):
        return [["first\nsecond", ""], ["third", ""]]


class FakeMarkdownExtractor:
    def __init__(self, _text):
        pass

    def extract_elements(self, include_meta=False):
        assert include_meta
        return [
            {
                "type": "header",
                "content": "# Heading",
                "start_line": 0,
                "end_line": 0,
            },
            {
                "type": "text_block",
                "content": "Body",
                "start_line": 1,
                "end_line": 1,
            },
        ]


class DeepDocHttpServiceTest(unittest.TestCase):
    def setUp(self) -> None:
        self.parser = FakeParser()
        self.runtime = DeepDocRuntime(
            {
                "PDF": self.parser,
                "DOCX": FakeDocxParser(),
                "TXT": FakeTxtParser(),
                "MARKDOWN": FakeMarkdownExtractor,
            },
            {"parser.fake": ["CUDAExecutionProvider"]},
            max_pdf_pages=10,
            max_blocks=100,
            parse_concurrency=1,
        )
        self.app = create_app(self.runtime, 1024 * 1024)
        self.payload = pdf_bytes()
        self.digest = hashlib.sha256(self.payload).hexdigest()

    def post(self, payload=None, source_format="PDF"):
        payload = self.payload if payload is None else payload
        digest = hashlib.sha256(payload).hexdigest()
        with self.app.test_client() as client:
            return client.post(
                "/v1/parse",
                data={"file": (io.BytesIO(payload), "document.bin")},
                headers={
                    "X-Source-SHA256": digest,
                    "X-Source-Format": source_format,
                },
                content_type="multipart/form-data",
            )

    def test_maps_neutral_blocks_and_utf16_offsets(self):
        response = self.post()
        self.assertEqual(200, response.status_code)
        body = response.get_json()
        self.assertEqual("docquery-deepdoc-http-v1", body["schemaVersion"])
        self.assertEqual("PDF", body["sourceFormat"])
        self.assertEqual("TITLE", body["blocks"][0]["kind"])
        self.assertTrue(body["blocks"][0]["documentTitle"])
        self.assertEqual("PARAGRAPH", body["blocks"][1]["kind"])
        self.assertEqual(4, body["blocks"][1]["pageCharacterEnd"]
                         - body["blocks"][1]["pageCharacterStart"])
        self.assertEqual("TABLE_CELL", body["blocks"][2]["kind"])
        self.assertIn(
            "DEEPDOC_TABLE_STRUCTURE_FLATTENED",
            {warning["code"] for warning in body["warnings"]},
        )

    def test_two_requests_wait_for_one_parse_slot_without_429(self):
        statuses = []

        def call():
            statuses.append(self.post().status_code)

        first = threading.Thread(target=call)
        second = threading.Thread(target=call)
        first.start()
        second.start()
        first.join()
        second.join()

        self.assertEqual([200, 200], sorted(statuses))
        self.assertEqual(1, self.parser.max_active)
        self.assertEqual(1, self.runtime.max_observed_active)

    def test_accepts_pdf_with_permission_encryption_and_empty_password(self):
        response = self.post(encrypted_pdf_bytes(""))

        self.assertEqual(200, response.status_code)

    def test_rejects_pdf_that_requires_a_password(self):
        response = self.post(encrypted_pdf_bytes("secret"))

        self.assertEqual(422, response.status_code)
        self.assertEqual(
            "ENCRYPTED_DOCUMENT_UNSUPPORTED",
            response.get_json()["error"]["code"],
        )

    def test_governs_title_candidates_without_dropping_their_text(self):
        boxes = [
            {"text": "Document title", "layout_type": "title", "positions": [[1, 0, 90, 0, 8]]},
            {"text": "UNIT 1: Foundations", "layout_type": "title", "positions": [[1, 0, 90, 15, 23]]},
            {"text": "Substantial body text that belongs to the numbered unit.", "layout_type": "text", "positions": [[1, 0, 90, 25, 35]]},
            {"text": "Repeated label", "layout_type": "title", "positions": [[1, 0, 90, 40, 48]]},
            {"text": "First repeated-label body remains searchable.", "layout_type": "text", "positions": [[1, 0, 90, 50, 60]]},
            {"text": "Repeated label", "layout_type": "title", "positions": [[2, 0, 90, 40, 48]]},
            {"text": "Second repeated-label body remains searchable.", "layout_type": "text", "positions": [[2, 0, 90, 50, 60]]},
            {"text": "Empty visual title", "layout_type": "title", "positions": [[2, 0, 90, 70, 78]]},
            {"text": "APPENDIX A: Evidence", "layout_type": "title", "positions": [[2, 0, 90, 80, 88]]},
        ]

        blocks, warnings = self.runtime._map_pdf_boxes(
            boxes,
            2,
            [("UNIT 1: Foundations", 1, 1)],
        )

        by_text = {block["text"]: block for block in blocks}
        self.assertEqual("TITLE", by_text["Document title"]["kind"])
        self.assertEqual("HEADING", by_text["UNIT 1: Foundations"]["kind"])
        self.assertEqual(2, by_text["UNIT 1: Foundations"]["headingLevel"])
        self.assertEqual("PDF_OUTLINE_MATCH", by_text["UNIT 1: Foundations"]["detectionSource"])
        self.assertEqual("PARAGRAPH", by_text["Repeated label"]["kind"])
        self.assertIsNone(by_text["Repeated label"]["headingLevel"])
        self.assertEqual("PARAGRAPH", by_text["Empty visual title"]["kind"])
        self.assertEqual("HEADING", by_text["APPENDIX A: Evidence"]["kind"])
        self.assertEqual(len(boxes), len(blocks))
        codes = {warning["code"] for warning in warnings}
        self.assertIn("DEEPDOC_TITLE_CANDIDATES_GOVERNED", codes)
        self.assertIn("DEEPDOC_REPEATED_TITLE_DOWNGRADED", codes)
        self.assertIn("DEEPDOC_EMPTY_TITLE_DOWNGRADED", codes)
        self.assertIn("DEEPDOC_TITLE_GOVERNANCE_TEXT_PRESERVED", codes)

    def test_merges_only_adjacent_weak_title_lines_on_the_same_page(self):
        boxes = [
            {"text": "Document title", "layout_type": "title", "positions": [[1, 0, 90, 0, 8]]},
            {"text": "Board meetings during the financial", "layout_type": "title", "positions": [[1, 0, 80, 20, 28]]},
            {"text": "year", "layout_type": "title", "positions": [[1, 0, 25, 30, 38]]},
            {"text": "Body text remains after the merged heading and is long enough.", "layout_type": "text", "positions": [[1, 0, 90, 45, 55]]},
            {"text": "UNIT 2: Strong heading", "layout_type": "title", "positions": [[1, 0, 90, 60, 68]]},
            {"text": "I.", "layout_type": "title", "positions": [[1, 0, 20, 70, 78]]},
        ]

        blocks, warnings = self.runtime._map_pdf_boxes(boxes, 1, [])

        texts = [block["text"] for block in blocks]
        self.assertIn("Board meetings during the financial year", texts)
        self.assertNotIn("Board meetings during the financial", texts)
        self.assertNotIn("year", texts)
        self.assertIn("UNIT 2: Strong heading", texts)
        self.assertIn("I.", texts)
        self.assertEqual("HEADING", next(block for block in blocks if block["text"] == "I.")["kind"])
        self.assertEqual(5, len(blocks))
        self.assertIn(
            "DEEPDOC_TITLE_FRAGMENTS_MERGED",
            {warning["code"] for warning in warnings},
        )

    def test_only_promotes_chapter_level_visual_titles(self):
        boxes = [
            {"text": "Document title", "layout_type": "title", "positions": [[1, 0, 90, 0, 8]]},
            {"text": "Board Agenda", "layout_type": "title", "positions": [[1, 0, 90, 20, 28]]},
            {"text": "Agenda body text is substantial but the label is not a chapter.", "layout_type": "text", "positions": [[1, 0, 90, 35, 45]]},
            {"text": "INTRODUCTION", "layout_type": "title", "positions": [[1, 0, 90, 55, 63]]},
            {"text": "Introduction body text is substantial enough for navigation.", "layout_type": "text", "positions": [[1, 0, 90, 70, 80]]},
            {"text": "结论", "layout_type": "title", "positions": [[1, 0, 90, 90, 98]]},
            {"text": "结论正文提供足够的章节内容用于导航和检索。", "layout_type": "text", "positions": [[1, 0, 90, 105, 115]]},
        ]

        blocks, warnings = self.runtime._map_pdf_boxes(boxes, 1, [])

        by_text = {block["text"]: block for block in blocks}
        self.assertEqual("PARAGRAPH", by_text["Board Agenda"]["kind"])
        self.assertEqual("HEADING", by_text["INTRODUCTION"]["kind"])
        self.assertEqual("HEADING", by_text["结论"]["kind"])
        self.assertIn(
            "DEEPDOC_WEAK_VISUAL_TITLE_DOWNGRADED",
            {warning["code"] for warning in warnings},
        )

    def test_numbered_structure_suppresses_untrusted_uppercase_labels(self):
        boxes = [
            {"text": "Document title", "layout_type": "title", "positions": [[1, 0, 90, 0, 8]]},
        ]
        for index in range(1, 5):
            boxes.extend([
                {"text": f"UNIT {index}: Chapter {index}", "layout_type": "title", "positions": [[index, 0, 90, 20, 28]]},
                {"text": f"Chapter {index} body text with enough content for retrieval navigation.", "layout_type": "text", "positions": [[index, 0, 90, 35, 45]]},
            ])
        boxes.extend([
            {"text": "PERSONAL REFLECTION", "layout_type": "title", "positions": [[5, 0, 90, 20, 28]]},
            {"text": "Reflection activity text remains searchable as ordinary evidence.", "layout_type": "text", "positions": [[5, 0, 90, 35, 45]]},
        ])

        blocks, _warnings = self.runtime._map_pdf_boxes(boxes, 5, [])

        headings = [block["text"] for block in blocks if block["kind"] == "HEADING"]
        self.assertEqual(
            [f"UNIT {index}: Chapter {index}" for index in range(1, 5)],
            headings,
        )
        reflection = next(
            block for block in blocks if block["text"] == "PERSONAL REFLECTION"
        )
        self.assertEqual("PARAGRAPH", reflection["kind"])

    def test_dispatches_docx_txt_and_markdown_with_native_positions(self):
        docx = self.post(docx_bytes(), "DOCX")
        self.assertEqual(200, docx.status_code)
        docx_body = docx.get_json()
        self.assertEqual("DOCX", docx_body["sourceFormat"])
        self.assertEqual("TITLE", docx_body["blocks"][0]["kind"])
        self.assertEqual("HEADING", docx_body["blocks"][1]["kind"])
        self.assertEqual(2, docx_body["blocks"][1]["headingLevel"])
        self.assertEqual("TABLE_CELL", docx_body["blocks"][-1]["kind"])

        text = self.post(b"first\nsecond\n\nthird", "TXT")
        self.assertEqual(200, text.status_code)
        text_blocks = text.get_json()["blocks"]
        self.assertEqual((1, 2), (text_blocks[0]["startLine"], text_blocks[0]["endLine"]))
        self.assertEqual((4, 4), (text_blocks[1]["startLine"], text_blocks[1]["endLine"]))

        markdown = self.post(b"# Heading\nBody", "MARKDOWN")
        self.assertEqual(200, markdown.status_code)
        markdown_blocks = markdown.get_json()["blocks"]
        self.assertEqual("HEADING", markdown_blocks[0]["kind"])
        self.assertEqual("Heading", markdown_blocks[0]["text"])
        self.assertEqual(1, markdown_blocks[0]["startLine"])

    def test_digest_mismatch_is_permanent_client_error(self):
        with self.app.test_client() as client:
            response = client.post(
                "/v1/parse",
                data={"file": (io.BytesIO(self.payload), "document.pdf")},
                headers={
                    "X-Source-SHA256": "0" * 64,
                    "X-Source-Format": "PDF",
                },
                content_type="multipart/form-data",
            )
        self.assertEqual(400, response.status_code)
        self.assertEqual(
            "SOURCE_OBJECT_INTEGRITY_MISMATCH",
            response.get_json()["error"]["code"],
        )
        self.assertFalse(response.get_json()["error"]["retryable"])


if __name__ == "__main__":
    unittest.main()
