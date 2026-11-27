"""Fetch and verify the model archives during image build."""

from concurrent.futures import ThreadPoolExecutor
from hashlib import file_digest
from shutil import copyfileobj
from tarfile import open as open_archive
from tempfile import TemporaryFile
from urllib.request import urlopen

from models import MODEL_ARCHIVE_BASE, MODEL_FILES, MODEL_ROOT, MODELS


def provision(model):
    name, digest = model
    archive_name = f"{name}_infer"
    with TemporaryFile() as download:
        with urlopen(f"{MODEL_ARCHIVE_BASE}/{archive_name}.tar", timeout=60) as response:
            copyfileobj(response, download, length=1 << 20)
        download.seek(0)
        if file_digest(download, "sha256").hexdigest() != digest:
            raise ValueError(f"Model archive failed checksum verification: {name}")
        download.seek(0)
        directory = MODEL_ROOT / name
        directory.mkdir(parents=True)
        with open_archive(fileobj=download, mode="r:") as archive:
            members = {member.name.removeprefix("./"): member for member in archive}
            for filename in MODEL_FILES:
                member = members[f"{archive_name}/{filename}"]
                if not member.isreg():
                    raise ValueError(f"Invalid model file: {name}/{filename}")
                with archive.extractfile(member) as source, (directory / filename).open("wb") as target:
                    copyfileobj(source, target, length=1 << 20)
    print(f"Provisioned {name}", flush=True)


if __name__ == "__main__":
    with ThreadPoolExecutor(max_workers=4) as executor:
        list(executor.map(provision, MODELS.values()))
