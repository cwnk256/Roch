"""Offline evaluation for the annotated traceability fixtures. PDF inputs require pypdf.

Run after generation: python scripts/trace_report_quality.py --output logs/trace-verify
Writes <report>-quality.json beside each input. Exit 1 means a check failed or an input
is missing. Probes absent from ordinary model output are explicitly NOT_EXERCISED.
"""

import argparse
import hashlib
import json
import re
from collections import defaultdict
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
FIXTURES = ROOT / "rag/src/test/resources/traceability"
SOURCES = {"analysis": ["A.pdf", "B.txt"], "compare": ["A.pdf", "B.txt", "C.txt"],
           "holdout": ["holdout/D.txt", "holdout/E.txt"]}
STATUSES = {"SUPPORTED", "PARTIAL", "CONFLICT", "INSUFFICIENT"}


def read_json(path):
    return json.loads(path.read_text(encoding="utf-8-sig"))


def compact(text):
    return "".join((text or "").lower().split())


def matches_terms(text, terms):
    normalized = compact(text).replace(",", "")
    return all(any(term in normalized for term in group.split("|")) for group in terms)


def ratio(count, total):
    return {"numerator": count, "denominator": total, "value": count / total if total else None}


def sha256(value):
    return hashlib.sha256(value.encode()).hexdigest()


def load_source(relative):
    path = FIXTURES / relative
    is_pdf = path.suffix == ".pdf"
    if is_pdf:
        from pypdf import PdfReader
        pages = [page.extract_text() for page in PdfReader(path).pages]
    else:
        pages = [path.read_text(encoding="utf-8")]
    # Short, plain-prose fixtures have one chunk per page or text file. The source
    # hashes below additionally detect changes to that layout or the normalization.
    chunks = ["".join(s for s in re.split(r"(?<=[。！？!?；;\n])|(?<=\. )", page.strip().lower())
                      if s.strip()) for page in pages]
    return {"file": path.name, "relative": relative, "pages": pages, "chunks": chunks,
            "pdf": is_pdf, "id": hashlib.md5(path.read_bytes()).hexdigest()}


def locate(evidence, source):
    quote = "".join((evidence.get("quote") or "").split())
    index = evidence.get("chunkIndex")
    if not source or not quote or evidence.get("fileName") != source["file"]:
        return None
    if type(index) is not int or not 0 <= index < len(source["chunks"]):
        return None
    chunk = source["chunks"][index]
    page = index + 1 if source["pdf"] else None
    if (evidence.get("pageStart"), evidence.get("pageEnd")) != (page, page):
        return None
    version = evidence.get("pipelineVersion")
    chunk_id = sha256(f"{source['id']}|{version}|prose|{index}|{chunk}")
    if not version or evidence.get("textHash") != sha256(chunk) or evidence.get("chunkId") != chunk_id:
        return None
    lines = source["pages"][index].splitlines()
    original = "".join("".join(line.split()) for line in lines)
    start = original.find(quote)
    if start < 0 or compact(quote) not in compact(chunk):
        return None
    if source["pdf"]:
        return f"{source['relative']}#page={page}"
    offset = 0
    numbers = []
    for number, line in enumerate(lines, 1):
        length = len(compact(line))
        if length and offset < start + len(quote) and start < offset + length:
            numbers.append(number)
        offset += length
    return f"{source['relative']}#L{numbers[0]}" + (f"-L{numbers[-1]}" if len(numbers) > 1 else "")


