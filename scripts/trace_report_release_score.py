"""Independent source, context, and comparison scoring for the retrieval release gate."""

import json
import logging
import math
import re
import statistics
import unicodedata
from collections import Counter
from xml.etree import ElementTree

from trace_report_release_gate import ANCHORED_VARIANT, VARIANTS, load_gold, read_json, save_json, sha256


CORE = {"real": {"full-document"}, "long": {"equipment-report", "agreement-long", "equipment-compare"},
        "expanded": {"maintenance", "procurement", "service", "scope"}}
GUARDS = {"rejectsWrongRank", "rejectsWrongTableColumn", "rejectsWrongObject", "rejectsWrongUnit",
          "rejectsRecommendationAsRequirement"}
ANCHOR_GUARDS = {"exposesOnlyWorksheetCellsAndPersistsTheirPhysicalRange",
                 "anchoredFallbackRejectsOtherDimensionsAndInventedOriginalFields"}
STABILITY = {"invalidCitationsDowngradeOnlyTheirClaimAndQuestionOrderIsRestored",
             "malformedJsonIsRetriedOnceAndBatchesHaveDistinctClaimIds",
             "persistentTimeoutIsExposedAsABatchFailureAfterOneRetry",
             "hybridMode_keywordFails_degradesToVector", "hybridMode_vectorFails_degradesToKeyword"}
HISTORY = {"reexportsTheSameSectionsFromAnArchivedV1Result", "reExportUsesSameSnapshotsWithEitherCitationStyle",
           "sourceDeletionAndReindexDoNotRewriteGenerationSnapshot"}
REASON_CHECKS = {
    "POSSIBLE_CONFLICT": (r"固定.{0,8}(?:不一致|冲突)|下限高于上限",),
    "COMMON": (r"相同|(?<!不)一致|均记载|均须|分别记载",),
    "NOT_FOUND": (r"未找到.{0,6}(?:依据|证据)|证据不足|没有.{0,6}(?:依据|证据)",),
}
CONDITION_CHECKS = {"故障到场时限": (r"工作日", r"节假日"), "到场时限": (r"寒潮", r"常温")}
logging.getLogger("pypdf").setLevel(logging.ERROR)


def compact(text):
    return re.sub(r"[\s\x00]", "", unicodedata.normalize("NFKC", text or "")).casefold()


def excerpt(text):
    return compact(text).strip("。；;.")


def fact_proofs(fact):
    return fact.get("sources") or [{"page": fact["page"], "excerpt": fact["anchor"]}]


def proves(fact, page, content):
    return any((ref["page"] is None or ref["page"] == page) and excerpt(ref["excerpt"]) in excerpt(content)
               for ref in fact_proofs(fact))


def document_text(path):
    if path.suffix == ".pdf":
        from pypdf import PdfReader
        return [page.extract_text() or "" for page in PdfReader(path).pages]
    if path.suffix == ".docx":
        from docx import Document
        return ["\n".join(paragraph.text for paragraph in Document(path).paragraphs)]
    if path.suffix == ".xlsx":
        from openpyxl import load_workbook
        workbook = load_workbook(path, data_only=True, read_only=True)
        try:
            return ["".join(str(value) for value in row if value is not None)
                    for sheet in workbook for row in sheet.values]
        finally:
            workbook.close()
    return [path.read_text(encoding="utf-8-sig")]


class Sources:
    def __init__(self, documents, corpus, gold):
        self.names = {value["id"]: name for name, value in documents.items()}
        self.chunks = {chunk["chunkId"]: chunk for value in documents.values() for chunk in value["chunks"]}
        self.pages = {}
        for name, indexed in documents.items():
            path = corpus / "fixtures" / name
            if sha256(path.read_bytes()) != gold["sha256"][name] or indexed["sha256"] != gold["sha256"][name]:
                raise ValueError(f"Indexed input differs from frozen fixture: {name}")
            self.pages[name] = document_text(path)

    def locate(self, evidence, allowed):
        document = self.names.get(evidence.get("documentId"))
        chunk = self.chunks.get(evidence.get("chunkId"))
        quote = compact(evidence.get("quote"))
        if (evidence.get("sourceStatus") != "MATCHED" or not quote or document not in allowed
                or evidence.get("fileName") != document or not chunk or chunk["documentId"] != evidence["documentId"]
                or chunk["chunkIndex"] != evidence.get("chunkIndex") or quote not in compact(chunk["content"])):
            return None
        page = evidence.get("pageStart")
        blocks = self.pages[document]
        if document.endswith(".pdf"):
            if not isinstance(page, int) or not 1 <= page <= len(blocks) or page != evidence.get("pageEnd") or page != chunk.get("pageStart"):
                return None
            blocks = [blocks[page - 1]]
        if document.endswith(".xlsx"):
            quote = quote.replace("|", "")
        return document if any(quote in compact(block) for block in blocks) else None


