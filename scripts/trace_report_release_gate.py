"""Run and score the document-task retrieval release gate against isolated local Roch instances.

Install pypdf, python-docx and openpyxl for scoring, then launch each variant with
rag.document-task.retrieval.strategy=baseline|hybrid-only|question-split|full.
Keep benchmark fixtures and output in an ignored local directory.

Run: python scripts/trace_report_release_gate.py run --variant baseline --api http://127.0.0.1:8087/api
Use --retrieval-log <app workdir>/logs/retrieval.jsonl with the instrumented application.
Use --cases real/full-document long/equipment-compare for an incremental run; repeat
without --cases to complete the gate, with existing finished tasks reused.
Score: python scripts/trace_report_release_gate.py summary --guardrail-proof rag/target/surefire-reports
       --compatibility-proof rag/target/surefire-reports
"""

import argparse
import hashlib
import json
import mimetypes
import time
import uuid
from pathlib import Path
from urllib.parse import urlsplit
from urllib.request import HTTPRedirectHandler, ProxyHandler, Request, build_opener


ROOT = Path(__file__).resolve().parents[1]
VARIANTS = ("baseline", "hybrid-only", "question-split", "full")
CORPORA = {
    "expanded": ROOT / "logs/model-benchmark-20261124-163206",
    "long": ROOT / "logs/long-benchmark-20261124-164748",
    "real": ROOT / "logs/real-pdf-benchmark-20261124-170558",
}
ENDPOINTS = {".txt": "", ".pdf": "/pdf", ".docx": "/word", ".xlsx": "/excel"}


def read_json(path):
    return json.loads(path.read_text(encoding="utf-8-sig"))


def save_json(path, value):
    temporary = path.with_suffix(path.suffix + ".tmp")
    temporary.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    temporary.replace(path)


def sha256(data):
    return hashlib.sha256(data).hexdigest()


def require_local(url):
    target = urlsplit(url)
    if (target.scheme not in ("http", "https") or target.hostname not in ("localhost", "127.0.0.1", "::1")
            or target.username or target.password or target.query or target.fragment):
        raise ValueError("The benchmark accepts only loopback service URLs")
    return url.rstrip("/")


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, request, response, code, message, headers, new_url):
        return None


class LocalApi:
    def __init__(self, url):
        self.url = require_local(url)
        self.opener = build_opener(ProxyHandler({}), NoRedirect())

    def transfer(self, path, method="GET", body=None, media="application/json"):
        if isinstance(body, dict):
            body = json.dumps(body, ensure_ascii=False).encode()
        request = Request(self.url + path, data=body, method=method, headers={"Content-Type": media})
        with self.opener.open(request, timeout=240) as response:
            return response.read()

    def json(self, path, method="GET", body=None, media="application/json"):
        return json.loads(self.transfer(path, method, body, media))


def load_gold(corpus):
    gold_path = corpus / "gold.json"
    expected = (corpus / "gold.sha256").read_text(encoding="utf-8-sig").strip()
    if sha256(gold_path.read_bytes()) != expected:
        raise ValueError(f"Frozen answer file failed its hash check: {gold_path}")
    return read_json(gold_path)


def upload(api, path, digest):
    binary = path.read_bytes()
    if sha256(binary) != digest:
        raise ValueError(f"Frozen fixture failed its hash check: {path.name}")
    boundary = uuid.uuid4().hex
    media = mimetypes.guess_type(path.name)[0] or "application/octet-stream"
    header = (f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="{path.name}"\r\n'
              f"Content-Type: {media}\r\n\r\n")
    body = header.encode() + binary + f"\r\n--{boundary}--\r\n".encode()
    result = api.json("/documents" + ENDPOINTS[path.suffix], "POST", body, f"multipart/form-data; boundary={boundary}")
    document_id = result["md5"]
    if document_id != hashlib.md5(binary).hexdigest() or api.transfer(f"/documents/{document_id}/file") != binary:
        raise ValueError(f"Original round trip does not match fixture: {path.name}")
    chunks = []
    while True:
        page = api.json(f"/documents/{document_id}/chunks?size=50&page={len(chunks) // 50}")
        chunks.extend(page["content"])
        if len(chunks) == page["total"] and chunks:
            break
        if not page["content"] or len(chunks) > page["total"] or len(chunks) % 50:
            raise ValueError(f"Invalid chunk pagination: {path.name}")
    if len({chunk["chunkId"] for chunk in chunks}) != len(chunks):
        raise ValueError(f"Duplicate indexed chunks: {path.name}")
    return {"id": document_id, "sha256": digest, "chunks": chunks}


