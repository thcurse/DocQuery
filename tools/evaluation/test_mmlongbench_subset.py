import unittest

import build_mmlongbench_subset as subset


def source_row(
    index: int,
    domain: str,
    answerable: bool,
    document: int,
) -> subset.SourceRow:
    return subset.SourceRow(
        index=index,
        doc_id=f"{domain[:3]}-{document:03d}.pdf",
        doc_type=domain,
        question=f"question {index}",
        answer=f"answer {index}" if answerable else subset.NOT_ANSWERABLE,
        evidence_pages=(1,) if answerable else (),
        evidence_sources=(subset.PURE_TEXT_SOURCE,) if answerable else (),
        answer_format="Str" if answerable else None,
    )


class MMLongBenchSubsetTest(unittest.TestCase):

    def test_none_answer_format_is_normalized(self) -> None:
        row = subset.source_row({
            "sourceRowIndex": 7,
            "doc_id": "document.pdf",
            "doc_type": "Guidebook",
            "question": "question",
            "answer": subset.NOT_ANSWERABLE,
            "evidence_pages": "[]",
            "evidence_sources": "[]",
            "answer_format": "None",
        })

        self.assertIsNone(row.answer_format)
        self.assertTrue(subset.is_unanswerable_candidate(row))
        case = subset.build_case(row)
        self.assertEqual("NONE", case["questionType"])
        self.assertIsNone(case["answerFormat"])

    def test_selection_is_deterministic_and_honors_domain_quotas(self) -> None:
        rows: list[subset.SourceRow] = []
        index = 0
        for domain in subset.DOMAIN_ORDER:
            answerable_quota = subset.ANSWERABLE_QUOTAS[domain]
            unanswerable_quota = subset.UNANSWERABLE_QUOTAS[domain]
            for local_index in range(answerable_quota + 2):
                rows.append(source_row(index, domain, True, local_index))
                index += 1
            for local_index in range(unanswerable_quota + 2):
                rows.append(source_row(index, domain, False, 100 + local_index))
                index += 1

        first = subset.select_cases(rows)
        second = subset.select_cases(list(reversed(rows)))

        self.assertEqual([row.case_id for row in first], [row.case_id for row in second])
        self.assertEqual(80, len(first))
        for domain in subset.DOMAIN_ORDER:
            domain_rows = [row for row in first if row.doc_type == domain]
            self.assertEqual(
                subset.ANSWERABLE_QUOTAS[domain],
                sum(subset.is_answerable_candidate(row) for row in domain_rows),
            )
            self.assertEqual(
                subset.UNANSWERABLE_QUOTAS[domain],
                sum(subset.is_unanswerable_candidate(row) for row in domain_rows),
            )

    def test_selection_rejects_a_short_domain(self) -> None:
        with self.assertRaisesRegex(ValueError, "insufficient answerable"):
            subset.select_cases([])


if __name__ == "__main__":
    unittest.main()