def read_log(path):
    records = {}
    if path.exists():
        for line in path.read_text(encoding="utf-8-sig").splitlines():
            if line.strip():
                record = json.loads(line)
                if record.get("taskId") and record.get("phase") in ("context", "anchors", "retrieval", "generation"):
                    records.setdefault(record["taskId"], []).append(record)
    return records


def context_coverage(facts, records, sources, case=None, split=False, phase="context"):
    if not records or not any(record["phase"] == phase for record in records):
        return None
    contexts = []
    for record in records:
        if record["phase"] != phase:
            continue
        for selected in record["selected"]:
            chunk = sources.chunks.get(selected["chunkId"])
            if not chunk or chunk["documentId"] != selected["documentId"]:
                raise ValueError(f"Unknown context chunk in {record['taskId']}: {selected['chunkId']}")
            # Index offsets and lengths are expressed in UTF-16 code units.
            encoded = chunk["content"].encode("utf-16-le")
            start = selected.get("start", 0) * 2
            end = start + selected["characters"] * 2
            if start < 0 or end > len(encoded) or end < start:
                raise ValueError(f"Invalid context span in {record['taskId']}: {selected['chunkId']}")
            visible = encoded[start:end].decode("utf-16-le")
            contexts.append((record.get("section"), record.get("batch"), sources.names[chunk["documentId"]],
                             chunk.get("pageStart"), visible))
    found = 0
    for index, fact in enumerate(facts):
        expected_section = None
        expected_batch = None
        if case and case["type"] == "COMPARE":
            expected_section = case["dimensions"].index(fact["dimension"]) + 1
            expected_batch = case["documents"].index(fact["document"]) + 1
        elif split and len(facts) > 4:
            expected_batch = index // 4 + 1
        found += any(name == fact["document"] and proves(fact, page, content)
                     and (expected_section is None or section == expected_section)
                     and (expected_batch is None or batch == expected_batch)
                     for section, batch, name, page, content in contexts)
    return found


def claims_by_id(report):
    claims = [claim for section in report.get("sections", []) for claim in section.get("claims", [])]
    return {claim["claimId"]: claim for claim in claims + report.get("summaryClaims", [])}


def coordinates(matrix, sources):
    cells = {}
    facts = {}
    for row in matrix.get("rows", []):
        for cell in row["cells"]:
            key = (row["dimension"], sources.names[cell["documentId"]])
            cells[key] = cell
            for fact in cell["facts"]:
                facts[fact["claimId"]] = key, fact["verified"]
    return cells, facts


def claim_matches(fact, claim, snapshots, located, structured):
    claim_id = claim["claimId"]
    if claim["supportStatus"] != "SUPPORTED":
        return False
    if fact.get("dimension") and structured.get(claim_id) != ((fact["dimension"], fact["document"]), True):
        return False
    text = compact(claim["text"]).replace(",", "")
    if not all(re.search(term.replace(r"\\+", r"\+"), text) for term in fact["checks"]):
        return False
    return any(located.get(key) == fact["document"] and proves(fact, snapshots[key].get("pageStart"), snapshots[key]["quote"])
               for key in claim["evidenceIds"])


def reason_matches(expected, text):
    kind = expected["kind"]
    if kind == "INCOMPARABLE":
        checks = (r"条件.{0,8}不同|条件.{0,8}不一致", *CONDITION_CHECKS.get(expected["dimension"], ()))
    else:
        checks = REASON_CHECKS[kind]
    return all(re.search(pattern, compact(text)) for pattern in checks)


