package com.agents.smith.settings

import com.agents.smith.state.SmithState
import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.application.ApplicationManager
import com.intellij.util.xmlb.XmlSerializerUtil
import com.intellij.util.messages.Topic
import com.intellij.openapi.project.Project

@State(
    name = "SmithSettings",
    storages = [Storage(StoragePathMacros.WORKSPACE_FILE)]
)
@Service(Service.Level.PROJECT)
class SmithSettingsService : PersistentStateComponent<SmithSettingsService.State> {

    private var state: State = State()
    @Volatile private var runtimeAllowTerminal: Boolean = false
    @Volatile private var runtimeAllowApplyPatch: Boolean = false

    override fun getState(): State = state

    override fun loadState(state: State) {
        XmlSerializerUtil.copyBean(state, this.state)
    }

    fun toSmithSettings(): SmithState.Settings {
        return SmithState.Settings(
            model = state.model,
            stream = state.stream,
            maxTokens = state.maxTokens,
            acpEndpoint = state.acpEndpoint,
            acpTokenAlias = tokenAliasOrNull(),
            acpCapabilities = SmithState.AcpCapabilities(
                allowFileSystem = state.allowFileSystem,
                allowTerminal = runtimeAllowTerminal,
                allowApplyPatch = runtimeAllowApplyPatch
            )
        )
    }

    fun save(settings: SmithState.Settings, token: String?) {
        state.model = settings.model
        state.stream = settings.stream
        state.maxTokens = settings.maxTokens
        state.acpEndpoint = settings.acpEndpoint
        state.allowFileSystem = settings.acpCapabilities.allowFileSystem

        val alias = settings.acpTokenAlias ?: DEFAULT_TOKEN_ALIAS
        if (token.isNullOrBlank()) {
            clearToken()
        } else {
            storeToken(alias, token)
        }

        state.acpTokenAlias = if (token.isNullOrBlank()) null else alias
        runtimeAllowTerminal = settings.acpCapabilities.allowTerminal
        runtimeAllowApplyPatch = settings.acpCapabilities.allowApplyPatch
        notifyListeners()
    }

    fun loadToken(alias: String? = tokenAliasOrNull()): String {
        val resolved = alias ?: return ""
        val attributes = credentialAttributes(resolved)
        return PasswordSafe.instance.get(attributes)?.getPasswordAsString().orEmpty()
    }

    private fun tokenAliasOrNull(): String? = state.acpTokenAlias?.takeIf { it.isNotBlank() }

    private fun credentialAttributes(alias: String) =
        CredentialAttributes("com.agents.smith.acp.$alias")

    private fun storeToken(alias: String, token: String) {
        val attributes = credentialAttributes(alias)
        PasswordSafe.instance.set(attributes, Credentials(alias, token))
    }

    private fun clearToken() {
        tokenAliasOrNull()?.let { alias ->
            val attributes = credentialAttributes(alias)
            PasswordSafe.instance.set(attributes, null)
        }
    }

    private fun notifyListeners() {
        val settings = toSmithSettings()
        ApplicationManager.getApplication()
            .messageBus
            .syncPublisher(TOPIC)
            .smithSettingsChanged(settings)
    }

    fun updateRuntimeCapabilities(allowTerminal: Boolean, allowApplyPatch: Boolean) {
        runtimeAllowTerminal = allowTerminal
        runtimeAllowApplyPatch = allowApplyPatch
    }

    class State {
        var model: String = "gpt-5-codex"
        var stream: Boolean = true
        var maxTokens: Int = 1024
        var acpEndpoint: String = ""
        var acpTokenAlias: String? = null
        var allowFileSystem: Boolean = false

    }

    companion object {
        const val DEFAULT_TOKEN_ALIAS: String = "default"

        val TOPIC: Topic<SmithSettingsListener> = Topic.create(
            "SmithSettingsChanged",
            SmithSettingsListener::class.java
        )

        fun getInstance(project: Project): SmithSettingsService =
            project.getService(SmithSettingsService::class.java)
    }
}

fun interface SmithSettingsListener {
    fun smithSettingsChanged(settings: SmithState.Settings)
}