def make_request(case, documents, templates):
    task_type = case["type"]
    template = templates[task_type]
    payload = {"type": task_type, "documentIds": [documents[name]["id"] for name in case["documents"]],
               "instruction": case["instruction"], "templateId": template["templateId"],
               "templateVersion": template["templateVersion"]}
    if task_type == "COMPARE":
        payload["comparisonDimensions"] = case["dimensions"]
        if case.get("section"):
            payload["sections"] = [{"sectionId": "matrix", "instruction": case["section"]}]
    else:
        payload["sections"] = [{"sectionId": "findings"}]
        if case.get("section"):
            payload["sections"][0]["instruction"] = case["section"]
    return payload


def check_strategy(response, expected):
    plan = (response.get("configuration") or {}).get("retrieval") or {}
    if plan.get("strategy", "baseline") != expected:
        raise ValueError(f"Unexpected retrieval strategy for task {response.get('taskId')}: {plan.get('strategy')!r}")


def run_case(api, directory, payload, expect_model, expected_strategy, timeout):
    directory.mkdir(parents=True, exist_ok=True)
    terminal = directory / "outcome.json"
    submitted = directory / "submission.json"
    if terminal.exists():
        return
    resumed = submitted.exists()
    if resumed:
        submission = read_json(submitted)
    else:
        save_json(directory / "request.json", payload)
        started = time.time()
        result = api.json("/document-tasks", "POST", payload)
        submission = {"taskId": result["taskId"], "startedEpoch": started}
        save_json(submitted, submission)
    task_id = submission["taskId"]
    endpoint = f"/document-tasks/{task_id}"
    print("STARTED", directory.name, task_id, flush=True)
    deadline = submission["startedEpoch"] + timeout
    while True:
        state = api.json(endpoint)
        check_strategy(state, expected_strategy)
        status = state["status"]
        if status in ("COMPLETED", "FAILED"):
            elapsed = time.time() - submission["startedEpoch"]
            save_json(directory / "state.json", state)
            if status == "COMPLETED":
                report = api.json(endpoint + "/result")
                check_strategy(report, expected_strategy)
                model = (report.get("configuration") or {}).get("model")
                if model != expect_model or report["schemaVersion"] != "v2":
                    raise ValueError(f"Unexpected running model or report schema for {task_id}")
                artifact = api.transfer(endpoint + "/file")
                if not artifact.startswith(b"%PDF"):
                    raise ValueError(f"Missing PDF export for {task_id}")
                (directory / "report.pdf").write_bytes(artifact)
                save_json(directory / "report.json", report)
            save_json(terminal, {"taskId": task_id, "status": status, "seconds": round(elapsed, 3),
                                 "timingReliable": not resumed})
            print(status, directory.name, f"{elapsed:.1f}s", flush=True)
            return
        if time.time() >= deadline:
            raise TimeoutError(f"Task {task_id} still active. Resolve it on the isolated instance before continuing.")
        time.sleep(3)


def collect_log(path, destination):
    if path is None or not path.exists():
        return
    contents = path.read_bytes()
    # The final line may be in flight while the application writes its log.
    complete_lines = contents[:contents.rfind(b"\n") + 1]
    previous = destination.read_bytes() if destination.exists() else b""
    lines = dict.fromkeys((previous + complete_lines).splitlines(keepends=True))
    temporary = destination.with_suffix(".tmp")
    temporary.write_bytes(b"".join(lines))
    temporary.replace(destination)


