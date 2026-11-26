"""Independent source, context, and comparison scoring for the retrieval release gate."""

import json
import logging
import math
import re
import statistics
import unicodedata
from collections import Counter
from datetime import date
from xml.etree import ElementTree

from trace_report_release_gate import ANCHORED_VARIANT, EXAM_CORPUS, VARIANTS, load_gold, read_json, save_json, sha256


EXPANDED_REPORTS = {"warehouse": 4, "backup": 4, "notice": 4}
EXPANDED_COMPARISONS = {"maintenance": (4, 2), "procurement": (4, 2), "service": (4, 2), "scope": (1, 1)}
EXPANDED_COUNTS = {**{case: (total, 0) for case, total in EXPANDED_REPORTS.items()}, **EXPANDED_COMPARISONS}
CORE = {"real": {"full-document"}, "long": {"equipment-report", "agreement-long", "equipment-compare"},
        "expanded": set(EXPANDED_COUNTS)}
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
SCOPE_EXCLUSION = {equipment: re.compile(
    rf'排除(?:的)?[「『“"(]*{equipment}(?![a-z0-9_-])|'
    rf'{equipment}(?![a-z0-9_-])(?:号)?(?:货梯)?[」』”")]*,?'
    r'(?:被(?:明确)?排除|(?:不在|不属于|超出)[^,。；;]{0,12}范围)') for equipment in ("z8", "z9")}
DENIED_SCOPE_EXCLUSION = re.compile(r'(?:没有|并未|未|不得|不应当?|不能|无须)(?:明确)?排除(?:的)?[「『“"(]*z9(?![a-z0-9_-])')
FAILED_COMPARISON = re.compile(r"(?:检索|提取).{0,8}(?:失败|超时)|未能完成.{0,8}(?:检索|提取)")
NATIVE_TEXT_SPAN = re.compile(r"(PDF 页内字符|原文字符) \[([0-9]+),([0-9]+)\)(?:（表[0-9]+）)?")
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
    return [path.read_bytes().decode("utf-8")]


def worksheet_rows(path):
    from openpyxl import load_workbook
    from openpyxl.utils import get_column_letter

    def cell_text(value):
        if value is None:
            return ""
        if isinstance(value, date):
            return value.isoformat()[:10]
        if isinstance(value, float) and value.is_integer():
            value = int(value)
        return str(value).strip().replace("|", "｜").replace("\n", " ")

    workbook = load_workbook(path, data_only=True, read_only=True)
    try:
        return {f"{sheet.title}!A{number}:{get_column_letter(len(row))}{number}":
                "| " + " | ".join(cell_text(value) for value in row) + " |"
                for sheet in workbook for number, row in enumerate(sheet.values, start=1) if row}
    finally:
        workbook.close()


class Sources:
    def __init__(self, documents, corpus, gold):
        self.names = {value["id"]: name for name, value in documents.items()}
        self.chunks = {chunk["chunkId"]: chunk for value in documents.values() for chunk in value["chunks"]}
        self.pages = {}
        self.worksheets = {}
        for name, indexed in documents.items():
            path = corpus / "fixtures" / name
            if sha256(path.read_bytes()) != gold["sha256"][name] or indexed["sha256"] != gold["sha256"][name]:
                raise ValueError(f"Indexed input differs from frozen fixture: {name}")
            if path.suffix == ".xlsx":
                self.worksheets[name] = worksheet_rows(path)
            else:
                self.pages[name] = document_text(path)

    def locate(self, evidence, allowed):
        document = self.names.get(evidence.get("documentId"))
        chunk = self.chunks.get(evidence.get("chunkId"))
        raw_quote = evidence.get("quote")
        quote = compact(raw_quote)
        if (evidence.get("sourceStatus") != "MATCHED" or not quote or document not in allowed
                or evidence.get("fileName") != document or not chunk or chunk["documentId"] != evidence["documentId"]
                or chunk["chunkIndex"] != evidence.get("chunkIndex") or quote not in compact(chunk["content"])):
            return None
        text_hash = evidence.get("textHash")
        if text_hash is not None and text_hash != sha256(chunk["content"].encode("utf-8")):
            return None
        native = evidence.get("nativeLocation")
        if native is not None and not isinstance(native, str):
            return None
        if document.endswith(".xlsx"):
            rows = self.worksheets[document]
            if native is not None:
                row = rows.get(native)
                sheet = native.rpartition("!")[0]
                right_sheet = chunk.get("section") in (None, sheet)
                return document if row is not None and right_sheet and quote == compact(row) else None
            # Legacy snapshots do not record a native worksheet address.
            return document if any(quote.replace("|", "") in compact(row).replace("|", "") for row in rows.values()) else None
        page = evidence.get("pageStart")
        blocks = self.pages[document]
        pdf = document.endswith(".pdf")
        if pdf:
            if not isinstance(page, int) or not 1 <= page <= len(blocks) or page != evidence.get("pageEnd") or page != chunk.get("pageStart"):
                return None
            blocks = [blocks[page - 1]]
        if native is not None:
            match = NATIVE_TEXT_SPAN.fullmatch(native)
            if not match or match[1] != ("PDF 页内字符" if pdf else "原文字符"):
                return None
            start, end = int(match[2]), int(match[3])
            quote_bytes = raw_quote.encode("utf-16-le")
            if (end - start) * 2 != len(quote_bytes):
                return None
            # Plain text has the same native layout in both extractors. PDFBox and pypdf offsets can differ.
            if document.endswith(".txt") and blocks[0].encode("utf-16-le")[start * 2:end * 2] != quote_bytes:
                return None
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


