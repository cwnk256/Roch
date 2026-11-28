import io
import tempfile
import unittest
from contextlib import redirect_stdout
from importlib.util import find_spec
from pathlib import Path
from types import SimpleNamespace

from trace_report_release_gate import check_strategy, parse_corpora, read_json, require_local, save_json, sha256
from trace_report_release_score import (Sources, aggregate, context_coverage, compare_revision_runs, measure,
                                        passing_tests, score_decisions, summarize_revisions)


class ReleaseGateTest(unittest.TestCase):
    def test_expanded_gate_scores_counts_and_pools_emitted_citations_in_each_trial(self):
        before = {"settings": {"variant": "full-v2"}, "measurements": {}}
        after = {"settings": before["settings"], "measurements": {}}
        expected = set()
        for repetition in range(1, 4):
            for case, total, passed, decisions, reason in (
                ("warehouse", 4, 3, 0, 0), ("backup", 4, 3, 0, 0), ("notice", 4, 3, 0, 0),
                ("maintenance", 4, 3, 2, 1), ("procurement", 4, 3, 2, 1),
                ("service", 4, 3, 2, 1), ("scope", 1, 1, 1, 1),
            ):
                key = f"expanded/{case}/{repetition}"
                expected.add(key)
                row = {"status": "COMPLETED", "factsTotal": total, "factsPassed": passed,
                       "decisionsTotal": decisions, "decisionCategoriesPassed": decisions, "decisionsPassed": reason,
                       "seconds": 1, "invalidSupported": 0, "citationsLocated": 19 if case == "warehouse" else 0,
                       "citationsTotal": 19 if case == "warehouse" else int(case == "backup")}
                before["measurements"][key] = row.copy()
                after["measurements"][key] = row
        result = compare_revision_runs(before, after, expected, expected)
        self.assertEqual(9, result["metrics"]["expandedReportGrounded"]["after"]["median"])
        self.assertEqual(10, result["metrics"]["expandedCompareGrounded"]["after"]["median"])
        self.assertEqual(1, result["metrics"]["expandedDecisionCategories"]["after"]["medianRate"])
        self.assertTrue(result["checks"]["expandedCitationLocation"])
        self.assertTrue(result["checks"]["allSelectedRunsPaired"])

        # A failing or malformed response must not pass on document counts or on other trials.
        row = after["measurements"]["expanded/warehouse/1"]
        row["status"] = "FAILED"
        self.assertFalse(compare_revision_runs(before, after, expected, expected)["checks"]["allSelectedRunsPaired"])
        row["status"] = "COMPLETED"
        row["factsPassed"] = 9
        self.assertFalse(compare_revision_runs(before, after, expected, expected)["checks"]["expandedReportGrounded"])
        row["factsPassed"] = 3
        row["citationsLocated"] = 18
        self.assertFalse(compare_revision_runs(before, after, expected, expected)["checks"]["expandedCitationLocation"])
        row["citationsLocated"] = row["citationsTotal"] = 0
        after["measurements"]["expanded/backup/1"]["citationsTotal"] = 0
        self.assertFalse(compare_revision_runs(before, after, expected, expected)["checks"]["expandedCitationLocation"])

        # The threshold is weighted by citations across all repetitions, including a below-threshold trial.
        for repetition, located, total in ((1, 9, 10), (2, 20, 20), (3, 20, 20)):
            after["measurements"][f"expanded/warehouse/{repetition}"].update(
                citationsLocated=located, citationsTotal=total)
            after["measurements"][f"expanded/backup/{repetition}"]["citationsTotal"] = 0
        pooled = compare_revision_runs(before, after, expected, expected)
        self.assertTrue(pooled["checks"]["expandedCitationLocation"])
        citations = pooled["metrics"]["expandedCitationLocation"]["after"]
        self.assertEqual((49, 50, 0.98), (citations["pooledLocated"], citations["pooledTotal"], citations["pooledRate"]))

    def test_scope_not_found_requires_explicit_exclusion_and_rejects_failure_codes(self):
        case = {"id": "scope", "decisions": [{"dimension": "周期", "kind": "NOT_FOUND", "document": "b"}]}
        cell = {"status": "NOT_FOUND", "facts": [], "reason": "本次未找到依据"}
        cells = {("周期", "b"): cell}
        decision = score_decisions(case, {}, cells, {})[0]
        self.assertTrue(decision["categoryCorrect"])
        self.assertFalse(decision["reasonCorrect"])
        cell.update(reason="原文中未找到符合范围的事实", reasonCode="OUTSIDE_SCOPE")
        self.assertFalse(score_decisions(case, {}, cells, {})[0]["reasonCorrect"])
        cell["reason"] = "Z8 货梯被排除。"
        self.assertFalse(score_decisions(case, {}, cells, {})[0]["reasonCorrect"])
        cell["reason"] = "请求没有排除 Z9 货梯。"
        self.assertFalse(score_decisions(case, {}, cells, {})[0]["reasonCorrect"])
        cell["reason"] = "原文中的事实适用于请求明确排除的「Z9 货梯」。"
        self.assertTrue(score_decisions(case, {}, cells, {})[0]["reasonCorrect"])
        cell["reasonCode"] = "RETRIEVAL_ERROR"
        self.assertFalse(score_decisions(case, {}, cells, {})[0]["reasonCorrect"])
        cell.update(status="EXTRACTION_FAILED", reasonCode="OUTSIDE_SCOPE")
        self.assertFalse(score_decisions(case, {}, cells, {})[0]["categoryCorrect"])

    def test_exam_coverage_is_required_for_each_repetition_when_selected(self):
        self.assertEqual(Path("exam-fixtures"), parse_corpora(["exam-check=exam-fixtures"])["exam-check"])
        expected = {f"exam-check/exam-check/{repetition}" for repetition in range(1, 4)}
        rows = {key: {"status": "COMPLETED", "factsPassed": 8, "factsTotal": 8, "contextHits": 8, "numberedAnchors": True,
                      "invalidSupported": 0} for key in expected}
        run = {"settings": {}, "measurements": rows}
        self.assertTrue(compare_revision_runs(run, run, expected, set())["checks"]["examCheckCoverage"])
        rows["exam-check/exam-check/2"]["numberedAnchors"] = False
        self.assertFalse(compare_revision_runs(run, run, expected, set())["checks"]["examQuestionAnchors"])
        rows["exam-check/exam-check/2"]["numberedAnchors"] = True
        self.assertTrue(compare_revision_runs(run, run, expected, set())["checks"]["examQuestionAnchors"])
        rows["exam-check/exam-check/2"]["factsPassed"] = 7
        self.assertTrue(compare_revision_runs(run, run, expected, set())["checks"]["examCheckCoverage"])
        rows["exam-check/exam-check/2"]["contextHits"] = 7
        self.assertFalse(compare_revision_runs(run, run, expected, set())["checks"]["examCheckCoverage"])

    def test_completed_marker_without_v2_response_cannot_be_scored(self):
        case = {"facts": [], "decisions": []}
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory)
            save_json(path / "outcome.json", {"status": "COMPLETED", "taskId": "a"})
            self.assertIsNone(measure(case, path, None, {}, "full-v2"))
            save_json(path / "report.json", {"schemaVersion": "v1"})
            self.assertIsNone(measure(case, path, None, {}, "full-v2"))

    def test_citation_totals_count_emitted_quotes_and_missing_supported_quotes_fail(self):
        case = {"facts": [], "decisions": [], "documents": ["original.txt"]}
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory)
            fixtures = path / "fixtures"
            fixtures.mkdir()
            original = fixtures / "original.txt"
            original.write_text("Published exact quote.", encoding="utf-8")
            digest = sha256(original.read_bytes())
            sources = Sources({original.name: {"id": "doc", "sha256": digest, "chunks": [{"chunkId": "chunk",
                              "chunkIndex": 0, "documentId": "doc", "content": "Published exact quote."}]}},
                              path, {"sha256": {original.name: digest}})
            save_json(path / "outcome.json", {"status": "COMPLETED", "taskId": "a"})
            citation = {"sourceStatus": "MATCHED", "fileName": original.name, "documentId": "doc",
                        "chunkId": "chunk", "chunkIndex": 0}
            save_json(path / "report.json", {"schemaVersion": "v2", "evidenceSnapshots": [
                {**citation, "evidenceId": "actual", "quote": "Published exact quote."},
                {**citation, "evidenceId": "fabricated", "quote": "Not present."},
                {**citation, "evidenceId": "diagnostic", "quote": None, "attemptedQuote": "Not emitted."}],
                "sections": [{"claims": [{"claimId": "claim", "supportStatus": "SUPPORTED", "text": "Test claim",
                                           "evidenceIds": ["actual", "diagnostic"]}]}]})
            measurement = measure(case, path, sources, {}, "full-v2")
            self.assertEqual((1, 2), (measurement["citationsLocated"], measurement["citationsTotal"]))
            self.assertEqual(1, measurement["invalidSupported"])

    @unittest.skipUnless(find_spec("openpyxl"), "independent worksheet scoring requires openpyxl")
    def test_worksheet_quote_requires_the_exact_physical_sheet_and_range_when_provided(self):
        from openpyxl import Workbook

        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory)
            fixtures = path / "fixtures"
            fixtures.mkdir()
            original = fixtures / "budget.xlsx"
            workbook = Workbook()
            workbook.active.title = "采购"
            workbook.active.append(["科目", "金额"])
            workbook.active.append(["电脑", "3000元"])
            workbook.active.append(["配件", "500元"])
            other = workbook.create_sheet("其他")
            other.append(["科目", "金额"])
            other.append(["电脑", "3000元"])
            workbook.save(original)
            workbook.close()
            digest = sha256(original.read_bytes())
            quote = "| 电脑 | 3000元 |"
            sources = Sources({original.name: {"id": "doc", "sha256": digest, "chunks": [{"chunkId": "chunk",
                              "chunkIndex": 0, "documentId": "doc", "section": "采购", "content": quote}]}},
                              path, {"sha256": {original.name: digest}})
            evidence = {"sourceStatus": "MATCHED", "fileName": original.name, "documentId": "doc",
                        "chunkId": "chunk", "chunkIndex": 0, "quote": quote}
            self.assertEqual(original.name, sources.locate(evidence, {original.name}))
            self.assertEqual(original.name, sources.locate({**evidence, "nativeLocation": "采购!A2:B2"}, {original.name}))
            for bad_location in ("采购!A3:B3", "采购!A2:A2", "其他!A2:B2", "文件某处", 2):
                self.assertIsNone(sources.locate({**evidence, "nativeLocation": bad_location}, {original.name}))

    def test_pdf_quote_verifies_saved_chunk_hash_page_and_utf16_span_size(self):
        sources = Sources.__new__(Sources)
        document = "ranks.pdf"
        quote = "𠮷排名第二"
        content = f"章节记录：{quote}。"
        sources.names = {"doc": document}
        sources.pages = {document: [content, content]}
        sources.chunks = {"chunk": {"documentId": "doc", "chunkIndex": 3, "pageStart": 2, "pageEnd": 2,
                                    "content": content}}
        evidence = {"sourceStatus": "MATCHED", "fileName": document, "documentId": "doc", "chunkId": "chunk",
                    "chunkIndex": 3, "quote": quote, "textHash": sha256(content.encode()),
                    "pageStart": 2, "pageEnd": 2, "nativeLocation": "PDF 页内字符 [4,10)（表12）"}
        self.assertEqual(document, sources.locate(evidence, {document}))
        for invalid in ({"nativeLocation": "PDF 页内字符 [4,9)"}, {"nativeLocation": "bad"}, {"textHash": "bad"},
                        {"pageStart": 1, "pageEnd": 1}):
            self.assertIsNone(sources.locate({**evidence, **invalid}, {document}))

    def test_archived_timing_gate_and_current_revision_timings_are_independent(self):
        key = "expanded/warehouse/1"
        rows = {"measurements": {key: {"status": "COMPLETED", "seconds": 2}}, "settings": {}}
        candidate = {"measurements": {key: {"status": "COMPLETED", "seconds": 3}}, "settings": {}}
        archived = {"measurements": {key: {"status": "COMPLETED", "seconds": 1}}}
        comparison = compare_revision_runs(rows, candidate, {key}, {key}, archived)
        self.assertEqual(2, comparison["revisionShortTaskP95"]["beforeSeconds"])
        self.assertEqual(1, comparison["shortTaskP95"]["beforeSeconds"])
        self.assertFalse(comparison["checks"]["shortTaskP95"])
        self.assertFalse(compare_revision_runs(rows, candidate, {key}, {key}, {})["checks"]["shortTaskP95"])

    def test_same_strategy_requires_exam_and_archived_timing_but_historical_mode_allows_omission(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory)
            corpora = {}
            for name in ("expanded", "real", "long", "exam-check"):
                corpus = path / name
                corpus.mkdir()
                gold = corpus / "gold.json"
                save_json(gold, {"runs": [], "sha256": {}})
                (corpus / "gold.sha256").write_text(sha256(gold.read_bytes()), encoding="utf-8")
                if name != "exam-check":
                    corpora[name] = corpus
            args = SimpleNamespace(output=path, before_output=path / "before", before_variant="full-v2",
                                   latency_reference_output=None, guardrail_proof=None, compatibility_proof=None)
            for output in (args.output, args.before_output):
                metadata = output / "full-v2" / "run.json"
                metadata.parent.mkdir(parents=True)
                gold_hashes = {name: sha256((path / name / "gold.json").read_bytes())
                               for name in (*corpora, "exam-check")}
                save_json(metadata, {"variant": "full-v2", "goldHashes": gold_hashes})

            with redirect_stdout(io.StringIO()):
                self.assertEqual(1, summarize_revisions(args, corpora))
            checks = read_json(path / "revision-comparison.json")["checks"]
            self.assertFalse(checks["examCorpusConfigured"])
            self.assertFalse(checks["archivedLatencyConfigured"])

            args.latency_reference_output = path / "archive"
            with redirect_stdout(io.StringIO()):
                self.assertEqual(1, summarize_revisions(args, {**corpora, "exam-check": path / "exam-check"}))
            checks = read_json(path / "revision-comparison.json")["checks"]
            self.assertTrue(checks["examCorpusConfigured"])
            self.assertTrue(checks["archivedLatencyConfigured"])
            self.assertFalse(checks["archivedLatencyModels"])

            args.before_variant = "full"
            args.latency_reference_output = None
            (args.output / "full-v2" / "run.json").unlink()
            with redirect_stdout(io.StringIO()):
                summarize_revisions(args, corpora)
            historical = read_json(path / "revision-comparison.json")
            self.assertNotIn("examCorpusConfigured", historical["checks"])
            self.assertNotIn("archivedLatencyConfigured", historical["checks"])
            self.assertEqual("full", historical["shortTaskP95"]["referenceVariant"])

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

    def test_visible_anchors_for_another_question_do_not_count(self):
        sources = type("Sources", (), {"names": {"doc": "original.txt"}, "chunks": {
            "chunk": {"documentId": "doc", "pageStart": None, "content": "target"},
        }})()
        facts = [{"document": "original.txt", "anchor": "target", "page": None}]
        span = {"chunkId": "chunk", "documentId": "doc", "characters": 6, "question": 2}
        record = {"phase": "anchors", "section": 1, "batch": 1, "selected": [span]}
        case = {"type": "REPORT"}
        self.assertEqual(0, context_coverage(facts, [record], sources, case, phase="anchors"))
        span["question"] = 1
        self.assertEqual(1, context_coverage(facts, [record], sources, case, phase="anchors"))
        facts = [{**facts[0], "anchor": "absent"}] * 4 + facts
        record["batch"] = 2
        self.assertEqual(1, context_coverage(facts, [record], sources, case, split=True, phase="anchors"))

    def test_latest_scoped_anchor_attempt_replaces_initial_but_legacy_attempts_keep_union(self):
        sources = type("Sources", (), {"names": {"doc": "original.txt"}, "chunks": {
            "chunk": {"documentId": "doc", "pageStart": None, "content": "target secondary"},
        }})()
        facts = [{"document": "original.txt", "anchor": text, "page": None} for text in ("target", "secondary")]
        template = {"phase": "anchors", "section": 1, "batch": 1}
        first = {"chunkId": "chunk", "documentId": "doc", "start": 0, "characters": 6, "question": 1}
        second = {"chunkId": "chunk", "documentId": "doc", "start": 7, "characters": 9, "question": 2}
        initial = {**template, "selected": [first, second]}
        retry = {**template, "selected": [second]}
        case = {"type": "REPORT"}
        self.assertEqual(2, context_coverage(facts, [initial], sources, case, phase="anchors"))
        self.assertEqual(1, context_coverage(facts, [initial, retry], sources, case, phase="anchors"))
        self.assertEqual(0, context_coverage(facts, [initial, {**retry, "selected": []}], sources, case, phase="anchors"))
        del first["question"], second["question"]
        self.assertEqual(2, context_coverage(facts, [initial, retry], sources, case, phase="anchors"))

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
        missing_fact = score_decisions(case, matrix, {}, {"a": {"ca"}, "b": set()})[0]
        self.assertTrue(missing_fact["categoryCorrect"])
        self.assertFalse(missing_fact["reasonCorrect"])

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
