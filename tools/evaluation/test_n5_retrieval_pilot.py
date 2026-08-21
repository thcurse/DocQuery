import unittest

import run_n5_retrieval_pilot as pilot


def case() -> dict:
    return {
        "relevance": [{
            "documentKey": "gold-document",
            "evidencePages": [2, 5],
        }]
    }


def result(rank: int, document: str, *pages: int) -> dict:
    return {
        "rank": rank,
        "documentKey": document,
        "evidence": [{"pageNumber": page} for page in pages],
    }


class N5RetrievalPilotMetricTest(unittest.TestCase):

    def test_primary_metrics_use_document_rank_and_any_gold_page(self) -> None:
        metrics = pilot.evaluate(case(), [
            result(1, "other", 2),
            result(2, "gold-document", 5),
        ])

        self.assertEqual(0.0, metrics["documentRecallAt1"])
        self.assertEqual(1.0, metrics["documentRecallAt5"])
        self.assertEqual(1.0, metrics["evidencePageRecallAt10"])
        self.assertEqual(0.5, metrics["evidencePageCoverageAt10"])
        self.assertEqual(0.0, metrics["allEvidencePagesHitAt10"])
        self.assertEqual(0.5, metrics["reciprocalRankAt10"])

    def test_page_from_wrong_document_does_not_count(self) -> None:
        metrics = pilot.evaluate(case(), [result(1, "other", 2, 5)])

        self.assertEqual(0.0, metrics["documentRecallAt5"])
        self.assertEqual(0.0, metrics["evidencePageRecallAt10"])
        self.assertEqual(0.0, metrics["evidencePageCoverageAt10"])
        self.assertEqual(0.0, metrics["reciprocalRankAt10"])

    def test_all_page_coverage_is_supplementary(self) -> None:
        metrics = pilot.evaluate(case(), [
            result(1, "gold-document", 2),
            result(3, "gold-document", 5),
        ])

        self.assertEqual(1.0, metrics["evidencePageRecallAt10"])
        self.assertEqual(1.0, metrics["evidencePageCoverageAt10"])
        self.assertEqual(1.0, metrics["allEvidencePagesHitAt10"])

    def test_hybrid_degradation_is_not_counted_as_normal_hybrid_quality(self) -> None:
        metrics = pilot.evaluate(case(), [result(1, "gold-document", 2)])
        rows = [{
            "mode": "HYBRID",
            "http": {"ok": True, "attemptCount": 1, "latencyMs": 10.0},
            "response": {"executedMode": "KEYWORD", "degraded": True},
            "metrics": metrics,
        }]

        aggregate = pilot.aggregate(rows)["HYBRID"]

        self.assertEqual(1, aggregate["degradedResponses"])
        self.assertEqual(0, aggregate["qualityPopulation"])
        self.assertIsNone(aggregate["documentRecallAt5"])


if __name__ == "__main__":
    unittest.main()