def latest_attempts(events):
    return {(event.get("section"), event.get("batch")): event for event in events}.values()


def context_coverage(facts, records, sources, case=None, split=False, phase="context"):
    events = [record for record in records if record["phase"] == phase]
    if not events:
        return None
    scoped = phase == "anchors" and case and case["type"] == "REPORT" and any(
        "question" in selected for event in events for selected in event["selected"])
    if scoped:
        # Log order is attempt order. A retry replaces the initial offer, even if the retry offered no spans.
        events = latest_attempts(events)
    contexts = []
    for record in events:
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
            contexts.append((record.get("section"), record.get("batch"), selected.get("question"), sources.names[chunk["documentId"]],
                             chunk.get("pageStart"), visible))
    found = 0
    for index, fact in enumerate(facts):
        expected_section = None
        expected_batch = None
        if case and case["type"] == "COMPARE":
            expected_section = case["dimensions"].index(fact["dimension"]) + 1
            expected_batch = case["documents"].index(fact["document"]) + 1
        elif scoped:
            expected_section = 1  # The benchmark submits a single findings section for each report.
            expected_batch = index // 4 + 1
        elif split and len(facts) > 4:
            expected_batch = index // 4 + 1
        found += any(name == fact["document"] and proves(fact, page, content)
                     and (expected_section is None or section == expected_section)
                     and (expected_batch is None or batch == expected_batch)
                     and (not scoped or question == index % 4 + 1)
                     for section, batch, question, name, page, content in contexts)
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
            if case.get("id") == "scope":
                text = compact(cell.get("reason"))
                reason = (category and cell.get("reasonCode") in (None, "OUTSIDE_SCOPE")
                          and bool(SCOPE_EXCLUSION["z9"].search(text)) and not SCOPE_EXCLUSION["z8"].search(text)
                          and not DENIED_SCOPE_EXCLUSION.search(text) and not FAILED_COMPARISON.search(text))
        else:
            labels = [matches[label] for label in expected["facts"]]
            candidates = [item for item in matrix.get("conclusions", []) if item["dimension"] == dimension
                          and item["kind"] != "KEY_DATA"]
            grounded = [item for item in candidates if all(choice.intersection(item["claimIds"]) for choice in labels)]
            category = {item["kind"] for item in candidates} == {kind}
            reason = category and bool(grounded) and all(correct_reason(expected, item) for item in grounded)
        decisions.append({"dimension": dimension, "expected": kind, "categoryCorrect": category,
                          "reasonCorrect": reason, "observed": candidates})
    return decisions


