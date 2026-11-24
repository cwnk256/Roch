import tempfile
import unittest
from pathlib import Path

from trace_report_release_gate import check_strategy, require_local
from trace_report_release_score import aggregate, context_coverage, compare_revision_runs, passing_tests, score_decisions


class ReleaseGateTest(unittest.TestCase):
    def test_revision_comparison_requires_paired_trials_and_frozen_inputs(self):
        settings = {"variant": "full", "model": "model", "embedding": "embed", "goldHashes": {"real": "hash"}}
        rows = {f"real/full-document/{run}": {"status": "COMPLETED", "contextHits": 10,
                "factsPassed": 0, "decisionsPassed": 0, "seconds": 9, "invalidSupported": 0} for run in range(1, 4)}
        before = {"settings": settings, "measurements": rows}
        after = {"settings": {**settings, "variant": "full-v2"}, "measurements": {
            key: {**row, "factsPassed": 6} for key, row in rows.items()}}

        result = compare_revision_runs(before, after, set(rows), set())
        self.assertTrue(result["checks"]["sameInputsAndModels"])
        self.assertTrue(result["checks"]["allSelectedRunsPaired"])
        self.assertEqual([0, 0, 0], result["metrics"]["realGrounded"]["before"]["runs"])
        self.assertEqual(6, result["metrics"]["realGrounded"]["after"]["median"])

        after["settings"]["model"] = "different"
        after["measurements"].pop("real/full-document/2")
        result = compare_revision_runs(before, after, set(rows), set())
        self.assertFalse(result["checks"]["sameInputsAndModels"])
        self.assertFalse(result["checks"]["allSelectedRunsPaired"])
        self.assertIsNone(result["metrics"]["realGrounded"]["after"]["median"])

    def test_context_coverage_requires_the_frozen_page_and_exact_selected_prefix(self):
        sources = type("Sources", (), {"names": {"doc": "original.pdf"}, "chunks": {
            "chunk": {"documentId": "doc", "pageStart": 7, "content": "𠮷排名第二，影响度7.22分"},
        }})()
        facts = [{"document": "original.pdf", "anchor": "7.22分", "page": 7}]
        event = {"phase": "context", "selected": [{"chunkId": "chunk", "documentId": "doc", "characters": 9}]}
        self.assertIsNone(context_coverage(facts, [], sources))
        self.assertEqual(0, context_coverage(facts, [event], sources))
        event["selected"][0]["characters"] = len(sources.chunks["chunk"]["content"].encode("utf-16-le")) // 2
        self.assertEqual(1, context_coverage(facts, [event], sources))
        facts[0]["page"] = 8
        self.assertEqual(0, context_coverage(facts, [event], sources))

    def test_visible_anchors_do_not_score_an_unoffered_part_of_the_chunk(self):
        sources = type("Sources", (), {"names": {"doc": "original.pdf"}, "chunks": {
            "chunk": {"documentId": "doc", "pageStart": 7, "content": "𠮷分数7；名次1"},
        }})()
        facts = [{"document": "original.pdf", "anchor": anchor, "page": 7} for anchor in ("分数7", "名次1")]
        context = {"phase": "context", "selected": [{"chunkId": "chunk", "documentId": "doc", "characters": 9}]}
        anchor = {"phase": "anchors", "selected": [{"chunkId": "chunk", "documentId": "doc", "start": 6, "characters": 3}]}
        self.assertEqual(2, context_coverage(facts, [context, anchor], sources))
        self.assertEqual(1, context_coverage(facts, [context, anchor], sources, phase="anchors"))

    def test_comparison_requires_correct_reason_tied_to_both_grounded_facts(self):
        case = {"decisions": [{"dimension": "故障到场时限", "kind": "INCOMPARABLE", "facts": ["a", "b"]}]}
        conclusion = {"dimension": "故障到场时限", "kind": "INCOMPARABLE",
                      "claimIds": ["ca", "cb"], "text": "原文缺少明确比较对象"}
        matches = {"a": {"ca"}, "b": {"cb"}}
        matrix = {"conclusions": [conclusion]}
        score = score_decisions(case, matrix, {}, matches)[0]
        self.assertTrue(score["categoryCorrect"])
        self.assertFalse(score["reasonCorrect"])
        conclusion["text"] = "适用条件不同（工作日 / 节假日）"
        self.assertTrue(score_decisions(case, matrix, {}, matches)[0]["reasonCorrect"])
        self.assertFalse(score_decisions(case, matrix, {}, {"a": {"ca"}, "b": set()})[0]["reasonCorrect"])

    def test_context_for_one_question_batch_does_not_cover_another_batch(self):
        sources = type("Sources", (), {"names": {"doc": "original.txt"}, "chunks": {
            "chunk": {"documentId": "doc", "pageStart": None, "content": "onlyfifth"},
        }})()
        facts = [{"document": "original.txt", "anchor": str(index), "page": None} for index in range(4)]
        facts.append({"document": "original.txt", "anchor": "onlyfifth", "page": None})
        record = {"phase": "context", "section": 1, "batch": 1,
                  "selected": [{"chunkId": "chunk", "documentId": "doc", "characters": 9}]}
        self.assertEqual(0, context_coverage(facts, [record], sources, {"type": "REPORT"}, split=True))
        record["batch"] = 2
        self.assertEqual(1, context_coverage(facts, [record], sources, {"type": "REPORT"}, split=True))

    def test_missing_repetitions_remain_inconclusive_and_failed_tasks_count_as_zero(self):
        rows = {"real/full/1": {"factsPassed": 6}, "real/full/2": {"factsPassed": 0}}
        self.assertEqual({"runs": [6, 0, None], "median": None}, aggregate(rows, "real", {"full"}, "factsPassed"))
        rows["real/full/3"] = {"factsPassed": 7}
        self.assertEqual({"runs": [6, 0, 7], "median": 6}, aggregate(rows, "real", {"full"}, "factsPassed"))

    def test_strategy_mismatch_and_non_local_urls_are_rejected(self):
        self.assertEqual("http://[::1]:8080/api", require_local("http://[::1]:8080/api/"))
        with self.assertRaises(ValueError):
            require_local("http://localhost@external.example/api")
        check_strategy({}, "baseline")
        with self.assertRaises(ValueError):
            check_strategy({"configuration": {"retrieval": {"strategy": "hybrid-only"}}}, "full")

    def test_skipped_and_failed_fixed_cases_cannot_be_used_as_release_proof(self):
        with tempfile.TemporaryDirectory() as directory:
            report = Path(directory) / "TEST-ClaimVerifier.xml"
            report.write_text('<testsuite><testcase name="rejectsWrongRank()"/>'
                              '<testcase name="rejectsWrongUnit()"><skipped/></testcase>'
                              '<testcase name="rejectsWrongObject()"><failure/></testcase></testsuite>', encoding="utf-8")
            self.assertEqual({"rejectsWrongRank"}, passing_tests(report.parent))


if __name__ == "__main__":
    unittest.main()
