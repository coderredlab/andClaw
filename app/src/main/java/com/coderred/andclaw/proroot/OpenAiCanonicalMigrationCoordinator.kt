package com.coderred.andclaw.proroot

import com.coderred.andclaw.data.OpenAiConnectionMode
import com.coderred.andclaw.data.OpenAiConnectionModeResolutionInput
import com.coderred.andclaw.data.PreferencesManager
import com.coderred.andclaw.data.resolveOpenAiConnectionMode
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.StandardCopyOption
import org.json.JSONArray
import org.json.JSONObject

internal class OpenAiCanonicalMigrationCoordinator(
    private val preferencesManager: PreferencesManager,
    private val rootfsDir: File,
    private val credentialInventoryReader: (File, String?) -> OpenClawAuthProfileStore.CredentialInventory =
        OpenClawAuthProfileStore::inspectCredentialInventory,
    private val credentialMigrator: (
        File,
        String?,
        OpenClawAuthProfileStore.CredentialType?,
    ) -> OpenClawAuthProfileStore.MigrationResult = { rootfs, apiKey, preferredType ->
        OpenClawAuthProfileStore.migrateCanonicalOpenAiProfiles(
            rootfsDir = rootfs,
            preferenceApiKey = apiKey,
            preferredCredentialType = preferredType,
            markComplete = {},
        )
    },
) {
    data class Outcome(
        val applicable: Boolean,
        val ran: Boolean,
        val mode: OpenAiConnectionMode?,
        val changed: Boolean,
    )

    suspend fun migrateIfNeeded(force: Boolean = false): Outcome {
        val installedVersion = OpenClawCodexModelScope.readInstalledOpenClawVersion(rootfsDir)
        if (!isAtLeast(installedVersion, MINIMUM_CANONICAL_VERSION)) {
            return Outcome(applicable = false, ran = false, mode = null, changed = false)
        }

        val preferencesSnapshot = preferencesManager.getOpenAiMigrationPreferencesSnapshot()
        val inventory = credentialInventoryReader(rootfsDir, preferencesSnapshot.preferenceApiKey)
        val inactiveWithoutOpenAiCredentials =
            preferencesSnapshot.activeProvider.isNonOpenAiLaunchProvider() &&
                !hasAnyOpenAiCredential(preferencesSnapshot.preferenceApiKey)
        val runtimeInspection = OpenAiCanonicalRuntimeState.inspect(
            rootfsDir = rootfsDir,
            hasUsableCodexCredential = inventory.hasUsableCodexOAuth,
            hasUsablePlatformApiKeyCredential = inventory.hasUsableOpenAiApiKey,
        )
        val hasLegacyActiveReference = preferencesSnapshot.hasLegacyActiveReference ||
            inventory.hasLegacyActiveReference ||
            runtimeInspection.hasLegacyActiveReference
        val storedCanonicalMode =
            OpenAiConnectionMode.fromStorageValue(preferencesSnapshot.canonicalMode)
        if (
            !force &&
            preferencesSnapshot.migrationComplete &&
            !hasLegacyActiveReference &&
            storedCanonicalMode != null &&
            (
                inactiveWithoutOpenAiCredentials ||
                    (
                        hasUsableCredential(inventory, storedCanonicalMode) &&
                            inventory.activeCredentialTypeHint.toConnectionMode() == storedCanonicalMode &&
                            hasUsableCanonicalSqliteActiveProfile(storedCanonicalMode)
                    )
                ) &&
            OpenAiCanonicalRuntimeState.isConsistent(rootfsDir, storedCanonicalMode)
        ) {
            return Outcome(
                applicable = true,
                ran = false,
                mode = storedCanonicalMode,
                changed = false,
            )
        }

        val mode = resolveOpenAiConnectionMode(
            OpenAiConnectionModeResolutionInput(
                canonicalMode = preferencesSnapshot.canonicalMode,
                hasUsableCodexCredential = inventory.hasUsableCodexOAuth,
                hasUsablePlatformApiKeyCredential = inventory.hasUsableOpenAiApiKey,
                legacyProvider = preferencesSnapshot.activeProvider,
                sqliteActiveModeHint = inventory.activeCredentialTypeHint.toConnectionMode(),
                configActiveModeHint = runtimeInspection.modeHint,
            ),
        )
        val preferredCredentialType = when (mode) {
            OpenAiConnectionMode.CODEX_SUBSCRIPTION -> OpenClawAuthProfileStore.CredentialType.OAUTH
            OpenAiConnectionMode.PLATFORM_API_KEY -> OpenClawAuthProfileStore.CredentialType.API_KEY
        }

        val authResult = if (inactiveWithoutOpenAiCredentials) {
            OpenClawAuthProfileStore.MigrationResult(
                changed = false,
                activeCredentialType = null,
                activeProfileId = null,
            )
        } else {
            check(hasUsableCredential(inventory, mode)) {
                "Canonical OpenAI auth state did not match target mode."
            }
            credentialMigrator(
                rootfsDir,
                preferencesSnapshot.preferenceApiKey,
                preferredCredentialType,
            )
        }
        val preferencesChanged = preferencesManager.canonicalizeOpenAiModelPreferences()
        val runtimeChanged = OpenAiCanonicalRuntimeState.normalize(rootfsDir, mode)
        val modeChanged = preferencesManager.applyCanonicalOpenAiMode(
            OpenAiConnectionModeResolutionInput(
                canonicalMode = mode.storageValue,
                hasUsableCodexCredential = inventory.hasUsableCodexOAuth,
                hasUsablePlatformApiKeyCredential = inventory.hasUsableOpenAiApiKey,
                legacyProvider = preferencesSnapshot.activeProvider,
                sqliteActiveModeHint = inventory.activeCredentialTypeHint.toConnectionMode(),
                configActiveModeHint = runtimeInspection.modeHint,
            ),
        )
        if (authResult.activeCredentialType != null) {
            check(authResult.activeCredentialType == preferredCredentialType) {
                "Canonical OpenAI auth state did not match target mode."
            }
            check(authResult.activeProfileId == OpenAiCanonicalRuntimeState.launchPolicy(mode).authProfileId) {
                "Canonical OpenAI active profile did not match target mode."
            }
        }
        val verifiedPreferences = preferencesManager.getOpenAiMigrationPreferencesSnapshot()
        check(
            OpenAiConnectionMode.fromStorageValue(verifiedPreferences.canonicalMode) == mode,
        ) {
            "Canonical OpenAI preference mode read-back did not match target mode."
        }
        OpenAiCanonicalRuntimeState.verify(rootfsDir, mode)
        preferencesManager.markOpenAiCanonicalMigrationComplete()
        return Outcome(
            applicable = true,
            ran = true,
            mode = mode,
            changed = authResult.changed || preferencesChanged || runtimeChanged || modeChanged,
        )
    }

    private fun OpenClawAuthProfileStore.CredentialType?.toConnectionMode(): OpenAiConnectionMode? =
        when (this) {
            OpenClawAuthProfileStore.CredentialType.OAUTH -> OpenAiConnectionMode.CODEX_SUBSCRIPTION
            OpenClawAuthProfileStore.CredentialType.API_KEY -> OpenAiConnectionMode.PLATFORM_API_KEY
            null -> null
        }

    private fun String?.isNonOpenAiLaunchProvider(): Boolean {
        val provider = this?.trim()?.lowercase().orEmpty()
        return provider.isNotEmpty() &&
            provider !in setOf("openai", "openai-codex", "codex", "codex-cli")
    }

    private fun hasAnyOpenAiCredential(preferenceApiKey: String?): Boolean {
        if (!preferenceApiKey.isNullOrBlank()) return true
        val snapshot = OpenClawAuthProfileStore.readRawSnapshot(rootfsDir)
        return listOf(snapshot.sqliteStore, snapshot.jsonStore).any { store ->
            val profiles = store?.optJSONObject("profiles") ?: return@any false
            profiles.keys().asSequence().any { profileId ->
                val credential = profiles.optJSONObject(profileId)
                val provider = credential?.optString("provider")?.trim()?.lowercase().orEmpty()
                profileId.trim().lowercase().let {
                    it.startsWith("openai:") || it.startsWith("openai-codex:")
                } || provider == "openai" || provider == "openai-codex"
            }
        }
    }

    private fun hasUsableCredential(
        inventory: OpenClawAuthProfileStore.CredentialInventory,
        mode: OpenAiConnectionMode,
    ): Boolean = when (mode) {
        OpenAiConnectionMode.CODEX_SUBSCRIPTION -> inventory.hasUsableCodexOAuth
        OpenAiConnectionMode.PLATFORM_API_KEY -> inventory.hasUsableOpenAiApiKey
    }

    private fun hasUsableCanonicalSqliteActiveProfile(mode: OpenAiConnectionMode): Boolean {
        val credentialType = when (mode) {
            OpenAiConnectionMode.CODEX_SUBSCRIPTION -> OpenClawAuthProfileStore.CredentialType.OAUTH
            OpenAiConnectionMode.PLATFORM_API_KEY -> OpenClawAuthProfileStore.CredentialType.API_KEY
        }
        return OpenClawAuthProfileStore.hasUsableCanonicalSqliteSelection(
            rootfsDir = rootfsDir,
            profileId = OpenAiCanonicalRuntimeState.launchPolicy(mode).authProfileId,
            expectedCredentialType = credentialType,
        )
    }

    private fun isAtLeast(version: String?, minimum: String): Boolean {
        val current = parseVersion(version) ?: return false
        val required = parseVersion(minimum) ?: return false
        val size = maxOf(current.size, required.size)
        for (index in 0 until size) {
            val comparison = current.getOrElse(index) { 0 }.compareTo(required.getOrElse(index) { 0 })
            if (comparison != 0) return comparison > 0
        }
        return true
    }

    private fun parseVersion(version: String?): List<Int>? {
        if (version.isNullOrBlank()) return null
        return version.trim().split('.').map { segment ->
            segment.takeWhile(Char::isDigit).takeIf(String::isNotEmpty)?.toIntOrNull() ?: return null
        }
    }

    private companion object {
        const val MINIMUM_CANONICAL_VERSION = "2026.7.1"
    }
}