def audit(report, sources):
    issues = []
    snapshots = {}
    locations = {}
    evidence_rows = []
    for evidence in report.get("evidenceSnapshots", []):
        identifier = evidence.get("evidenceId")
        if not identifier or identifier in snapshots:
            issues.append(f"Duplicate or missing snapshot ID: {identifier}")
        snapshots[identifier] = evidence
        location = locate(evidence, sources.get(evidence.get("documentId")))
        if location and evidence.get("sourceStatus") == "MATCHED":
            locations[identifier] = location
        elif evidence.get("sourceStatus") == "MATCHED" or evidence.get("documentId") not in sources:
            issues.append(f"Invalid source for snapshot {identifier}")
        evidence_rows.append({"id": identifier, "location": location, "status": evidence.get("sourceStatus"),
                              "quote": evidence.get("quote") or evidence.get("attemptedQuote")})
    claims = {}
    sections = report.get("sections", [])
    all_claims = [claim for section in sections for claim in section.get("claims", [])]
    for claim in all_claims + report.get("summaryClaims", []):
        identifier = claim["claimId"]
        if identifier in claims and claim != claims[identifier]:
            issues.append(f"Inconsistent claim ID {identifier}")
        claims[identifier] = claim
        ids = claim.get("evidenceIds") or []
        if any(key not in snapshots for key in ids):
            issues.append(f"Missing snapshot for {identifier}")
        if claim.get("supportStatus") == "SUPPORTED" and (not ids or any(key not in locations for key in ids)):
            issues.append(f"SUPPORTED claim without valid citations: {identifier}")
    for key in report.get("summaryClaimIds", []):
        if key not in claims:
            issues.append(f"Missing summary claim {key}")
    copies = report.get("references", []) + [e for section in sections for e in section.get("evidences", [])]
    embedded = [(identifier, claim.get("claimId")) for claim in claims.values()
                for identifier in claim.get("evidenceIds", [])]
    for evidence in copies:
        embedded.append((evidence.get("evidenceId"), "section/reference"))
        snapshot = snapshots.get(evidence.get("evidenceId"), {})
        if not snapshot or any(snapshot.get(k) != evidence.get(k) for k in (
                "documentId", "fileName", "chunkId", "chunkIndex", "quote", "pageStart", "pageEnd",
                "textHash", "pipelineVersion", "sourceStatus")):
            issues.append(f"Reference differs from snapshot {evidence.get('evidenceId')}")
    dangling_ids = {key for key, _ in embedded if key not in snapshots}
    for key in sorted(dangling_ids, key=str):
        evidence_rows.append({"id": key, "location": None, "status": "NO_SNAPSHOT",
                              "referencedBy": [owner for ref, owner in embedded if ref == key]})
    return claims, snapshots, locations, evidence_rows, issues


def annotated_facts(cases, sources):
    facts = {}
    for case in cases:
        for locator, quote, terms in zip(case["sources"], case.get("quotes", []),
                                         case.get("claimTerms", []), strict=True):
            relative, position = locator.split("#")
            source = next(s for s in sources.values() if s["relative"] == relative)
            if position.startswith("page="):
                original = source["pages"][int(position[5:]) - 1]
            else:
                original = source["pages"][0].splitlines()[int(position[1:]) - 1]
            if compact(quote) not in compact(original):
                raise ValueError(f"Annotation does not match {locator}: {quote}")
            facts[locator, quote] = (source["id"], terms)
    return facts


def cited_fact(identifier, locator, quote, source_id, snapshots, locations):
    if identifier not in locations:
        return False
    evidence = snapshots[identifier]
    if evidence["documentId"] != source_id or compact(quote) not in compact(evidence.get("quote")):
        return False
    actual = locations[identifier]
    if "#page=" in locator:
        return locator == actual
    expected_file, line = locator.split("#L")
    observed_file, span = actual.split("#L")
    bounds = [int(value) for value in span.split("-L")]
    return observed_file == expected_file and bounds[0] <= int(line) <= bounds[-1]


