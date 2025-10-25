from __future__ import annotations

import asyncio
import contextlib
import json
import logging
import os
from dataclasses import dataclass
from pathlib import Path
from typing import Any, AsyncIterator, Awaitable, Callable, Dict, List, Optional


_LOG = logging.getLogger("codex.bridge")


class CodexError(Exception):
    """Raised when the Codex ACP process returns a JSON-RPC error."""

    def __init__(self, code: int, message: str, data: Optional[Any] = None) -> None:
        super().__init__(message)
        self.code = code
        self.data = data

    def to_dict(self) -> Dict[str, Any]:
        payload: Dict[str, Any] = {"code": self.code, "message": str(self)}
        if self.data is not None:
            payload["data"] = self.data
        return payload


class CodexProcessClosed(Exception):
    """Raised when operations are attempted on a closed Codex process."""


NotificationHandler = Callable[[str, Dict[str, Any]], Awaitable[None]]


class CodexProcess:
    """Manages the codex-acp child process and JSON-RPC traffic."""

    def __init__(self, binary_path: Path) -> None:
        self._binary_path = binary_path
        self._proc: Optional[asyncio.subprocess.Process] = None
        self._stdout_task: Optional[asyncio.Task[None]] = None
        self._stderr_task: Optional[asyncio.Task[None]] = None
        self._pending: Dict[str, asyncio.Future[Any]] = {}
        self._notification_handler: Optional[NotificationHandler] = None
        self._id_counter = 0
        self._lock = asyncio.Lock()
        self._current_token: Optional[str] = None

    async def ensure_started(self, token: Optional[str]) -> bool:
        """Ensure the child process is running with the given token.

        Returns True if the process was started or restarted.
        """
        async with self._lock:
            if (
                self._proc is not None
                and self._proc.returncode is None
                and token == self._current_token
            ):
                return False

            await self._shutdown_locked()

            env = os.environ.copy()
            env.pop("CODEX_API_KEY", None)
            if token:
                env["OPENAI_API_KEY"] = token
            else:
                env.pop("OPENAI_API_KEY", None)

            _LOG.info(
                json.dumps(
                    {
                        "message": "Starting codex-acp process",
                        "binary_path": str(self._binary_path),
                        "with_token": bool(token),
                    }
                )
            )

            self._proc = await asyncio.create_subprocess_exec(
                str(self._binary_path),
                stdin=asyncio.subprocess.PIPE,
                stdout=asyncio.subprocess.PIPE,
                stderr=asyncio.subprocess.PIPE,
                env=env,
            )
            self._current_token = token
            self._stdout_task = asyncio.create_task(self._read_stdout())
            self._stderr_task = asyncio.create_task(self._read_stderr())
            return True

    async def _shutdown_locked(self) -> None:
        if self._stdout_task:
            self._stdout_task.cancel()
        if self._stderr_task:
            self._stderr_task.cancel()

        if self._proc and self._proc.returncode is None:
            self._proc.terminate()
            try:
                await asyncio.wait_for(self._proc.wait(), timeout=5)
            except asyncio.TimeoutError:
                self._proc.kill()
                await self._proc.wait()

        if self._stdout_task:
            with contextlib.suppress(asyncio.CancelledError):
                await self._stdout_task
        if self._stderr_task:
            with contextlib.suppress(asyncio.CancelledError):
                await self._stderr_task

        self._stdout_task = None
        self._stderr_task = None
        self._proc = None
        self._current_token = None

        # Fail any pending requests.
        for future in self._pending.values():
            if not future.done():
                future.set_exception(CodexProcessClosed("codex-acp process stopped"))
        self._pending.clear()

    async def stop(self) -> None:
        async with self._lock:
            await self._shutdown_locked()

    def set_notification_handler(self, handler: NotificationHandler) -> None:
        self._notification_handler = handler

    async def request(self, method: str, params: Optional[Dict[str, Any]]) -> Any:
        if not self._proc or self._proc.stdin is None:
            raise CodexProcessClosed("codex-acp process is not running")

        self._id_counter += 1
        request_id = str(self._id_counter)
        message = {
            "jsonrpc": "2.0",
            "id": request_id,
            "method": method,
            "params": params or {},
        }

        if _LOG.isEnabledFor(logging.DEBUG):
            _LOG.debug(
                json.dumps(
                    {
                        "message": "codex_request",
                        "id": request_id,
                        "method": method,
                        "params": params or {},
                    }
                )
            )

        future: asyncio.Future[Any] = asyncio.get_running_loop().create_future()
        self._pending[request_id] = future

        payload = (json.dumps(message) + "\n").encode("utf-8")
        try:
            self._proc.stdin.write(payload)
            await self._proc.stdin.drain()
        except BrokenPipeError as exc:
            if not future.done():
                future.set_exception(CodexProcessClosed(str(exc)))
            raise CodexProcessClosed(str(exc)) from exc

        return await future

    async def _read_stdout(self) -> None:
        assert self._proc and self._proc.stdout
        reader = self._proc.stdout

        while True:
            line = await reader.readline()
            if not line:
                break

            raw = line.decode("utf-8").strip()
            if not raw:
                continue

            try:
                message = json.loads(raw)
            except json.JSONDecodeError:
                _LOG.error(json.dumps({"message": "Failed to decode Codex output", "raw": raw}))
                continue

            if "id" in message:
                request_id = str(message["id"])
                future = self._pending.pop(request_id, None)
                if not future:
                    _LOG.warning(
                        json.dumps(
                            {"message": "No pending request for response", "id": request_id}
                        )
                    )
                    continue

                if "error" in message and message["error"] is not None:
                    error_obj = message["error"]
                    if not future.done():
                        future.set_exception(
                            CodexError(
                                code=error_obj.get("code", -32001),
                                message=error_obj.get("message", "Unknown error"),
                                data=error_obj.get("data"),
                            )
                        )
                    if _LOG.isEnabledFor(logging.DEBUG):
                        _LOG.debug(
                            json.dumps(
                                {
                                    "message": "codex_response_error",
                                    "id": request_id,
                                    "error": error_obj,
                                }
                            )
                        )
                else:
                    if _LOG.isEnabledFor(logging.DEBUG):
                        _LOG.debug(
                            json.dumps(
                                {
                                    "message": "codex_response",
                                    "id": request_id,
                                    "result": message.get("result"),
                                }
                            )
                        )
                    if not future.done():
                        future.set_result(message.get("result"))
            elif "method" in message:
                handler = self._notification_handler
                if handler:
                    asyncio.create_task(handler(message["method"], message.get("params") or {}))
            else:
                _LOG.debug(
                    json.dumps({"message": "Received unexpected message without id/method", "raw": raw})
                )

        # Process exited, fail all pending futures.
        for future in self._pending.values():
            if not future.done():
                future.set_exception(CodexProcessClosed("codex-acp stdout closed"))
        self._pending.clear()

    async def _read_stderr(self) -> None:
        assert self._proc and self._proc.stderr
        reader = self._proc.stderr

        while True:
            line = await reader.readline()
            if not line:
                break
            _LOG.info(
                json.dumps(
                    {
                        "message": "codex-acp stderr",
                        "line": line.decode("utf-8", errors="replace").rstrip(),
                    }
                )
            )

    @property
    def current_token(self) -> Optional[str]:
        return self._current_token


