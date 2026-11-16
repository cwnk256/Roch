"""Run against a disposable Roch API. Writes review artifacts and reindexes fixture A."""

import argparse
import hashlib
import json
import mimetypes
import time
import uuid
from pathlib import Path
from urllib import request


ROOT = Path(__file__).resolve().parents[1]
FIXTURES = ROOT / "rag/src/test/resources/traceability"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=("upload", "analysis", "compare", "holdout", "reexport"))
    parser.add_argument("--api", default="http://127.0.0.1:8087/api")
    parser.add_argument("--output", type=Path, default=ROOT / "logs/trace-verify")
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    def transfer(path, method="GET", payload=None, content_type="application/json"):
        if isinstance(payload, dict):
            payload = json.dumps(payload, ensure_ascii=False).encode()
        req = request.Request(args.api + path, data=payload, method=method,
                              headers={"Content-Type": content_type})
        with request.urlopen(req, timeout=240) as response:
            return response.read()

    def save(name, data):
        (args.output / name).write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding="utf-8")

    ids_path = args.output / "fixture-ids.json"
    if args.action == "upload":
        ids = {}
        for relative in ["A.pdf", "B.txt", "C.txt", "holdout/D.txt", "holdout/E.txt"]:
            path = FIXTURES / relative
            data = path.read_bytes()
            boundary = uuid.uuid4().hex
            envelope = (f'--{boundary}\r\nContent-Disposition: form-data; name="file"; '
                        f'filename="{path.name}"\r\nContent-Type: {mimetypes.guess_type(path)[0]}\r\n\r\n')
            body = envelope.encode() + data + f"\r\n--{boundary}--\r\n".encode()
            endpoint = "/documents/pdf" if path.suffix == ".pdf" else "/documents"
            result = json.loads(transfer(endpoint, "POST", body, f"multipart/form-data; boundary={boundary}"))
            assert result["md5"] == hashlib.md5(data).hexdigest(), relative
            ids[path.stem] = result["md5"]
            print(path.name, result["chunkCount"], flush=True)
        save(ids_path.name, ids)
        return

    ids = json.loads(ids_path.read_text(encoding="utf-8"))
    if args.action == "reexport":
        task_id = (args.output / "analysis.id").read_text().strip()
        path = f"/document-tasks/{task_id}"
        before = json.loads(transfer(path + "/result"))
        transfer("/documents/" + ids["A"] + "/reindex", "POST")
        after = json.loads(transfer(path + "/result"))
        for field in ("summary", "sections", "summaryClaimIds", "evidenceSnapshots"):
            assert before.get(field) == after.get(field), field
        artifact_ids = set()
        for style, appendix in (("NUMBERED", True), ("FILE_LOCATION", False)):
            options = {"citationStyle": style, "includeToc": appendix, "includeEvidenceAppendix": appendix}
            artifact = json.loads(transfer(path + "/artifacts/pdf", "POST", options))
            artifact_ids.add(artifact["artifactId"])
            pdf = transfer(artifact["downloadUrl"].removeprefix("/api"))
            assert pdf.startswith(b"%PDF")
            (args.output / f"analysis-{style}.pdf").write_bytes(pdf)
        assert len(artifact_ids) == 2
        return

    is_analysis = args.action == "analysis"
    task_type = "REPORT" if is_analysis else "COMPARE"
    templates = json.loads(transfer("/document-tasks/templates"))
    template = next(t for t in templates if t["type"] == task_type)
    docs = ["A", "B"] if is_analysis else (["D", "E"] if args.action == "holdout" else ["A", "B", "C"])
    dimensions = ["固定检查周期", "测试预算"] if args.action == "holdout" else ["固定校准周期", "检修断电", "年度设备预算"]
    payload = {"type": task_type, "documentIds": [ids[key] for key in docs],
               "templateId": template["templateId"], "templateVersion": template["templateVersion"],
               "instruction": "逐项核对所选资料中的设备维护要求及金额。引用原文，明确条件和单位。"}
    if not is_analysis:
        payload["comparisonDimensions"] = dimensions
    path = "/document-tasks/" + json.loads(transfer("/document-tasks", "POST", payload))["taskId"]
    task_id = path.split("/")[-1]
    (args.output / f"{args.action}.id").write_text(task_id)
    print(args.action, task_id, flush=True)
    deadline = time.monotonic() + 1800
    while time.monotonic() < deadline:
        state = json.loads(transfer(path))
        if state["status"] == "FAILED":
            raise RuntimeError((task_id, state.get("errorCode"), state.get("errorMessage")))
        if state["status"] == "COMPLETED":
            break
        time.sleep(3)
    else:
        raise TimeoutError(task_id)
    report = json.loads(transfer(path + "/result"))
    save(args.action + ".json", report)
    assert report["schemaVersion"] == "v2"
    if not is_analysis:
        assert {d["documentId"] for d in report["comparison"]["documents"]} == set(payload["documentIds"])
        for row in report["comparison"]["rows"]:
            assert {c["documentId"] for c in row["cells"]} == set(payload["documentIds"])
    assert report.get("evidenceSnapshots"), "No evidence captured; review the generated report"
    print("Review", args.output / (args.action + ".json"), flush=True)


if __name__ == "__main__":
    main()