def measure(case, directory, sources, log, variant):
    outcome_path = directory / "outcome.json"
    if not outcome_path.exists():
        return None
    outcome = read_json(outcome_path)
    report = None
    if outcome["status"] == "COMPLETED":
        report_path = directory / "report.json"
        if not report_path.is_file():
            return None
        report = read_json(report_path)
        if (report.get("schemaVersion") != "v2" or not report.get("sections")
                or not all(isinstance(report.get(field), list) for field in ("sections", "evidenceSnapshots"))):
            return None
    facts = case["facts"]
    events = log.get(outcome["taskId"], [])
    split = variant in ("question-split", "full", ANCHORED_VARIANT)
    retrieved_hits = context_coverage(facts, events, sources, case, split)
    visible_hits = (context_coverage(facts, events, sources, case, split, phase="anchors")
                    if variant == ANCHORED_VARIANT else retrieved_hits)
    anchors = latest_attempts(event for event in events if event["phase"] == "anchors")
    numbered_anchors = bool(anchors) and all(event["selected"] and all(
        type(span.get("question")) is int and 1 <= span["question"] <= 4 for span in event["selected"]
    ) for event in anchors)
    result = {**outcome, "contextHits": visible_hits, "retrievedHits": retrieved_hits,
              "numberedAnchors": numbered_anchors,
              "factsPassed": 0, "factsTotal": len(facts),
              "decisionsPassed": 0, "decisionCategoriesPassed": 0, "notFoundPassed": 0,
              "decisionsTotal": len(case["decisions"]),
              "citationsLocated": 0, "citationsTotal": 0, "invalidSupported": 0,
              "phaseOutcomes": dict(Counter(f"{event['phase']}/{event['outcome']}" for event in events))}
    if report is None:
        return result
    claims = claims_by_id(report)
    snapshots = {value["evidenceId"]: value for value in report["evidenceSnapshots"]}
    allowed = set(case["documents"])
    located = {key: sources.locate(evidence, allowed) for key, evidence in snapshots.items()
               if compact(evidence.get("quote"))}
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
                  decisionCategoriesPassed=sum(item["categoryCorrect"] for item in decisions),
                  notFoundPassed=sum(item["categoryCorrect"] for item in decisions if item["expected"] == "NOT_FOUND"),
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


def collect_variant(output, corpora, gold, variant, only=None):
    directory = output / variant
    metadata = directory / "run.json"
    if not metadata.exists():
        return None
    run = read_json(metadata)
    if run["variant"] != variant:
        raise ValueError(f"Incorrect variant in {metadata}")
    selected = corpora if only is None else {name: corpora[name] for name in only}
    hashes = run["goldHashes"] if only is None else {name: run["goldHashes"].get(name) for name in only}
    if hashes != {name: sha256((path / "gold.json").read_bytes()) for name, path in selected.items()}:
        raise ValueError(f"Frozen corpus labels changed since {metadata} was written")
    observed = {}
    logs = read_log(directory / "retrieval.jsonl")
    for name, source_dir in selected.items():
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


def valid_counts(row, total_field, expected, *count_fields):
    total = row.get(total_field)
    return (type(total) is int and total >= 0 and (expected is None or total == expected)
            and all(type(row.get(field)) is int and 0 <= row[field] <= total for field in count_fields))


def completed_run(row, key):
    if row.get("status") != "COMPLETED":
        return False
    corpus, case, _ = key.split("/")
    if corpus == EXAM_CORPUS:
        return valid_counts(row, "factsTotal", 8, "factsPassed")
    if corpus != "expanded":
        return True
    if case not in EXPANDED_COUNTS:
        return False
    facts, decisions = EXPANDED_COUNTS[case]
    return (valid_counts(row, "factsTotal", facts, "factsPassed")
            and valid_counts(row, "decisionsTotal", decisions, "decisionsPassed", "decisionCategoriesPassed")
            and row["decisionsPassed"] <= row["decisionCategoriesPassed"]
            and valid_counts(row, "citationsTotal", None, "citationsLocated"))


def expanded_metrics(measurements):
    completed = {key: row for key, row in measurements.items() if key.startswith("expanded/") and completed_run(row, key)}
    metrics = {}
    for name, cases, field, total in (
        ("expandedRecall", EXPANDED_COUNTS, "contextHits", 25),
        ("expandedGrounded", EXPANDED_COUNTS, "factsPassed", 25),
        ("expandedReportGrounded", EXPANDED_REPORTS, "factsPassed", 12),
        ("expandedCompareGrounded", EXPANDED_COMPARISONS, "factsPassed", 13),
        ("expandedDecisionCategories", EXPANDED_COMPARISONS, "decisionCategoriesPassed", 7),
        ("expandedDecisions", EXPANDED_COMPARISONS, "decisionsPassed", 7),
        ("expandedNotFound", {"scope"}, "notFoundPassed", 1),
    ):
        metric = aggregate(completed, "expanded", cases, field)
        median = metric["median"]
        metrics[name] = {**metric, "totalPerRun": total, "medianRate": None if median is None else median / total}
    located = aggregate(completed, "expanded", EXPANDED_COUNTS, "citationsLocated")["runs"]
    totals = aggregate(completed, "expanded", EXPANDED_COUNTS, "citationsTotal")["runs"]
    rates = [hits / total if hits is not None and total else None for hits, total in zip(located, totals)]
    pooled_located = None if None in located else sum(located)
    pooled_total = None if None in totals else sum(totals)
    metrics["expandedCitationLocation"] = {"located": located, "totals": totals, "runs": rates,
                                           "median": None if None in rates else statistics.median(rates),
                                           "pooledLocated": pooled_located, "pooledTotal": pooled_total,
                                           "pooledRate": (pooled_located / pooled_total
                                                          if pooled_located is not None and pooled_total else None)}
    return metrics