@dataclass
class RunResult:
    session_id: str
    codex_session_id: str
    stop_reason: str
    output_messages: List[Dict[str, Any]]
    events: List[Dict[str, Any]]


class CodexManager:
    """High-level orchestrator that exposes prompt execution."""

    def __init__(self, binary_path: Path, workspace: Path) -> None:
        self._process = CodexProcess(binary_path)
        self._workspace = workspace
        self._initialized = False
        self._client_to_codex: Dict[str, str] = {}
        self._session_watchers: Dict[str, List[asyncio.Queue[Dict[str, Any]]]] = {}
        self._auth_methods: set[str] = set()
        self._lock = asyncio.Lock()

        self._process.set_notification_handler(self._handle_notification)

    async def stop(self) -> None:
        await self._process.stop()

    async def run_prompt(
        self,
        token: Optional[str],
        client_session_id: Optional[str],
        messages: List[Dict[str, Any]],
    ) -> RunResult:
        client_session = client_session_id or ""
        restarted = await self._process.ensure_started(token)
        if restarted:
            _LOG.info(
                json.dumps(
                    {
                        "message": "codex-acp restarted",
                        "reason": "token_changed" if token else "process_started",
                    }
                )
            )
            self._initialized = False
            self._client_to_codex.clear()
            self._session_watchers.clear()

        await self._ensure_initialized(token)

        codex_session = await self._ensure_session(client_session)
        queue: asyncio.Queue[Dict[str, Any]] = asyncio.Queue()
        watchers = self._session_watchers.setdefault(codex_session, [])
        watchers.append(queue)
        attached = True

        try:
            prompt_payload = _build_prompt_payload(messages)
        except ValueError as exc:
            self._detach_watcher(codex_session, queue)
            attached = False
            raise

        _LOG.info(
            json.dumps(
                {
                    "message": "Dispatching prompt to codex-acp",
                    "session_id": client_session or None,
                    "codex_session_id": codex_session,
                    "content_blocks": len(prompt_payload),
                }
            )
        )

        accumulator = _PromptAccumulator()
        response_task = asyncio.create_task(
            self._process.request(
                "session/prompt",
                {
                    "sessionId": codex_session,
                    "prompt": prompt_payload,
                },
            )
        )

        queue_task: Optional[asyncio.Task[Dict[str, Any]]] = None
        stop_reason = "unknown"

        try:
            while True:
                if queue_task is None:
                    queue_task = asyncio.create_task(queue.get())
                done, _ = await asyncio.wait(
                    {response_task, queue_task}, return_when=asyncio.FIRST_COMPLETED
                )

                if queue_task in done:
                    notification = queue_task.result()
                    accumulator.ingest(notification)
                    queue_task = None

                if response_task in done:
                    prompt_response = response_task.result()
                    stop_reason = (prompt_response or {}).get("stopReason", "unknown")
                    break
        finally:
            if queue_task:
                queue_task.cancel()
                with contextlib.suppress(asyncio.CancelledError):
                    await queue_task
            if attached:
                self._detach_watcher(codex_session, queue)

        # Drain remaining notifications if any were queued.
        while not queue.empty():
            accumulator.ingest(queue.get_nowait())

        output_messages = accumulator.build_output()
        events = list(accumulator.events)

        _LOG.info(
            json.dumps(
                {
                    "message": "Prompt completed",
                    "session_id": client_session or None,
                    "codex_session_id": codex_session,
                    "stop_reason": stop_reason,
                    "assistant_tokens": accumulator.assistant_token_count,
                }
            )
        )
        if _LOG.isEnabledFor(logging.DEBUG):
            _LOG.debug(
                json.dumps(
                    {
                        "message": "prompt_stream_summary",
                        "codex_session_id": codex_session,
                        "output_messages": output_messages,
                        "events": events,
                    }
                )
            )

        return RunResult(
            session_id=client_session or codex_session,
            codex_session_id=codex_session,
            stop_reason=stop_reason,
            output_messages=output_messages,
            events=events,
        )

    async def stream_session(
        self, token: Optional[str], client_session_id: str
    ) -> AsyncIterator[Dict[str, Any]]:
        effective_token = token if token is not None else self._process.current_token
        await self._process.ensure_started(effective_token)
        await self._ensure_initialized(effective_token)
        codex_session = await self._ensure_session(client_session_id)

        queue: asyncio.Queue[Dict[str, Any]] = asyncio.Queue()
        watchers = self._session_watchers.setdefault(codex_session, [])
        watchers.append(queue)

        try:
            while True:
                update = await queue.get()
                if _LOG.isEnabledFor(logging.DEBUG):
                    _LOG.debug(
                        json.dumps(
                            {
                                "message": "session_update_dispatch",
                                "client_session_id": client_session_id or None,
                                "codex_session_id": codex_session,
                                "payload": update,
                            }
                        )
                    )
                yield update
        finally:
            self._detach_watcher(codex_session, queue)

    async def _ensure_initialized(self, token: Optional[str]) -> None:
        if self._initialized:
            return

        init_result = await self._process.request(
            "initialize",
            {
                "protocolVersion": 1,
                "clientCapabilities": {
                    "fs": {"readTextFile": False, "writeTextFile": False},
                    "terminal": False,
                },
            },
        )

        self._auth_methods = {
            method.get("id")
            for method in (init_result or {}).get("authMethods", [])
            if isinstance(method, dict) and "id" in method
        }

        if token:
            if "openai-api-key" not in self._auth_methods:
                raise CodexError(
                    code=-32000,
                    message="Codex agent does not support OPENAI_API_KEY authentication",
                )
            await self._process.request("authenticate", {"methodId": "openai-api-key"})

        self._initialized = True

    async def _ensure_session(self, client_session_id: str) -> str:
        if client_session_id in self._client_to_codex:
            return self._client_to_codex[client_session_id]

        response = await self._process.request(
            "session/new",
            {
                "cwd": str(self._workspace),
                "mcpServers": [],
            },
        )
        codex_session_raw = response.get("sessionId")
        if not codex_session_raw:
            raise CodexError(-32603, "Codex session/new did not return a sessionId")
        codex_session = str(codex_session_raw)
        self._client_to_codex[client_session_id] = codex_session
        _LOG.info(
            json.dumps(
                {
                    "message": "Created Codex session",
                    "client_session_id": client_session_id or None,
                    "codex_session_id": codex_session,
                }
            )
        )
        return codex_session

    async def _handle_notification(self, method: str, params: Dict[str, Any]) -> None:
        if method != "session/update":
            _LOG.debug(json.dumps({"message": "Ignoring Codex notification", "method": method}))
            return

        session_id = str(params.get("sessionId", ""))
        if not session_id:
            return

        if _LOG.isEnabledFor(logging.DEBUG):
            _LOG.debug(
                json.dumps(
                    {
                        "message": "codex_notification",
                        "method": method,
                        "session_id": session_id,
                        "payload": params,
                    }
                )
            )

        for queue in self._session_watchers.get(session_id, []):
            queue.put_nowait(params)
        _LOG.warning(
            json.dumps(
                {
                    "message": "codex_notification",
                    "method": method,
                    "params": params,
                }
            )
        )

    def _detach_watcher(
        self, session_id: str, queue: asyncio.Queue[Dict[str, Any]]
    ) -> None:
        watchers = self._session_watchers.get(session_id)
        if not watchers:
            return
        with contextlib.suppress(ValueError):
            watchers.remove(queue)
        if not watchers:
            self._session_watchers.pop(session_id, None)


