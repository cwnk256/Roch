FROM python:3.11.14-slim-bookworm@sha256:65a93d69fa75478d554f4ad27c85c1e69fa184956261b4301ebaf6dbb0a3543d AS dependencies

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PIP_DISABLE_PIP_VERSION_CHECK=1

RUN apt-get update \
    && apt-get install -y --no-install-recommends libgl1 libglib2.0-0 libgomp1 \
    && rm -rf /var/lib/apt/lists/*

COPY requirements.txt /tmp/ocr-requirements.txt
RUN --mount=type=cache,target=/root/.cache/pip \
    python -m pip install --only-binary=:all: -r /tmp/ocr-requirements.txt \
    && python -m pip check

FROM python:3.11.14-slim-bookworm@sha256:65a93d69fa75478d554f4ad27c85c1e69fa184956261b4301ebaf6dbb0a3543d AS models

COPY models.py provision.py /build/
RUN python /build/provision.py

FROM dependencies AS service

ENV HOME=/tmp \
    XDG_CACHE_HOME=/tmp/.cache \
    HF_HUB_OFFLINE=1 \
    PADDLE_PDX_DISABLE_MODEL_SOURCE_CHECK=True \
    OPENBLAS_NUM_THREADS=1 \
    OMP_NUM_THREADS=1
WORKDIR /opt/roch-ocr
COPY --from=models /opt/ocr-models /opt/ocr-models
COPY adapter.py models.py network_guard.py server.py ./
USER 10001:10001
EXPOSE 8080
CMD ["python", "server.py"]