internal object OpenAiCanonicalRuntimeState {
    private const val OPENCLAW_CONFIG_PATH = "root/.openclaw/openclaw.json"
    private const val SESSIONS_INDEX_PATH = "root/.openclaw/agents/main/sessions/sessions.json"
    private const val SESSIONS_DIR_PATH = "root/.openclaw/agents/main/sessions"
    private val LEGACY_OPENAI_PROVIDERS = setOf("openai-codex", "codex", "codex-cli")

    data class Inspection(
        val modeHint: OpenAiConnectionMode?,
        val hasLegacyActiveReference: Boolean,
    )

    data class LaunchPolicy(
        val authProfileId: String,
        val agentRuntimeId: String,
        val injectOpenAiApiKey: Boolean,
    )

    class ManagedConfigSnapshot internal constructor(
        internal val originalConfig: JSONObject,
        internal val managedPaths: List<List<String>>,
    )

    fun captureManagedConfigSnapshot(rootfsDir: File): ManagedConfigSnapshot {
        val originalConfig =
            readJsonIfPresent(File(rootfsDir, OPENCLAW_CONFIG_PATH)) ?: JSONObject()
        val managedPaths = linkedSetOf<List<String>>()
        for (mode in OpenAiConnectionMode.entries) {
            val normalized = JSONObject(originalConfig.toString())
            normalizeConfig(normalized, launchPolicy(mode))
            collectManagedConfigPaths(
                originalPresent = true,
                original = originalConfig,
                normalizedPresent = true,
                normalized = normalized,
                path = emptyList(),
                paths = managedPaths,
            )
        }
        managedPaths += listOf(
            jsonKeyToken("agents"),
            jsonKeyToken("defaults"),
            jsonKeyToken("model"),
        )
        managedPaths += listOf(
            jsonKeyToken("agents"),
            jsonKeyToken("defaults"),
            jsonKeyToken("models"),
        )
        managedPaths += listOf(
            jsonKeyToken("models"),
            jsonKeyToken("providers"),
        )
        return ManagedConfigSnapshot(
            originalConfig = JSONObject(originalConfig.toString()),
            managedPaths = managedPaths.toList(),
        )
    }