def score_decisions(case, matrix, cells, matches):
    structured = {fact["claimId"]: fact for cell in cells.values() for fact in cell["facts"]}

    def correct_reason(expected, item):
        if not reason_matches(expected, item["text"]):
            return False
        if expected["kind"] != "COMMON" or expected["dimension"] == "校准前断电":
            return True
        values = {(structured[key].get("normalizedValue"), structured[key].get("normalizedUnit"))
                  for key in item["claimIds"]}
        return len(values) == 1 and all(next(iter(values)))

    decisions = []
    for expected in case["decisions"]:
        dimension, kind = expected["dimension"], expected["kind"]
        if kind == "NOT_FOUND":
            cell = cells.get((dimension, expected["document"]), {})
            candidates = [cell] if cell else []
            category = cell.get("status") == kind and not cell.get("facts")
            reason = category and reason_matches(expected, cell.get("reason"))
        else:
            labels = [matches[label] for label in expected["facts"]]
            candidates = [item for item in matrix.get("conclusions", []) if item["dimension"] == dimension
                          and item["kind"] != "KEY_DATA" and all(choice.intersection(item["claimIds"]) for choice in labels)]
            category = {item["kind"] for item in candidates} == {kind}
            reason = category and all(correct_reason(expected, item) for item in candidates)
        decisions.append({"dimension": dimension, "expected": kind, "categoryCorrect": category,
                          "reasonCorrect": reason, "observed": candidates})
    return decisions


def measure(case, directory, sources, log, variant):
    outcome_path = directory / "outcome.json"
    if not outcome_path.exists():
        return None
    outcome = read_json(outcome_path)
    facts = case["facts"]
    events = log.get(outcome["taskId"], [])
    split = variant in ("question-split", "full", ANCHORED_VARIANT)
    retrieved_hits = context_coverage(facts, events, sources, case, split)
    visible_hits = (context_coverage(facts, events, sources, case, split, phase="anchors")
                    if variant == ANCHORED_VARIANT else retrieved_hits)
    result = {**outcome, "contextHits": visible_hits, "retrievedHits": retrieved_hits,
              "factsPassed": 0, "factsTotal": len(facts),
              "decisionsPassed": 0, "decisionsTotal": len(case["decisions"]), "invalidSupported": 0,
              "phaseOutcomes": dict(Counter(f"{event['phase']}/{event['outcome']}" for event in events))}
    if outcome["status"] != "COMPLETED":
        return result
    report = read_json(directory / "report.json")
    claims = claims_by_id(report)
    snapshots = {value["evidenceId"]: value for value in report["evidenceSnapshots"]}
    located = {key: sources.locate(evidence, set(case["documents"])) for key, evidence in snapshots.items()}
    matrix = report.get("comparison") or {}
    cells, structured = coordinates(matrix, sources)
    matches = {fact["id"]: {claim_id for claim_id, claim in claims.items()
                            if claim_matches(fact, claim, snapshots, located, structured)} for fact in facts}
    decisions = score_decisions(case, matrix, cells, matches)
    invalid = [claim["claimId"] for claim in claims.values() if claim["supportStatus"] == "SUPPORTED"
               and (not claim["evidenceIds"] or not all(located.get(key) for key in claim["evidenceIds"]))]
    # Known original-PDF regression where the row's score was cited but the rank was omitted or wrong.
    wrong_ecology_rank = re.compile(r"23\.10?分?\W*(?:排[名位]?第[2二]|位列第[2二])")
    invalid.extend(claim["claimId"] for claim in claims.values() if claim["supportStatus"] == "SUPPORTED"
                   and "中国" in claim["text"] and re.search(r"生态[与和]环境科学", claim["text"])
                   and wrong_ecology_rank.search(compact(claim["text"])))
    result.update(factsPassed=sum(bool(values) for values in matches.values()),
                  matchingClaims={key: sorted(values) for key, values in matches.items()},
                  decisionsPassed=sum(item["reasonCorrect"] for item in decisions), decisions=decisions,
                  citationsLocated=sum(value is not None for value in located.values()), citationsTotal=len(located),
                  invalidSupported=len(set(invalid)), invalidClaimIds=sorted(set(invalid)))
    return result


def passing_tests(path):
    if path is None:
        return set()
    files = sorted(path.glob("TEST-*.xml")) if path.is_dir() else [path]
    passed = set()
    for file in files:
        root = ElementTree.parse(file).getroot()
        for case in root.iter("testcase"):
            if not any(case.find(name) is not None for name in ("failure", "error", "skipped")):
                passed.add(case.attrib["name"].split("(", 1)[0])
    return passed


def percentile95(values):
    return sorted(values)[math.ceil(len(values) * .95) - 1] if values else None