def exam_metrics(measurements):
    valid = {key: row for key, row in measurements.items() if key.startswith(f"{EXAM_CORPUS}/")
             and completed_run(row, key)}
    return {name: {**aggregate(valid, EXAM_CORPUS, {EXAM_CORPUS}, field), "totalPerRun": 8}
            for name, field in (("examCheckCoverage", "contextHits"), ("examCheckGrounded", "factsPassed"))}


def quality_checks(metrics):
    checks = {}
    for name, minimum in {"realRecall": 8, "realGrounded": 6, "longRecall": 18, "longGrounded": 12,
                          "expandedReportGrounded": 9, "expandedCompareGrounded": 10, "expandedDecisions": 4}.items():
        median = metrics[name]["median"]
        checks[name] = median is not None and median >= minimum
    citations = metrics["expandedCitationLocation"]
    checks["expandedCitationLocation"] = (all(total is not None and total > 0 for total in citations["totals"])
                                          and 100 * citations["pooledLocated"] >= 95 * citations["pooledTotal"])
    if "examCheckCoverage" in metrics:
        checks["examCheckCoverage"] = metrics["examCheckCoverage"]["runs"] == [8, 8, 8]
    return checks


def supports_are_locatable(rows, expected):
    return bool(expected) and all(rows.get(key, {}).get("status") == "COMPLETED"
                                  and rows[key].get("invalidSupported") == 0 for key in expected)


def paired_timing(reference, candidate, short):
    def timed(row):
        seconds = row.get("seconds")
        return (row.get("status") == "COMPLETED" and row.get("timingReliable", True)
                and type(seconds) in (int, float) and math.isfinite(seconds) and seconds > 0)

    paired = {key for key in short if timed(reference.get(key, {})) and timed(candidate.get(key, {}))}
    before = percentile95([reference[key]["seconds"] for key in paired])
    after = percentile95([candidate[key]["seconds"] for key in paired])
    return {"beforeSeconds": before, "afterSeconds": after, "matchedSuccessful": len(paired), "expected": len(short)}


def timing_passes(timing):
    return (timing["matchedSuccessful"] == timing["expected"] and timing["beforeSeconds"] is not None
            and timing["afterSeconds"] <= timing["beforeSeconds"] * 2)


def compare_revision_runs(before, after, expected, short, latency_reference=None):
    before_rows = before["measurements"] if before else {}
    after_rows = after["measurements"] if after else {}
    settings = [{k: v for k, v in run["settings"].items() if k != "variant"} for run in (before, after) if run]
    revision_timing = paired_timing(before_rows, after_rows, short)
    gate_timing = (revision_timing if latency_reference is None
                   else paired_timing(latency_reference.get("measurements", {}), after_rows, short))
    details = {}
    for label, rows in (("before", before_rows), ("after", after_rows)):
        details[label] = {name: aggregate(rows, corpus, CORE[corpus], field) for name, corpus, field in (
            ("realRecall", "real", "contextHits"), ("realRetrieved", "real", "retrievedHits"),
            ("realGrounded", "real", "factsPassed"),
            ("longRecall", "long", "contextHits"), ("longRetrieved", "long", "retrievedHits"),
            ("longGrounded", "long", "factsPassed"),
        )}
        details[label].update(expanded_metrics(rows))
        if any(key.startswith(f"{EXAM_CORPUS}/") for key in expected):
            details[label].update(exam_metrics(rows))
    checks = {"sameInputsAndModels": len(settings) == 2 and settings[0] == settings[1],
              "allSelectedRunsPaired": bool(expected) and all(completed_run(rows.get(key, {}), key)
                                                            for rows in (before_rows, after_rows) for key in expected),
              "shortTaskP95": timing_passes(gate_timing),
              "unsupportedClaims": supports_are_locatable(after_rows, expected), **quality_checks(details["after"])}
    exam_runs = {key for key in expected if key.startswith(f"{EXAM_CORPUS}/")}
    if exam_runs:
        checks["examQuestionAnchors"] = all(after_rows.get(key, {}).get("numberedAnchors") is True for key in exam_runs)
    metrics = {name: {label: values[name] for label, values in details.items()} for name in details["after"]}
    return {"checks": checks, "metrics": metrics,
            "shortTaskP95": gate_timing, "revisionShortTaskP95": revision_timing,
            "before": before, "after": after,
            "missingBefore": sorted(expected - before_rows.keys()), "missingAfter": sorted(expected - after_rows.keys())}