    fun restoreManagedConfigSnapshot(
        rootfsDir: File,
        snapshot: ManagedConfigSnapshot,
    ) {
        if (snapshot.managedPaths.isEmpty()) return
        val configFile = File(rootfsDir, OPENCLAW_CONFIG_PATH)
        val current = readJsonIfPresent(configFile) ?: JSONObject()
        val original = snapshot.originalConfig

        snapshot.managedPaths
            .filter { jsonPathValue(original, it).present }
            .sortedBy { it.size }
            .forEach { path ->
                setJsonPathValue(
                    root = current,
                    path = path,
                    value = checkNotNull(jsonPathValue(original, path).value),
                )
            }
        snapshot.managedPaths
            .filterNot { jsonPathValue(original, it).present }
            .sortedByDescending { it.size }
            .forEach { path ->
                removeJsonPathValue(current, path)
                pruneAbsentEmptyParents(current, original, path)
            }

        writeJsonAtomically(configFile, current)
        verifyManagedConfigSnapshot(rootfsDir, snapshot)
    }

    fun verifyManagedConfigSnapshot(
        rootfsDir: File,
        snapshot: ManagedConfigSnapshot,
    ) {
        if (snapshot.managedPaths.isEmpty()) return
        val restored = readJsonIfPresent(File(rootfsDir, OPENCLAW_CONFIG_PATH))
            ?: error("OpenAI managed config disappeared during transition rollback.")
        val original = snapshot.originalConfig
        val mismatches = snapshot.managedPaths.filter { path ->
            val expected = jsonPathValue(original, path)
            val actual = jsonPathValue(restored, path)
            expected.present != actual.present ||
                (expected.present && !jsonValuesEqual(expected.value, actual.value))
        }
        check(mismatches.isEmpty()) {
            "OpenAI managed config read-back did not match the transition snapshot: " +
                mismatches.joinToString { renderJsonPath(it) }
        }
    }

    fun launchPolicy(mode: OpenAiConnectionMode): LaunchPolicy = when (mode) {
        OpenAiConnectionMode.CODEX_SUBSCRIPTION -> LaunchPolicy(
            authProfileId = "openai:codex",
            agentRuntimeId = "codex",
            injectOpenAiApiKey = false,
        )
        OpenAiConnectionMode.PLATFORM_API_KEY -> LaunchPolicy(
            authProfileId = "openai:api-key",
            agentRuntimeId = "openclaw",
            injectOpenAiApiKey = true,
        )
    }

    fun inspect(
        rootfsDir: File,
        hasUsableCodexCredential: Boolean? = null,
        hasUsablePlatformApiKeyCredential: Boolean? = null,
    ): Inspection {
        val config = readJsonIfPresent(File(rootfsDir, OPENCLAW_CONFIG_PATH))
        val sessions = readJsonIfPresent(File(rootfsDir, SESSIONS_INDEX_PATH))
        val bindings = codexBindingFiles(rootfsDir)
        val modeHint = config?.let {
            detectModeHint(
                config = it,
                hasUsableCodexCredential = hasUsableCodexCredential,
                hasUsablePlatformApiKeyCredential = hasUsablePlatformApiKeyCredential,
            )
        }
        return Inspection(
            modeHint = modeHint,
            hasLegacyActiveReference =
                (config?.let(::containsLegacyOpenAiConfigReference) == true) ||
                    (sessions?.let(::containsLegacyOpenAiSessionReference) == true) ||
                    bindings.any { binding ->
                        readJsonIfPresent(binding)
                            ?.optString("authProfileId")
                            ?.let(::isLegacyOpenAiProfileId) == true
                    },
        )
    }

    fun normalize(rootfsDir: File, mode: OpenAiConnectionMode): Boolean {
        val policy = launchPolicy(mode)
        var changed = false
        val configFile = File(rootfsDir, OPENCLAW_CONFIG_PATH)
        readJsonIfPresent(configFile)?.let { config ->
            if (normalizeConfig(config, policy)) {
                writeJsonAtomically(configFile, config)
                changed = true
            }
        }
        val sessionsFile = File(rootfsDir, SESSIONS_INDEX_PATH)
        readJsonIfPresent(sessionsFile)?.let { sessions ->
            if (normalizeSessions(sessions, policy)) {
                writeJsonAtomically(sessionsFile, sessions)
                changed = true
            }
        }
        if (normalizeCodexBindings(rootfsDir, policy)) changed = true
        verify(rootfsDir, mode)
        return changed
    }