def collect_variant(output, corpora, gold, variant):
    directory = output / variant
    metadata = directory / "run.json"
    if not metadata.exists():
        return None
    run = read_json(metadata)
    if run["variant"] != variant:
        raise ValueError(f"Incorrect variant in {metadata}")
    if run["goldHashes"] != {name: sha256((path / "gold.json").read_bytes()) for name, path in corpora.items()}:
        raise ValueError(f"Frozen corpus labels changed since {metadata} was written")
    observed = {}
    logs = read_log(directory / "retrieval.jsonl")
    for name, source_dir in corpora.items():
        registry = directory / name / "documents.json"
        if not registry.exists():
            continue
        sources = Sources(read_json(registry), source_dir, gold[name])
        for repetition in range(1, 4):
            for case in gold[name]["runs"]:
                result = measure(case, directory / name / str(repetition) / case["id"], sources, logs, variant)
                if result is not None:
                    observed[f"{name}/{case['id']}/{repetition}"] = result
    return {"settings": run, "measurements": observed, "failures": sum(r["status"] != "COMPLETED" for r in observed.values())}


def aggregate(measurements, corpus, cases, field):
    values = []
    for repetition in range(1, 4):
        sample = [measurements.get(f"{corpus}/{case}/{repetition}", {}).get(field) for case in cases]
        values.append(None if None in sample else sum(sample))
    return {"runs": values, "median": None if None in values else statistics.median(values)}


def compare_revision_runs(before, after, expected, short):
    before_rows = before["measurements"] if before else {}
    after_rows = after["measurements"] if after else {}
    settings = [{k: v for k, v in run["settings"].items() if k != "variant"} for run in (before, after) if run]
    paired = {key for key in short
              if before_rows.get(key, {}).get("status") == after_rows.get(key, {}).get("status") == "COMPLETED"
              and before_rows[key].get("timingReliable", True) and after_rows[key].get("timingReliable", True)}
    before_p95 = percentile95([before_rows[key]["seconds"] for key in paired])
    after_p95 = percentile95([after_rows[key]["seconds"] for key in paired])
    metrics = {}
    for name, corpus, field in (
        ("realRecall", "real", "contextHits"), ("realRetrieved", "real", "retrievedHits"),
        ("realGrounded", "real", "factsPassed"),
        ("longRecall", "long", "contextHits"), ("longRetrieved", "long", "retrievedHits"),
        ("longGrounded", "long", "factsPassed"),
        ("expandedRecall", "expanded", "contextHits"), ("expandedGrounded", "expanded", "factsPassed"),
        ("expandedDecisions", "expanded", "decisionsPassed"),
    ):
        metrics[name] = {
            "before": aggregate(before_rows, corpus, CORE[corpus], field),
            "after": aggregate(after_rows, corpus, CORE[corpus], field),
        }
    checks = {"sameInputsAndModels": len(settings) == 2 and settings[0] == settings[1],
              "allSelectedRunsPaired": expected.issubset(before_rows) and expected.issubset(after_rows),
              "shortTaskP95": paired == short and before_p95 is not None and after_p95 <= before_p95 * 2,
              "unsupportedClaims": bool(after_rows) and all(not row["invalidSupported"] for row in after_rows.values())}
    for name, minimum in {"realRecall": 8, "realGrounded": 6, "longRecall": 18,
                          "longGrounded": 12, "expandedDecisions": 4}.items():
        median = metrics[name]["after"]["median"]
        checks[name] = median is not None and median >= minimum
    return {"checks": checks, "metrics": metrics,
            "shortTaskP95": {"beforeSeconds": before_p95, "afterSeconds": after_p95,
                             "matchedSuccessful": len(paired), "expected": len(short)},
            "before": before, "after": after,
            "missingBefore": sorted(expected - before_rows.keys()), "missingAfter": sorted(expected - after_rows.keys())}


def summarize_revisions(args, corpora):
    gold = {name: load_gold(source) for name, source in corpora.items()}
    before = collect_variant(args.before_output, corpora, gold, "full")
    after = collect_variant(args.output, corpora, gold, ANCHORED_VARIANT)
    short = {f"expanded/{case['id']}/{rep}" for case in gold["expanded"]["runs"] for rep in range(1, 4)}
    expected = short | {f"{name}/{case}/{rep}" for name in ("real", "long") for case in CORE[name] for rep in range(1, 4)}
    result = compare_revision_runs(before, after, expected, short)
    proof = passing_tests(args.guardrail_proof)
    result["checks"].update(negativeFixtures=(GUARDS | ANCHOR_GUARDS).issubset(proof),
                            generationAndRetrievalFailures=STABILITY.issubset(proof),
                            legacyReadAndReexport=HISTORY.issubset(passing_tests(args.compatibility_proof)))
    result["passed"] = all(result["checks"].values())
    save_json(args.output / "revision-comparison.json", result)
    for name, values in result["metrics"].items():
        print(f"{name}: {values['before']['median']} -> {values['after']['median']}")
    for name, passed in result["checks"].items():
        print(f"{name}: {'PASS' if passed else 'FAIL OR INCOMPLETE'}")
    return 0 if result["passed"] else 1