def summarize_revisions(args, corpora):
    gold = {name: load_gold(source) for name, source in corpora.items()}
    same_strategy = args.before_variant == ANCHORED_VARIANT
    exam_configured = EXAM_CORPUS in gold
    # Record the missing exam setup even when the saved runs also include that corpus.
    selected = set(corpora) if same_strategy and not exam_configured else None
    before = collect_variant(args.before_output, corpora, gold, args.before_variant, only=selected)
    after = collect_variant(args.output, corpora, gold, ANCHORED_VARIANT, only=selected)
    short = {f"expanded/{case}/{rep}" for case in EXPANDED_COUNTS for rep in range(1, 4)}
    expected = {f"{name}/{case}/{rep}" for name, cases in CORE.items() for case in cases for rep in range(1, 4)}
    if exam_configured:
        expected.update(f"{EXAM_CORPUS}/{EXAM_CORPUS}/{rep}" for rep in range(1, 4))
    archived = None
    if args.latency_reference_output is not None:
        archived = collect_variant(args.latency_reference_output, corpora, gold, "full", only={"expanded"}) or {}
    result = compare_revision_runs(before, after, expected, short, archived)
    if same_strategy:
        result["checks"].update(examCorpusConfigured=exam_configured,
                                archivedLatencyConfigured=args.latency_reference_output is not None)
    if archived is not None:
        result["checks"]["archivedLatencyModels"] = bool(archived and after) and all(
            archived["settings"].get(key) == after["settings"].get(key) for key in ("model", "embedding"))
        result["shortTaskP95"]["referenceVariant"] = "full"
    else:
        result["shortTaskP95"]["referenceVariant"] = args.before_variant
    proof = passing_tests(args.guardrail_proof)
    result["checks"].update(negativeFixtures=(GUARDS | ANCHOR_GUARDS).issubset(proof),
                            generationAndRetrievalFailures=STABILITY.issubset(proof),
                            legacyReadAndReexport=HISTORY.issubset(passing_tests(args.compatibility_proof)))
    result["passed"] = all(result["checks"].values())
    save_json(args.output / "revision-comparison.json", result)
    for name, values in result["metrics"].items():
        field = "pooledRate" if name == "expandedCitationLocation" else "median"
        print(f"{name}: {values['before'][field]} -> {values['after'][field]}")
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
                ("longRecall", "long", "contextHits"), ("longGrounded", "long", "factsPassed"))}
        result["medianMetrics"].update(expanded_metrics(runs))
        if EXAM_CORPUS in gold:
            result["medianMetrics"].update(exam_metrics(runs))
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
    timing = paired_timing(base_runs, full_runs, short)
    same_inputs = [{key: value for key, value in item["settings"].items() if key != "variant"}
                   for item in variants.values() if item is not None]
    proof = passing_tests(args.guardrail_proof)
    checks = {
        "allVariantsRanThreeTimes": all(v is not None and all(completed_run(v["measurements"].get(key, {}), key)
                                                             for key in expected_cases) for v in variants.values()),
        "sameInputsAndModels": len(same_inputs) == len(VARIANTS) and all(item == same_inputs[0] for item in same_inputs),
        "shortTaskP95": timing_passes(timing),
        "unsupportedClaims": supports_are_locatable(full_runs, expected_cases),
        "negativeFixtures": GUARDS.issubset(proof),
        "generationAndRetrievalFailures": STABILITY.issubset(proof),
        "legacyReadAndReexport": HISTORY.issubset(passing_tests(args.compatibility_proof)),
    }
    empty_metrics = {**dict.fromkeys(("realRecall", "realGrounded", "longRecall", "longGrounded"), {"median": None}),
                     **expanded_metrics({})}
    if EXAM_CORPUS in gold:
        empty_metrics.update(exam_metrics({}))
    checks.update(quality_checks(full["medianMetrics"] if full else empty_metrics))
    result = {"passed": all(checks.values()), "checks": checks, "variants": variants,
              "shortTaskP95": {"baselineSeconds": timing["beforeSeconds"], "fullSeconds": timing["afterSeconds"],
                               "matchedSuccessful": timing["matchedSuccessful"], "expected": timing["expected"]}}
    save_json(args.output / "summary.json", result)
    for name, variant in variants.items():
        if variant:
            medians = ", ".join(f"{key}={value.get('pooledRate', value['median'])}"
                                for key, value in variant["medianMetrics"].items())
            print(f"{name}: {len(variant['measurements'])} observed tasks, {variant['failures']} failed, {medians}")
    for check, passed in checks.items():
        print(f"{check}: {'PASS' if passed else 'FAIL OR INCOMPLETE'}")
    return 0 if result["passed"] else 1