def audit_matrix(matrix, sources, claims, snapshots, issues):
    facts = {}
    cells = {}
    columns = [doc["documentId"] for doc in matrix.get("documents", [])]
    if len(columns) != len(sources) or set(columns) != sources.keys():
        issues.append("Comparison columns differ from the selected fixture documents")
    for row in matrix.get("rows", []):
        dimension = row["dimension"]
        if [cell["documentId"] for cell in row["cells"]] != columns:
            issues.append(f"Invalid columns in {dimension}")
        for cell in row["cells"]:
            doc_id = cell["documentId"]
            key = (dimension, doc_id)
            if key in cells:
                issues.append(f"Duplicate matrix cell {key}")
            cells[key] = cell
            for fact in cell["facts"]:
                identifier = fact["claimId"]
                claim = claims.get(identifier, {})
                evidence = [snapshots.get(e, {}) for e in claim.get("evidenceIds", [])]
                if identifier in facts or not claim or any(e.get("documentId") != doc_id for e in evidence):
                    issues.append(f"Invalid cell provenance {identifier}")
                if fact.get("verified"):
                    if claim.get("supportStatus") != "SUPPORTED":
                        issues.append(f"Verified matrix fact lacks supported claim {identifier}")
                    if not any("".join((e.get("quote") or "").split()) == "".join((fact.get("quote") or "").split())
                               for e in evidence):
                        issues.append(f"Matrix quote differs from supported snapshot: {identifier}")
                facts[identifier] = (dimension, doc_id, fact)
    for conclusion in matrix.get("conclusions", []):
        ids = conclusion.get("claimIds", [])
        doc_ids = {facts[key][1] for key in ids if key in facts}
        if (not ids or (conclusion["kind"] != "KEY_DATA" and len(doc_ids) < 2) or
                any(key not in facts or facts[key][0] != conclusion["dimension"]
                    or not facts[key][2].get("verified") for key in ids)):
            issues.append(f"Invalid conclusion provenance {conclusion['kind']}: {ids}")
    return facts, cells


def classify_pairs(cases, conclusions, fact_matches, matrix_facts, cells, sources):
    reviews = []
    false_negatives = []
    predictions = [i for i, row in enumerate(conclusions) if row["kind"] == "POSSIBLE_CONFLICT"]
    true_positives = set()
    expected_conflicts = 0
    for case in (c for c in cases if "dimension" in c):
        dimension = case["dimension"]
        groups = [fact_matches[key] for key in zip(case["sources"], case["quotes"])]
        pairs = []
        valid = []
        for i, result in enumerate(conclusions):
            ids = result["claimIds"]
            if result["dimension"] != dimension or len(ids) != 2:
                continue
            for ordered in (ids, list(reversed(ids))):
                if not all(key in group for key, group in zip(ordered, groups, strict=True)):
                    continue
                facts = [matrix_facts[key][2] for key in ordered]
                raw = [f"{f.get('rawValue') or ''}{f.get('unit') or ''}" for f in facts]
                normalized = [f"{f.get('normalizedValue') or ''}{f.get('normalizedUnit') or ''}" for f in facts]
                pairs.append({"kind": result["kind"], "text": result["text"], "claimIds": ordered,
                              "raw": raw, "normalized": normalized})
                wanted = "COMMON" if case["kind"] == "EQUAL_VALUE" else case["kind"]
                if result["kind"] == wanted and matches_terms(result["text"], case.get("reasonTerms", [])) and all(
                        [compact(v) for v in actual] == [compact(v) for v in case[field]]
                        for field, actual in (("raw", raw), ("normalized", normalized)) if field in case):
                    valid.append(i)
                break
        missing = {}
        for filename in case.get("missing", []):
            doc_id = next(s["id"] for s in sources.values() if s["file"] == filename)
            cell = cells.get((dimension, doc_id), {})
            missing[filename] = cell.get("status") if not cell.get("facts") else "HAS_FACTS"
        passed = bool(valid) and all(status == "NOT_FOUND" for status in missing.values())
        review = {"expectation": case, "actualPairs": pairs, "missingDocuments": missing, "passed": passed}
        reviews.append(review)
        if case["kind"] == "POSSIBLE_CONFLICT":
            expected_conflicts += 1
            if valid:
                true_positives.add(valid[0])
            else:
                false_negatives.append(review)
    false_positives = [conclusions[i] for i in predictions if i not in true_positives]
    count = len(true_positives)
    return reviews, {"TP": count, "FP": len(false_positives), "FN": len(false_negatives),
                     "precision": ratio(count, len(predictions)), "recall": ratio(count, expected_conflicts),
                     "falsePositives": false_positives, "falseNegatives": false_negatives}