def run(args, corpora):
    directory = args.output / args.variant
    directory.mkdir(parents=True, exist_ok=True)
    api = LocalApi(args.api)
    models = LocalApi(args.model_api).json("/models")
    if args.model not in {model["id"] for model in models["data"]}:
        raise ValueError(f"Requested model not found on the isolated model service: {args.model}")
    manifests = {name: load_gold(path) for name, path in corpora.items()}
    configuration = {"model": args.model, "embedding": args.embedding, "variant": args.variant,
                     "goldHashes": {name: sha256((path / "gold.json").read_bytes()) for name, path in corpora.items()}}
    if args.embedding not in {model["id"] for model in models["data"]}:
        raise ValueError(f"Requested embedding model not found on the isolated model service: {args.embedding}")
    metadata = directory / "run.json"
    if metadata.exists() and read_json(metadata) != configuration:
        raise ValueError(f"Cannot mix configurations in {directory}")
    save_json(metadata, configuration)
    templates = {item["type"]: item for item in api.json("/document-tasks/templates")}
    known_cases = {f"{name}/{case['id']}" for name, gold in manifests.items() for case in gold["runs"]}
    requested = set(args.cases or known_cases)
    if requested - known_cases:
        raise ValueError(f"Unknown benchmark cases: {', '.join(sorted(requested - known_cases))}")
    for name, gold in manifests.items():
        cases = [case for case in gold["runs"] if f"{name}/{case['id']}" in requested]
        if not cases:
            continue
        corpus_output = directory / name
        corpus_output.mkdir(exist_ok=True)
        registry = corpus_output / "documents.json"
        documents = read_json(registry) if registry.exists() else {}
        required = {document for case in cases for document in case["documents"]}
        for document in sorted(required):
            documents[document] = upload(api, corpora[name] / "fixtures" / document, gold["sha256"][document])
        save_json(registry, documents)
        for repetition in range(1, args.repetitions + 1):
            for case in cases:
                try:
                    run_case(api, corpus_output / str(repetition) / case["id"], make_request(case, documents, templates),
                             args.model, args.variant, args.timeout)
                finally:
                    collect_log(args.retrieval_log, directory / "retrieval.jsonl")


def parse_corpora(values):
    corpora = dict(CORPORA)
    for value in values:
        name, separator, directory = value.partition("=")
        if not separator or name not in corpora:
            raise ValueError("Use --corpus expanded=DIR, long=DIR or real=DIR")
        corpora[name] = Path(directory)
    return corpora


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    common = argparse.ArgumentParser(add_help=False)
    common.add_argument("--output", type=Path, default=ROOT / "logs/retrieval-release-gate")
    common.add_argument("--corpus", action="append", default=[], metavar="NAME=DIR")
    commands = parser.add_subparsers(dest="action", required=True)
    runner = commands.add_parser("run", parents=[common])
    runner.add_argument("--variant", choices=VARIANTS, required=True)
    runner.add_argument("--api", required=True)
    runner.add_argument("--model-api", default="http://127.0.0.1:1234/v1")
    runner.add_argument("--model", default="google/gemma-4-e4b")
    runner.add_argument("--embedding", default="text-embedding-bge-m3")
    runner.add_argument("--repetitions", type=int, choices=(1, 2, 3), default=3)
    runner.add_argument("--timeout", type=int, default=1800)
    runner.add_argument("--cases", nargs="+")
    runner.add_argument("--retrieval-log", type=Path)
    judge = commands.add_parser("summary", parents=[common])
    judge.add_argument("--guardrail-proof", type=Path, help="Surefire XML with the required negative cases")
    judge.add_argument("--compatibility-proof", type=Path, help="Surefire XML for legacy read/re-export")
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    corpora = parse_corpora(args.corpus)
    if args.action == "run":
        run(args, corpora)
        return 0
    from trace_report_release_score import summarize
    return summarize(args, corpora)


if __name__ == "__main__":
    raise SystemExit(main())