    fun isConsistent(rootfsDir: File, mode: OpenAiConnectionMode): Boolean {
        return try {
            verify(rootfsDir, mode)
            true
        } catch (_: IllegalStateException) {
            false
        }
    }

    fun verify(rootfsDir: File, mode: OpenAiConnectionMode) {
        val policy = launchPolicy(mode)
        readJsonIfPresent(File(rootfsDir, OPENCLAW_CONFIG_PATH))?.let { config ->
            check(!normalizeConfig(JSONObject(config.toString()), policy)) {
                "Canonical OpenAI config read-back did not match target mode."
            }
            check(!containsLegacyOpenAiConfigReference(config)) {
                "Canonical OpenAI config still contains a legacy active reference."
            }
        }
        readJsonIfPresent(File(rootfsDir, SESSIONS_INDEX_PATH))?.let { sessions ->
            check(!normalizeSessions(JSONObject(sessions.toString()), policy)) {
                "Canonical OpenAI sessions read-back did not match target mode."
            }
            check(!containsLegacyOpenAiSessionReference(sessions)) {
                "Canonical OpenAI sessions still contain a legacy active reference."
            }
        }
        for (bindingFile in codexBindingFiles(rootfsDir)) {
            val authProfileId = readJsonIfPresent(bindingFile)?.optString("authProfileId")?.trim().orEmpty()
            check(!isLegacyOpenAiProfileId(authProfileId)) {
                "Codex app-server binding still contains a legacy OpenAI profile."
            }
            check(!authProfileId.isOpenAiProfileId() || authProfileId == policy.authProfileId) {
                "Codex app-server binding conflicts with the target OpenAI mode."
            }
        }
    }

    fun applyConfigPolicy(
        config: JSONObject,
        mode: OpenAiConnectionMode,
    ): Boolean = normalizeConfig(config, launchPolicy(mode))

    private fun normalizeConfig(config: JSONObject, policy: LaunchPolicy): Boolean {
        var changed = false
        if (normalizeAuthOrder(config, policy.authProfileId)) changed = true
        val modelsRoot = config.optJSONObject("models") ?: JSONObject().also {
            config.put("models", it)
            changed = true
        }
        val providers = modelsRoot.optJSONObject("providers") ?: JSONObject().also {
            modelsRoot.put("providers", it)
            changed = true
        }
        val openAiProvider = providers.optJSONObject("openai") ?: JSONObject().also {
            providers.put("openai", it)
            changed = true
        }
        for (legacyProvider in LEGACY_OPENAI_PROVIDERS) {
            val legacyConfig = providers.optJSONObject(legacyProvider) ?: continue
            mergeMissing(openAiProvider, legacyConfig)
            providers.remove(legacyProvider)
            changed = true
        }
        if (putRuntimePolicy(openAiProvider, policy.agentRuntimeId)) changed = true
        openAiProvider.optJSONArray("models")?.let { models ->
            for (index in 0 until models.length()) {
                val model = models.optJSONObject(index) ?: continue
                if (putRuntimePolicy(model, policy.agentRuntimeId)) changed = true
            }
        }

        val agents = config.optJSONObject("agents") ?: JSONObject().also {
            config.put("agents", it)
            changed = true
        }
        val defaults = agents.optJSONObject("defaults") ?: JSONObject().also {
            agents.put("defaults", it)
            changed = true
        }
        if (normalizeAgentContainer(defaults, policy, ensureWildcard = true)) changed = true
        agents.optJSONArray("list")?.let { list ->
            for (index in 0 until list.length()) {
                val agent = list.optJSONObject(index) ?: continue
                if (normalizeAgentContainer(agent, policy, ensureWildcard = false)) changed = true
            }
        }
        return changed
    }

    private fun normalizeAgentContainer(
        container: JSONObject,
        policy: LaunchPolicy,
        ensureWildcard: Boolean,
    ): Boolean {
        var changed = false
        val model = container.opt("model")
        when (model) {
            is String -> {
                val canonical = canonicalModelRef(model)
                if (canonical != model) {
                    container.put("model", canonical)
                    changed = true
                }
            }
            is JSONObject -> {
                for (key in listOf("primary", "utility")) {
                    val raw = model.optString(key)
                    val canonical = canonicalModelRef(raw)
                    if (raw.isNotBlank() && canonical != raw) {
                        model.put(key, canonical)
                        changed = true
                    }
                }
                for (key in listOf("fallbacks")) {
                    val values = model.optJSONArray(key) ?: continue
                    for (index in 0 until values.length()) {
                        val raw = values.optString(index)
                        val canonical = canonicalModelRef(raw)
                        if (raw.isNotBlank() && canonical != raw) {
                            values.put(index, canonical)
                            changed = true
                        }
                    }
                }
            }
        }

        val configuredModels = container.optJSONObject("models") ?: if (ensureWildcard) {
            JSONObject().also {
                container.put("models", it)
                changed = true
            }
        } else {
            null
        }
        if (configuredModels != null) {
            val keys = configuredModels.keys().asSequence().toList()
            for (rawRef in keys) {
                val canonicalRef = canonicalModelRef(rawRef)
                val entry = configuredModels.optJSONObject(rawRef) ?: JSONObject()
                if (isOpenAiModelRef(canonicalRef)) {
                    if (putRuntimePolicy(entry, policy.agentRuntimeId)) changed = true
                    if (canonicalRef != rawRef) {
                        val existing = configuredModels.optJSONObject(canonicalRef)
                        val merged = if (existing == null) entry else mergeMissing(existing, entry)
                        putRuntimePolicy(merged, policy.agentRuntimeId)
                        configuredModels.put(canonicalRef, merged)
                        configuredModels.remove(rawRef)
                        changed = true
                    } else if (configuredModels.optJSONObject(rawRef) == null) {
                        configuredModels.put(rawRef, entry)
                        changed = true
                    }
                }
            }
            if (ensureWildcard) {
                val wildcard = configuredModels.optJSONObject("openai/*") ?: JSONObject().also {
                    configuredModels.put("openai/*", it)
                    changed = true
                }
                if (putRuntimePolicy(wildcard, policy.agentRuntimeId)) changed = true
            }
        }
        return changed
    }