def summarize(args, corpora):
    gold = {name: load_gold(path) for name, path in corpora.items()}
    variants = {variant: collect_variant(args.output, corpora, gold, variant) for variant in VARIANTS}
    for result in variants.values():
        if result is None:
            continue
        runs = result["measurements"]
        result["medianMetrics"] = {
            key: aggregate(runs, corpus, CORE[corpus], field) for key, corpus, field in (
                ("realRecall", "real", "contextHits"), ("realGrounded", "real", "factsPassed"),
                ("longRecall", "long", "contextHits"), ("longGrounded", "long", "factsPassed"),
                ("expandedDecisions", "expanded", "decisionsPassed"))}
        result["caseMedians"] = {
            f"{name}/{case['id']}": {
                field: aggregate(runs, name, {case["id"]}, field)
                for field in ("contextHits", "factsPassed", "decisionsPassed", "seconds")
            }
            for name, labels in gold.items() for case in labels["runs"]
        }
        stages = Counter()
        for row in runs.values():
            stages.update(row["phaseOutcomes"])
        result["phaseOutcomes"] = dict(stages)
    full = variants["full"]
    baseline = variants["baseline"]
    full_runs = full["measurements"] if full else {}
    base_runs = baseline["measurements"] if baseline else {}
    expected_cases = {f"{name}/{case['id']}/{repetition}" for name, labels in gold.items()
                      for case in labels["runs"] for repetition in range(1, 4)}
    short = {key for key in expected_cases if key.startswith("expanded/")}
    successful = {key for key in short if base_runs.get(key, {}).get("status") == full_runs.get(key, {}).get("status") == "COMPLETED"
                  and base_runs[key].get("timingReliable", True) and full_runs[key].get("timingReliable", True)}
    base_p95 = percentile95([base_runs[key]["seconds"] for key in successful])
    full_p95 = percentile95([full_runs[key]["seconds"] for key in successful])
    same_inputs = [{key: value for key, value in item["settings"].items() if key != "variant"}
                   for item in variants.values() if item is not None]
    proof = passing_tests(args.guardrail_proof)
    checks = {
        "allVariantsRanThreeTimes": all(v is not None and set(v["measurements"]) == expected_cases for v in variants.values()),
        "sameInputsAndModels": len(same_inputs) == len(VARIANTS) and all(item == same_inputs[0] for item in same_inputs),
        "shortTaskP95": successful == short and base_p95 is not None and full_p95 <= base_p95 * 2,
        "unsupportedClaims": bool(full_runs) and all(not row["invalidSupported"] for row in full_runs.values()),
        "negativeFixtures": GUARDS.issubset(proof),
        "generationAndRetrievalFailures": STABILITY.issubset(proof),
        "legacyReadAndReexport": HISTORY.issubset(passing_tests(args.compatibility_proof)),
    }
    for key, target in {"realRecall": 8, "realGrounded": 6, "longRecall": 18,
                        "longGrounded": 12, "expandedDecisions": 4}.items():
        median = full["medianMetrics"][key]["median"] if full else None
        checks[key] = median is not None and median >= target
    result = {"passed": all(checks.values()), "checks": checks, "variants": variants,
              "shortTaskP95": {"baselineSeconds": base_p95, "fullSeconds": full_p95,
                               "matchedSuccessful": len(successful), "expected": len(short)}}
    save_json(args.output / "summary.json", result)
    for name, variant in variants.items():
        if variant:
            medians = ", ".join(f"{key}={value['median']}" for key, value in variant["medianMetrics"].items())
            print(f"{name}: {len(variant['measurements'])} observed tasks, {variant['failures']} failed, {medians}")
    for check, passed in checks.items():
        print(f"{check}: {'PASS' if passed else 'FAIL OR INCOMPLETE'}")
    return 0 if result["passed"] else 1