def evaluate(name, report, cases):
    sources = {s["id"]: s for s in map(load_source, SOURCES[name])}
    labels = annotated_facts(cases, sources)
    claims, snapshots, locations, evidence_rows, issues = audit(report, sources)
    matrix = report.get("comparison") or {}
    matrix_facts, cells = ({}, {}) if name == "analysis" else audit_matrix(matrix, sources, claims, snapshots, issues)
    matches = defaultdict(list)
    coverage = []
    for (locator, quote), (source_id, terms) in labels.items():
        for key, claim in claims.items():
            if name != "analysis" and key not in matrix_facts:
                continue
            if claim.get("supportStatus") not in STATUSES or not matches_terms(claim["text"], terms):
                continue
            if any(cited_fact(e, locator, quote, source_id, snapshots, locations)
                   for e in claim.get("evidenceIds", [])):
                matches[locator, quote].append(key)
        coverage.append({"source": locator, "quote": quote, "claimIds": matches[locator, quote]})
    probes = []
    for case in (c for c in cases if "probeTerms" in c):
        observed = []
        for claim in claims.values():
            # Keep obligation and action in the same clause. A claim may require
            # calibration and merely recommend inspection in a separate clause.
            clauses = re.split(r"[。；;！!？?\n]", claim["text"])
            if not any(matches_terms(clause, case["probeTerms"]) for clause in clauses):
                continue
            evidence_ids = claim.get("evidenceIds", [])
            if case["kind"] == "UNSUPPORTED_STRENGTH":
                located = any(e in locations and snapshots[e]["fileName"] == Path(case["sources"][0].split("#")[0]).name
                              and compact(case["quotes"][0]) in compact(snapshots[e]["quote"]) for e in evidence_ids)
            else:
                located = not evidence_ids
            observed.append({"claim": claim, "passed": located and claim["supportStatus"] in case["allowedStatus"]})
        status = ("PASS" if all(c["passed"] for c in observed) else "FAIL") if observed else "NOT_EXERCISED"
        probes.append({"expectation": case, "observed": observed, "status": status})
    # A record can have a real source yet attach its value to the wrong predicate.
    # Surface these cases for review without grading unrestricted prose by keyword.
    semantic_flags = []
    if matrix:
        for key, (_, _, fact) in matrix_facts.items():
            claim = claims.get(key, {})
            if claim.get("supportStatus") != "SUPPORTED":
                continue
            cited = " ".join(snapshots.get(e, {}).get("quote") or "" for e in claim.get("evidenceIds", []))
            claim_numbers = set(re.findall(r"\d+(?:\.\d+)?", compact(claim.get("text")).replace(",", "")))
            quote_numbers = set(re.findall(r"\d+(?:\.\d+)?", compact(cited).replace(",", "")))
            ungrounded = sorted(claim_numbers - quote_numbers)
            if ungrounded:
                semantic_flags.append({"claimId": key, "claim": claim["text"], "cited": cited,
                                       "matrixVerified": fact.get("verified"),
                                       "numbersAbsentFromExcerpt": ungrounded})
    pairs, conflicts = ([], None) if name == "analysis" else classify_pairs(
        cases, matrix.get("conclusions", []), matches, matrix_facts, cells, sources)
    review_queue = [{"id": key, "claim": claim["text"], "status": claim.get("supportStatus"),
                     "labelMatches": [row["source"] for row in coverage if key in row["claimIds"]],
                     "citations": [{"id": eid, "location": locations.get(eid),
                                    "quote": snapshots.get(eid, {}).get("quote")}
                                   for eid in claim.get("evidenceIds", [])]} for key, claim in claims.items()]
    covered = sum(bool(row["claimIds"]) for row in coverage)
    requested = {(loc, quote) for case in cases if case.get("dimension") in (report.get("configuration") or {}).get(
        "comparisonDimensions", []) for loc, quote in zip(case["sources"], case.get("quotes", []))}
    dimension_coverage = ratio(sum(bool(matches[key]) for key in requested), len(requested))
    passed = (report.get("schemaVersion") == "v2" and bool(evidence_rows) and not issues
              and len(locations) == len(evidence_rows) and bool(labels)
              and (covered == len(labels) if name == "analysis" else dimension_coverage["value"] == 1)
              and all(row["passed"] for row in pairs) and not (conflicts or {}).get("FP")
              and not any(p["status"] == "FAIL" for p in probes))
    return {"observedChecksPassed": passed, "integrityViolations": issues,
            "citationLocation": ratio(len(locations), len(evidence_rows)), "citations": evidence_rows,
            "annotatedFactCoverage": ratio(covered, len(labels)), "annotatedFacts": coverage,
            "requestedDimensionFactCoverage": dimension_coverage,
            "conflicts": conflicts, "comparisonCases": pairs, "negativeProbes": probes, "claimReview": review_queue,
            "numericReviewFlags": semantic_flags,
            "generationMetrics": matrix.get("metrics"), "model": (report.get("configuration") or {}).get("model"),
            "scope": "One citation per unique evidence snapshot or unresolved evidence ID. Location checks source MD5, chunk index, page, "
                     "chunk ID, content SHA256 and excerpt. TXT lines are reconstructed from the original. "
                     "Coverage counts distinct labelled source facts with an explicit claim status, matching "
                     "claim terms and an independently located quote; it does not grade every paraphrase. "
                     "For comparison, requestedDimensionFactCoverage restricts the denominator to the task's dimensions. "
                     "Conflict TP requires the labelled dimension, both source facts, kind and retained values. "
                     "Unmatched/duplicate predictions are FP. A null rate means zero denominator. "
                     "An absent negative probe requires separate controlled replay. Results cover these fixtures only."}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, default=ROOT / "logs/trace-verify")
    parser.add_argument("--storage", type=Path, help="Persisted report directory when separate from <output>/reports")
    parser.add_argument("--reports", nargs="+", choices=list(SOURCES), default=list(SOURCES))
    args = parser.parse_args()
    labels = read_json(FIXTURES / "expected.json")
    failed = False
    for name in args.reports:
        path = args.output / f"{name}.json"
        if not path.is_file():
            print(f"{name}: missing {path}")
            failed = True
            continue
        report = read_json(path)
        result = evaluate(name, report, labels["holdout" if name == "holdout" else "development"])
        result["inputSha256"] = hashlib.sha256(path.read_bytes()).hexdigest()
        id_path = args.output / f"{name}.id"
        result["taskId"] = id_path.read_text(encoding="utf-8-sig").strip() if id_path.exists() else None
        stored = (args.storage or args.output / "reports") / (result["taskId"] or "") / "result.json"
        result["storedArtifactMatches"] = None
        if stored.is_file():
            persisted = read_json(stored)
            fields = (persisted.keys() | report.keys()) - {"currentSources"}
            result["storedArtifactMatches"] = all(persisted.get(k) == report.get(k) for k in fields)
            if not result["storedArtifactMatches"]:
                result["observedChecksPassed"] = False
                result["integrityViolations"].append("Saved API JSON differs from persisted result for supplied task ID")
        target = args.output / f"{name}-quality.json"
        target.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        print(f"{name}: {result['observedChecksPassed']} -> {target}")
        failed |= not result["observedChecksPassed"]
    return int(failed)


if __name__ == "__main__":
    raise SystemExit(main())
