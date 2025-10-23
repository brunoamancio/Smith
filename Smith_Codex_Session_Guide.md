# Smith Project — Codex Session Initialization Guide

This file defines everything a new Codex session needs to know to collaborate on **Smith**, an IntelliJ IDEA plugin project (UI-only stage).

---

## 1. Project Overview
Smith is a project-scoped AI coding agent embedded in the IDE as a **Tool Window**. Initially, only the frontend/UI is implemented. A backend service (BE) will be integrated later.

- **IDE**: IntelliJ IDEA Community (2025+)
- **JDK**: Oracle JDK 24
- **Language**: Kotlin (JVM)
- **Scope**: UI-only (no backend yet)
- **Plugin ID**: `com.yourorg.smith`
- **Version**: `0.1.0`
- **Privacy mode**: `true` (no code upload without consent)

---

## 2. Capabilities (UI-only phase)
Allowed now:
- Show conversational UI inside a Tool Window.
- Send prepared requests to an external endpoint (no sensitive code unless user consents).
- Read editor selection when the user explicitly requests context.
- Preview generated code or patches before applying.

Forbidden:
- Sending full project contents automatically.
- Writing or executing code without user confirmation.
- Storing secrets insecurely.

---

## 3. Tool Window Layout
- **Header**: Smith name + connection indicator.
- **Main Area**: chat/message feed with streaming updates.
- **Prompt Editor**: multi-line text box with context toggles.
- **Buttons**: “Send”, “Insert”, “Explain”, “Apply Patch”.
- **Settings Cog**: API key, endpoint, model, streaming toggle.
- **History Pane**: collapsible previous sessions.

UI updates must be **non-blocking** — background threads for work, Swing EDT for rendering.

---

## 4. Data Structures (JSON Schemas)

**Init / settings**
```json
{
  "client_id": "smith-kotlin-0.1",
  "session_id": "uuid-v4",
  "project": "MyProject",
  "user": {"id": "local-user", "consent_for_context": false},
  "settings": {"model": "gpt-4o-mini", "stream": true, "max_tokens": 1024}
}
```

**Chat request**
```json
{
  "session_id": "uuid-v4",
  "messages": [
    {"role": "system", "content": "You are Smith, an IDE assistant."},
    {"role": "user", "content": "Refactor this method to X."}
  ],
  "context": {
    "include_selection": true,
    "selection_text": "public void foo() { ... }"
  }
}
```

**Streaming response frames**
```json
{"type": "delta", "content": "public void fooRefactored() {"}
{"type": "delta", "content": " ..."} 
{"type": "done"}
```

**Action result**
```json
{"action": "insert_patch", "editor_file": "src/Main.java", "patch": "@@ -1,6 +1,8 ...", "requires_confirmation": true}
```

---

## 5. Consent & Security Rules
Smith must **always** ask the user before sending code externally.

Example consent prompt:
> Smith wants to include the current file (7.2 KB) in a request to the AI. Allow?  
> [Include Selection] [Include File] [Cancel]

Store user choices in workspace config. Use IDE **Password Safe** for API keys.

---

## 6. UX Flows
1. **Quick Question** → send text-only prompt, stream response.
2. **Contextual Assist** → ask user for consent → send selection context.
3. **Apply Change** → show diff preview → confirm before write.
4. **Explain Code** → display formatted explanation block.
5. **Monitor (future)** → live-updating tool window (poll or event-driven).

---

## 7. Session State Model
```kotlin
data class SmithState(
  val sessionId: String,
  val connected: Boolean,
  val history: List<Message>,
  val streaming: Boolean,
  val settings: Settings,
  val consentMap: Map<String, Boolean>
)
```

---

## 8. Error Handling
- Show descriptive messages for network/auth errors.
- Cancel ongoing requests gracefully.
- Retry transient errors (2x, exponential backoff).
- Offer user a “copy request JSON” for debugging.

---

## 9. Kotlin / JDK 24 Implementation Notes
- Use Kotlin coroutines (`Dispatchers.IO` for background, `Dispatchers.Main` for UI).
- Use IntelliJ UI components (`JPanel`, `JBList`, etc.).
- Wrap file edits in `WriteCommandAction.runWriteCommandAction(project)`.
- Dispose coroutines on project close (`Disposable` lifecycle).

---

## 10. Streaming Example (Kotlin)
```kotlin
val call = client.streamChat(request)
val uiScope = CoroutineScope(Dispatchers.Main)

scope.launch {
    client.streamChat(request).collect { frame ->
        when (frame.type) {
            Frame.Type.DELTA -> uiScope.launch { appendToConversation(frame.content) }
            Frame.Type.DONE -> uiScope.launch { markRequestComplete() }
            Frame.Type.ERROR -> uiScope.launch { showError(frame.message) }
        }
    }
}
```

---

## 11. Codex System Prompt Example
```
SYSTEM: You are Smith's UI assistant generator.
Environment:
- IntelliJ IDEA Community + Oracle JDK 24
- Kotlin plugin project, UI-only
- Your job: produce Kotlin-safe UI code, JSON schemas, and workflows.

Constraints:
- No file writes without user confirmation.
- No secret values in examples.
- Use coroutines for async work.
- Always request user consent before sending code externally.
- Focus on incremental, streaming-friendly UI updates.
```

---

## 12. Testing Checklist
- [ ] Tool window loads in sandbox.
- [ ] API key + endpoint persisted correctly.
- [ ] Consent dialog appears before sending context.
- [ ] Streaming response updates UI incrementally.
- [ ] Cancel works mid-response.
- [ ] Patch preview confirm/deny flow functions.
- [ ] Error handling covers auth + network failures.

---

## 13. Next Steps
- Implement `ToolWindowFactory`, `Panel`, and `Settings` classes in Kotlin.
- Integrate streaming client (Ktor preferred).
- Add consent + patch preview UIs.
- Later: connect to backend/LLM endpoint (BE project).
