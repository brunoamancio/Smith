from __future__ import annotations

import json
import logging
import os
import uuid
from pathlib import Path
from typing import Any, AsyncIterator, Dict, Optional

from fastapi import FastAPI, Header, HTTPException
from fastapi.responses import JSONResponse, StreamingResponse
from pydantic import BaseModel, Field

from .codex_bridge import CodexError, CodexManager, CodexProcessClosed


def _configure_logging() -> None:
    level = os.getenv("LOG_LEVEL", "INFO").upper()
    logging.basicConfig(level=level, format="%(message)s")


_configure_logging()
logger = logging.getLogger("codex.api")


def _default_binary() -> Path:
    return Path(os.getenv("CODEX_ACP_BIN", "/usr/local/bin/codex-acp"))


def _default_workspace() -> Path:
    return Path(os.getenv("CODEX_WORKDIR", "/workspace"))


manager = CodexManager(binary_path=_default_binary(), workspace=_default_workspace())
app = FastAPI(title="Smith Codex Bridge", version="0.1.0")


class RunRequest(BaseModel):
    agent_name: str = Field(alias="agent_name")
    mode: str
    session_id: Optional[str] = Field(default=None, alias="session_id")
    input: list[Dict[str, Any]]

    class Config:
        populate_by_name = True


def _bearer_token(header: Optional[str]) -> Optional[str]:
    if not header:
        return None
    if header.lower().startswith("bearer "):
        token = header[7:].strip()
        return token or None
    return header.strip() or None


@app.on_event("shutdown")
async def _shutdown_event() -> None:
    await manager.stop()


@app.on_event("startup")
async def _startup_event() -> None:
    logger.info("%s", json.dumps({"message": "Agent started"}))


@app.get("/ping")
async def ping() -> Dict[str, str]:
    return {"status": "ok"}


@app.get("/agents")
async def list_agents() -> Dict[str, Any]:
    return {
        "agents": [
            {
                "name": "codex",
                "description": "Codex ACP agent",
                "metadata": {"provider": "openai", "sdk": "codex-acp"},
            }
        ]
    }


@app.post("/runs")
async def create_run(
    payload: RunRequest,
    authorization: Optional[str] = Header(default=None, alias="Authorization"),
) -> JSONResponse:
    logger.debug(
        "%s",
        json.dumps(
            {
                "message": "incoming_request",
                "path": "/runs",
                "agent": payload.agent_name,
                "mode": payload.mode,
                "session_id": payload.session_id,
                "input_length": len(payload.input),
                "has_token": bool(authorization),
            }
        ),
    )

    token = _bearer_token(authorization)

    if payload.agent_name.lower() != "codex":
        raise HTTPException(
            status_code=404,
            detail={"message": f"Unknown agent '{payload.agent_name}'"},
        )
    if payload.mode.lower() != "sync":
        raise HTTPException(
            status_code=400,
            detail={"message": f"Unsupported run mode '{payload.mode}'"},
        )

    try:
        result = await manager.run_prompt(token, payload.session_id, payload.input)
    except ValueError as exc:
        raise HTTPException(status_code=422, detail={"message": str(exc)}) from exc
    except CodexError as exc:
        status = 401 if exc.code == -32000 else 502
        raise HTTPException(status_code=status, detail=exc.to_dict()) from exc
    except CodexProcessClosed as exc:
        # The process died unexpectedly; surface as 502 for the client.
        raise HTTPException(status_code=502, detail={"message": str(exc)}) from exc

    run_id = str(uuid.uuid4())
    response_payload = {
        "run": {
            "run_id": run_id,
            "agent_name": "codex",
            "session_id": result.session_id,
            "status": "completed",
            "stop_reason": result.stop_reason,
            "output": result.output_messages,
            "events": result.events,
        }
    }

    logger.info(
        "%s",
        json.dumps(
            {
                "message": "run_completed",
                "run_id": run_id,
                "session_id": result.session_id,
                "stop_reason": result.stop_reason,
            }
        ),
    )
    logger.debug(
        "%s",
        json.dumps(
            {
                "message": "outgoing_response",
                "path": "/runs",
                "run_id": run_id,
                "session_id": result.session_id,
                "stop_reason": result.stop_reason,
                "output_messages": len(result.output_messages),
                "event_count": len(result.events),
            }
        ),
    )

    return JSONResponse(response_payload)


@app.get("/session/{session_id}/updates")
async def session_updates(
    session_id: str,
    authorization: Optional[str] = Header(default=None, alias="Authorization"),
) -> StreamingResponse:
    logger.debug(
        "%s",
        json.dumps(
            {
                "message": "incoming_request",
                "path": f"/session/{session_id}/updates",
                "session_id": session_id,
                "has_token": bool(authorization),
            }
        ),
    )

    token = _bearer_token(authorization)

    async def event_stream() -> AsyncIterator[str]:
        try:
            async for update in manager.stream_session(token, session_id):
                payload = json.dumps(update)
                logger.debug(
                    "%s",
                    json.dumps(
                        {
                            "message": "outgoing_stream_event",
                            "session_id": session_id,
                            "payload": update,
                        }
                    ),
                )
                yield f"data: {payload}\n\n"
        except CodexError as exc:
            logger.error(
                "%s",
                json.dumps(
                    {
                        "message": "stream_error",
                        "session_id": session_id,
                        "error": exc.to_dict(),
                    }
                ),
            )
        except CodexProcessClosed as exc:
            logger.warning(
                "%s",
                json.dumps(
                    {
                        "message": "stream_closed",
                        "session_id": session_id,
                        "reason": str(exc),
                    }
                ),
            )
        finally:
            logger.debug(
                "%s",
                json.dumps(
                    {
                        "message": "stream_finished",
                        "session_id": session_id,
                    }
                ),
            )

    return StreamingResponse(event_stream(), media_type="text/event-stream")