class _PromptAccumulator:
    """Collects updates streaming from Codex and renders final output."""

    def __init__(self) -> None:
        self._assistant_text_parts: List[str] = []
        self.events: List[Dict[str, Any]] = []

    def ingest(self, notification: Dict[str, Any]) -> None:
        if not isinstance(notification, dict):
            return

        update = notification.get("update") or {}
        if not isinstance(update, dict):
            return

        kind = update.get("sessionUpdate")
        if kind == "agent_message_chunk":
            content = update.get("content") or {}
            if isinstance(content, dict) and content.get("type") == "text":
                text = content.get("text")
                if isinstance(text, str) and text:
                    self._assistant_text_parts.append(text)
        self.events.append(notification)

    def build_output(self) -> List[Dict[str, Any]]:
        if not self._assistant_text_parts:
            return []

        message = {
            "role": "assistant",
            "parts": [
                {
                    "content_type": "text/plain",
                    "content_encoding": "plain",
                    "content": "".join(self._assistant_text_parts),
                }
            ],
        }
        return [message]

    @property
    def assistant_token_count(self) -> int:
        return sum(len(part) for part in self._assistant_text_parts)


def _build_prompt_payload(messages: List[Dict[str, Any]]) -> List[Dict[str, Any]]:
    content_blocks: List[Dict[str, Any]] = []

    for message in messages:
        parts = message.get("parts") or []
        for part in parts:
            if not isinstance(part, dict):
                continue
            content_type = part.get("content_type") or part.get("type")
            if content_type not in {"text/plain", "text"}:
                continue
            text = part.get("content") or part.get("text") or ""
            if text:
                content_blocks.append({"type": "text", "text": text})

    if not content_blocks:
        raise ValueError("Prompt payload did not contain any plain text content")

    return content_blocks