    private fun normalizeAuthOrder(config: JSONObject, authProfileId: String): Boolean {
        var changed = false
        val auth = config.optJSONObject("auth") ?: JSONObject().also {
            config.put("auth", it)
            changed = true
        }
        val order = auth.optJSONObject("order") ?: JSONObject().also {
            auth.put("order", it)
            changed = true
        }
        for (legacyProvider in LEGACY_OPENAI_PROVIDERS) {
            if (order.has(legacyProvider)) {
                order.remove(legacyProvider)
                changed = true
            }
        }
        val openAiOrder = order.optJSONArray("openai")
        if (
            openAiOrder == null ||
            openAiOrder.length() != 1 ||
            openAiOrder.optString(0) != authProfileId
        ) {
            order.put("openai", JSONArray().put(authProfileId))
            changed = true
        }
        return changed
    }

    private fun normalizeSessions(root: JSONObject, policy: LaunchPolicy): Boolean {
        var changed = false
        for (sessionKey in root.keys().asSequence().toList()) {
            val session = root.optJSONObject(sessionKey) ?: continue
            val routeIsOpenAi = sessionRouteIsOpenAi(session)
            for (key in listOf("model", "modelOverride")) {
                val raw = session.optString(key)
                val canonical = canonicalModelRef(raw)
                if (raw.isNotBlank() && canonical != raw) {
                    session.put(key, canonical)
                    changed = true
                }
            }
            for (key in listOf("modelProvider", "providerOverride")) {
                val raw = session.optString(key).trim().lowercase()
                if (raw in LEGACY_OPENAI_PROVIDERS) {
                    session.put(key, "openai")
                    changed = true
                }
            }
            if (!routeIsOpenAi) continue
            for (key in listOf("agentHarnessId", "agentRuntimeOverride")) {
                if (session.has(key)) {
                    session.remove(key)
                    changed = true
                }
            }
            val authProfileOverride = session.optString("authProfileOverride").trim()
            if (
                authProfileOverride.isOpenAiProfileId() &&
                authProfileOverride != policy.authProfileId
            ) {
                session.remove("authProfileOverride")
                session.remove("authProfileOverrideSource")
                changed = true
            }
        }
        return changed
    }

    private fun detectModeHint(
        config: JSONObject,
        hasUsableCodexCredential: Boolean?,
        hasUsablePlatformApiKeyCredential: Boolean?,
    ): OpenAiConnectionMode? {
        detectAuthOrderMode(
            config = config,
            hasUsableCodexCredential = hasUsableCodexCredential,
            hasUsablePlatformApiKeyCredential = hasUsablePlatformApiKeyCredential,
        )?.let { return it }

        val defaults = config.optJSONObject("agents")?.optJSONObject("defaults")
        val defaultsModels = defaults?.optJSONObject("models")
        if (defaultsModels != null) {
            val configuredPrimary = when (val model = defaults.opt("model")) {
                is String -> model
                is JSONObject -> model.optString("primary")
                else -> ""
            }
            val canonicalPrimary = canonicalModelRef(configuredPrimary)
            val rawRefs = defaultsModels.keys().asSequence().toList()
            val candidateRawRefs = buildList {
                if (isOpenAiModelRef(canonicalPrimary)) {
                    rawRefs.firstOrNull { canonicalModelRef(it) == canonicalPrimary }?.let(::add)
                }
                rawRefs
                    .filter {
                        val canonical = canonicalModelRef(it)
                        isOpenAiModelRef(canonical) && canonical != "openai/*" && it !in this
                    }
                    .forEach(::add)
                rawRefs.firstOrNull { canonicalModelRef(it) == "openai/*" }?.let(::add)
            }
            for (rawRef in candidateRawRefs) {
                val runtime = defaultsModels.optJSONObject(rawRef)
                    ?.optJSONObject("agentRuntime")
                    ?.optString("id")
                runtimeMode(runtime)?.let { return it }
            }
        }

        val providerRuntime = config
            .optJSONObject("models")
            ?.optJSONObject("providers")
            ?.optJSONObject("openai")
            ?.optJSONObject("agentRuntime")
            ?.optString("id")
        runtimeMode(providerRuntime)?.let { return it }
        return if (containsLegacyOpenAiConfigReference(config)) {
            OpenAiConnectionMode.CODEX_SUBSCRIPTION
        } else {
            null
        }
    }

