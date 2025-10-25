# Codex ACP Bridge

This service wraps the `codex-acp` binary in an HTTP interface that matches the
REST shape expected by the Smith Rider plugin. It launches the upstream agent,
handles JSON-RPC over stdio, and exposes endpoints under `/runs`, `/agents`,
and `/ping`.

## Running locally

```bash
docker compose up codex-agent
```

The container listens on port `8080` (published as `8002` in the shared
compose file). Each request should include the user's OpenAI API key in the
`Authorization: Bearer <TOKEN>` header so the shim can restart Codex with the
correct credentials.

### Streaming updates

Clients can subscribe to server-sent events at
`GET /session/<session-id>/updates`. Each event contains the raw Codex ACP
`session/update` payload so the UI can stream tokens as they arrive.

## Environment

| Variable            | Description                                           |
| ------------------- | ----------------------------------------------------- |
| `CODEX_ACP_VERSION` | Codex release to download (defaults to `0.3.9`).      |
| `CODEX_ACP_BIN`     | Path to the extracted binary (defaults to `/usr/local/bin/codex-acp`). |
| `CODEX_WORKDIR`     | Working directory passed to Codex sessions.           |
| `LOG_LEVEL`         | Optional log level for shim diagnostics (`INFO`).     |

Structured logs are written to stdout so they surface in container logs.
