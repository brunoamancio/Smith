# Smith Project - Codex Session Initialization Guide

This guide captures the state of the **Smith** IntelliJ Platform plugin as of the current workspace so a fresh Codex session can collaborate effectively.

---

## 1. Project Overview
Smith is a JetBrains tool window plugin that delivers an AI assistant UI inside the IDE. The implementation focuses on UI and local state management; backend integration is not wired yet.

- **Gradle project**: `smith`
- **Group / version**: `com.agents.smith` / `1.0-SNAPSHOT`
- **Plugin ID**: `com.agents.smith.smith`
- **Target IDE**: JetBrains Rider 2025.2.2.1 (via `intellijPlatform.create("RD", "2025.2.2.1")`)
- **Tooling stack**: Kotlin 2.1.0, IntelliJ Platform Gradle Plugin 2.7.1, Java 21 bytecode
- **JDK for development**: 21 or newer (Oracle JDK 21+ recommended)
- **Current scope**: UI-only; SmithViewModel simulates responses

---

## 2. Build & Run Essentials
- Use the bundled Gradle wrapper (`gradlew`, `gradlew.bat`).
- Useful tasks:
  - `./gradlew runIde` launches Rider with the plugin.
  - `./gradlew buildPlugin` packages the artifact.
- `gradle.properties` enables configuration and build caches and opts out of auto-bundling the Kotlin stdlib (`kotlin.stdlib.default.dependency=false`).
- No external services or secrets are required to launch the UI; all remote calls are stubbed.

---

## 3. Current Capabilities
- The tool window loads a **Task overview** layout with:
  - A conversation preview card (chat history list).
  - A "Tasks" column that currently mirrors the active conversation.
  - A prompt composer with Send and auxiliary toggles.
- Pressing **Send** (or `Ctrl+Enter`) routes text to `SmithViewModel.sendUserMessage`. The view model appends the user message and emits a placeholder assistant reply after ~250 ms.
- Conversations can switch between overview and focused chat using the back button in the header.
- Toolbar toggles (`Auto context`, `Agent/Chat`, `Think More`) are UI-only; they flip icons and labels but do not alter behaviour yet.
- The overflow menu (Add button) exposes three placeholder actions (project file/image upload, guideline creation, AI ignore file). Each shows a "Coming Soon" dialog.
- A Settings gear in the header opens an informational dialog; persistent settings screens are not implemented.

---

## 4. Tool Window Layout
- **Header**: Back button (`AllIcons.Actions.Back`), dynamic title label, Settings gear.
- **Body (overview mode)**: Grid-style layout produced by `buildMainTable()`:
  - Left cell: conversation preview (history list rendered with custom borders).
  - Right cell: tasks pane (`JBList` of `ConversationSummary` items).
  - Bottom row: prompt editor cell spanning full width.
- **Conversation mode**: Replaces the main layout with a full-height chat pane (`JBList<SmithState.Message>` inside a scroll pane) while keeping the prompt composer docked.
- All panels share a dark theme palette (`JBColor(0x1F2023, 0x1F2023)`) and thin separator borders (`idleBorderColor`).

---

## 5. Interaction Flow
1. `SmithState.initial()` seeds the state with a system welcome message.
2. Text entry in the prompt enables the Send button; clearing it disables the button.
3. Send action:
   - Clears the editor, disables Send, forwards the text to the view model.
   - Switches UI into conversation mode.
4. View model emits:
   - User message appended immediately.
   - Streaming flag toggled true, then false after the simulated response.
   - Assistant response describing the echo.
5. History list auto-scrolls to the latest message; conversation summary title derives from the first user message.

---

## 6. State & ViewModel
State is modelled in `src/main/kotlin/com/agents/smith/state/SmithState.kt`:

```kotlin
data class SmithState(
    val sessionId: String = UUID.randomUUID().toString(),
    val connected: Boolean = false,
    val history: List<Message> = emptyList(),
    val streaming: Boolean = false,
    val settings: Settings = Settings(),
    val consentMap: Map<String, Boolean> = emptyMap()
)
```

- `Message` tracks `role`, `content`, and a timestamp.
- `Settings` stores model name, streaming flag, and max tokens.
- `initial()` seeds the history with a system welcome note.

`SmithViewModel` (`src/main/kotlin/com/agents/smith/viewmodel/SmithViewModel.kt`) manages state via `MutableStateFlow` scoped to `Dispatchers.IO`. Key operations:

- `updateConnectionStatus`, `updateSettings`, `updateConsent` mutate top-level fields.
- `sendUserMessage` trims input, appends a user message, and calls `simulateAssistantResponse`.
- `simulateAssistantResponse` toggles `streaming`, delays 250 ms, and appends a canned assistant reply that echoes the user's text.
- `dispose()` cancels the coroutine scope; the tool window panel registers itself as a `Disposable`.

---

## 7. Consent, Settings, and Security
- Consent tracking exists in `SmithState.consentMap`, but no UI prompts are wired to it yet.
- Settings button now opens the IntelliJ Settings dialog (`Settings > Tools > Smith`) backed by a project `PersistentStateComponent`. Fields:
  - Default model name, streaming toggle, max tokens.
  - ACP endpoint URL and optional API token (stored via Password Safe under alias `com.agents.smith.acp.default`).
  - Capability toggles for filesystem, terminal, and apply-patch permissions.
  Updating the form persists immediately and notifies the tool window to refresh its runtime settings.
- No code exits the IDE. All network interactions are stubbed, keeping the UI safe for development without secrets.

---

## 8. Error Handling & UX Notes
- Placeholder actions surface `Messages.showInfoMessage` dialogs labelled "Coming Soon".
- There is no retry or error pipeline yet; `SmithViewModel` assumes success.
- `streaming` is surfaced in state but the UI currently uses it only to toggle internal flags; no spinner/progress indicator is rendered.

---

## 9. Assets & Icons
- Custom icons live in `src/main/resources/icons/` (SVG): `chat.svg`, `cloud.svg`, `cloud_filled.svg`, `lightning_bolt.svg`, `lightning_bolt_filled.svg`, `terminal.svg`, plus existing Smith brand assets.
- Toggle buttons switch between outline/filled variants for visual feedback.
- `plugin.xml` registers `icons/Smith.svg` as the primary plugin icon and binds the tool window factory.

---

## 10. Testing Checklist
- [ ] `./gradlew runIde` launches Rider with the Smith tool window visible on the right.
- [ ] Sending a message appends both user and assistant messages in the chat list.
- [ ] Back button correctly toggles between overview and conversation layouts.
- [ ] Prompt overflow actions and Settings button display their informational dialogs without exceptions.
- [ ] Toggle buttons change icons/text and leave the UI stable.
- [ ] No unexpected exceptions in the IDE log during basic interactions.

---

## 11. Next Steps
- Replace the simulated assistant with real backend connectivity (streaming client, consent prompts, error handling).
- Implement persistent conversation history and multi-conversation navigation in the tasks pane.
- Wire toggle states (`Auto context`, `Agent`, `Think More`) into the future backend contract.
- Build settings UI for endpoint configuration and API key management.
- Extend consent handling so `consentMap` drives outbound requests and prompts the user when new context is required.