    private fun detectAuthOrderMode(
        config: JSONObject,
        hasUsableCodexCredential: Boolean?,
        hasUsablePlatformApiKeyCredential: Boolean?,
    ): OpenAiConnectionMode? {
        val order = config.optJSONObject("auth")?.optJSONObject("order") ?: return null
        val profileIds = buildList {
            order.optJSONArray("openai")?.let { profiles ->
                for (index in 0 until profiles.length()) add(profiles.optString(index))
            }
            for (legacyProvider in LEGACY_OPENAI_PROVIDERS) {
                order.optJSONArray(legacyProvider)?.let { profiles ->
                    for (index in 0 until profiles.length()) add(profiles.optString(index))
                }
            }
        }
        return profileIds.asSequence()
            .mapNotNull(::profileMode)
            .firstOrNull { candidate ->
                when (candidate) {
                    OpenAiConnectionMode.CODEX_SUBSCRIPTION -> hasUsableCodexCredential != false
                    OpenAiConnectionMode.PLATFORM_API_KEY -> hasUsablePlatformApiKeyCredential != false
                }
            }
    }

    private fun profileMode(profileId: String): OpenAiConnectionMode? {
        val normalized = profileId.trim().lowercase()
        return when {
            normalized == "openai:codex" || normalized.startsWith("openai-codex:") ->
                OpenAiConnectionMode.CODEX_SUBSCRIPTION
            normalized == "openai:api-key" -> OpenAiConnectionMode.PLATFORM_API_KEY
            else -> null
        }
    }

    private fun runtimeMode(runtimeId: String?): OpenAiConnectionMode? = when (runtimeId?.trim()?.lowercase()) {
        "codex", "codex-cli" -> OpenAiConnectionMode.CODEX_SUBSCRIPTION
        "openclaw", "pi" -> OpenAiConnectionMode.PLATFORM_API_KEY
        else -> null
    }

    private fun containsLegacyOpenAiConfigReference(config: JSONObject): Boolean {
        val models = config.optJSONObject("models")
        val agents = config.optJSONObject("agents")
        val auth = config.optJSONObject("auth")
        return (models?.let(::containsLegacyOpenAiModelContext) == true) ||
            (agents?.let(::containsLegacyOpenAiModelContext) == true) ||
            (auth?.let(::containsLegacyOpenAiAuthContext) == true)
    }

    private fun containsLegacyOpenAiModelContext(value: Any?): Boolean = when (value) {
        is JSONObject -> value.keys().asSequence().any { key ->
            val normalizedKey = key.trim().lowercase()
            val child = value.opt(key)
            normalizedKey in LEGACY_OPENAI_PROVIDERS ||
                LEGACY_OPENAI_PROVIDERS.any { normalizedKey.startsWith("$it/") } ||
                (
                    normalizedKey in setOf("provider", "modelprovider", "provideroverride") &&
                        child is String &&
                        child.trim().lowercase() in LEGACY_OPENAI_PROVIDERS
                ) ||
                containsLegacyOpenAiModelContext(child)
        }
        is JSONArray -> (0 until value.length()).any { containsLegacyOpenAiModelContext(value.opt(it)) }
        is String -> isLegacyOpenAiString(value)
        else -> false
    }

    private fun containsLegacyOpenAiAuthContext(value: Any?): Boolean = when (value) {
        is JSONObject -> value.keys().asSequence().any { key ->
            key.trim().lowercase() in LEGACY_OPENAI_PROVIDERS ||
                containsLegacyOpenAiAuthContext(value.opt(key))
        }
        is JSONArray -> (0 until value.length()).any { containsLegacyOpenAiAuthContext(value.opt(it)) }
        is String -> isLegacyOpenAiString(value)
        else -> false
    }

    private fun containsLegacyOpenAiSessionReference(value: Any?): Boolean = when (value) {
        is JSONObject -> value.keys().asSequence().any { key ->
            val normalizedKey = key.trim().lowercase()
            val child = value.opt(key)
            (
                normalizedKey in setOf("modelprovider", "provideroverride") &&
                    child is String &&
                    child.trim().lowercase() in LEGACY_OPENAI_PROVIDERS
            ) ||
                (
                    normalizedKey in setOf("model", "modeloverride", "authprofileoverride") &&
                        child is String &&
                        isLegacyOpenAiString(child)
                ) ||
                containsLegacyOpenAiSessionReference(child)
        }
        is JSONArray -> (0 until value.length()).any { containsLegacyOpenAiSessionReference(value.opt(it)) }
        else -> false
    }

    private fun isLegacyOpenAiString(value: String): Boolean {
        val normalized = value.trim().lowercase()
        return normalized.startsWith("openai-codex:") ||
            normalized == "openai:default" ||
            LEGACY_OPENAI_PROVIDERS.any { normalized.startsWith("$it/") }
    }

    private fun isLegacyOpenAiProfileId(value: String): Boolean {
        val normalized = value.trim().lowercase()
        return normalized.startsWith("openai-codex:") || normalized == "openai:default"
    }

    private fun sessionRouteIsOpenAi(session: JSONObject): Boolean {
        val providers = listOf("modelProvider", "providerOverride")
            .map { session.optString(it).trim().lowercase() }
        val modelRefs = listOf("model", "modelOverride")
            .map(session::optString)
        val authProfiles = listOf("authProfileOverride")
            .map(session::optString)
        return providers.any { it == "openai" || it in LEGACY_OPENAI_PROVIDERS } ||
            modelRefs.any { isOpenAiModelRef(canonicalModelRef(it)) } ||
            authProfiles.any { it.isOpenAiProfileId() }
    }

