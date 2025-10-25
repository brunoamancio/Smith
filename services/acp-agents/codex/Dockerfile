FROM python:3.12-slim

ARG CODEX_ACP_VERSION=0.3.9
ARG CODEX_ACP_TARGET=x86_64-unknown-linux-gnu
ARG CODEX_ACP_SHA256=b91fcb8ffd990cb6be9e8e841477e45c83e8bdfc97fabee6bc4285c0c4591969

ENV PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1 \
    CODEX_ACP_VERSION=${CODEX_ACP_VERSION}

WORKDIR /app

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl ca-certificates \
    && rm -rf /var/lib/apt/lists/*

RUN curl -fsSL "https://github.com/zed-industries/codex-acp/releases/download/v${CODEX_ACP_VERSION}/codex-acp-${CODEX_ACP_VERSION}-${CODEX_ACP_TARGET}.tar.gz" -o /tmp/codex-acp.tar.gz \
    && echo "${CODEX_ACP_SHA256}  /tmp/codex-acp.tar.gz" | sha256sum -c - \
    && tar -xzf /tmp/codex-acp.tar.gz -C /usr/local/bin \
    && chmod +x /usr/local/bin/codex-acp \
    && rm /tmp/codex-acp.tar.gz

RUN mkdir -p /workspace

COPY requirements.txt /app/requirements.txt
RUN pip install --no-cache-dir -r /app/requirements.txt

COPY app /app/app

ENV CODEX_ACP_BIN=/usr/local/bin/codex-acp \
    CODEX_WORKDIR=/workspace

EXPOSE 8080

CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8080"]
