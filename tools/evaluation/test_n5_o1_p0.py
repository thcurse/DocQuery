import importlib.util
import json
import sys
import tempfile
import unittest
from pathlib import Path


MODULE_PATH = Path(__file__).with_name("run_n5_o1_p0.py")
SPEC = importlib.util.spec_from_file_location("run_n5_o1_p0", MODULE_PATH)
MODULE = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
sys.modules[SPEC.name] = MODULE
SPEC.loader.exec_module(MODULE)


def block(ordinal: int, text: str = "x" * 4000) -> dict:
    return {
        "recordType": "block",
        "blockId": f"1:b:{ordinal:06d}",
        "ordinal": ordinal,
        "kind": "PARAGRAPH",
        "text": text,
        "sourcePosition": {"pageNumber": ordinal + 1},
    }


class N5O1P0Test(unittest.TestCase):
    def test_estimator_and_partition_count_keep_titles_small(self):
        self.assertEqual(64_001, MODULE.estimate_tokens_from_chars(256_001))
        self.assertEqual((2, 3), MODULE.partition_count_bounds(68_745, 64_000))
        self.assertEqual((3, 4), MODULE.partition_count_bounds(132_781, 64_000))

    def test_request_contains_explicit_candidate_count_without_formatting_error(self):
        request, metadata = MODULE.build_request(
            {
                "documentName": "sample.pdf",
                "headingNodeId": "1:h:000001",
                "headingTitle": "Overview",
                "sectionStartBlockOrdinal": 0,
                "sectionEndBlockOrdinalExclusive": 2,
            },
            [block(0), block(1)],
            64_000,
        )
        self.assertIn("return 6..10 candidates", request["messages"][0]["content"])
        self.assertEqual(6, metadata["minimumBoundaryCandidates"])
        self.assertEqual(10, metadata["maximumBoundaryCandidates"])

    def test_materializes_contiguous_ranges_from_model_starts(self):
        blocks = [block(index) for index in range(0, 192)]
        sample = {
            "sectionStartBlockOrdinal": 0,
            "sectionEndBlockOrdinalExclusive": 192,
        }
        payload = {
            "boundaryCandidates": [
                {
                    "title": "Business and strategy",
                    "startBlockOrdinal": 0,
                    "boundaryStrength": 5,
                    "topics": ["business"],
                },
                {
                    "title": "Opening context",
                    "startBlockOrdinal": 16,
                    "boundaryStrength": 1,
                    "topics": ["context"],
                },
                {
                    "title": "Early operations",
                    "startBlockOrdinal": 32,
                    "boundaryStrength": 2,
                    "topics": ["performance"],
                },
                {
                    "title": "Operating transition",
                    "startBlockOrdinal": 48,
                    "boundaryStrength": 1,
                    "topics": ["transition"],
                },
                {
                    "title": "Operating performance",
                    "startBlockOrdinal": 64,
                    "boundaryStrength": 5,
                    "topics": ["performance"],
                },
                {
                    "title": "Late operations",
                    "startBlockOrdinal": 96,
                    "boundaryStrength": 2,
                    "topics": ["performance"],
                },
                {
                    "title": "Risk and outlook",
                    "startBlockOrdinal": 128,
                    "boundaryStrength": 5,
                    "topics": ["risk"],
                },
                {
                    "title": "Risk transition",
                    "startBlockOrdinal": 144,
                    "boundaryStrength": 1,
                    "topics": ["risk"],
                },
                {
                    "title": "Closing disclosures",
                    "startBlockOrdinal": 160,
                    "boundaryStrength": 2,
                    "topics": ["disclosures"],
                },
            ]
        }
        partitions, validation = MODULE.materialize_partitions(
            sample, blocks, payload, 64_000
        )
        self.assertEqual(
            [(0, 64), (64, 128), (128, 192)],
            [
                (item["startBlockOrdinal"], item["endBlockOrdinalExclusive"])
                for item in partitions
            ],
        )
        self.assertEqual(1.0, validation["coverageRatio"])
        self.assertEqual(0, validation["gapCount"])
        self.assertEqual(0, validation["overlapCount"])
        self.assertFalse(validation["canonicalHeadingTreeChanged"])

    def test_rejects_model_start_outside_section(self):
        blocks = [block(index) for index in range(0, 130)]
        sample = {
            "sectionStartBlockOrdinal": 0,
            "sectionEndBlockOrdinalExclusive": 130,
        }
        payload = {
            "boundaryCandidates": [
                {
                    "title": "One",
                    "startBlockOrdinal": 1,
                    "boundaryStrength": 5,
                    "topics": [],
                },
                {
                    "title": "Two",
                    "startBlockOrdinal": 20,
                    "boundaryStrength": 4,
                    "topics": [],
                },
                {
                    "title": "Three",
                    "startBlockOrdinal": 40,
                    "boundaryStrength": 3,
                    "topics": [],
                },
                {
                    "title": "Four",
                    "startBlockOrdinal": 60,
                    "boundaryStrength": 2,
                    "topics": [],
                },
                {
                    "title": "Five",
                    "startBlockOrdinal": 80,
                    "boundaryStrength": 2,
                    "topics": [],
                },
                {
                    "title": "Six",
                    "startBlockOrdinal": 100,
                    "boundaryStrength": 2,
                    "topics": [],
                },
            ]
        }
        with self.assertRaises(MODULE.ValidationError):
            MODULE.materialize_partitions(sample, blocks, payload, 64_000)

    def test_candidate_density_is_guidance_when_final_ranges_are_valid(self):
        blocks = [block(index) for index in range(0, 128)]
        sample = {
            "sectionStartBlockOrdinal": 0,
            "sectionEndBlockOrdinalExclusive": 128,
        }
        payload = {
            "boundaryCandidates": [
                {
                    "title": "First topic",
                    "startBlockOrdinal": 0,
                    "boundaryStrength": 5,
                    "topics": ["first"],
                },
                {
                    "title": "Second topic",
                    "startBlockOrdinal": 64,
                    "boundaryStrength": 5,
                    "topics": ["second"],
                },
            ]
        }
        partitions, validation = MODULE.materialize_partitions(
            sample, blocks, payload, 64_000
        )
        self.assertEqual(2, len(partitions))
        self.assertEqual(0, validation["oversizedPartitionCount"])

    def test_dense_semantic_candidates_are_reduced_without_fixed_boundaries(self):
        blocks = [block(index) for index in range(0, 192)]
        sample = {
            "sectionStartBlockOrdinal": 0,
            "sectionEndBlockOrdinalExclusive": 192,
        }
        candidates = [
            {
                "title": f"Semantic topic {index}",
                "startBlockOrdinal": index,
                "boundaryStrength": 5 if index in {0, 64, 128} else 1,
                "topics": [f"topic-{index}"],
            }
            for index in range(0, 105)
        ]
        candidates.extend(
            {
                "title": f"Semantic topic {index}",
                "startBlockOrdinal": index,
                "boundaryStrength": 5 if index == 128 else 1,
                "topics": [f"topic-{index}"],
            }
            for index in range(105, 192)
        )
        partitions, validation = MODULE.materialize_partitions(
            sample, blocks, {"boundaryCandidates": candidates}, 64_000
        )
        self.assertEqual(
            [(0, 64), (64, 128), (128, 192)],
            [
                (item["startBlockOrdinal"], item["endBlockOrdinalExclusive"])
                for item in partitions
            ],
        )
        self.assertEqual(192, validation["boundaryCandidateCount"])
        self.assertEqual(1.0, validation["coverageRatio"])

    def test_rejects_unbounded_candidate_output(self):
        blocks = [block(index) for index in range(0, 257)]
        sample = {
            "sectionStartBlockOrdinal": 0,
            "sectionEndBlockOrdinalExclusive": 257,
        }
        candidates = [
            {
                "title": f"Topic {index}",
                "startBlockOrdinal": index,
                "boundaryStrength": 1,
                "topics": [],
            }
            for index in range(0, 257)
        ]
        with self.assertRaises(MODULE.ValidationError):
            MODULE.materialize_partitions(
                sample, blocks, {"boundaryCandidates": candidates}, 64_000
            )

    def test_parses_complete_canonical_jsonl(self):
        lines = [
            {"recordType": "header", "documentVersionId": 7},
            block(0, "alpha"),
            {"recordType": "heading", "nodeId": "7:h:root"},
            {"recordType": "footer", "blockCount": 1, "complete": True},
        ]
        parsed = MODULE.parse_canonical_jsonl(
            "\n".join(json.dumps(item) for item in lines)
        )
        self.assertEqual(7, parsed["header"]["documentVersionId"])
        self.assertEqual([0], [item["ordinal"] for item in parsed["blocks"]])

    def test_loads_only_retrieval_chat_settings(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "application-secrets.yml"
            path.write_text(
                """docquery:
  retrieval:
    chat-api-key: 'test-key-value'
    chat-base-url: https://example.invalid
    embedding-api-key: must-not-be-read
""",
                encoding="utf-8",
            )
            settings = MODULE.load_chat_settings(path)
            self.assertEqual("test-key-value", settings.api_key)
            self.assertEqual("https://example.invalid", settings.base_url)


if __name__ == "__main__":
    unittest.main()