    private fun normalizeCodexBindings(rootfsDir: File, policy: LaunchPolicy): Boolean {
        var changed = false
        for (bindingFile in codexBindingFiles(rootfsDir)) {
            val binding = readJsonIfPresent(bindingFile) ?: continue
            val authProfileId = binding.optString("authProfileId").trim()
            if (!authProfileId.isOpenAiProfileId()) continue
            val profileMode = profileMode(authProfileId)
            val targetMode = profileMode(policy.authProfileId)
            when {
                authProfileId == policy.authProfileId -> Unit
                authProfileId.equals("openai:default", ignoreCase = true) -> {
                    binding.put("authProfileId", policy.authProfileId)
                    writeJsonAtomically(bindingFile, binding)
                    changed = true
                }
                profileMode == targetMode && isLegacyOpenAiProfileId(authProfileId) -> {
                    binding.put("authProfileId", policy.authProfileId)
                    writeJsonAtomically(bindingFile, binding)
                    changed = true
                }
                else -> {
                    check(bindingFile.delete()) {
                        "Failed to reset conflicting Codex app-server binding ${bindingFile.absolutePath}."
                    }
                    changed = true
                }
            }
        }
        return changed
    }

    private fun codexBindingFiles(rootfsDir: File): List<File> {
        val sessionsDir = File(rootfsDir, SESSIONS_DIR_PATH)
        return sessionsDir.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".codex-app-server.json") }
            .orEmpty()
    }

    private fun canonicalModelRef(raw: String): String {
        val normalized = raw.trim()
        val lower = normalized.lowercase()
        val legacyProvider = LEGACY_OPENAI_PROVIDERS.firstOrNull { lower.startsWith("$it/") }
        return if (legacyProvider == null) normalized else "openai/${normalized.substringAfter('/')}"
    }

    private fun isOpenAiModelRef(modelRef: String): Boolean =
        modelRef.trim().lowercase().startsWith("openai/")

    private fun String.isOpenAiProfileId(): Boolean {
        val normalized = trim().lowercase()
        return normalized.startsWith("openai:") || normalized.startsWith("openai-codex:")
    }

    private fun putRuntimePolicy(target: JSONObject, runtimeId: String): Boolean {
        val current = target.optJSONObject("agentRuntime")
        if (current?.optString("id") == runtimeId && current.length() == 1) return false
        target.put("agentRuntime", JSONObject().put("id", runtimeId))
        return true
    }

    private fun mergeMissing(target: JSONObject, source: JSONObject): JSONObject {
        for (key in source.keys().asSequence().toList()) {
            if (!target.has(key)) target.put(key, source.opt(key))
        }
        return target
    }

    private data class JsonPathValue(
        val present: Boolean,
        val value: Any? = null,
    )

    private fun collectManagedConfigPaths(
        originalPresent: Boolean,
        original: Any?,
        normalizedPresent: Boolean,
        normalized: Any?,
        path: List<String>,
        paths: MutableSet<List<String>>,
    ) {
        if (originalPresent && !normalizedPresent) {
            paths += path
            return
        }
        if (!originalPresent && normalizedPresent && normalized is JSONObject) {
            val keys = normalized.keys().asSequence().toList()
            if (keys.isEmpty()) {
                paths += path
            } else {
                for (key in keys) {
                    collectManagedConfigPaths(
                        originalPresent = false,
                        original = null,
                        normalizedPresent = true,
                        normalized = normalized.opt(key),
                        path = path + jsonKeyToken(key),
                        paths = paths,
                    )
                }
            }
            return
        }
        if (!originalPresent || !normalizedPresent) {
            paths += path
            return
        }
        if (original is JSONObject && normalized is JSONObject) {
            val keys = (
                original.keys().asSequence().toSet() +
                    normalized.keys().asSequence().toSet()
                )
            for (key in keys) {
                collectManagedConfigPaths(
                    originalPresent = original.has(key),
                    original = original.opt(key),
                    normalizedPresent = normalized.has(key),
                    normalized = normalized.opt(key),
                    path = path + jsonKeyToken(key),
                    paths = paths,
                )
            }
            return
        }
        if (
            original is JSONArray &&
            normalized is JSONArray &&
            original.length() == normalized.length()
        ) {
            for (index in 0 until original.length()) {
                collectManagedConfigPaths(
                    originalPresent = true,
                    original = original.opt(index),
                    normalizedPresent = true,
                    normalized = normalized.opt(index),
                    path = path + jsonIndexToken(index),
                    paths = paths,
                )
            }
            return
        }
        if (!jsonValuesEqual(original, normalized)) paths += path
    }

    private fun jsonPathValue(root: JSONObject, path: List<String>): JsonPathValue {
        var current: Any = root
        for (token in path) {
            current = when {
                token.startsWith(JSON_KEY_TOKEN_PREFIX) && current is JSONObject -> {
                    val key = token.removePrefix(JSON_KEY_TOKEN_PREFIX)
                    if (!current.has(key)) return JsonPathValue(false)
                    current.opt(key) ?: JSONObject.NULL
                }
                token.startsWith(JSON_INDEX_TOKEN_PREFIX) && current is JSONArray -> {
                    val index = token.removePrefix(JSON_INDEX_TOKEN_PREFIX).toInt()
                    if (index !in 0 until current.length()) return JsonPathValue(false)
                    current.opt(index) ?: JSONObject.NULL
                }
                else -> return JsonPathValue(false)
            }
        }
        return JsonPathValue(true, current)
    }

    private fun setJsonPathValue(
        root: JSONObject,
        path: List<String>,
        value: Any,
    ) {
        var current: Any = root
        for (index in 0 until path.lastIndex) {
            val token = path[index]
            val nextToken = path[index + 1]
            current = when {
                token.startsWith(JSON_KEY_TOKEN_PREFIX) && current is JSONObject -> {
                    val key = token.removePrefix(JSON_KEY_TOKEN_PREFIX)
                    val existing = current.opt(key)
                    val usable = when {
                        nextToken.startsWith(JSON_KEY_TOKEN_PREFIX) -> existing is JSONObject
                        else -> existing is JSONArray
                    }
                    if (usable) {
                        checkNotNull(existing)
                    } else {
                        newJsonContainer(nextToken).also { current.put(key, it) }
                    }
                }
                token.startsWith(JSON_INDEX_TOKEN_PREFIX) && current is JSONArray -> {
                    val arrayIndex = token.removePrefix(JSON_INDEX_TOKEN_PREFIX).toInt()
                    while (current.length() <= arrayIndex) current.put(JSONObject.NULL)
                    val existing = current.opt(arrayIndex)
                    val usable = when {
                        nextToken.startsWith(JSON_KEY_TOKEN_PREFIX) -> existing is JSONObject
                        else -> existing is JSONArray
                    }
                    if (usable) {
                        checkNotNull(existing)
                    } else {
                        newJsonContainer(nextToken).also { current.put(arrayIndex, it) }
                    }
                }
                else -> error("Invalid OpenAI managed config path: ${renderJsonPath(path)}")
            }
        }
        val last = path.last()
        val restoredValue = copyJsonValue(value)
        when {
            last.startsWith(JSON_KEY_TOKEN_PREFIX) && current is JSONObject ->
                current.put(last.removePrefix(JSON_KEY_TOKEN_PREFIX), restoredValue)
            last.startsWith(JSON_INDEX_TOKEN_PREFIX) && current is JSONArray -> {
                val arrayIndex = last.removePrefix(JSON_INDEX_TOKEN_PREFIX).toInt()
                current.put(arrayIndex, restoredValue)
            }
            else -> error("Invalid OpenAI managed config path: ${renderJsonPath(path)}")
        }
    }

    private fun removeJsonPathValue(root: JSONObject, path: List<String>) {
        if (path.isEmpty()) return
        val parent = jsonPathValue(root, path.dropLast(1))
        if (!parent.present) return
        val last = path.last()
        when {
            last.startsWith(JSON_KEY_TOKEN_PREFIX) && parent.value is JSONObject ->
                parent.value.remove(last.removePrefix(JSON_KEY_TOKEN_PREFIX))
            last.startsWith(JSON_INDEX_TOKEN_PREFIX) && parent.value is JSONArray -> {
                val index = last.removePrefix(JSON_INDEX_TOKEN_PREFIX).toInt()
                if (index in 0 until parent.value.length()) parent.value.remove(index)
            }
        }
    }

    private fun pruneAbsentEmptyParents(
        current: JSONObject,
        original: JSONObject,
        path: List<String>,
    ) {
        for (length in path.lastIndex downTo 1) {
            val prefix = path.take(length)
            if (jsonPathValue(original, prefix).present) return
            if (prefix.last().startsWith(JSON_INDEX_TOKEN_PREFIX)) continue
            val currentValue = jsonPathValue(current, prefix)
            val empty = when (val value = currentValue.value) {
                is JSONObject -> value.length() == 0
                is JSONArray -> value.length() == 0
                else -> false
            }
            if (!currentValue.present || !empty) return
            removeJsonPathValue(current, prefix)
        }
    }

    private fun newJsonContainer(nextToken: String): Any =
        if (nextToken.startsWith(JSON_KEY_TOKEN_PREFIX)) JSONObject() else JSONArray()

    private fun copyJsonValue(value: Any): Any = when (value) {
        is JSONObject -> JSONObject(value.toString())
        is JSONArray -> JSONArray(value.toString())
        else -> value
    }

    private fun jsonValuesEqual(left: Any?, right: Any?): Boolean = when {
        left is JSONObject && right is JSONObject -> {
            val leftKeys = left.keys().asSequence().toSet()
            val rightKeys = right.keys().asSequence().toSet()
            leftKeys == rightKeys && leftKeys.all { key ->
                jsonValuesEqual(left.opt(key), right.opt(key))
            }
        }
        left is JSONArray && right is JSONArray ->
            left.length() == right.length() &&
                (0 until left.length()).all { index ->
                    jsonValuesEqual(left.opt(index), right.opt(index))
                }
        else -> left == right
    }

    private fun jsonKeyToken(key: String): String = "$JSON_KEY_TOKEN_PREFIX$key"

    private fun jsonIndexToken(index: Int): String = "$JSON_INDEX_TOKEN_PREFIX$index"

    private fun renderJsonPath(path: List<String>): String = path.joinToString(
        separator = "",
        prefix = "$",
    ) { token ->
        if (token.startsWith(JSON_KEY_TOKEN_PREFIX)) {
            ".${token.removePrefix(JSON_KEY_TOKEN_PREFIX)}"
        } else {
            "[${token.removePrefix(JSON_INDEX_TOKEN_PREFIX)}]"
        }
    }

    private const val JSON_KEY_TOKEN_PREFIX = "key:"
    private const val JSON_INDEX_TOKEN_PREFIX = "index:"

    private fun readJsonIfPresent(file: File): JSONObject? {
        if (!file.isFile) return null
        return JSONObject(file.readText())
    }

    private fun writeJsonAtomically(file: File, json: JSONObject) {
        file.parentFile?.mkdirs()
        val temp = File.createTempFile("${file.name}.", ".tmp", file.parentFile)
        try {
            FileOutputStream(temp).use { output ->
                output.write(json.toString(2).toByteArray(StandardCharsets.UTF_8))
                output.fd.sync()
            }
            runCatching {
                java.nio.file.Files.move(
                    temp.toPath(),
                    file.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }.getOrElse {
                java.nio.file.Files.move(
                    temp.toPath(),
                    file.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }
        } finally {
            temp.delete()
        }
    }
}
