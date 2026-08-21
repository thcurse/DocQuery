"""Deterministic metric and contract tests for the N3 evaluation scripts."""

from __future__ import annotations

import unittest
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from n3_eval_common import latency_summary, normalize_text, percentile
from run_n3_eval import evaluate_answer, evaluate_retrieve


class EvaluationMetricTest(unittest.TestCase):

    def test_recall_mrr_ndcg_and_anchor_recall(self):
        case = {
            "answerability": "ANSWERABLE",
            "relevance": [
                {"documentKey": "doc-a", "anchorTexts": ["alpha evidence"]},
                {"documentKey": "doc-b", "anchorTexts": ["beta evidence"]},
            ],
        }
        ranked = [
            {"rank": 1, "documentKey": "noise", "evidenceTexts": ["noise"]},
            {"rank": 2, "documentKey": "doc-a", "evidenceTexts": ["Alpha   evidence"]},
            {"rank": 3, "documentKey": "doc-b", "evidenceTexts": ["beta evidence"]},
        ]

        metrics = evaluate_retrieve(case, ranked)

        self.assertEqual(1.0, metrics["documentRecallAt5"])
        self.assertEqual(1.0, metrics["anchorRecallAt5"])
        self.assertEqual(0.5, metrics["reciprocalRankAt10"])
        self.assertAlmostEqual(0.6934264, metrics["ndcgAt10"], places=6)

    def test_unanswerable_metrics_are_not_fabricated(self):
        metrics = evaluate_retrieve(
            {"answerability": "NOT_ANSWERABLE", "relevance": []},
            [{"rank": 1, "documentKey": "noise", "evidenceTexts": ["noise"]}],
        )
        self.assertIsNone(metrics["documentRecallAt5"])
        self.assertIsNone(metrics["anchorRecallAt5"])
        self.assertIsNone(metrics["reciprocalRankAt10"])
        self.assertIsNone(metrics["ndcgAt10"])

    def test_controlled_refusal_rejects_fabricated_citations(self):
        case = {
            "answerability": "NOT_ANSWERABLE",
            "relevance": [],
            "requiredClaims": [],
        }
        assessment = evaluate_answer(
            case,
            {"status": "INSUFFICIENT_EVIDENCE", "citations": []},
            {},
        )
        self.assertTrue(assessment["statusCorrect"])
        self.assertFalse(assessment["fabricatedCitationOnRefusal"])

    def test_nearest_rank_percentiles_are_stable(self):
        self.assertEqual(4, percentile([1, 2, 3, 4], 0.95))
        self.assertEqual(
            {"count": 4, "p50Ms": 2, "p95Ms": 4, "p99Ms": 4,
             "minMs": 1, "maxMs": 4, "meanMs": 2.5},
            latency_summary([1, 2, 3, 4]),
        )
        self.assertEqual("abc", normalize_text(" A \n B\tC "))


if __name__ == "__main__":
    unittest.main()
