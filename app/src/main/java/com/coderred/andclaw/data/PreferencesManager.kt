package com.coderred.andclaw.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.coderred.andclaw.proot.OpenClawModelCatalogReader
import com.coderred.andclaw.proot.ProotManager
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URI
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "andclaw_prefs")

internal var gitHubCopilotEnvProvider: () -> Map<String, String> = { System.getenv() }

internal fun hasGitHubCopilotEnvAuth(env: Map<String, String> = gitHubCopilotEnvProvider()): Boolean =
    listOf("COPILOT_GITHUB_TOKEN", "GH_TOKEN", "GITHUB_TOKEN").any { !env[it].isNullOrBlank() }

internal fun hasOpenClawSecretRef(profile: org.json.JSONObject, key: String): Boolean {
    return when (val rawValue = profile.opt(key)) {
        is String -> rawValue.trim().isNotBlank()
        is org.json.JSONObject -> {
            val source = rawValue.optString("source").trim()
            val id = rawValue.optString("id").trim()
            source.isNotBlank() && id.isNotBlank()
        }
        else -> false
    }
}

internal fun extractJsonObjectPayload(output: String?): String? {
    if (output.isNullOrBlank()) return null

    var depth = 0
    var startIndex = -1
    var inString = false
    var escaping = false

    output.forEachIndexed { index, ch ->
        if (escaping) {
            escaping = false
            return@forEachIndexed
        }

        when (ch) {
            '\\' -> if (inString) escaping = true
            '"' -> inString = !inString
            '{' -> if (!inString) {
                if (depth == 0) startIndex = index
                depth += 1
            }
            '}' -> if (!inString && depth > 0) {
                depth -= 1
                if (depth == 0 && startIndex >= 0) {
                    return output.substring(startIndex, index + 1)
                }
            }
        }
    }

    return null
}

internal fun hasOpenClawModelsStatusAuth(output: String?, providerId: String): Boolean {
    if (output.isNullOrBlank()) return false
    val normalizedProviderId = providerId.trim().lowercase()
    if (normalizedProviderId.isBlank()) return false

    fun isUsableStatus(status: String): Boolean {
        return when (status.trim().lowercase()) {
            "ok", "expiring", "static" -> true
            else -> false
        }
    }

    return runCatching {
        val jsonPayload = extractJsonObjectPayload(output) ?: return@runCatching false
        val root = org.json.JSONObject(jsonPayload)
        val auth = root.optJSONObject("auth") ?: return@runCatching false

        val providers = auth.optJSONArray("providers")
        if (providers != null) {
            for (index in 0 until providers.length()) {
                val provider = providers.optJSONObject(index) ?: continue
                if (provider.optString("provider").trim().lowercase() != normalizedProviderId) continue
                val effectiveKind = provider.optJSONObject("effective")?.optString("kind")?.trim()?.lowercase()
                if (effectiveKind == "env" || effectiveKind == "models.json") {
                    return@runCatching true
                }
            }
        }

        val oauth = auth.optJSONObject("oauth") ?: return@runCatching false
        val oauthProviders = oauth.optJSONArray("providers")
        if (oauthProviders != null) {
            for (index in 0 until oauthProviders.length()) {
                val provider = oauthProviders.optJSONObject(index) ?: continue
                if (provider.optString("provider").trim().lowercase() != normalizedProviderId) continue
                if (isUsableStatus(provider.optString("status"))) return@runCatching true
            }
        }

        val profiles = oauth.optJSONArray("profiles")
        if (profiles != null) {
            for (index in 0 until profiles.length()) {
                val profile = profiles.optJSONObject(index) ?: continue
                if (profile.optString("provider").trim().lowercase() != normalizedProviderId) continue
                if (isUsableStatus(profile.optString("status"))) return@runCatching true
            }
        }

        false
    }.getOrDefault(false)
}

class PreferencesManager(private val context: Context) {

    suspend fun hasOllamaServerModelsAvailable(): Boolean {
        // Read base URL from preferences (default to localhost Ollama port)
        val baseUrl = kotlin.runCatching {
            context.dataStore.data.first()[KEY_OLLAMA_BASE_URL] ?: "http://127.0.0.1:11434"
        }.getOrDefault("http://127.0.0.1:11434")

        val normalized = baseUrl.trim().trimEnd('/')
        val endpoint = "$normalized/api/tags"
        return try {
            val url = URL(endpoint)
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 5_000
            conn.readTimeout = 5_000
            conn.setRequestProperty("Accept", "application/json")
            val code = conn.responseCode
            conn.disconnect()
            code == 200
        } catch (_: Exception) {
            false
        }
    }

    private fun resolveDefaultModelMetadata(
        provider: String,
        modelId: String,
    ): SelectedModelConfigEntry {
        val normalizedProvider = normalizeProvider(provider)
        val canonicalModelId = canonicalizeModelIdForProvider(normalizedProvider, modelId)
        if (canonicalModelId.isBlank()) return defaultModelMetadata(modelId)

        val installedMetadata = resolveInstalledModelMetadata(normalizedProvider, canonicalModelId)
        return installedMetadata ?: defaultModelMetadata(canonicalModelId)
    }

    private fun resolveInstalledModelMetadata(
        provider: String,
        modelId: String,
    ): SelectedModelConfigEntry? {
        val normalizedProvider = normalizeProvider(provider)
        val rootfsDir = runCatching { ProotManager(context).rootfsDir }.getOrNull() ?: return null
        if (!rootfsDir.exists()) return null

        val bundleEntry = when (normalizedProvider) {
            "openai-codex" -> resolveInstalledCodexEntries(rootfsDir)
                .firstOrNull { normalizeCodexModelIdForComparison(it.id) == normalizeCodexModelIdForComparison(modelId) }

            else -> OpenClawModelCatalogReader.loadProviderModels(rootfsDir, normalizedProvider)
                .firstOrNull { canonicalizeModelIdForProvider(normalizedProvider, it.id) == modelId }
        } ?: return null

        return SelectedModelConfigEntry(
            id = modelId,
            supportsReasoning = bundleEntry.supportsReasoning,
            supportsImages = bundleEntry.supportsImages,
            contextLength = bundleEntry.contextWindow,
            maxOutputTokens = bundleEntry.maxTokens,
        )
    }

    private fun resolveInstalledCodexEntries(rootfsDir: java.io.File): List<OpenClawModelCatalogReader.ModelEntry> {
        val directCodexEntries = OpenClawModelCatalogReader.loadProviderModels(rootfsDir, "openai-codex")
        if (directCodexEntries.isNotEmpty()) return directCodexEntries.distinctBy { it.id.lowercase() }

        val legacyCodexEntries = OpenClawModelCatalogReader.loadProviderModels(rootfsDir, "openai")
            .asSequence()
            .filter { isLegacyOpenAiCodexModelId(it.id) }
            .map { it.copy(provider = "openai-codex") }
            .distinctBy { it.id.lowercase() }
            .toList()
        val syntheticCodexEntries = OpenClawModelCatalogReader.loadSyntheticFallbackEntries(
            rootfsDir = rootfsDir,
            provider = "openai-codex",
            baseEntries = legacyCodexEntries,
        )
        return (legacyCodexEntries + syntheticCodexEntries)
            .distinctBy { it.id.lowercase() }
    }

    private fun normalizeCodexModelIdForComparison(modelId: String): String {
        return modelId.trim()
            .removePrefix("openai-codex/")
            .removePrefix("openai/")
            .lowercase()
    }

    private fun isLegacyOpenAiCodexModelId(modelId: String): Boolean {
        val normalizedModelId = normalizeCodexModelIdForComparison(modelId)
        return normalizedModelId.contains("codex") || normalizedModelId in BARE_CODEX_MODEL_IDS
    }

    private fun hasGitHubCopilotAuthProfile(): Boolean {
        if (hasGitHubCopilotEnvAuth()) return true

        val prootManager = runCatching { ProotManager(context) }.getOrNull() ?: return false
        val rootfsDir = prootManager.rootfsDir
        if (!rootfsDir.exists()) return false

        val authFile = java.io.File(rootfsDir, "root/.openclaw/agents/main/agent/auth-profiles.json")
        val hasStoredProfile = if (authFile.exists()) {
            runCatching {
                val root = org.json.JSONObject(authFile.readText())
                val profiles = root.optJSONObject("profiles") ?: return@runCatching false
                val keys = profiles.keys()
                while (keys.hasNext()) {
                    val profile = profiles.optJSONObject(keys.next()) ?: continue
                    if (profile.optString("provider").trim().lowercase() != "github-copilot") continue
                    val hasInlineToken = profile.optString("token").trim().isNotBlank()
                    val hasTokenRef = hasOpenClawSecretRef(profile, "tokenRef")
                    if (hasInlineToken || hasTokenRef) return@runCatching true
                }
                false
            }.getOrDefault(false)
        } else {
            false
        }
        if (hasStoredProfile) return true

        return runCatching {
            val authStatusCommand = "export NODE_OPTIONS='--require /root/.openclaw-patch.js' && " +
                "openclaw models status --json 2>&1"
            val authStatusOutput = prootManager.executeAndCapture(authStatusCommand)
            hasOpenClawModelsStatusAuth(authStatusOutput, "github-copilot")
        }.getOrDefault(false)
    }

    companion object {
        // All preference keys eligible for device transfer export/import.
        // Adding a key here automatically includes it in export, import, and pre-import clear.
        // Device-specific settings (auto_start_on_boot, charge_only_mode, log_section_unlocked)
        // are intentionally excluded.
        val TRANSFER_EXPORTABLE_KEYS: Set<String> = setOf(
            "api_provider",
            "api_key",
            "api_key_openrouter",
            "api_key_openai",
            "api_key_anthropic",
            "api_key_google",
            "api_key_zai",
            "api_key_kimi_coding",
            "api_key_minimax",
            "api_key_openai_compatible",
            "api_key_ollama",
            "api_key_ollama_cloud",
            "ollama_cloud_model_id",
            "openai_compatible_base_url",
            "openai_compatible_model_id",
            "ollama_base_url",
            "ollama_model_id",
            "selected_model",
            "selected_model_provider",
            "selected_model_reasoning",
            "selected_model_images",
            "selected_model_context",
            "selected_model_max_output",
            "selected_models_by_provider_json",
            "primary_model_by_provider_json",
            "model_metadata_by_provider_json",
            "openai_compatible_profiles_json",
            "active_openai_compatible_profile_id",
            "whatsapp_enabled",
            "telegram_enabled",
            "telegram_bot_token",
            "discord_enabled",
            "discord_bot_token",
            "discord_guild_allowlist",
            "discord_require_mention",
            "brave_search_api_key",
            "memory_search_enabled",
            "memory_search_provider",
            "memory_search_api_key",
        )

        private val KEY_SETUP_COMPLETE = booleanPreferencesKey("setup_complete")
        private val KEY_ONBOARDING_COMPLETE = booleanPreferencesKey("onboarding_complete")
        private val KEY_API_PROVIDER = stringPreferencesKey("api_provider")
        private val KEY_API_KEY = stringPreferencesKey("api_key")
        private val KEY_API_KEY_OPENROUTER = stringPreferencesKey("api_key_openrouter")
        private val KEY_API_KEY_ANTHROPIC = stringPreferencesKey("api_key_anthropic")
        private val KEY_API_KEY_OPENAI = stringPreferencesKey("api_key_openai")
        private val KEY_API_KEY_GOOGLE = stringPreferencesKey("api_key_google")
        private val KEY_API_KEY_ZAI = stringPreferencesKey("api_key_zai")
        private val KEY_API_KEY_KIMI_CODING = stringPreferencesKey("api_key_kimi_coding")
        private val KEY_API_KEY_MINIMAX = stringPreferencesKey("api_key_minimax")
        private val KEY_GITHUB_COPILOT_AUTHENTICATED = booleanPreferencesKey("github_copilot_authenticated")
        private val KEY_API_KEY_OPENAI_COMPATIBLE = stringPreferencesKey("api_key_openai_compatible")
        private val KEY_OPENAI_COMPATIBLE_BASE_URL = stringPreferencesKey("openai_compatible_base_url")
        private val KEY_OPENAI_COMPATIBLE_MODEL_ID = stringPreferencesKey("openai_compatible_model_id")
        private val KEY_API_KEY_OLLAMA = stringPreferencesKey("api_key_ollama")
        private val KEY_API_KEY_OLLAMA_CLOUD = stringPreferencesKey("api_key_ollama_cloud")
        private val KEY_OLLAMA_CLOUD_MODEL_ID = stringPreferencesKey("ollama_cloud_model_id")
private val KEY_OLLAMA_BASE_URL = stringPreferencesKey("ollama_base_url")
private val KEY_OLLAMA_MODEL_ID = stringPreferencesKey("ollama_model_id")
private val OLLAMA_SERVER_PREFERRED_KEY = booleanPreferencesKey("ollama_server_preferred")
private val OLLAMA_MANUAL_FALLBACK_KEY = booleanPreferencesKey("ollama_manual_fallback")
        private val KEY_AUTO_START_ON_BOOT = booleanPreferencesKey("auto_start_on_boot")
        private val KEY_CHARGE_ONLY_MODE = booleanPreferencesKey("charge_only_mode")
        private val KEY_OPENCLAW_VERSION = stringPreferencesKey("openclaw_version")
        private val KEY_SELECTED_MODEL = stringPreferencesKey("selected_model")
        private val KEY_SELECTED_MODEL_PROVIDER = stringPreferencesKey("selected_model_provider")
        private val KEY_SELECTED_MODEL_REASONING = booleanPreferencesKey("selected_model_reasoning")
        private val KEY_SELECTED_MODEL_IMAGES = booleanPreferencesKey("selected_model_images")
        private val KEY_SELECTED_MODEL_CONTEXT = stringPreferencesKey("selected_model_context")
        private val KEY_SELECTED_MODEL_MAX_OUTPUT = stringPreferencesKey("selected_model_max_output")
        private val KEY_SELECTED_MODELS_BY_PROVIDER_JSON =
            stringPreferencesKey("selected_models_by_provider_json")
        private val KEY_PRIMARY_MODEL_BY_PROVIDER_JSON =
            stringPreferencesKey("primary_model_by_provider_json")
        private val KEY_MODEL_METADATA_BY_PROVIDER_JSON =
            stringPreferencesKey("model_metadata_by_provider_json")
        private val KEY_OPENAI_COMPATIBLE_PROFILES_JSON =
            stringPreferencesKey("openai_compatible_profiles_json")
        private val KEY_ACTIVE_OPENAI_COMPATIBLE_PROFILE_ID =
            stringPreferencesKey("active_openai_compatible_profile_id")
        private val KEY_WHATSAPP_ENABLED = booleanPreferencesKey("whatsapp_enabled")
        private val KEY_TELEGRAM_ENABLED = booleanPreferencesKey("telegram_enabled")
        private val KEY_TELEGRAM_BOT_TOKEN = stringPreferencesKey("telegram_bot_token")
        private val KEY_DISCORD_ENABLED = booleanPreferencesKey("discord_enabled")
        private val KEY_DISCORD_BOT_TOKEN = stringPreferencesKey("discord_bot_token")
        private val KEY_DISCORD_GUILD_ALLOWLIST = stringPreferencesKey("discord_guild_allowlist")
        private val KEY_DISCORD_REQUIRE_MENTION = booleanPreferencesKey("discord_require_mention")
        private val KEY_BRAVE_SEARCH_API_KEY = stringPreferencesKey("brave_search_api_key")
        private val KEY_MEMORY_SEARCH_ENABLED = booleanPreferencesKey("memory_search_enabled")
        private val KEY_MEMORY_SEARCH_PROVIDER = stringPreferencesKey("memory_search_provider")
        private val KEY_MEMORY_SEARCH_API_KEY = stringPreferencesKey("memory_search_api_key")
        private val KEY_GATEWAY_WAS_RUNNING = booleanPreferencesKey("gateway_was_running")
        private val KEY_GATEWAY_SURVIVOR_PID = intPreferencesKey("gateway_survivor_pid")
        private val KEY_GATEWAY_SURVIVOR_LAUNCHED_AT = longPreferencesKey("gateway_survivor_launched_at")
        private val KEY_GATEWAY_SURVIVOR_WS_ENDPOINT = stringPreferencesKey("gateway_survivor_ws_endpoint")
        private val KEY_GATEWAY_SURVIVOR_STARTUP_ATTEMPT_ACTIVE =
            booleanPreferencesKey("gateway_survivor_startup_attempt_active")
        private val KEY_GATEWAY_SURVIVOR_UPDATED_AT = longPreferencesKey("gateway_survivor_updated_at")
        private val KEY_BUNDLE_UPDATE_FAIL_COUNT_BY_VERSION = stringPreferencesKey("bundle_update_fail_count_by_version")
        private val KEY_BUNDLE_UPDATE_LAST_FAIL_AT = longPreferencesKey("bundle_update_last_fail_at")
        private val KEY_BUNDLE_UPDATE_LAST_FAIL_ELAPSED = longPreferencesKey("bundle_update_last_fail_elapsed")
        private val KEY_BUNDLE_UPDATE_LAST_FAIL_VERSION = intPreferencesKey("bundle_update_last_fail_version")
        private val KEY_BUNDLE_UPDATE_LAST_ERROR = stringPreferencesKey("bundle_update_last_error")
        private val KEY_BUNDLE_UPDATE_LAST_FAILURE_TYPE = stringPreferencesKey("bundle_update_last_failure_type")
        private val KEY_BUNDLE_UPDATE_MANUAL_RETRY_USED = booleanPreferencesKey("bundle_update_manual_retry_used")
        private val KEY_BUNDLE_UPDATE_MANUAL_RETRY_VERSION = intPreferencesKey("bundle_update_manual_retry_version")
        private val KEY_OPENCLAW_UPDATE_PROMPT_SUPPRESSED_BUNDLED_VERSION =
            stringPreferencesKey("openclaw_update_prompt_suppressed_bundled_version")
        private val KEY_LOG_SECTION_UNLOCKED = booleanPreferencesKey("log_section_unlocked")
        private val KEY_IN_APP_REVIEW_GATEWAY_HEALTHY_RUN_COUNT =
            intPreferencesKey("in_app_review_gateway_healthy_run_count")
        private val KEY_IN_APP_REVIEW_LAST_REQUEST_AT =
            longPreferencesKey("in_app_review_last_request_at")
        private val KEY_IN_APP_REVIEW_LAST_REQUEST_VERSION =
            intPreferencesKey("in_app_review_last_request_version")
        private val KEY_IN_APP_REVIEW_LAST_ATTEMPT_AT =
            longPreferencesKey("in_app_review_last_attempt_at")
        private val KEY_IN_APP_REVIEW_PENDING_REQUEST_VERSION =
            intPreferencesKey("in_app_review_pending_request_version")
        private val KEY_IN_APP_REVIEW_PENDING_REQUEST_AT =
            longPreferencesKey("in_app_review_pending_request_at")

        private const val IN_APP_REVIEW_MIN_HEALTHY_RUNS = 3
        private const val IN_APP_REVIEW_COOLDOWN_MS = 90L * 24L * 60L * 60L * 1000L
        private const val IN_APP_REVIEW_ATTEMPT_COOLDOWN_MS = 24L * 60L * 60L * 1000L
        private const val IN_APP_REVIEW_PENDING_TIMEOUT_MS = 10L * 60L * 1000L

        internal fun isInAppReviewEligibleByPolicy(
            gatewayHealthyRunCount: Int,
            lastRequestAtEpochMs: Long?,
            lastRequestVersion: Int?,
            currentVersion: Int,
            nowEpochMs: Long,
            minHealthyRuns: Int = IN_APP_REVIEW_MIN_HEALTHY_RUNS,
            cooldownMs: Long = IN_APP_REVIEW_COOLDOWN_MS,
        ): Boolean {
            val normalizedRunCount = gatewayHealthyRunCount.coerceAtLeast(0)
            val normalizedLastRequestAt = lastRequestAtEpochMs?.takeIf { it in 0..nowEpochMs }

            if (normalizedRunCount < minHealthyRuns) return false
            if (lastRequestVersion == currentVersion) return false
            if (normalizedLastRequestAt != null && nowEpochMs - normalizedLastRequestAt < cooldownMs) return false
            return true
        }

        private fun normalizeProvider(provider: String): String {
            val raw = provider.trim().lowercase()
            return when (raw) {
                "nvidia" -> "openrouter"
                else -> raw
            }
        }

        private val MODEL_SELECTION_PROVIDERS = listOf(
            "openrouter",
            "anthropic",
            "openai",
            "openai-codex",
            "github-copilot",
            "google",
            "zai",
            "kimi-coding",
            "minimax",
            "openai-compatible",
            "ollama-cloud",
            "ollama",
        )
        private val BARE_CODEX_MODEL_IDS = setOf(
            "gpt-5.1",
            "gpt-5.2",
            "gpt-5.4",
        )

        private fun defaultModelIdForProvider(provider: String): String {
            return when (normalizeProvider(provider)) {
                "openrouter" -> "openrouter/free"
                "anthropic" -> "claude-sonnet-4-5"
                "openai" -> "gpt-5-mini"
                "openai-codex" -> "gpt-5.3-codex"
                "github-copilot" -> "gpt-4o"
                "zai" -> "glm-5"
                "kimi-coding" -> "k2p5"
                "minimax" -> "MiniMax-M2.5"
            "openai-compatible" -> ""
            "ollama" -> ""
            "ollama-cloud" -> ""
                "google" -> "gemini-2.5-flash"
                else -> "openrouter/free"
            }
        }

        private fun defaultModelMetadata(modelId: String): SelectedModelConfigEntry {
            return SelectedModelConfigEntry(
                id = modelId.trim(),
                supportsReasoning = false,
                supportsImages = false,
                contextLength = 200000,
                maxOutputTokens = 4096,
            )
        }

        private fun knownProviderPrefix(modelId: String): String? {
            val normalized = modelId.trim().lowercase()
            val candidates = listOf(
                "openrouter",
                "anthropic",
                "openai",
                "openai-codex",
                "github-copilot",
                "openai-compatible",
                "ollama-cloud",
                "ollama",
                "google",
                "zai",
                "kimi-coding",
                "minimax",
            )
            return candidates.firstOrNull { normalized.startsWith("$it/") }
        }

        fun canonicalizeModelId(provider: String, modelId: String): String =
            canonicalizeModelIdForProvider(provider, modelId)

        private fun canonicalizeModelIdForProvider(provider: String, modelId: String): String {
            val trimmedModelId = modelId.trim()
            if (trimmedModelId.isBlank()) return ""
            return when (normalizeProvider(provider)) {
                "openai-compatible" -> trimmedModelId.removePrefix("openai-compatible/").trim()
                "ollama", "ollama-cloud" -> trimmedModelId.removePrefix("ollama/").removePrefix("ollama-cloud/").removeSuffix(":latest").trim()
                else -> trimmedModelId
            }
        }

        private fun canonicalizeMetadataByIdForProvider(
            provider: String,
            metadataById: Map<String, SelectedModelConfigEntry>,
        ): Map<String, SelectedModelConfigEntry> {
            if (metadataById.isEmpty()) return emptyMap()
            val normalizedProvider = normalizeProvider(provider)
            val canonicalized = linkedMapOf<String, SelectedModelConfigEntry>()
            metadataById.forEach { (rawModelId, metadata) ->
                val canonicalModelId = canonicalizeModelIdForProvider(normalizedProvider, rawModelId)
                if (canonicalModelId.isBlank()) return@forEach
                val prefersThisEntry = rawModelId.trim() == canonicalModelId
                if (!canonicalized.containsKey(canonicalModelId) || prefersThisEntry) {
                    canonicalized[canonicalModelId] = metadata.copy(id = canonicalModelId)
                }
            }
            return canonicalized.toMap()
        }

        private fun isPrivateIpv4Host(host: String): Boolean {
            val parts = host.split(".")
            if (parts.size != 4) return false
            val octets = parts.map { it.toIntOrNull() ?: return false }
            if (octets.any { it !in 0..255 }) return false
            val first = octets[0]
            val second = octets[1]
            return when {
                first == 10 -> true
                first == 127 -> true
                first == 192 && second == 168 -> true
                first == 172 && second in 16..31 -> true
                first == 169 && second == 254 -> true
                first == 100 && second in 64..127 -> true
                else -> false
            }
        }

        private fun isPrivateIpv6Host(host: String): Boolean {
            val normalized = host.trim().trim('[', ']').lowercase()
            if (normalized.isBlank() || !normalized.contains(':')) return false
            return normalized == "::1" ||
                normalized.startsWith("fe80:") ||
                normalized.startsWith("fc") ||
                normalized.startsWith("fd")
        }

        private fun isLikelyPrivateHost(host: String): Boolean {
            val normalized = host.trim().trim('[', ']').lowercase()
            if (normalized.isBlank()) return false
            if (
                normalized == "localhost" ||
                normalized.endsWith(".localhost") ||
                normalized == "10.0.2.2"
            ) {
                return true
            }
            if (isPrivateIpv4Host(normalized) || isPrivateIpv6Host(normalized)) return true
            if (!normalized.contains('.')) return true
            return normalized.endsWith(".local") ||
                normalized.endsWith(".localdomain") ||
                normalized.endsWith(".home.arpa") ||
                normalized.endsWith(".internal") ||
                normalized.endsWith(".lan") ||
                normalized.endsWith(".ts.net")
        }

        internal fun isKnownKeylessOpenAiCompatibleBaseUrl(baseUrl: String): Boolean {
            val normalizedBaseUrl = baseUrl.trim().let { trimmed ->
                when {
                    trimmed.isBlank() -> ""
                    trimmed.contains("://") -> trimmed
                    trimmed.startsWith("//") -> "http:$trimmed"
                    else -> "http://$trimmed"
                }
            }
            val host = runCatching { URI(normalizedBaseUrl).host.orEmpty() }
                .getOrNull()
                .orEmpty()
                .trim()
                .trim('[', ']')
                .lowercase()
            return isLikelyPrivateHost(host)
        }

        private fun inferProviderFromLegacyUnscopedModelId(
            modelId: String,
            profileMatchesSelectedModel: Boolean,
        ): String {
            val normalizedModelId = modelId.trim()
            if (normalizedModelId.isBlank()) return ""

            val compatibleProviders = listOf(
                "openai-codex",
                "anthropic",
                "google",
                "zai",
                "kimi-coding",
                "minimax",
                "openrouter",
                "openai",
                "openai-compatible",
            ).filter { provider ->
                val canonicalModelId = canonicalizeModelIdForProvider(provider, normalizedModelId)
                isLegacyModelCompatibleWithProvider(provider, canonicalModelId)
            }

            if (profileMatchesSelectedModel && compatibleProviders.contains("openai-compatible")) {
                return "openai-compatible"
            }

            val specificMatches = compatibleProviders.filter { it != "openai-compatible" }
            if (specificMatches.isEmpty()) return ""

            if (specificMatches.contains("openai") && specificMatches.contains("openai-codex")) {
                return "openai"
            }

            return when {
                specificMatches.contains("openai-codex") -> "openai-codex"
                specificMatches.contains("anthropic") -> "anthropic"
                specificMatches.contains("google") -> "google"
                specificMatches.contains("zai") -> "zai"
                specificMatches.contains("kimi-coding") -> "kimi-coding"
                specificMatches.contains("minimax") -> "minimax"
                specificMatches.contains("openrouter") -> "openrouter"
                specificMatches.contains("openai") -> "openai"
                else -> specificMatches.first()
            }
        }

        private fun isLegacyModelCompatibleWithProvider(
            provider: String,
            modelId: String,
        ): Boolean {
            val trimmedModelId = modelId.trim()
            if (trimmedModelId.isBlank()) return false
            val normalizedProvider = normalizeProvider(provider)
            val providerPrefix = knownProviderPrefix(trimmedModelId)
            if (providerPrefix == null) {
                val lowerModelId = trimmedModelId.lowercase()
                return when (normalizedProvider) {
                    "openrouter" -> trimmedModelId.contains("/")
                    "anthropic" -> lowerModelId.startsWith("claude")
                    "openai" -> {
                        val looksLikeOpenAiFamily = lowerModelId.startsWith("gpt-") ||
                            lowerModelId.matches(Regex("""o\d+.*"""))
                        looksLikeOpenAiFamily && !lowerModelId.contains("codex")
                    }
                    "openai-codex" -> {
                        lowerModelId.contains("codex") || lowerModelId in BARE_CODEX_MODEL_IDS
                    }
                    "github-copilot" -> true
                    "zai" -> lowerModelId.startsWith("glm-")
                    "kimi-coding" -> lowerModelId == "k2p5" || lowerModelId.startsWith("kimi-")
                    "minimax" -> lowerModelId.startsWith("minimax-")
                    "openai-compatible" -> true
                    "ollama", "ollama-cloud" -> true
                    "google" -> lowerModelId.startsWith("gemini")
                    else -> false
                }
            }
            return when (normalizedProvider) {
                "openrouter" -> providerPrefix != "openai-compatible" && providerPrefix != "openai-codex"
                "openai-codex" -> {
                    providerPrefix == "openai-codex" ||
                        (providerPrefix == "openai" &&
                            trimmedModelId.substringAfter("openai/").lowercase().contains("codex"))
                }
                "openai-compatible" -> providerPrefix == "openai-compatible" || providerPrefix == "openai"
                "ollama", "ollama-cloud" -> providerPrefix == "ollama" || providerPrefix == "ollama-cloud"
                else -> providerPrefix == normalizedProvider
            }
        }

        private fun decodeStringListMap(raw: String?): Map<String, List<String>> {
            if (raw.isNullOrBlank()) return emptyMap()
            return runCatching {
                val json = org.json.JSONObject(raw)
                val result = linkedMapOf<String, MutableList<String>>()
                val keys = json.keys()
                while (keys.hasNext()) {
                    val rawProvider = keys.next()
                    val provider = normalizeProvider(rawProvider)
                    if (provider.isBlank()) continue
                    val array = json.optJSONArray(rawProvider) ?: continue
                    val target = result.getOrPut(provider) { mutableListOf() }
                    for (index in 0 until array.length()) {
                        val modelId = array.optString(index).trim()
                        if (modelId.isNotBlank() && modelId !in target) {
                            target += modelId
                        }
                    }
                }
                result.mapValues { (_, ids) -> ids.toList() }
            }.getOrDefault(emptyMap())
        }

        private fun encodeStringListMap(map: Map<String, List<String>>): String {
            val root = org.json.JSONObject()
            map.forEach { (providerKey, modelIds) ->
                val provider = normalizeProvider(providerKey)
                if (provider.isBlank()) return@forEach
                val normalizedModelIds = modelIds
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
                    .distinct()
                if (normalizedModelIds.isEmpty()) return@forEach
                val array = org.json.JSONArray()
                normalizedModelIds.forEach(array::put)
                root.put(provider, array)
            }
            return root.toString()
        }

        private fun decodeModelMetadataByProvider(
            raw: String?,
        ): Map<String, Map<String, SelectedModelConfigEntry>> {
            if (raw.isNullOrBlank()) return emptyMap()
            return runCatching {
                val root = org.json.JSONObject(raw)
                val result = linkedMapOf<String, MutableMap<String, SelectedModelConfigEntry>>()
                val providerKeys = root.keys()
                while (providerKeys.hasNext()) {
                    val rawProvider = providerKeys.next()
                    val provider = normalizeProvider(rawProvider)
                    if (provider.isBlank()) continue
                    val providerObject = root.optJSONObject(rawProvider) ?: continue
                    val target = result.getOrPut(provider) { linkedMapOf() }
                    val modelKeys = providerObject.keys()
                    while (modelKeys.hasNext()) {
                        val modelId = modelKeys.next().trim()
                        if (modelId.isBlank()) continue
                        val modelObject = providerObject.optJSONObject(modelId) ?: continue
                        target[modelId] = SelectedModelConfigEntry(
                            id = modelId,
                            supportsReasoning = modelObject.optBoolean("supportsReasoning", false),
                            supportsImages = modelObject.optBoolean("supportsImages", false),
                            contextLength = modelObject.optInt("contextLength", 200000).coerceAtLeast(1),
                            maxOutputTokens = modelObject.optInt("maxOutputTokens", 4096).coerceAtLeast(1),
                        )
                    }
                    if (target.isEmpty()) {
                        result.remove(provider)
                    }
                }
                buildMap {
                    result.forEach { (provider, metadataById) ->
                        if (metadataById.isNotEmpty()) {
                            put(provider, metadataById.toMap())
                        }
                    }
                }
            }.getOrDefault(emptyMap())
        }

        private fun encodeModelMetadataByProvider(
            map: Map<String, Map<String, SelectedModelConfigEntry>>,
        ): String {
            val root = org.json.JSONObject()
            map.forEach { (providerKey, metadataById) ->
                val provider = normalizeProvider(providerKey)
                if (provider.isBlank()) return@forEach
                val providerObject = org.json.JSONObject()
                metadataById.forEach { (modelIdKey, entry) ->
                    val modelId = modelIdKey.trim()
                    if (modelId.isBlank()) return@forEach
                    providerObject.put(
                        modelId,
                        org.json.JSONObject().apply {
                            put("supportsReasoning", entry.supportsReasoning)
                            put("supportsImages", entry.supportsImages)
                            put("contextLength", entry.contextLength.coerceAtLeast(1))
                            put("maxOutputTokens", entry.maxOutputTokens.coerceAtLeast(1))
                        },
                    )
                }
                if (providerObject.length() > 0) {
                    root.put(provider, providerObject)
                }
            }
            return root.toString()
        }

        private fun decodeOpenAiCompatibleProfiles(raw: String?): List<OpenAiCompatibleProfile> {
            if (raw.isNullOrBlank()) return emptyList()
            return runCatching {
                val array = org.json.JSONArray(raw)
                buildList {
                    for (index in 0 until array.length()) {
                        val obj = array.optJSONObject(index) ?: continue
                        val id = obj.optString("id").trim()
                        val name = obj.optString("name").trim()
                        val baseUrl = obj.optString("baseUrl").trim()
                        val apiKey = obj.optString("apiKey")
                        if (id.isBlank()) continue
                        val selectedModels = buildList {
                            val modelsArray = obj.optJSONArray("selectedModels")
                            if (modelsArray != null) {
                                for (modelIndex in 0 until modelsArray.length()) {
                                    val modelId = modelsArray.optString(modelIndex).trim()
                                    if (modelId.isNotBlank() && modelId !in this) add(modelId)
                                }
                            }
                        }
                        val primaryModel = obj.optString("primaryModel").trim().ifBlank { null }
                        add(
                            OpenAiCompatibleProfile(
                                id = id,
                                name = name.ifBlank { id },
                                baseUrl = baseUrl.ifBlank { "https://api.openai.com/v1" },
                                apiKey = apiKey,
                                selectedModels = selectedModels,
                                primaryModel = primaryModel,
                            ),
                        )
                    }
                }
            }.getOrDefault(emptyList())
        }

        private fun encodeOpenAiCompatibleProfiles(
            profiles: List<OpenAiCompatibleProfile>,
        ): String {
            val array = org.json.JSONArray()
            profiles.forEach { profile ->
                val id = profile.id.trim()
                if (id.isBlank()) return@forEach
                val selectedModels = org.json.JSONArray()
                profile.selectedModels
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
                    .distinct()
                    .forEach(selectedModels::put)
                array.put(
                    org.json.JSONObject().apply {
                        put("id", id)
                        put("name", profile.name.trim().ifBlank { id })
                        put("baseUrl", profile.baseUrl.trim().ifBlank { "https://api.openai.com/v1" })
                        put("apiKey", profile.apiKey)
                        put("selectedModels", selectedModels)
                        put("primaryModel", profile.primaryModel?.trim().orEmpty())
                    },
                )
            }
            return array.toString()
        }
    }

        // Server preference: whether to prefer server-based Ollama models when available
        suspend fun shouldUseServerOllamaModels(): Boolean {
            val prefs = context.dataStore.data.first()
            return prefs[OLLAMA_SERVER_PREFERRED_KEY] ?: true
        }

        // Allow toggling server-based Ollama usage (default true for backward compatibility)
        suspend fun setOllamaServerPreference(useServer: Boolean) {
            context.dataStore.edit { prefs -> prefs[OLLAMA_SERVER_PREFERRED_KEY] = useServer }
        }

        suspend fun shouldUseOllamaManualFallbackModels(): Boolean {
            val prefs = context.dataStore.data.first()
            return prefs[OLLAMA_MANUAL_FALLBACK_KEY] ?: false
        }

        suspend fun setOllamaManualFallbackEnabled(useManualFallback: Boolean) {
            context.dataStore.edit { prefs -> prefs[OLLAMA_MANUAL_FALLBACK_KEY] = useManualFallback }
        }

    val isSetupComplete: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_SETUP_COMPLETE] ?: false
    }.catch { error ->
        if (error is IOException) {
            emit(emptyPreferences()[KEY_SETUP_COMPLETE] ?: false)
        } else {
            throw error
        }
    }

    val isOnboardingComplete: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_ONBOARDING_COMPLETE] ?: false
    }.catch { error ->
        if (error is IOException) {
            emit(emptyPreferences()[KEY_ONBOARDING_COMPLETE] ?: false)
        } else {
            throw error
        }
    }

    val apiProvider: Flow<String> = context.dataStore.data.map { prefs ->
        when (prefs[KEY_API_PROVIDER]) {
            "nvidia" -> "openrouter"
            null, "" -> "openrouter"
            else -> prefs[KEY_API_PROVIDER] ?: "openrouter"
        }
    }

    val apiKey: Flow<String> = combine(
        apiProvider,
        context.dataStore.data,
    ) { provider, prefs ->
        val legacy = prefs[KEY_API_KEY] ?: ""
        val profiles = decodeOpenAiCompatibleProfiles(prefs[KEY_OPENAI_COMPATIBLE_PROFILES_JSON])
        val activeProfileId = prefs[KEY_ACTIVE_OPENAI_COMPATIBLE_PROFILE_ID].orEmpty().trim()
        val activeProfile = profiles.firstOrNull { it.id == activeProfileId } ?: profiles.firstOrNull()
        when (provider) {
            "openrouter" -> prefs[KEY_API_KEY_OPENROUTER] ?: legacy
            "anthropic" -> prefs[KEY_API_KEY_ANTHROPIC] ?: ""
            "openai", "openai-codex" -> prefs[KEY_API_KEY_OPENAI] ?: ""
            "github-copilot" -> ""
            "zai" -> prefs[KEY_API_KEY_ZAI] ?: ""
            "kimi-coding" -> prefs[KEY_API_KEY_KIMI_CODING] ?: ""
            "minimax" -> prefs[KEY_API_KEY_MINIMAX] ?: ""
            "openai-compatible" -> activeProfile?.apiKey?.trim().orEmpty()
                .ifBlank { prefs[KEY_API_KEY_OPENAI_COMPATIBLE].orEmpty() }
            "ollama" -> prefs[KEY_API_KEY_OLLAMA] ?: ""
            "ollama-cloud" -> prefs[KEY_API_KEY_OLLAMA_CLOUD] ?: ""
            "google" -> prefs[KEY_API_KEY_GOOGLE] ?: ""
            else -> legacy
        }
    }

    val ollamaBaseUrl: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_OLLAMA_BASE_URL] ?: "http://127.0.0.1:11434"
    }

    val ollamaModelId: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_OLLAMA_MODEL_ID]?.trim()?.removePrefix("ollama/")?.removeSuffix(":latest").orEmpty()
    }

    val ollamaCloudModelId: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_OLLAMA_CLOUD_MODEL_ID]?.trim()?.removePrefix("ollama-cloud/")?.removePrefix("ollama/")?.removeSuffix(":latest").orEmpty()
    }

    val openAiCompatibleBaseUrl: Flow<String> = context.dataStore.data.map { prefs ->
        val profiles = decodeOpenAiCompatibleProfiles(prefs[KEY_OPENAI_COMPATIBLE_PROFILES_JSON])
        val activeProfileId = prefs[KEY_ACTIVE_OPENAI_COMPATIBLE_PROFILE_ID].orEmpty().trim()
        val activeProfile = profiles.firstOrNull { it.id == activeProfileId } ?: profiles.firstOrNull()
        activeProfile?.baseUrl ?: (prefs[KEY_OPENAI_COMPATIBLE_BASE_URL] ?: "https://api.openai.com/v1")
    }

    val openAiCompatibleModelId: Flow<String> = context.dataStore.data.map { prefs ->
        resolveOpenAiCompatibleModelId(prefs)
    }

    private fun resolveOpenAiCompatibleModelId(prefs: Preferences): String {
        val profiles = decodeOpenAiCompatibleProfiles(prefs[KEY_OPENAI_COMPATIBLE_PROFILES_JSON])
        val activeProfileId = prefs[KEY_ACTIVE_OPENAI_COMPATIBLE_PROFILE_ID].orEmpty().trim()
        val activeProfile = profiles.firstOrNull { it.id == activeProfileId } ?: profiles.firstOrNull()
        val legacyModelId = prefs[KEY_OPENAI_COMPATIBLE_MODEL_ID]
            ?.trim()
            .orEmpty()
            .removePrefix("openai-compatible/")
        val profileModelId = activeProfile?.primaryModel
            ?.trim()
            .orEmpty()
            .removePrefix("openai-compatible/")
        val activeProfileSelectedIds = activeProfile?.selectedModels
            .orEmpty()
            .map { it.trim().removePrefix("openai-compatible/") }
            .filter { it.isNotBlank() }
            .distinct()
        return if (activeProfile != null) {
            profileModelId.ifBlank {
                legacyModelId.takeIf {
                    it.isNotBlank() &&
                        (activeProfileSelectedIds.isEmpty() || activeProfileSelectedIds.contains(it))
                }.orEmpty()
            }
        } else {
            legacyModelId
        }
    }

    val openAiCompatibleProfiles: Flow<List<OpenAiCompatibleProfile>> = context.dataStore.data.map { prefs ->
        decodeOpenAiCompatibleProfiles(prefs[KEY_OPENAI_COMPATIBLE_PROFILES_JSON])
    }

    val activeOpenAiCompatibleProfileId: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_ACTIVE_OPENAI_COMPATIBLE_PROFILE_ID].orEmpty().trim()
    }

    private val activeOpenAiCompatibleProfile: Flow<OpenAiCompatibleProfile?> = combine(
        openAiCompatibleProfiles,
        activeOpenAiCompatibleProfileId,
    ) { profiles, activeProfileId ->
        profiles.firstOrNull { it.id == activeProfileId } ?: profiles.firstOrNull()
    }

    val autoStartOnBoot: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_AUTO_START_ON_BOOT] ?: false
    }

    val chargeOnlyMode: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_CHARGE_ONLY_MODE] ?: false
    }

    val openClawVersion: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_OPENCLAW_VERSION] ?: ""
    }

    val selectedModel: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_SELECTED_MODEL] ?: ""
    }

    private fun resolveSelectedModelProvider(
        snapshot: Preferences,
        selectedByProviderInput: Map<String, List<String>>? = null,
    ): String {
        val explicitProvider = normalizeProvider(snapshot[KEY_SELECTED_MODEL_PROVIDER].orEmpty())
        if (explicitProvider.isNotBlank()) return explicitProvider

        return normalizeProvider(snapshot[KEY_API_PROVIDER] ?: "openrouter")
    }

    private fun resolveActiveOpenAiCompatibleProfile(
        snapshot: Preferences,
        profilesInput: List<OpenAiCompatibleProfile>? = null,
    ): OpenAiCompatibleProfile? {
        val profiles = profilesInput ?: decodeOpenAiCompatibleProfiles(snapshot[KEY_OPENAI_COMPATIBLE_PROFILES_JSON])
        val activeProfileId = snapshot[KEY_ACTIVE_OPENAI_COMPATIBLE_PROFILE_ID].orEmpty().trim()
        return profiles.firstOrNull { it.id == activeProfileId } ?: profiles.firstOrNull()
    }

    private fun resolveStoredSelectedIdsForProvider(
        snapshot: Preferences,
        provider: String,
        selectedByProviderInput: Map<String, List<String>>? = null,
        activeProfileInput: OpenAiCompatibleProfile? = null,
        includeLegacyGlobalFallback: Boolean = false,
    ): List<String> {
        val normalizedProvider = normalizeProvider(provider)
        val selectedByProvider = selectedByProviderInput
            ?: decodeStringListMap(snapshot[KEY_SELECTED_MODELS_BY_PROVIDER_JSON])
        val selectedFromProvider = selectedByProvider[normalizedProvider].orEmpty()
            .map { canonicalizeModelIdForProvider(normalizedProvider, it) }
            .filter { isLegacyModelCompatibleWithProvider(normalizedProvider, it) }
            .distinct()
        if (selectedFromProvider.isNotEmpty()) {
            return selectedFromProvider
        }

        if (normalizedProvider == "openai-compatible") {
            val activeProfile = activeProfileInput ?: resolveActiveOpenAiCompatibleProfile(snapshot)
            val selectedFromProfile = activeProfile?.selectedModels
                .orEmpty()
                .map { canonicalizeModelIdForProvider(normalizedProvider, it) }
                .filter { it.isNotBlank() }
                .distinct()
            if (selectedFromProfile.isNotEmpty()) {
                return selectedFromProfile
            }
        }

        if (!includeLegacyGlobalFallback) return emptyList()

        val globalPrimaryProvider = resolveSelectedModelProvider(
            snapshot = snapshot,
            selectedByProviderInput = selectedByProvider,
        )
        val legacySelectedModelRaw = snapshot[KEY_SELECTED_MODEL].orEmpty().trim()
        val canonicalLegacyModelId = canonicalizeModelIdForProvider(normalizedProvider, legacySelectedModelRaw)
        return canonicalLegacyModelId
            .takeIf {
                globalPrimaryProvider == normalizedProvider &&
                    isLegacyModelCompatibleWithProvider(normalizedProvider, it)
            }
            ?.let(::listOf)
            .orEmpty()
    }

    private fun resolveStableSelectedModelProviderForBackfill(
        snapshot: Preferences,
        selectedByProviderInput: Map<String, List<String>>? = null,
    ): String {
        val explicitProvider = normalizeProvider(snapshot[KEY_SELECTED_MODEL_PROVIDER].orEmpty())
        if (explicitProvider.isNotBlank()) return explicitProvider

        // 레거시 selected_model_provider 백필은 "마지막으로 저장된 설정 탭 provider"를 그대로 복원한다.
        // selected_model / compat profile / 현재 후보 모델로 owner를 추론하면
        // 업그레이드 시 compat/openrouter owner가 뒤집히는 회귀가 반복돼서 여기서는 추론을 금지한다.
        return normalizeProvider(snapshot[KEY_API_PROVIDER] ?: "openrouter")
    }

    val selectedModelProvider: Flow<String> = context.dataStore.data.map { prefs ->
        resolveSelectedModelProvider(prefs)
    }

    private val globalPrimarySelection: Flow<Pair<String, String>> = combine(
        selectedModel,
        selectedModelProvider,
    ) { modelId, provider ->
        modelId to provider
    }

    val selectedModelReasoning: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_SELECTED_MODEL_REASONING] ?: false
    }

    val selectedModelImages: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_SELECTED_MODEL_IMAGES] ?: false
    }

    val selectedModelContext: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[KEY_SELECTED_MODEL_CONTEXT]?.toIntOrNull() ?: 200000
    }

    val selectedModelMaxOutput: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[KEY_SELECTED_MODEL_MAX_OUTPUT]?.toIntOrNull() ?: 4096
    }

    val selectedModelsByProvider: Flow<Map<String, List<String>>> = context.dataStore.data.map { prefs ->
        decodeStringListMap(prefs[KEY_SELECTED_MODELS_BY_PROVIDER_JSON])
    }

    val selectedModelMetadataByProvider: Flow<Map<String, Map<String, SelectedModelConfigEntry>>> =
        context.dataStore.data.map { prefs ->
            decodeModelMetadataByProvider(prefs[KEY_MODEL_METADATA_BY_PROVIDER_JSON])
                .mapValues { (provider, metadataById) ->
                    canonicalizeMetadataByIdForProvider(provider, metadataById)
                }
        }

    val currentProviderSelectedModelIds: Flow<List<String>> = combine(
        apiProvider,
        selectedModelsByProvider,
        selectedModel,
        selectedModelProvider,
        activeOpenAiCompatibleProfile,
    ) { provider, selectedByProvider, legacySelectedModel, globalPrimaryProvider, activeProfile ->
        val normalizedProvider = normalizeProvider(provider)
        val fromMulti = selectedByProvider[normalizedProvider]
            .orEmpty()
            .map { canonicalizeModelIdForProvider(normalizedProvider, it) }
            .filter { it.isNotBlank() }
            .distinct()
        if (fromMulti.isNotEmpty()) {
            return@combine fromMulti
        }
        if (normalizedProvider == "openai-compatible") {
            val profileSelectedIds = activeProfile?.selectedModels
                .orEmpty()
                .map { it.trim().removePrefix("openai-compatible/") }
                .filter { it.isNotBlank() }
                .distinct()
            if (profileSelectedIds.isNotEmpty()) {
                return@combine profileSelectedIds
            }
        }
        if (globalPrimaryProvider != normalizedProvider) {
            return@combine emptyList()
        }
        val canonicalLegacyModelId = canonicalizeModelIdForProvider(normalizedProvider, legacySelectedModel)
        canonicalLegacyModelId
            .takeIf { isLegacyModelCompatibleWithProvider(normalizedProvider, it) }
            ?.let(::listOf)
            .orEmpty()
    }

    val currentProviderPrimaryModelId: Flow<String> = combine(
        apiProvider,
        currentProviderSelectedModelIds,
        globalPrimarySelection,
        openAiCompatibleModelId,
    ) { provider, selectedModelIds, globalPrimarySelection, compatPrimaryModelId ->
        if (selectedModelIds.isEmpty()) return@combine ""
        val normalizedProvider = normalizeProvider(provider)
        val legacySelectedModel = globalPrimarySelection.first
        val globalPrimaryProvider = globalPrimarySelection.second
        val normalizedLegacyModelId = canonicalizeModelIdForProvider(normalizedProvider, legacySelectedModel)
        when {
            // Non-compat providers do not keep a separate provider-local primary anymore.
            // A radio selection is meaningful only for the single global primary model.
            normalizedLegacyModelId.isNotBlank() &&
                globalPrimaryProvider == normalizedProvider &&
                selectedModelIds.contains(normalizedLegacyModelId) &&
                isLegacyModelCompatibleWithProvider(normalizedProvider, normalizedLegacyModelId) ->
                normalizedLegacyModelId

            normalizedProvider == "openai-compatible" -> {
                val profilePrimary = compatPrimaryModelId
                    .trim()
                    .removePrefix("openai-compatible/")
                profilePrimary.takeIf { it.isNotBlank() && selectedModelIds.contains(it) }.orEmpty()
            }

            else -> ""
        }
    }

    val currentProviderGlobalPrimaryModelId: Flow<String> = combine(
        apiProvider,
        currentProviderSelectedModelIds,
        globalPrimarySelection,
    ) { provider, selectedModelIds, globalPrimarySelection ->
        if (selectedModelIds.isEmpty()) return@combine ""
        val normalizedProvider = normalizeProvider(provider)
        val legacySelectedModel = globalPrimarySelection.first
        val globalPrimaryProvider = globalPrimarySelection.second
        val normalizedLegacyModelId = canonicalizeModelIdForProvider(normalizedProvider, legacySelectedModel)
        when {
            normalizedLegacyModelId.isNotBlank() &&
                globalPrimaryProvider == normalizedProvider &&
                selectedModelIds.contains(normalizedLegacyModelId) &&
                isLegacyModelCompatibleWithProvider(normalizedProvider, normalizedLegacyModelId) ->
                normalizedLegacyModelId

            else -> ""
        }
    }

    private val currentProviderLegacySelectedModelEntry: Flow<SelectedModelConfigEntry?> = combine(
        selectedModel,
        selectedModelReasoning,
        selectedModelImages,
        selectedModelContext,
        selectedModelMaxOutput,
    ) { legacySelectedModel, legacyReasoning, legacyImages, legacyContext, legacyMaxOutput ->
        legacySelectedModel.trim()
            .takeIf { it.isNotBlank() }
            ?.let { modelId ->
                SelectedModelConfigEntry(
                    id = modelId,
                    supportsReasoning = legacyReasoning,
                    supportsImages = legacyImages,
                    contextLength = legacyContext.coerceAtLeast(1),
                    maxOutputTokens = legacyMaxOutput.coerceAtLeast(1),
                )
            }
    }

    val currentProviderSelectedModelEntries: Flow<List<SelectedModelConfigEntry>> = combine(
        apiProvider,
        currentProviderSelectedModelIds,
        selectedModelMetadataByProvider,
        currentProviderLegacySelectedModelEntry,
    ) { provider, selectedModelIds, metadataByProvider, legacySelectedModelEntry ->
        val normalizedProvider = normalizeProvider(provider)
        val metadataById = canonicalizeMetadataByIdForProvider(
            normalizedProvider,
            metadataByProvider[normalizedProvider].orEmpty(),
        )
        selectedModelIds.map { modelId ->
            metadataById[modelId]
                ?: legacySelectedModelEntry?.takeIf {
                    canonicalizeModelIdForProvider(normalizedProvider, it.id) == modelId
                }?.copy(id = modelId)
                ?: defaultModelMetadata(modelId)
        }
    }

    val globalDefaultModelOptions: Flow<List<GlobalDefaultModelOption>> = context.dataStore.data.map { prefs ->
        val selectedByProvider = decodeStringListMap(prefs[KEY_SELECTED_MODELS_BY_PROVIDER_JSON])
        val profiles = decodeOpenAiCompatibleProfiles(prefs[KEY_OPENAI_COMPATIBLE_PROFILES_JSON])
        val activeProfileId = prefs[KEY_ACTIVE_OPENAI_COMPATIBLE_PROFILE_ID].orEmpty().trim()
        val activeProfile = profiles.firstOrNull { it.id == activeProfileId } ?: profiles.firstOrNull()
        buildList {
            MODEL_SELECTION_PROVIDERS.forEach { provider ->
                resolveStoredSelectedIdsForProvider(
                    snapshot = prefs,
                    provider = provider,
                    selectedByProviderInput = selectedByProvider,
                    activeProfileInput = activeProfile,
                    includeLegacyGlobalFallback = true,
                ).forEach { modelId ->
                    add(
                        GlobalDefaultModelOption(
                            provider = provider,
                            modelId = canonicalizeModelIdForProvider(provider, modelId),
                        ),
                    )
                }
            }
        }.distinct()
    }

    suspend fun setSetupComplete(complete: Boolean) {
        context.dataStore.edit { it[KEY_SETUP_COMPLETE] = complete }
    }

    suspend fun setOnboardingComplete(complete: Boolean) {
        context.dataStore.edit { it[KEY_ONBOARDING_COMPLETE] = complete }
    }

    suspend fun setApiProvider(provider: String) {
        val normalizedProvider = if (provider == "nvidia") "openrouter" else provider
        context.dataStore.edit { prefs ->
            val selectedModelProvider = normalizeProvider(prefs[KEY_SELECTED_MODEL_PROVIDER].orEmpty())
            val selectedByProvider = decodeStringListMap(prefs[KEY_SELECTED_MODELS_BY_PROVIDER_JSON]).toMutableMap()
            val metadataByProvider = decodeModelMetadataByProvider(prefs[KEY_MODEL_METADATA_BY_PROVIDER_JSON]).toMutableMap()
            val activeProfile = resolveActiveOpenAiCompatibleProfile(prefs)
            if (selectedModelProvider.isBlank()) {
                val stableLegacyOwner = resolveStableSelectedModelProviderForBackfill(
                    snapshot = prefs,
                    selectedByProviderInput = selectedByProvider,
                )
                if (stableLegacyOwner.isNotBlank()) {
                    prefs[KEY_SELECTED_MODEL_PROVIDER] = stableLegacyOwner
                }
            }
            prefs[KEY_API_PROVIDER] = normalizedProvider

            val providerKey = normalizeProvider(normalizedProvider)
            val selectedIds = resolveStoredSelectedIdsForProvider(
                snapshot = prefs,
                provider = providerKey,
                selectedByProviderInput = selectedByProvider,
                activeProfileInput = activeProfile,
            )
            if (selectedIds.isNotEmpty()) {
                selectedByProvider[providerKey] = selectedIds
                val sanitizedMetadata = canonicalizeMetadataByIdForProvider(
                    providerKey,
                    metadataByProvider[providerKey].orEmpty(),
                ).filterKeys(selectedIds::contains)
                if (sanitizedMetadata.isNotEmpty()) {
                    metadataByProvider[providerKey] = sanitizedMetadata
                } else {
                    metadataByProvider.remove(providerKey)
                }
            } else {
                selectedByProvider.remove(providerKey)
                metadataByProvider.remove(providerKey)
            }

            if (selectedByProvider.isEmpty()) {
                prefs.remove(KEY_SELECTED_MODELS_BY_PROVIDER_JSON)
            } else {
                prefs[KEY_SELECTED_MODELS_BY_PROVIDER_JSON] = encodeStringListMap(selectedByProvider)
            }
            prefs.remove(KEY_PRIMARY_MODEL_BY_PROVIDER_JSON)
            if (metadataByProvider.isEmpty()) {
                prefs.remove(KEY_MODEL_METADATA_BY_PROVIDER_JSON)
            } else {
                prefs[KEY_MODEL_METADATA_BY_PROVIDER_JSON] = encodeModelMetadataByProvider(metadataByProvider)
            }
        }
    }

    suspend fun setApiKey(key: String) {
        val provider = apiProvider.first()
        setApiKeyForProvider(provider = provider, key = key, updateLegacyKey = true)
    }

    suspend fun setApiKeyForProvider(
        provider: String,
        key: String,
        updateLegacyKey: Boolean = false,
    ) {
        val normalizedProvider = normalizeProvider(provider)
        context.dataStore.edit {
            if (updateLegacyKey) {
                // 레거시 키 유지 (하위 호환)
                it[KEY_API_KEY] = key
            }
            when (normalizedProvider) {
                "openrouter" -> {
                    it[KEY_API_KEY_OPENROUTER] = key
                    if (updateLegacyKey) {
                        it[KEY_API_KEY] = key
                    }
                }
                "anthropic" -> it[KEY_API_KEY_ANTHROPIC] = key
                "openai", "openai-codex" -> it[KEY_API_KEY_OPENAI] = key
                "zai" -> it[KEY_API_KEY_ZAI] = key
                "kimi-coding" -> it[KEY_API_KEY_KIMI_CODING] = key
                "minimax" -> it[KEY_API_KEY_MINIMAX] = key
                "openai-compatible" -> {
                    it[KEY_API_KEY_OPENAI_COMPATIBLE] = key
                    val profiles = decodeOpenAiCompatibleProfiles(it[KEY_OPENAI_COMPATIBLE_PROFILES_JSON])
                        .map { profile ->
                            val normalizedSelectedModels = profile.selectedModels
                                .map { modelId -> canonicalizeModelIdForProvider("openai-compatible", modelId) }
                                .filter { modelId -> modelId.isNotBlank() }
                                .distinct()
                            val normalizedPrimaryModel = canonicalizeModelIdForProvider(
                                "openai-compatible",
                                profile.primaryModel.orEmpty(),
                            ).ifBlank { null }
                            profile.copy(
                                selectedModels = normalizedSelectedModels,
                                primaryModel = normalizedPrimaryModel,
                            )
                        }
                        .toMutableList()
                    val activeProfileId = it[KEY_ACTIVE_OPENAI_COMPATIBLE_PROFILE_ID].orEmpty().trim()
                    if (profiles.isEmpty()) {
                        val normalizedLegacyModelId = canonicalizeModelIdForProvider(
                            "openai-compatible",
                            it[KEY_OPENAI_COMPATIBLE_MODEL_ID].orEmpty(),
                        )
                        profiles += OpenAiCompatibleProfile(
                            id = "default",
                            name = "Default",
                            baseUrl = it[KEY_OPENAI_COMPATIBLE_BASE_URL] ?: "https://api.openai.com/v1",
                            apiKey = key,
                            selectedModels = normalizedLegacyModelId.takeIf { it.isNotBlank() }?.let(::listOf).orEmpty(),
                            primaryModel = normalizedLegacyModelId.ifBlank { null },
                        )
                        it[KEY_ACTIVE_OPENAI_COMPATIBLE_PROFILE_ID] = "default"
                    } else {
                        val targetIndex = profiles.indexOfFirst { profile -> profile.id == activeProfileId }
                            .takeIf { index -> index >= 0 }
                            ?: 0
                        val target = profiles[targetIndex]
                        profiles[targetIndex] = target.copy(apiKey = key)
                    }
                    it[KEY_OPENAI_COMPATIBLE_PROFILES_JSON] = encodeOpenAiCompatibleProfiles(profiles)
                }
                "ollama" -> it[KEY_API_KEY_OLLAMA] = key
                "ollama-cloud" -> it[KEY_API_KEY_OLLAMA_CLOUD] = key
                "google" -> it[KEY_API_KEY_GOOGLE] = key
                else -> { /* no-op */ }
            }
        }
    }

    suspend fun getApiKeyForProvider(provider: String): String {
        val snapshot = context.dataStore.data.first()
        val legacy = snapshot[KEY_API_KEY].orEmpty()
        return when (normalizeProvider(provider)) {
            "openrouter" -> snapshot[KEY_API_KEY_OPENROUTER] ?: legacy
            "anthropic" -> snapshot[KEY_API_KEY_ANTHROPIC].orEmpty()
            "openai", "openai-codex" -> snapshot[KEY_API_KEY_OPENAI].orEmpty()
            "github-copilot" -> if (hasGitHubCopilotAuthProfile()) "__github_copilot_auth__" else ""
            "zai" -> snapshot[KEY_API_KEY_ZAI].orEmpty()
            "kimi-coding" -> snapshot[KEY_API_KEY_KIMI_CODING].orEmpty()
            "minimax" -> snapshot[KEY_API_KEY_MINIMAX].orEmpty()
            "openai-compatible" -> {
                val profiles = decodeOpenAiCompatibleProfiles(snapshot[KEY_OPENAI_COMPATIBLE_PROFILES_JSON])
                val activeProfileId = snapshot[KEY_ACTIVE_OPENAI_COMPATIBLE_PROFILE_ID].orEmpty().trim()
                val activeProfile = profiles.firstOrNull { it.id == activeProfileId } ?: profiles.firstOrNull()
                activeProfile?.apiKey?.trim().orEmpty()
                    .ifBlank { snapshot[KEY_API_KEY_OPENAI_COMPATIBLE].orEmpty() }
            }
            "ollama" -> snapshot[KEY_API_KEY_OLLAMA].orEmpty()
            "ollama-cloud" -> snapshot[KEY_API_KEY_OLLAMA_CLOUD].orEmpty()
            "google" -> snapshot[KEY_API_KEY_GOOGLE].orEmpty()
            else -> legacy
        }
    }

    suspend fun hasApiKeyForProvider(provider: String): Boolean {
        val snapshot = context.dataStore.data.first()
        val legacy = snapshot[KEY_API_KEY].orEmpty()
        val key = when (normalizeProvider(provider)) {
            "openrouter" -> snapshot[KEY_API_KEY_OPENROUTER] ?: legacy
            "anthropic" -> snapshot[KEY_API_KEY_ANTHROPIC].orEmpty()
            "openai", "openai-codex" -> snapshot[KEY_API_KEY_OPENAI].orEmpty()
            "github-copilot" -> if (hasGitHubCopilotAuthProfile()) "__github_copilot_auth__" else ""
            "zai" -> snapshot[KEY_API_KEY_ZAI].orEmpty()
            "kimi-coding" -> snapshot[KEY_API_KEY_KIMI_CODING].orEmpty()
            "minimax" -> snapshot[KEY_API_KEY_MINIMAX].orEmpty()
            "openai-compatible" -> {
                val profiles = decodeOpenAiCompatibleProfiles(snapshot[KEY_OPENAI_COMPATIBLE_PROFILES_JSON])
                val activeProfileId = snapshot[KEY_ACTIVE_OPENAI_COMPATIBLE_PROFILE_ID].orEmpty().trim()
                val activeProfile = profiles.firstOrNull { it.id == activeProfileId } ?: profiles.firstOrNull()
                activeProfile?.apiKey?.trim().orEmpty()
                    .ifBlank { snapshot[KEY_API_KEY_OPENAI_COMPATIBLE].orEmpty() }
            }
            "ollama" -> snapshot[KEY_API_KEY_OLLAMA].orEmpty()
            "ollama-cloud" -> snapshot[KEY_API_KEY_OLLAMA_CLOUD].orEmpty()
            "google" -> snapshot[KEY_API_KEY_GOOGLE].orEmpty()
            else -> legacy
        }
        return key.isNotBlank()
    }

    suspend fun setGitHubCopilotAuthenticated(authenticated: Boolean) {
        context.dataStore.edit { prefs ->
            if (authenticated) {
                prefs[KEY_GITHUB_COPILOT_AUTHENTICATED] = true
            } else {
                prefs.remove(KEY_GITHUB_COPILOT_AUTHENTICATED)
            }
        }
    }

    suspend fun getLaunchApiKeyWarning(): LaunchApiKeyWarning {
        val launchConfig = getGatewayLaunchConfigSnapshot()
        if (launchConfig.selectedModelEntries.isEmpty()) {
            return LaunchApiKeyWarning(
                shouldWarn = false,
                provider = launchConfig.apiProvider,
                hasSelectedModels = false,
            )
        }
        val shouldWarn = when (launchConfig.apiProvider) {
            "openai-codex" -> false
            "openai-compatible" -> {
                launchConfig.apiKey.isBlank() &&
                    !isKnownKeylessOpenAiCompatibleBaseUrl(launchConfig.openAiCompatibleBaseUrl)
            }
            "ollama" -> false
            else -> launchConfig.apiKey.isBlank()
        }
        return LaunchApiKeyWarning(
            shouldWarn = shouldWarn,
            provider = launchConfig.apiProvider,
            hasSelectedModels = true,
        )
    }

    suspend fun shouldWarnApiKeyForLaunch(): Boolean = getLaunchApiKeyWarning().shouldWarn

    suspend fun setOpenAiCompatibleBaseUrl(baseUrl: String) {
        context.dataStore.edit { prefs ->
            prefs[KEY_OPENAI_COMPATIBLE_BASE_URL] = baseUrl
            val profiles = decodeOpenAiCompatibleProfiles(prefs[KEY_OPENAI_COMPATIBLE_PROFILES_JSON]).toMutableList()
            if (profiles.isEmpty()) return@edit
            val activeProfileId = prefs[KEY_ACTIVE_OPENAI_COMPATIBLE_PROFILE_ID].orEmpty().trim()
            val targetIndex = profiles.indexOfFirst { it.id == activeProfileId }
                .takeIf { it >= 0 }
                ?: 0
            profiles[targetIndex] = profiles[targetIndex].copy(baseUrl = baseUrl.trim().ifBlank { "https://api.openai.com/v1" })
            prefs[KEY_OPENAI_COMPATIBLE_PROFILES_JSON] = encodeOpenAiCompatibleProfiles(profiles)
        }
    }

    suspend fun setOpenAiCompatibleModelId(modelId: String) {
        context.dataStore.edit { prefs ->
            val normalizedModelId = modelId.trim().removePrefix("openai-compatible/")
            if (normalizedModelId.isBlank()) {
                prefs.remove(KEY_OPENAI_COMPATIBLE_MODEL_ID)
            } else {
                prefs[KEY_OPENAI_COMPATIBLE_MODEL_ID] = normalizedModelId
            }
            val profiles = decodeOpenAiCompatibleProfiles(prefs[KEY_OPENAI_COMPATIBLE_PROFILES_JSON]).toMutableList()
            if (profiles.isEmpty()) return@edit
            val activeProfileId = prefs[KEY_ACTIVE_OPENAI_COMPATIBLE_PROFILE_ID].orEmpty().trim()
            val targetIndex = profiles.indexOfFirst { it.id == activeProfileId }
                .takeIf { it >= 0 }
                ?: 0
            val target = profiles[targetIndex]
            val existingSelectedModels = target.selectedModels
                .map { it.trim().removePrefix("openai-compatible/") }
                .filter { it.isNotBlank() }
            val selectedModels = if (normalizedModelId.isBlank()) {
                existingSelectedModels.distinct()
            } else {
                buildList {
                    add(normalizedModelId)
                    addAll(existingSelectedModels.filterNot { it == normalizedModelId })
                }.distinct()
            }
            profiles[targetIndex] = target.copy(
                selectedModels = selectedModels,
                primaryModel = normalizedModelId.ifBlank { null },
            )
            prefs[KEY_OPENAI_COMPATIBLE_PROFILES_JSON] = encodeOpenAiCompatibleProfiles(profiles)
        }
    }

    suspend fun setOllamaBaseUrl(baseUrl: String) {
        context.dataStore.edit { prefs ->
            prefs[KEY_OLLAMA_BASE_URL] = baseUrl.trim().ifBlank { "http://127.0.0.1:11434" }
        }
    }

    suspend fun setOllamaModelId(modelId: String) {
        context.dataStore.edit { prefs ->
            val normalizedModelId = modelId.trim().removePrefix("ollama/").removeSuffix(":latest")
            if (normalizedModelId.isBlank()) {
                prefs.remove(KEY_OLLAMA_MODEL_ID)
            } else {
                prefs[KEY_OLLAMA_MODEL_ID] = normalizedModelId
            }
        }
    }

    suspend fun setAutoStartOnBoot(enabled: Boolean) {
        context.dataStore.edit { it[KEY_AUTO_START_ON_BOOT] = enabled }
    }

    suspend fun setChargeOnlyMode(enabled: Boolean) {
        context.dataStore.edit { it[KEY_CHARGE_ONLY_MODE] = enabled }
    }

    suspend fun setOpenClawVersion(version: String) {
        context.dataStore.edit { it[KEY_OPENCLAW_VERSION] = version }
    }

    suspend fun setSelectedModel(model: OpenRouterModel) {
        val provider = apiProvider.first()
        val normalizedModelId = canonicalizeModelIdForProvider(provider, model.id)
        context.dataStore.edit {
            it[KEY_SELECTED_MODEL] = normalizedModelId
            it[KEY_SELECTED_MODEL_PROVIDER] = normalizeProvider(provider)
            it[KEY_SELECTED_MODEL_REASONING] = model.supportsReasoning
            it[KEY_SELECTED_MODEL_IMAGES] = model.supportsImages
            it[KEY_SELECTED_MODEL_CONTEXT] = model.contextLength.toString()
            it[KEY_SELECTED_MODEL_MAX_OUTPUT] = model.maxOutputTokens.toString()
        }
        setSelectedModels(provider, listOf(model), primary = model.id)
    }

    suspend fun setSelectedModelId(modelId: String) {
        val provider = apiProvider.first()
        val normalizedModelId = canonicalizeModelIdForProvider(provider, modelId)
        context.dataStore.edit {
            it[KEY_SELECTED_MODEL] = normalizedModelId
            if (normalizedModelId.isBlank()) {
                it.remove(KEY_SELECTED_MODEL_PROVIDER)
            } else {
                it[KEY_SELECTED_MODEL_PROVIDER] = normalizeProvider(provider)
            }
        }
        if (normalizedModelId.isBlank()) {
            clearSelectedModels(provider)
            return
        }
        val fallbackMetadata = resolveDefaultModelMetadata(provider, normalizedModelId)
        setSelectedModelIds(
            provider = provider,
            modelIds = listOf(normalizedModelId),
            primary = normalizedModelId,
            metadataById = mapOf(normalizedModelId to fallbackMetadata),
        )
    }

    suspend fun setSelectedModels(
        provider: String,
        models: List<OpenRouterModel>,
        primary: String? = null,
    ) {
        val metadataById = models.associate { model ->
            model.id to SelectedModelConfigEntry(
                id = model.id,
                supportsReasoning = model.supportsReasoning,
                supportsImages = model.supportsImages,
                contextLength = model.contextLength,
                maxOutputTokens = model.maxOutputTokens,
            )
        }
        setSelectedModelIds(
            provider = provider,
            modelIds = models.map { it.id },
            primary = primary,
            metadataById = metadataById,
        )
    }

    suspend fun setSelectedModelsWithoutActivatingProvider(
        provider: String,
        models: List<OpenRouterModel>,
        primary: String? = null,
    ) {
        val metadataById = models.associate { model ->
            model.id to SelectedModelConfigEntry(
                id = model.id,
                supportsReasoning = model.supportsReasoning,
                supportsImages = model.supportsImages,
                contextLength = model.contextLength,
                maxOutputTokens = model.maxOutputTokens,
            )
        }
        setSelectedModelIdsInternal(
            provider = provider,
            modelIds = models.map { it.id },
            primary = primary,
            metadataById = metadataById,
            activateProvider = false,
        )
    }

    suspend fun setSelectedModelIds(
        provider: String,
        modelIds: List<String>,
        primary: String? = null,
        metadataById: Map<String, SelectedModelConfigEntry> = emptyMap(),
    ) {
        setSelectedModelIdsInternal(
            provider = provider,
            modelIds = modelIds,
            primary = primary,
            metadataById = metadataById,
            activateProvider = true,
        )
    }

    suspend fun setSelectedModelIdsWithoutActivatingProvider(
        provider: String,
        modelIds: List<String>,
        primary: String? = null,
        metadataById: Map<String, SelectedModelConfigEntry> = emptyMap(),
    ) {
        setSelectedModelIdsInternal(
            provider = provider,
            modelIds = modelIds,
            primary = primary,
            metadataById = metadataById,
            activateProvider = false,
        )
    }

    private suspend fun setSelectedModelIdsInternal(
        provider: String,
        modelIds: List<String>,
        primary: String? = null,
        metadataById: Map<String, SelectedModelConfigEntry> = emptyMap(),
        activateProvider: Boolean,
    ) {
        val normalizedProvider = normalizeProvider(provider)
        val normalizedIds = modelIds
            .map { canonicalizeModelIdForProvider(normalizedProvider, it) }
            .filter { it.isNotBlank() }
            .distinct()
        context.dataStore.edit { prefs ->
            val selectedByProvider = decodeStringListMap(prefs[KEY_SELECTED_MODELS_BY_PROVIDER_JSON]).toMutableMap()
            val metadataByProvider = decodeModelMetadataByProvider(prefs[KEY_MODEL_METADATA_BY_PROVIDER_JSON]).toMutableMap()
            val globalPrimaryProvider = resolveSelectedModelProvider(
                snapshot = prefs,
                selectedByProviderInput = selectedByProvider,
            )

            fun syncOpenAiCompatibleProfileSelection(
                selectedModelIds: List<String>,
                primaryModelId: String?,
            ) {
                if (normalizedProvider != "openai-compatible") return
                val profiles = decodeOpenAiCompatibleProfiles(prefs[KEY_OPENAI_COMPATIBLE_PROFILES_JSON]).toMutableList()
                val activeProfileId = prefs[KEY_ACTIVE_OPENAI_COMPATIBLE_PROFILE_ID].orEmpty().trim()
                if (profiles.isEmpty()) {
                    val createdProfileId = activeProfileId.ifBlank { "default" }
                    profiles += OpenAiCompatibleProfile(
                        id = createdProfileId,
                        name = if (createdProfileId == "default") "Default" else createdProfileId,
                        baseUrl = prefs[KEY_OPENAI_COMPATIBLE_BASE_URL] ?: "https://api.openai.com/v1",
                        apiKey = prefs[KEY_API_KEY_OPENAI_COMPATIBLE].orEmpty(),
                        selectedModels = selectedModelIds,
                        primaryModel = primaryModelId,
                    )
                    prefs[KEY_ACTIVE_OPENAI_COMPATIBLE_PROFILE_ID] = createdProfileId
                } else {
                    val targetIndex = profiles.indexOfFirst { it.id == activeProfileId }
                        .takeIf { it >= 0 }
                        ?: 0
                    val targetProfile = profiles[targetIndex]
                    profiles[targetIndex] = targetProfile.copy(
                        selectedModels = selectedModelIds,
                        primaryModel = primaryModelId,
                    )
                    if (activeProfileId.isBlank()) {
                        prefs[KEY_ACTIVE_OPENAI_COMPATIBLE_PROFILE_ID] = profiles[targetIndex].id
                    }
                }
                prefs[KEY_OPENAI_COMPATIBLE_PROFILES_JSON] = encodeOpenAiCompatibleProfiles(profiles)
            }

            if (normalizedIds.isEmpty()) {
                selectedByProvider.remove(normalizedProvider)
                metadataByProvider.remove(normalizedProvider)
                syncOpenAiCompatibleProfileSelection(emptyList(), null)
                if (normalizedProvider == "openai-compatible") {
                    prefs.remove(KEY_OPENAI_COMPATIBLE_MODEL_ID)
                }
                if (globalPrimaryProvider == normalizedProvider) {
                    prefs[KEY_SELECTED_MODEL] = ""
                    prefs.remove(KEY_SELECTED_MODEL_PROVIDER)
                    prefs.remove(KEY_SELECTED_MODEL_REASONING)
                    prefs.remove(KEY_SELECTED_MODEL_IMAGES)
                    prefs.remove(KEY_SELECTED_MODEL_CONTEXT)
                    prefs.remove(KEY_SELECTED_MODEL_MAX_OUTPUT)
                }
            } else {
                selectedByProvider[normalizedProvider] = normalizedIds
                val hasExplicitPrimaryDirective = primary != null
                val requestedPrimary = canonicalizeModelIdForProvider(normalizedProvider, primary.orEmpty())
                val explicitPrimary = requestedPrimary
                    .takeIf { it.isNotBlank() && normalizedIds.contains(it) }
                    .orEmpty()
                val profiles = if (normalizedProvider == "openai-compatible") {
                    decodeOpenAiCompatibleProfiles(prefs[KEY_OPENAI_COMPATIBLE_PROFILES_JSON])
                } else {
                    emptyList()
                }
                val activeProfileId = prefs[KEY_ACTIVE_OPENAI_COMPATIBLE_PROFILE_ID].orEmpty().trim()
                val activeProfile = profiles.firstOrNull { it.id == activeProfileId } ?: profiles.firstOrNull()
                val existingProfilePrimary = activeProfile?.primaryModel
                    .orEmpty()
                    .trim()
                    .removePrefix("openai-compatible/")
                    .takeIf { it.isNotBlank() && normalizedIds.contains(it) }
                    .orEmpty()
                val effectiveProfilePrimary = when {
                    explicitPrimary.isNotBlank() -> explicitPrimary
                    hasExplicitPrimaryDirective -> ""
                    else -> existingProfilePrimary
                }
                syncOpenAiCompatibleProfileSelection(normalizedIds, effectiveProfilePrimary.ifBlank { null })

                val providerMetadata = canonicalizeMetadataByIdForProvider(
                    normalizedProvider,
                    metadataByProvider[normalizedProvider].orEmpty(),
                ).toMutableMap()
                metadataById.forEach { (modelId, metadata) ->
                    val normalizedModelId = canonicalizeModelIdForProvider(normalizedProvider, modelId)
                    if (normalizedModelId.isBlank()) return@forEach
                    providerMetadata[normalizedModelId] = metadata.copy(id = normalizedModelId)
                }
                providerMetadata.keys
                    .filterNot { normalizedIds.contains(it) }
                    .forEach(providerMetadata::remove)
                if (providerMetadata.isNotEmpty()) {
                    metadataByProvider[normalizedProvider] = providerMetadata
                } else {
                    metadataByProvider.remove(normalizedProvider)
                }

                if (explicitPrimary.isNotBlank()) {
                    val metadataForLegacy = providerMetadata[explicitPrimary]
                        ?: resolveDefaultModelMetadata(normalizedProvider, explicitPrimary)
                    if (activateProvider || globalPrimaryProvider == normalizedProvider) {
                        prefs[KEY_SELECTED_MODEL] = explicitPrimary
                        prefs[KEY_SELECTED_MODEL_PROVIDER] = normalizedProvider
                        prefs[KEY_SELECTED_MODEL_REASONING] = metadataForLegacy.supportsReasoning
                        prefs[KEY_SELECTED_MODEL_IMAGES] = metadataForLegacy.supportsImages
                        prefs[KEY_SELECTED_MODEL_CONTEXT] = metadataForLegacy.contextLength.toString()
                        prefs[KEY_SELECTED_MODEL_MAX_OUTPUT] = metadataForLegacy.maxOutputTokens.toString()
                    }
                    if (activateProvider) {
                        prefs[KEY_API_PROVIDER] = normalizedProvider
                    }
                    if (normalizedProvider == "openai-compatible") {
                        prefs[KEY_OPENAI_COMPATIBLE_MODEL_ID] = explicitPrimary
                    } else if (normalizedProvider == "ollama") {
                        prefs[KEY_OLLAMA_MODEL_ID] = explicitPrimary
                    } else if (normalizedProvider == "ollama-cloud") {
                        prefs[KEY_OLLAMA_CLOUD_MODEL_ID] = explicitPrimary
                    }
                } else if (hasExplicitPrimaryDirective) {
                    if (globalPrimaryProvider == normalizedProvider) {
                        prefs[KEY_SELECTED_MODEL] = ""
                        if (normalizedIds.isEmpty()) {
                            prefs.remove(KEY_SELECTED_MODEL_PROVIDER)
                        } else {
                            prefs[KEY_SELECTED_MODEL_PROVIDER] = normalizedProvider
                        }
                        prefs.remove(KEY_SELECTED_MODEL_REASONING)
                        prefs.remove(KEY_SELECTED_MODEL_IMAGES)
                        prefs.remove(KEY_SELECTED_MODEL_CONTEXT)
                        prefs.remove(KEY_SELECTED_MODEL_MAX_OUTPUT)
                    }
                    if (normalizedProvider == "openai-compatible") {
                        prefs.remove(KEY_OPENAI_COMPATIBLE_MODEL_ID)
                    } else if (normalizedProvider == "ollama") {
                        prefs.remove(KEY_OLLAMA_MODEL_ID)
                    } else if (normalizedProvider == "ollama-cloud") {
                        prefs.remove(KEY_OLLAMA_CLOUD_MODEL_ID)
                    }
                }
            }

            if (selectedByProvider.isEmpty()) {
                prefs.remove(KEY_SELECTED_MODELS_BY_PROVIDER_JSON)
            } else {
                prefs[KEY_SELECTED_MODELS_BY_PROVIDER_JSON] = encodeStringListMap(selectedByProvider)
            }
            prefs.remove(KEY_PRIMARY_MODEL_BY_PROVIDER_JSON)
            if (metadataByProvider.isEmpty()) {
                prefs.remove(KEY_MODEL_METADATA_BY_PROVIDER_JSON)
            } else {
                prefs[KEY_MODEL_METADATA_BY_PROVIDER_JSON] = encodeModelMetadataByProvider(metadataByProvider)
            }
        }
    }

    suspend fun clearSelectedModels(provider: String) {
        setSelectedModelIds(provider = provider, modelIds = emptyList())
    }

    suspend fun setGlobalPrimaryModel(provider: String, modelId: String) {
        val normalizedProvider = normalizeProvider(provider)
        val normalizedModelId = canonicalizeModelIdForProvider(normalizedProvider, modelId)
        if (normalizedProvider.isBlank() || normalizedModelId.isBlank()) return

        context.dataStore.edit { prefs ->
            val selectedByProvider = decodeStringListMap(prefs[KEY_SELECTED_MODELS_BY_PROVIDER_JSON])
            val profiles = decodeOpenAiCompatibleProfiles(prefs[KEY_OPENAI_COMPATIBLE_PROFILES_JSON]).toMutableList()
            val activeProfileId = prefs[KEY_ACTIVE_OPENAI_COMPATIBLE_PROFILE_ID].orEmpty().trim()
            val activeProfile = profiles.firstOrNull { it.id == activeProfileId } ?: profiles.firstOrNull()
            val selectedIds = resolveStoredSelectedIdsForProvider(
                snapshot = prefs,
                provider = normalizedProvider,
                selectedByProviderInput = selectedByProvider,
                activeProfileInput = activeProfile,
                includeLegacyGlobalFallback = true,
            )
            if (!selectedIds.contains(normalizedModelId)) return@edit

            val metadataByProvider = decodeModelMetadataByProvider(prefs[KEY_MODEL_METADATA_BY_PROVIDER_JSON])
            val metadata = canonicalizeMetadataByIdForProvider(
                normalizedProvider,
                metadataByProvider[normalizedProvider].orEmpty(),
            )[normalizedModelId] ?: resolveDefaultModelMetadata(normalizedProvider, normalizedModelId)

            prefs[KEY_SELECTED_MODEL] = normalizedModelId
            prefs[KEY_SELECTED_MODEL_PROVIDER] = normalizedProvider
            prefs[KEY_SELECTED_MODEL_REASONING] = metadata.supportsReasoning
            prefs[KEY_SELECTED_MODEL_IMAGES] = metadata.supportsImages
            prefs[KEY_SELECTED_MODEL_CONTEXT] = metadata.contextLength.toString()
            prefs[KEY_SELECTED_MODEL_MAX_OUTPUT] = metadata.maxOutputTokens.toString()

            if (normalizedProvider == "openai-compatible") {
                prefs[KEY_OPENAI_COMPATIBLE_MODEL_ID] = normalizedModelId
                if (profiles.isNotEmpty()) {
                    val targetIndex = profiles.indexOfFirst { it.id == activeProfileId }
                        .takeIf { it >= 0 }
                        ?: 0
                    val targetProfile = profiles[targetIndex]
                    val normalizedSelectedModels = targetProfile.selectedModels
                        .map { it.trim().removePrefix("openai-compatible/") }
                        .filter { it.isNotBlank() }
                        .distinct()
                    if (normalizedSelectedModels.contains(normalizedModelId)) {
                        profiles[targetIndex] = targetProfile.copy(primaryModel = normalizedModelId)
                        prefs[KEY_OPENAI_COMPATIBLE_PROFILES_JSON] = encodeOpenAiCompatibleProfiles(profiles)
                    }
                }
            } else if (normalizedProvider == "ollama") {
                prefs[KEY_OLLAMA_MODEL_ID] = normalizedModelId
            } else if (normalizedProvider == "ollama-cloud") {
                prefs[KEY_OLLAMA_CLOUD_MODEL_ID] = normalizedModelId
            }
        }
    }

    suspend fun backfillSelectedModelProviderIfMissing(): Boolean {
        var changed = false
        context.dataStore.edit { prefs ->
            if (!prefs[KEY_SELECTED_MODEL_PROVIDER].isNullOrBlank()) {
                return@edit
            }
            val stableSelectedModelProvider = resolveStableSelectedModelProviderForBackfill(snapshot = prefs)
            if (stableSelectedModelProvider.isNotBlank()) {
                prefs[KEY_SELECTED_MODEL_PROVIDER] = stableSelectedModelProvider
                changed = true
            }
        }
        return changed
    }

    suspend fun ensureCurrentProviderModelSelection(): Boolean {
        var changed = false
        context.dataStore.edit { prefs ->
            val provider = normalizeProvider(prefs[KEY_API_PROVIDER] ?: "openrouter")
            val originalSelectedModelProvider = normalizeProvider(prefs[KEY_SELECTED_MODEL_PROVIDER].orEmpty())
            val selectedByProviderOriginal = decodeStringListMap(prefs[KEY_SELECTED_MODELS_BY_PROVIDER_JSON])
            val metadataByProviderOriginal = decodeModelMetadataByProvider(prefs[KEY_MODEL_METADATA_BY_PROVIDER_JSON])
            val activeProfile = resolveActiveOpenAiCompatibleProfile(prefs)

            val selectedByProvider = selectedByProviderOriginal.toMutableMap()
            val metadataByProvider = metadataByProviderOriginal.toMutableMap()
            var legacyProviderBackfilled = false

            if (originalSelectedModelProvider.isBlank()) {
                val stableSelectedModelProvider = resolveStableSelectedModelProviderForBackfill(
                    snapshot = prefs,
                    selectedByProviderInput = selectedByProvider,
                )
                if (stableSelectedModelProvider.isNotBlank()) {
                    prefs[KEY_SELECTED_MODEL_PROVIDER] = stableSelectedModelProvider
                    legacyProviderBackfilled = true
                }
            }

            val effectiveSelectedIds = resolveStoredSelectedIdsForProvider(
                snapshot = prefs,
                provider = provider,
                selectedByProviderInput = selectedByProvider,
                activeProfileInput = activeProfile,
            )

            if (effectiveSelectedIds.isEmpty()) {
                selectedByProvider.remove(provider)
                metadataByProvider.remove(provider)
            } else {
                selectedByProvider[provider] = effectiveSelectedIds

                val sanitizedMetadata = canonicalizeMetadataByIdForProvider(
                    provider,
                    metadataByProvider[provider].orEmpty(),
                ).filterKeys(effectiveSelectedIds::contains)
                if (sanitizedMetadata.isNotEmpty()) {
                    metadataByProvider[provider] = sanitizedMetadata
                } else {
                    metadataByProvider.remove(provider)
                }
            }

            if (
                !legacyProviderBackfilled &&
                selectedByProvider == selectedByProviderOriginal &&
                metadataByProvider == metadataByProviderOriginal
            ) {
                return@edit
            }

            if (selectedByProvider.isEmpty()) {
                prefs.remove(KEY_SELECTED_MODELS_BY_PROVIDER_JSON)
            } else {
                prefs[KEY_SELECTED_MODELS_BY_PROVIDER_JSON] = encodeStringListMap(selectedByProvider)
            }
            prefs.remove(KEY_PRIMARY_MODEL_BY_PROVIDER_JSON)
            if (metadataByProvider.isEmpty()) {
                prefs.remove(KEY_MODEL_METADATA_BY_PROVIDER_JSON)
            } else {
                prefs[KEY_MODEL_METADATA_BY_PROVIDER_JSON] = encodeModelMetadataByProvider(metadataByProvider)
            }
            changed = true
        }
        return changed
    }

    suspend fun getGatewayLaunchConfigSnapshot(): GatewayLaunchConfigSnapshot {
        backfillSelectedModelProviderIfMissing()
        val snapshot = context.dataStore.data.first()
        val configuredProvider = normalizeProvider(snapshot[KEY_API_PROVIDER] ?: "openrouter")

        val profiles = decodeOpenAiCompatibleProfiles(snapshot[KEY_OPENAI_COMPATIBLE_PROFILES_JSON])
        val activeProfileId = snapshot[KEY_ACTIVE_OPENAI_COMPATIBLE_PROFILE_ID].orEmpty().trim()
        val activeProfile = profiles.firstOrNull { it.id == activeProfileId } ?: profiles.firstOrNull()

        val selectedByProvider = decodeStringListMap(snapshot[KEY_SELECTED_MODELS_BY_PROVIDER_JSON])
        val metadataByProvider = decodeModelMetadataByProvider(snapshot[KEY_MODEL_METADATA_BY_PROVIDER_JSON])
        val globalPrimaryProvider = resolveSelectedModelProvider(
            snapshot = snapshot,
            selectedByProviderInput = selectedByProvider,
        )
        val legacySelectedModelRaw = snapshot[KEY_SELECTED_MODEL].orEmpty().trim()

        fun resolveSelectedModelIds(targetProvider: String): List<String> {
            return resolveStoredSelectedIdsForProvider(
                snapshot = snapshot,
                provider = targetProvider,
                selectedByProviderInput = selectedByProvider,
                activeProfileInput = activeProfile,
                includeLegacyGlobalFallback = true,
            )
        }

        var provider = configuredProvider
        var selectedModelIds = resolveSelectedModelIds(provider)
        val normalizedGlobalPrimaryProvider = normalizeProvider(globalPrimaryProvider)
        if (normalizedGlobalPrimaryProvider.isNotBlank()) {
            val globalSelectedModelIds = resolveSelectedModelIds(normalizedGlobalPrimaryProvider)
            if (globalSelectedModelIds.isNotEmpty()) {
                // 런타임 provider는 현재 설정 탭이 아니라 전역 기본 모델의 owner를 따른다.
                // 사용자가 다른 provider 탭을 열어도 기본 모델을 바꾸기 전까지는 기존 runtime provider를 유지해야 한다.
                provider = normalizedGlobalPrimaryProvider
                selectedModelIds = globalSelectedModelIds
            }
        }
        if (selectedModelIds.isEmpty()) {
            val fallbackProviders = buildList {
                val explicitSelectedModelProvider = normalizeProvider(
                    snapshot[KEY_SELECTED_MODEL_PROVIDER].orEmpty(),
                )
                if (explicitSelectedModelProvider.isNotBlank()) add(explicitSelectedModelProvider)
                selectedByProvider.keys
                    .map(::normalizeProvider)
                    .filter { it.isNotBlank() && it !in this }
                    .forEach(::add)
                if (activeProfile?.selectedModels.orEmpty().isNotEmpty() && "openai-compatible" !in this) {
                    add("openai-compatible")
                }
            }
            val fallbackProvider = fallbackProviders.firstOrNull { candidate ->
                resolveSelectedModelIds(candidate).isNotEmpty()
            }
            if (fallbackProvider != null) {
                provider = fallbackProvider
                selectedModelIds = resolveSelectedModelIds(fallbackProvider)
            }
        }

        val legacyApiKey = snapshot[KEY_API_KEY].orEmpty()
        val apiKey = when (provider) {
            "openrouter" -> snapshot[KEY_API_KEY_OPENROUTER] ?: legacyApiKey
            "anthropic" -> snapshot[KEY_API_KEY_ANTHROPIC].orEmpty()
            "openai", "openai-codex" -> snapshot[KEY_API_KEY_OPENAI].orEmpty()
            "github-copilot" -> if (hasGitHubCopilotAuthProfile()) "__github_copilot_auth__" else ""
            "zai" -> snapshot[KEY_API_KEY_ZAI].orEmpty()
            "kimi-coding" -> snapshot[KEY_API_KEY_KIMI_CODING].orEmpty()
            "minimax" -> snapshot[KEY_API_KEY_MINIMAX].orEmpty()
            "openai-compatible" -> activeProfile?.apiKey?.trim().orEmpty()
                .ifBlank { snapshot[KEY_API_KEY_OPENAI_COMPATIBLE].orEmpty() }
            "ollama" -> snapshot[KEY_API_KEY_OLLAMA].orEmpty()
            "ollama-cloud" -> snapshot[KEY_API_KEY_OLLAMA_CLOUD].orEmpty()
            "google" -> snapshot[KEY_API_KEY_GOOGLE].orEmpty()
            else -> legacyApiKey
        }

        val openAiCompatibleBaseUrl = activeProfile?.baseUrl
            ?: (snapshot[KEY_OPENAI_COMPATIBLE_BASE_URL] ?: "https://api.openai.com/v1")
        val ollamaBaseUrl = snapshot[KEY_OLLAMA_BASE_URL] ?: "http://127.0.0.1:11434"

        val legacySelectedModel = canonicalizeModelIdForProvider(
            provider,
            legacySelectedModelRaw,
        )

        val globalPrimary = legacySelectedModel
            .takeIf {
                it.isNotBlank() &&
                    globalPrimaryProvider == provider &&
                    selectedModelIds.contains(it) &&
                    isLegacyModelCompatibleWithProvider(provider, it)
            }
            .orEmpty()
        val profilePrimary = if (provider == "openai-compatible") {
            activeProfile?.primaryModel
                .orEmpty()
                .trim()
                .removePrefix("openai-compatible/")
                .ifBlank {
                    snapshot[KEY_OPENAI_COMPATIBLE_MODEL_ID]
                        .orEmpty()
                        .trim()
                        .removePrefix("openai-compatible/")
                }
                .takeIf { it.isNotBlank() && selectedModelIds.contains(it) }
                .orEmpty()
        } else {
            ""
        }
        val ollamaPrimary = when (provider) {
            "ollama" -> snapshot[KEY_OLLAMA_MODEL_ID]
                .orEmpty()
                .trim()
                .removePrefix("ollama/")
                .takeIf { it.isNotBlank() && selectedModelIds.contains(it) }
                .orEmpty()
            "ollama-cloud" -> snapshot[KEY_OLLAMA_CLOUD_MODEL_ID]
                .orEmpty()
                .trim()
                .removePrefix("ollama-cloud/")
                .removePrefix("ollama/")
                .takeIf { it.isNotBlank() && selectedModelIds.contains(it) }
                .orEmpty()
            else -> ""
        }
        val effectivePrimary = globalPrimary.ifBlank { profilePrimary.ifBlank { ollamaPrimary } }

        val legacyEntry = legacySelectedModel
            .takeIf { it.isNotBlank() }
            ?.let { modelId ->
                SelectedModelConfigEntry(
                    id = modelId,
                    supportsReasoning = snapshot[KEY_SELECTED_MODEL_REASONING] ?: false,
                    supportsImages = snapshot[KEY_SELECTED_MODEL_IMAGES] ?: false,
                    contextLength = snapshot[KEY_SELECTED_MODEL_CONTEXT]?.toIntOrNull()?.coerceAtLeast(1) ?: 200000,
                    maxOutputTokens = snapshot[KEY_SELECTED_MODEL_MAX_OUTPUT]?.toIntOrNull()?.coerceAtLeast(1) ?: 4096,
                )
            }

        val providerMetadataById = canonicalizeMetadataByIdForProvider(
            provider,
            metadataByProvider[provider].orEmpty(),
        )
        val selectedEntries = selectedModelIds.map { modelId ->
            providerMetadataById[modelId]
                ?: legacyEntry?.takeIf { it.id == modelId }
                ?: resolveDefaultModelMetadata(provider, modelId)
        }

        val selectedModelForRuntime = when {
            effectivePrimary.isNotBlank() -> effectivePrimary
            selectedModelIds.isNotEmpty() -> selectedModelIds.first()
            else -> ""
        }
        val selectedModelEntryForRuntime = selectedEntries.firstOrNull { it.id == selectedModelForRuntime }

        val memorySearchProvider = snapshot[KEY_MEMORY_SEARCH_PROVIDER].orEmpty().trim().lowercase().let { raw ->
            when (raw) {
                "", "auto", "openai", "gemini", "voyage", "mistral", "local" ->
                    if (raw.isBlank()) "auto" else raw
                else -> "auto"
            }
        }

        return GatewayLaunchConfigSnapshot(
            apiProvider = provider,
            apiKey = apiKey,
            selectedModel = selectedModelForRuntime,
            selectedModelEntries = selectedEntries,
            primaryModelId = effectivePrimary,
            openAiCompatibleBaseUrl = openAiCompatibleBaseUrl,
            ollamaBaseUrl = ollamaBaseUrl,
            modelReasoning = selectedModelEntryForRuntime?.supportsReasoning
                ?: (snapshot[KEY_SELECTED_MODEL_REASONING] ?: false),
            modelImages = selectedModelEntryForRuntime?.supportsImages
                ?: (snapshot[KEY_SELECTED_MODEL_IMAGES] ?: false),
            modelContext = selectedModelEntryForRuntime?.contextLength
                ?: (snapshot[KEY_SELECTED_MODEL_CONTEXT]?.toIntOrNull() ?: 200000),
            modelMaxOutput = selectedModelEntryForRuntime?.maxOutputTokens
                ?: (snapshot[KEY_SELECTED_MODEL_MAX_OUTPUT]?.toIntOrNull() ?: 4096),
            channelConfig = ChannelConfig(
                whatsappEnabled = true,
                telegramEnabled = snapshot[KEY_TELEGRAM_ENABLED] ?: false,
                telegramBotToken = snapshot[KEY_TELEGRAM_BOT_TOKEN] ?: "",
                discordEnabled = snapshot[KEY_DISCORD_ENABLED] ?: false,
                discordBotToken = snapshot[KEY_DISCORD_BOT_TOKEN] ?: "",
                discordGuildAllowlist = snapshot[KEY_DISCORD_GUILD_ALLOWLIST] ?: "",
                discordRequireMention = snapshot[KEY_DISCORD_REQUIRE_MENTION] ?: true,
            ),
            braveSearchApiKey = snapshot[KEY_BRAVE_SEARCH_API_KEY] ?: "",
            hasExplicitMemorySearchPrefs =
                snapshot.contains(KEY_MEMORY_SEARCH_ENABLED) ||
                    snapshot.contains(KEY_MEMORY_SEARCH_PROVIDER) ||
                    snapshot.contains(KEY_MEMORY_SEARCH_API_KEY),
            memorySearchEnabled = snapshot[KEY_MEMORY_SEARCH_ENABLED] ?: true,
            memorySearchProvider = memorySearchProvider,
            memorySearchApiKey = snapshot[KEY_MEMORY_SEARCH_API_KEY] ?: "",
        )
    }

    suspend fun getEffectivePrimary(provider: String): String? {
        val normalizedProvider = normalizeProvider(provider)
        val snapshot = context.dataStore.data.first()
        val selectedByProvider = decodeStringListMap(snapshot[KEY_SELECTED_MODELS_BY_PROVIDER_JSON])
        val selectedIds = selectedByProvider[normalizedProvider]
            .orEmpty()
            .map { canonicalizeModelIdForProvider(normalizedProvider, it) }
            .filter { it.isNotBlank() }
            .distinct()
        if (selectedIds.isEmpty()) {
            if (normalizedProvider == "openai-compatible") {
                val profiles = decodeOpenAiCompatibleProfiles(snapshot[KEY_OPENAI_COMPATIBLE_PROFILES_JSON])
                val activeProfileId = snapshot[KEY_ACTIVE_OPENAI_COMPATIBLE_PROFILE_ID].orEmpty().trim()
                val activeProfile = profiles.firstOrNull { it.id == activeProfileId } ?: profiles.firstOrNull()
                val profileSelectedIds = activeProfile?.selectedModels
                    .orEmpty()
                    .map { it.trim().removePrefix("openai-compatible/") }
                    .filter { it.isNotBlank() }
                    .distinct()
                val profilePrimary = activeProfile?.primaryModel
                    .orEmpty()
                    .trim()
                    .removePrefix("openai-compatible/")
                if (profilePrimary.isNotBlank() && profileSelectedIds.contains(profilePrimary)) {
                    return profilePrimary
                }
                if (profileSelectedIds.isNotEmpty()) {
                    return profileSelectedIds.first()
                }
            }
            val globalPrimaryProvider = resolveSelectedModelProvider(
                snapshot = snapshot,
                selectedByProviderInput = selectedByProvider,
            )
            return if (
                normalizeProvider(snapshot[KEY_API_PROVIDER] ?: "openrouter") == normalizedProvider &&
                globalPrimaryProvider == normalizedProvider
            ) {
                snapshot[KEY_SELECTED_MODEL]
                    ?.let { canonicalizeModelIdForProvider(normalizedProvider, it) }
                    ?.takeIf { it.isNotBlank() }
            } else {
                null
            }
        }
        val globalPrimaryProvider = resolveSelectedModelProvider(
            snapshot = snapshot,
            selectedByProviderInput = selectedByProvider,
        )
        val requestedPrimary = if (
            globalPrimaryProvider == normalizedProvider &&
            normalizeProvider(snapshot[KEY_SELECTED_MODEL_PROVIDER].orEmpty()) == normalizedProvider
        ) {
            snapshot[KEY_SELECTED_MODEL]
                ?.let { canonicalizeModelIdForProvider(normalizedProvider, it) }
                .orEmpty()
        } else if (normalizedProvider == "openai-compatible") {
            val profiles = decodeOpenAiCompatibleProfiles(snapshot[KEY_OPENAI_COMPATIBLE_PROFILES_JSON])
            val activeProfileId = snapshot[KEY_ACTIVE_OPENAI_COMPATIBLE_PROFILE_ID].orEmpty().trim()
            val activeProfile = profiles.firstOrNull { it.id == activeProfileId } ?: profiles.firstOrNull()
            activeProfile?.primaryModel
                .orEmpty()
                .trim()
                .removePrefix("openai-compatible/")
                .ifBlank {
                    snapshot[KEY_OPENAI_COMPATIBLE_MODEL_ID]
                        .orEmpty()
                        .trim()
                        .removePrefix("openai-compatible/")
                }
        } else {
            ""
        }
        return when {
            requestedPrimary.isNotBlank() && selectedIds.contains(requestedPrimary) -> requestedPrimary
            else -> selectedIds.firstOrNull()
        }
    }

    suspend fun getSelectedModelIdsForProvider(provider: String): List<String> {
        val normalizedProvider = normalizeProvider(provider)
        if (normalizedProvider.isBlank()) return emptyList()

        val snapshot = context.dataStore.data.first()
        val selectedByProvider = decodeStringListMap(snapshot[KEY_SELECTED_MODELS_BY_PROVIDER_JSON])
        val profiles = decodeOpenAiCompatibleProfiles(snapshot[KEY_OPENAI_COMPATIBLE_PROFILES_JSON])
        val activeProfileId = snapshot[KEY_ACTIVE_OPENAI_COMPATIBLE_PROFILE_ID].orEmpty().trim()
        val activeProfile = profiles.firstOrNull { it.id == activeProfileId } ?: profiles.firstOrNull()

        return resolveStoredSelectedIdsForProvider(
            snapshot = snapshot,
            provider = normalizedProvider,
            selectedByProviderInput = selectedByProvider,
            activeProfileInput = activeProfile,
            includeLegacyGlobalFallback = true,
        )
    }

    suspend fun upsertOpenAiCompatibleProfile(profile: OpenAiCompatibleProfile, activate: Boolean = false) {
        val normalizedId = profile.id.trim()
        if (normalizedId.isBlank()) return
        context.dataStore.edit { prefs ->
            val currentProfiles = decodeOpenAiCompatibleProfiles(prefs[KEY_OPENAI_COMPATIBLE_PROFILES_JSON]).toMutableList()
            val normalizedSelectedModels = profile.selectedModels
                .map { it.trim().removePrefix("openai-compatible/") }
                .filter { it.isNotBlank() }
                .distinct()
            val normalizedPrimaryModel = profile.primaryModel
                ?.trim()
                ?.removePrefix("openai-compatible/")
                ?.ifBlank { null }
            val normalizedProfile = profile.copy(
                id = normalizedId,
                name = profile.name.trim().ifBlank { normalizedId },
                baseUrl = profile.baseUrl.trim().ifBlank { "https://api.openai.com/v1" },
                selectedModels = normalizedSelectedModels,
                primaryModel = normalizedPrimaryModel,
            )
            val existingIndex = currentProfiles.indexOfFirst { it.id == normalizedId }
            if (existingIndex >= 0) {
                currentProfiles[existingIndex] = normalizedProfile
            } else {
                currentProfiles += normalizedProfile
            }
            prefs[KEY_OPENAI_COMPATIBLE_PROFILES_JSON] = encodeOpenAiCompatibleProfiles(currentProfiles)
            if (activate || prefs[KEY_ACTIVE_OPENAI_COMPATIBLE_PROFILE_ID].isNullOrBlank()) {
                prefs[KEY_ACTIVE_OPENAI_COMPATIBLE_PROFILE_ID] = normalizedId
            }
        }
    }

    suspend fun removeOpenAiCompatibleProfile(profileId: String) {
        val normalizedId = profileId.trim()
        if (normalizedId.isBlank()) return
        context.dataStore.edit { prefs ->
            val updatedProfiles = decodeOpenAiCompatibleProfiles(prefs[KEY_OPENAI_COMPATIBLE_PROFILES_JSON])
                .filterNot { it.id == normalizedId }
            if (updatedProfiles.isEmpty()) {
                prefs.remove(KEY_OPENAI_COMPATIBLE_PROFILES_JSON)
                prefs.remove(KEY_ACTIVE_OPENAI_COMPATIBLE_PROFILE_ID)
            } else {
                prefs[KEY_OPENAI_COMPATIBLE_PROFILES_JSON] = encodeOpenAiCompatibleProfiles(updatedProfiles)
                if (prefs[KEY_ACTIVE_OPENAI_COMPATIBLE_PROFILE_ID] == normalizedId) {
                    prefs[KEY_ACTIVE_OPENAI_COMPATIBLE_PROFILE_ID] = updatedProfiles.first().id
                }
            }
        }
    }

    suspend fun setActiveOpenAiCompatibleProfile(profileId: String) {
        val normalizedId = profileId.trim()
        if (normalizedId.isBlank()) return
        context.dataStore.edit { prefs ->
            val profiles = decodeOpenAiCompatibleProfiles(prefs[KEY_OPENAI_COMPATIBLE_PROFILES_JSON])
            val targetProfile = profiles.firstOrNull { it.id == normalizedId } ?: return@edit

            prefs[KEY_ACTIVE_OPENAI_COMPATIBLE_PROFILE_ID] = normalizedId

            val normalizedSelectedIds = targetProfile.selectedModels
                .map { it.trim().removePrefix("openai-compatible/") }
                .filter { it.isNotBlank() }
                .distinct()
                .toMutableList()

            val normalizedPrimaryFromProfile = targetProfile.primaryModel
                .orEmpty()
                .trim()
                .removePrefix("openai-compatible/")

            if (normalizedPrimaryFromProfile.isNotBlank() && !normalizedSelectedIds.contains(normalizedPrimaryFromProfile)) {
                normalizedSelectedIds.add(0, normalizedPrimaryFromProfile)
            }

            val effectivePrimary = when {
                normalizedPrimaryFromProfile.isNotBlank() && normalizedSelectedIds.contains(normalizedPrimaryFromProfile) ->
                    normalizedPrimaryFromProfile

                else -> ""
            }

            val selectedByProvider = decodeStringListMap(prefs[KEY_SELECTED_MODELS_BY_PROVIDER_JSON]).toMutableMap()
            if (normalizedSelectedIds.isEmpty()) {
                selectedByProvider.remove("openai-compatible")
            } else {
                selectedByProvider["openai-compatible"] = normalizedSelectedIds
            }
            if (selectedByProvider.isEmpty()) {
                prefs.remove(KEY_SELECTED_MODELS_BY_PROVIDER_JSON)
            } else {
                prefs[KEY_SELECTED_MODELS_BY_PROVIDER_JSON] = encodeStringListMap(selectedByProvider)
            }
            prefs.remove(KEY_PRIMARY_MODEL_BY_PROVIDER_JSON)

            val metadataByProvider =
                decodeModelMetadataByProvider(prefs[KEY_MODEL_METADATA_BY_PROVIDER_JSON]).toMutableMap()
            val providerMetadata = canonicalizeMetadataByIdForProvider(
                "openai-compatible",
                metadataByProvider["openai-compatible"].orEmpty(),
            ).toMutableMap()
            normalizedSelectedIds.forEach { modelId ->
                if (!providerMetadata.containsKey(modelId)) {
                    providerMetadata[modelId] = defaultModelMetadata(modelId)
                }
            }
            providerMetadata.keys
                .filterNot { normalizedSelectedIds.contains(it) }
                .forEach(providerMetadata::remove)
            if (providerMetadata.isEmpty()) {
                metadataByProvider.remove("openai-compatible")
            } else {
                metadataByProvider["openai-compatible"] = providerMetadata
            }
            if (metadataByProvider.isEmpty()) {
                prefs.remove(KEY_MODEL_METADATA_BY_PROVIDER_JSON)
            } else {
                prefs[KEY_MODEL_METADATA_BY_PROVIDER_JSON] = encodeModelMetadataByProvider(metadataByProvider)
            }

            if (effectivePrimary.isNotBlank()) {
                prefs[KEY_OPENAI_COMPATIBLE_MODEL_ID] = effectivePrimary
            } else {
                prefs.remove(KEY_OPENAI_COMPATIBLE_MODEL_ID)
            }

            val currentProvider = normalizeProvider(prefs[KEY_API_PROVIDER] ?: "openrouter")
            if (currentProvider == "openai-compatible") {
                if (effectivePrimary.isNotBlank()) {
                    prefs[KEY_SELECTED_MODEL] = effectivePrimary
                    prefs[KEY_SELECTED_MODEL_PROVIDER] = "openai-compatible"
                    val primaryMetadata = providerMetadata[effectivePrimary] ?: defaultModelMetadata(effectivePrimary)
                    prefs[KEY_SELECTED_MODEL_REASONING] = primaryMetadata.supportsReasoning
                    prefs[KEY_SELECTED_MODEL_IMAGES] = primaryMetadata.supportsImages
                    prefs[KEY_SELECTED_MODEL_CONTEXT] = primaryMetadata.contextLength.toString()
                    prefs[KEY_SELECTED_MODEL_MAX_OUTPUT] = primaryMetadata.maxOutputTokens.toString()
                } else {
                    prefs[KEY_SELECTED_MODEL] = ""
                    prefs.remove(KEY_SELECTED_MODEL_PROVIDER)
                    prefs.remove(KEY_SELECTED_MODEL_REASONING)
                    prefs.remove(KEY_SELECTED_MODEL_IMAGES)
                    prefs.remove(KEY_SELECTED_MODEL_CONTEXT)
                    prefs.remove(KEY_SELECTED_MODEL_MAX_OUTPUT)
                }
            }
        }
    }

    // ── Brave Search ──

    val braveSearchApiKey: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_BRAVE_SEARCH_API_KEY] ?: ""
    }

    suspend fun setBraveSearchApiKey(key: String) {
        context.dataStore.edit { it[KEY_BRAVE_SEARCH_API_KEY] = key }
    }

    // ── Memory Search ──

    val memorySearchEnabled: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_MEMORY_SEARCH_ENABLED] ?: true
    }

    val hasExplicitMemorySearchPrefs: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs.contains(KEY_MEMORY_SEARCH_ENABLED) ||
            prefs.contains(KEY_MEMORY_SEARCH_PROVIDER) ||
            prefs.contains(KEY_MEMORY_SEARCH_API_KEY)
    }

    val memorySearchProvider: Flow<String> = context.dataStore.data.map { prefs ->
        val raw = prefs[KEY_MEMORY_SEARCH_PROVIDER].orEmpty().trim().lowercase()
        when (raw) {
            "", "auto", "openai", "gemini", "voyage", "mistral", "local" -> if (raw.isBlank()) "auto" else raw
            else -> "auto"
        }
    }

    val memorySearchApiKey: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_MEMORY_SEARCH_API_KEY] ?: ""
    }

    suspend fun setMemorySearchEnabled(enabled: Boolean) {
        context.dataStore.edit { it[KEY_MEMORY_SEARCH_ENABLED] = enabled }
    }

    suspend fun setMemorySearchProvider(provider: String) {
        val normalized = provider.trim().lowercase().ifBlank { "auto" }
        context.dataStore.edit { it[KEY_MEMORY_SEARCH_PROVIDER] = normalized }
    }

    suspend fun setMemorySearchApiKey(key: String) {
        context.dataStore.edit { it[KEY_MEMORY_SEARCH_API_KEY] = key }
    }

    // ── Channel settings ──

    // WhatsApp는 항상 활성화 상태로 고정한다.
    val whatsappEnabled: Flow<Boolean> = context.dataStore.data.map { true }

    val telegramEnabled: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_TELEGRAM_ENABLED] ?: false
    }

    val telegramBotToken: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_TELEGRAM_BOT_TOKEN] ?: ""
    }

    val discordEnabled: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_DISCORD_ENABLED] ?: false
    }

    val discordBotToken: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_DISCORD_BOT_TOKEN] ?: ""
    }

    val discordGuildAllowlist: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_DISCORD_GUILD_ALLOWLIST] ?: ""
    }

    val discordRequireMention: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_DISCORD_REQUIRE_MENTION] ?: true
    }

    val channelConfig: Flow<ChannelConfig> = combine(
        whatsappEnabled,
        telegramEnabled,
        telegramBotToken,
        discordEnabled,
        discordBotToken,
    ) { wa, tgEnabled, tgToken, dcEnabled, dcToken ->
        ChannelConfig(
            whatsappEnabled = wa,
            telegramEnabled = tgEnabled,
            telegramBotToken = tgToken,
            discordEnabled = dcEnabled,
            discordBotToken = dcToken,
        )
    }
        .combine(discordGuildAllowlist) { config, dcGuildAllowlist ->
            config.copy(discordGuildAllowlist = dcGuildAllowlist)
        }
        .combine(discordRequireMention) { config, dcRequireMention ->
            config.copy(discordRequireMention = dcRequireMention)
        }

    suspend fun setWhatsappEnabled(enabled: Boolean) {
        // no-op: WhatsApp는 always-on 정책
    }

    suspend fun setTelegramEnabled(enabled: Boolean) {
        context.dataStore.edit { it[KEY_TELEGRAM_ENABLED] = enabled }
    }

    suspend fun setTelegramBotToken(token: String) {
        context.dataStore.edit { it[KEY_TELEGRAM_BOT_TOKEN] = token }
    }

    suspend fun setDiscordEnabled(enabled: Boolean) {
        context.dataStore.edit { it[KEY_DISCORD_ENABLED] = enabled }
    }

    suspend fun setDiscordBotToken(token: String) {
        context.dataStore.edit { it[KEY_DISCORD_BOT_TOKEN] = token }
    }

    suspend fun setDiscordGuildAllowlist(raw: String) {
        context.dataStore.edit { it[KEY_DISCORD_GUILD_ALLOWLIST] = raw }
    }

    suspend fun setDiscordRequireMention(enabled: Boolean) {
        context.dataStore.edit { it[KEY_DISCORD_REQUIRE_MENTION] = enabled }
    }

    // ── Gateway running state (앱 업데이트 후 자동 재시작용) ──

    val gatewayWasRunning: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_GATEWAY_WAS_RUNNING] ?: false
    }

    suspend fun setGatewayWasRunning(running: Boolean) {
        context.dataStore.edit { it[KEY_GATEWAY_WAS_RUNNING] = running }
    }

    suspend fun getGatewaySurvivorMetadata(
        nowEpochMs: Long = System.currentTimeMillis(),
    ): GatewaySurvivorMetadata? {
        val snapshot = context.dataStore.data.first()
        val rawEndpoint = snapshot[KEY_GATEWAY_SURVIVOR_WS_ENDPOINT]?.trim().orEmpty()
        val endpoint = rawEndpoint.lowercase()
        if (endpoint != "127.0.0.1:18789" && endpoint != "localhost:18789") {
            return null
        }
        val pid = snapshot[KEY_GATEWAY_SURVIVOR_PID]
            ?.takeIf { it > 0 }
            ?: return null
        val launchedAtEpochMs = snapshot[KEY_GATEWAY_SURVIVOR_LAUNCHED_AT]
            ?.takeIf { it in 0..nowEpochMs }
            ?: return null
        val updatedAtEpochMs = snapshot[KEY_GATEWAY_SURVIVOR_UPDATED_AT]
            ?.takeIf { it in 0..nowEpochMs }
            ?: return null
        val startupAttemptActive = snapshot[KEY_GATEWAY_SURVIVOR_STARTUP_ATTEMPT_ACTIVE] ?: false
        return GatewaySurvivorMetadata(
            pid = pid,
            launchedAtEpochMs = launchedAtEpochMs,
            wsEndpoint = endpoint,
            startupAttemptActive = startupAttemptActive,
            updatedAtEpochMs = updatedAtEpochMs,
        )
    }

    suspend fun setGatewaySurvivorMetadata(metadata: GatewaySurvivorMetadata) {
        val normalizedEndpoint = metadata.wsEndpoint.trim().lowercase()
        if (normalizedEndpoint != "127.0.0.1:18789" && normalizedEndpoint != "localhost:18789") {
            clearGatewaySurvivorMetadata()
            return
        }
        context.dataStore.edit { prefs ->
            if (metadata.pid > 0) {
                prefs[KEY_GATEWAY_SURVIVOR_PID] = metadata.pid
            } else {
                prefs.remove(KEY_GATEWAY_SURVIVOR_PID)
            }
            if (metadata.launchedAtEpochMs >= 0L) {
                prefs[KEY_GATEWAY_SURVIVOR_LAUNCHED_AT] = metadata.launchedAtEpochMs
            } else {
                prefs.remove(KEY_GATEWAY_SURVIVOR_LAUNCHED_AT)
            }
            prefs[KEY_GATEWAY_SURVIVOR_WS_ENDPOINT] = normalizedEndpoint
            prefs[KEY_GATEWAY_SURVIVOR_STARTUP_ATTEMPT_ACTIVE] = metadata.startupAttemptActive
            if (metadata.updatedAtEpochMs >= 0L) {
                prefs[KEY_GATEWAY_SURVIVOR_UPDATED_AT] = metadata.updatedAtEpochMs
            } else {
                prefs.remove(KEY_GATEWAY_SURVIVOR_UPDATED_AT)
            }
        }
    }

    suspend fun clearGatewaySurvivorMetadata() {
        context.dataStore.edit { prefs ->
            prefs.remove(KEY_GATEWAY_SURVIVOR_PID)
            prefs.remove(KEY_GATEWAY_SURVIVOR_LAUNCHED_AT)
            prefs.remove(KEY_GATEWAY_SURVIVOR_WS_ENDPOINT)
            prefs.remove(KEY_GATEWAY_SURVIVOR_STARTUP_ATTEMPT_ACTIVE)
            prefs.remove(KEY_GATEWAY_SURVIVOR_UPDATED_AT)
        }
    }

    suspend fun incrementInAppReviewGatewayHealthyRunCount() {
        context.dataStore.edit { prefs ->
            val current = (prefs[KEY_IN_APP_REVIEW_GATEWAY_HEALTHY_RUN_COUNT] ?: 0).coerceAtLeast(0)
            prefs[KEY_IN_APP_REVIEW_GATEWAY_HEALTHY_RUN_COUNT] = (current + 1).coerceAtMost(1_000)
        }
    }

    suspend fun resetInAppReviewGatewayHealthyRunCount() {
        context.dataStore.edit { prefs ->
            prefs[KEY_IN_APP_REVIEW_GATEWAY_HEALTHY_RUN_COUNT] = 0
        }
    }

    suspend fun getInAppReviewEligibility(
        currentVersion: Int,
        nowEpochMs: Long = System.currentTimeMillis(),
    ): InAppReviewEligibility {
        val snapshot = context.dataStore.data.first()
        val gatewayHealthyRunCount = (snapshot[KEY_IN_APP_REVIEW_GATEWAY_HEALTHY_RUN_COUNT] ?: 0).coerceAtLeast(0)
        val lastRequestAtEpochMs = snapshot[KEY_IN_APP_REVIEW_LAST_REQUEST_AT]
            ?.takeIf { it in 0..nowEpochMs }
        val lastRequestVersion = snapshot[KEY_IN_APP_REVIEW_LAST_REQUEST_VERSION]
        return InAppReviewEligibility(
            eligible = isInAppReviewEligibleByPolicy(
                gatewayHealthyRunCount = gatewayHealthyRunCount,
                lastRequestAtEpochMs = lastRequestAtEpochMs,
                lastRequestVersion = lastRequestVersion,
                currentVersion = currentVersion,
                nowEpochMs = nowEpochMs,
            ),
            gatewayHealthyRunCount = gatewayHealthyRunCount,
            lastRequestAtEpochMs = lastRequestAtEpochMs,
            lastRequestVersion = lastRequestVersion,
        )
    }

    suspend fun tryBeginInAppReviewRequest(
        currentVersion: Int,
        nowEpochMs: Long = System.currentTimeMillis(),
    ): Boolean {
        var begun = false
        context.dataStore.edit { prefs ->
            var clearedStalePendingForCurrentVersion = false
            val pendingVersion = prefs[KEY_IN_APP_REVIEW_PENDING_REQUEST_VERSION]
            val pendingAt = prefs[KEY_IN_APP_REVIEW_PENDING_REQUEST_AT]
            if (pendingVersion != null) {
                val pendingIsFreshForCurrentVersion =
                    pendingVersion == currentVersion &&
                        pendingAt != null &&
                        pendingAt in 0..nowEpochMs &&
                        nowEpochMs - pendingAt < IN_APP_REVIEW_PENDING_TIMEOUT_MS
                if (pendingIsFreshForCurrentVersion) {
                    begun = false
                    return@edit
                }
                // stale pending(크래시/강제종료 잔여) 또는 다른 버전 pending은 정리하고 진행한다.
                if (pendingVersion == currentVersion) {
                    clearedStalePendingForCurrentVersion = true
                }
                prefs.remove(KEY_IN_APP_REVIEW_PENDING_REQUEST_VERSION)
                prefs.remove(KEY_IN_APP_REVIEW_PENDING_REQUEST_AT)
            }

            val lastAttemptAt = prefs[KEY_IN_APP_REVIEW_LAST_ATTEMPT_AT]
                ?.takeIf { it in 0..nowEpochMs }
            if (
                !clearedStalePendingForCurrentVersion &&
                lastAttemptAt != null &&
                nowEpochMs - lastAttemptAt < IN_APP_REVIEW_ATTEMPT_COOLDOWN_MS
            ) {
                begun = false
                return@edit
            }

            val gatewayHealthyRunCount = (prefs[KEY_IN_APP_REVIEW_GATEWAY_HEALTHY_RUN_COUNT] ?: 0).coerceAtLeast(0)
            val lastRequestAtEpochMs = prefs[KEY_IN_APP_REVIEW_LAST_REQUEST_AT]
                ?.takeIf { it in 0..nowEpochMs }
            val lastRequestVersion = prefs[KEY_IN_APP_REVIEW_LAST_REQUEST_VERSION]
            val eligible = isInAppReviewEligibleByPolicy(
                gatewayHealthyRunCount = gatewayHealthyRunCount,
                lastRequestAtEpochMs = lastRequestAtEpochMs,
                lastRequestVersion = lastRequestVersion,
                currentVersion = currentVersion,
                nowEpochMs = nowEpochMs,
            )
            if (!eligible) {
                begun = false
                return@edit
            }
            prefs[KEY_IN_APP_REVIEW_PENDING_REQUEST_VERSION] = currentVersion
            prefs[KEY_IN_APP_REVIEW_PENDING_REQUEST_AT] = nowEpochMs
            prefs[KEY_IN_APP_REVIEW_LAST_ATTEMPT_AT] = nowEpochMs
            begun = true
        }
        return begun
    }

    suspend fun finalizeInAppReviewRequest(
        currentVersion: Int,
        requestedAtEpochMs: Long = System.currentTimeMillis(),
    ): Boolean {
        var finalized = false
        context.dataStore.edit { prefs ->
            val pendingVersion = prefs[KEY_IN_APP_REVIEW_PENDING_REQUEST_VERSION]
            if (pendingVersion != currentVersion) {
                finalized = false
                return@edit
            }
            prefs.remove(KEY_IN_APP_REVIEW_PENDING_REQUEST_VERSION)
            prefs.remove(KEY_IN_APP_REVIEW_PENDING_REQUEST_AT)
            prefs[KEY_IN_APP_REVIEW_LAST_REQUEST_AT] = requestedAtEpochMs
            prefs[KEY_IN_APP_REVIEW_LAST_REQUEST_VERSION] = currentVersion
            prefs[KEY_IN_APP_REVIEW_GATEWAY_HEALTHY_RUN_COUNT] = 0
            finalized = true
        }
        return finalized
    }

    suspend fun cancelInAppReviewRequest(currentVersion: Int) {
        context.dataStore.edit { prefs ->
            val pendingVersion = prefs[KEY_IN_APP_REVIEW_PENDING_REQUEST_VERSION]
            if (pendingVersion == currentVersion) {
                prefs.remove(KEY_IN_APP_REVIEW_PENDING_REQUEST_VERSION)
                prefs.remove(KEY_IN_APP_REVIEW_PENDING_REQUEST_AT)
            }
        }
    }

    suspend fun getOpenClawUpdatePromptSuppressedBundledVersion(): String? {
        return context.dataStore.data.first()[KEY_OPENCLAW_UPDATE_PROMPT_SUPPRESSED_BUNDLED_VERSION]
            ?.trim()
            ?.ifBlank { null }
    }

    suspend fun setOpenClawUpdatePromptSuppressedBundledVersion(version: String?) {
        context.dataStore.edit { prefs ->
            val normalized = version?.trim().orEmpty()
            if (normalized.isBlank()) {
                prefs.remove(KEY_OPENCLAW_UPDATE_PROMPT_SUPPRESSED_BUNDLED_VERSION)
            } else {
                prefs[KEY_OPENCLAW_UPDATE_PROMPT_SUPPRESSED_BUNDLED_VERSION] = normalized
            }
        }
    }

    val logSectionUnlocked: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_LOG_SECTION_UNLOCKED] ?: false
    }

    suspend fun setLogSectionUnlocked(unlocked: Boolean) {
        context.dataStore.edit { it[KEY_LOG_SECTION_UNLOCKED] = unlocked }
    }

    suspend fun getBundleUpdateFailure(currentVersion: Int): BundleUpdateFailureRecord {
        val snapshot = context.dataStore.data.first()
        val failCountByVersion = decodeFailCountByVersion(snapshot[KEY_BUNDLE_UPDATE_FAIL_COUNT_BY_VERSION])
        val lastFailVersion = snapshot[KEY_BUNDLE_UPDATE_LAST_FAIL_VERSION]
        val manualRetryVersion = snapshot[KEY_BUNDLE_UPDATE_MANUAL_RETRY_VERSION]
        return BundleUpdateFailureRecord(
            failCountForCurrentVersion = failCountByVersion[currentVersion] ?: 0,
            lastFailAtEpochMs = snapshot[KEY_BUNDLE_UPDATE_LAST_FAIL_AT],
            lastFailElapsedMs = snapshot[KEY_BUNDLE_UPDATE_LAST_FAIL_ELAPSED],
            lastFailVersion = lastFailVersion,
            lastError = snapshot[KEY_BUNDLE_UPDATE_LAST_ERROR],
            lastFailureType = snapshot[KEY_BUNDLE_UPDATE_LAST_FAILURE_TYPE],
            manualRetryUsed = (snapshot[KEY_BUNDLE_UPDATE_MANUAL_RETRY_USED] ?: false) &&
                manualRetryVersion == currentVersion &&
                lastFailVersion == currentVersion,
        )
    }

    suspend fun recordBundleUpdateFailure(
        currentVersion: Int,
        failureType: String,
        errorMessage: String,
        nowEpochMs: Long,
        nowElapsedMs: Long,
    ) {
        context.dataStore.edit { prefs ->
            val counts = decodeFailCountByVersion(prefs[KEY_BUNDLE_UPDATE_FAIL_COUNT_BY_VERSION]).toMutableMap()
            val nextCount = (counts[currentVersion] ?: 0) + 1
            val previousFailVersion = prefs[KEY_BUNDLE_UPDATE_LAST_FAIL_VERSION]
            counts[currentVersion] = nextCount
            prefs[KEY_BUNDLE_UPDATE_FAIL_COUNT_BY_VERSION] = encodeFailCountByVersion(counts)
            prefs[KEY_BUNDLE_UPDATE_LAST_FAIL_AT] = nowEpochMs
            prefs[KEY_BUNDLE_UPDATE_LAST_FAIL_ELAPSED] = nowElapsedMs
            prefs[KEY_BUNDLE_UPDATE_LAST_FAIL_VERSION] = currentVersion
            prefs[KEY_BUNDLE_UPDATE_LAST_ERROR] = errorMessage
            prefs[KEY_BUNDLE_UPDATE_LAST_FAILURE_TYPE] = failureType
            if (previousFailVersion != currentVersion) {
                prefs[KEY_BUNDLE_UPDATE_MANUAL_RETRY_USED] = false
                prefs.remove(KEY_BUNDLE_UPDATE_MANUAL_RETRY_VERSION)
            }
        }
    }

    suspend fun setBundleUpdateManualRetryUsed(currentVersion: Int, used: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[KEY_BUNDLE_UPDATE_MANUAL_RETRY_USED] = used
            if (used) {
                prefs[KEY_BUNDLE_UPDATE_MANUAL_RETRY_VERSION] = currentVersion
            } else {
                prefs.remove(KEY_BUNDLE_UPDATE_MANUAL_RETRY_VERSION)
            }
        }
    }

    suspend fun clearBundleUpdateFailure(currentVersion: Int) {
        context.dataStore.edit { prefs ->
            val counts = decodeFailCountByVersion(prefs[KEY_BUNDLE_UPDATE_FAIL_COUNT_BY_VERSION]).toMutableMap()
            counts.remove(currentVersion)
            prefs[KEY_BUNDLE_UPDATE_FAIL_COUNT_BY_VERSION] = encodeFailCountByVersion(counts)
            prefs.remove(KEY_BUNDLE_UPDATE_LAST_FAIL_AT)
            prefs.remove(KEY_BUNDLE_UPDATE_LAST_FAIL_ELAPSED)
            prefs.remove(KEY_BUNDLE_UPDATE_LAST_FAIL_VERSION)
            prefs.remove(KEY_BUNDLE_UPDATE_LAST_ERROR)
            prefs.remove(KEY_BUNDLE_UPDATE_LAST_FAILURE_TYPE)
            prefs[KEY_BUNDLE_UPDATE_MANUAL_RETRY_USED] = false
            prefs.remove(KEY_BUNDLE_UPDATE_MANUAL_RETRY_VERSION)
        }
    }

    suspend fun getTransferCandidatePreferencesSnapshot(): Map<String, String> {
        val snapshot = context.dataStore.data.first()
        val out = linkedMapOf<String, String>()
        snapshot.asMap().forEach { (key, value) ->
            when (value) {
                is String -> out[key.name] = value
                is Boolean -> out[key.name] = value.toString()
                is Int -> out[key.name] = value.toString()
                is Long -> out[key.name] = value.toString()
                is Float -> out[key.name] = value.toString()
                is Double -> out[key.name] = value.toString()
            }
        }
        return out.toMap()
    }

    suspend fun applyTransferCandidatePreferencesSnapshot(snapshot: Map<String, String>) {
        context.dataStore.edit { prefs ->
            // Clear all transferable keys before applying snapshot.
            // Uses the same TRANSFER_EXPORTABLE_KEYS set to stay in sync with export.
            prefs.asMap().keys
                .filter { it.name in TRANSFER_EXPORTABLE_KEYS }
                .forEach { prefs.remove(it) }

            snapshot.forEach { (key, rawValue) ->
                when (key) {
                    "api_provider" -> prefs[KEY_API_PROVIDER] = rawValue
                    "api_key" -> prefs[KEY_API_KEY] = rawValue
                    "api_key_openrouter" -> prefs[KEY_API_KEY_OPENROUTER] = rawValue
                    "api_key_anthropic" -> prefs[KEY_API_KEY_ANTHROPIC] = rawValue
                    "api_key_openai" -> prefs[KEY_API_KEY_OPENAI] = rawValue
                    "api_key_google" -> prefs[KEY_API_KEY_GOOGLE] = rawValue
                    "api_key_zai" -> prefs[KEY_API_KEY_ZAI] = rawValue
                    "api_key_kimi_coding" -> prefs[KEY_API_KEY_KIMI_CODING] = rawValue
                    "api_key_minimax" -> prefs[KEY_API_KEY_MINIMAX] = rawValue
                    "api_key_openai_compatible" -> prefs[KEY_API_KEY_OPENAI_COMPATIBLE] = rawValue
                    "api_key_ollama" -> prefs[KEY_API_KEY_OLLAMA] = rawValue
                    "api_key_ollama_cloud" -> prefs[KEY_API_KEY_OLLAMA_CLOUD] = rawValue
                    "ollama_cloud_model_id" -> prefs[KEY_OLLAMA_CLOUD_MODEL_ID] = rawValue
                    "openai_compatible_base_url" -> prefs[KEY_OPENAI_COMPATIBLE_BASE_URL] = rawValue
                    "openai_compatible_model_id" -> prefs[KEY_OPENAI_COMPATIBLE_MODEL_ID] = rawValue
                    "ollama_base_url" -> prefs[KEY_OLLAMA_BASE_URL] = rawValue
                    "ollama_model_id" -> prefs[KEY_OLLAMA_MODEL_ID] = rawValue
                    "selected_model" -> prefs[KEY_SELECTED_MODEL] = rawValue
                    "selected_model_provider" -> prefs[KEY_SELECTED_MODEL_PROVIDER] = rawValue
                    "selected_model_reasoning" -> rawValue.toBooleanStrictOrNull()?.let {
                        prefs[KEY_SELECTED_MODEL_REASONING] = it
                    }
                    "selected_model_images" -> rawValue.toBooleanStrictOrNull()?.let {
                        prefs[KEY_SELECTED_MODEL_IMAGES] = it
                    }
                    "selected_model_context" -> if (rawValue.toLongOrNull() != null) {
                        prefs[KEY_SELECTED_MODEL_CONTEXT] = rawValue
                    }
                    "selected_model_max_output" -> if (rawValue.toLongOrNull() != null) {
                        prefs[KEY_SELECTED_MODEL_MAX_OUTPUT] = rawValue
                    }
                    "selected_models_by_provider_json" -> prefs[KEY_SELECTED_MODELS_BY_PROVIDER_JSON] = rawValue
                    "primary_model_by_provider_json" -> prefs[KEY_PRIMARY_MODEL_BY_PROVIDER_JSON] = rawValue
                    "model_metadata_by_provider_json" -> prefs[KEY_MODEL_METADATA_BY_PROVIDER_JSON] = rawValue
                    "openai_compatible_profiles_json" -> prefs[KEY_OPENAI_COMPATIBLE_PROFILES_JSON] = rawValue
                    "active_openai_compatible_profile_id" -> prefs[KEY_ACTIVE_OPENAI_COMPATIBLE_PROFILE_ID] = rawValue
                    "telegram_bot_token" -> prefs[KEY_TELEGRAM_BOT_TOKEN] = rawValue
                    "discord_bot_token" -> prefs[KEY_DISCORD_BOT_TOKEN] = rawValue
                    "discord_guild_allowlist" -> prefs[KEY_DISCORD_GUILD_ALLOWLIST] = rawValue
                    "brave_search_api_key" -> prefs[KEY_BRAVE_SEARCH_API_KEY] = rawValue
                    "memory_search_provider" -> prefs[KEY_MEMORY_SEARCH_PROVIDER] = rawValue
                    "memory_search_api_key" -> prefs[KEY_MEMORY_SEARCH_API_KEY] = rawValue
                    "whatsapp_enabled" -> rawValue.toBooleanStrictOrNull()?.let { prefs[KEY_WHATSAPP_ENABLED] = it }
                    "telegram_enabled" -> rawValue.toBooleanStrictOrNull()?.let { prefs[KEY_TELEGRAM_ENABLED] = it }
                    "discord_enabled" -> rawValue.toBooleanStrictOrNull()?.let { prefs[KEY_DISCORD_ENABLED] = it }
                    "discord_require_mention" -> rawValue.toBooleanStrictOrNull()?.let {
                        prefs[KEY_DISCORD_REQUIRE_MENTION] = it
                    }
                    "memory_search_enabled" -> rawValue.toBooleanStrictOrNull()?.let {
                        prefs[KEY_MEMORY_SEARCH_ENABLED] = it
                    }
                }
            }
        }
    }

    private fun decodeFailCountByVersion(raw: String?): Map<Int, Int> {
        if (raw.isNullOrBlank()) return emptyMap()
        return runCatching {
            val json = org.json.JSONObject(raw)
            buildMap {
                val keys = json.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val version = key.toIntOrNull() ?: continue
                    val count = json.optInt(key, 0)
                    if (count > 0) put(version, count)
                }
            }
        }.getOrDefault(emptyMap())
    }

    private fun encodeFailCountByVersion(map: Map<Int, Int>): String {
        val out = org.json.JSONObject()
        map.forEach { (version, count) ->
            if (count > 0) {
                out.put(version.toString(), count)
            }
        }
        return out.toString()
    }
}

data class SelectedModelConfigEntry(
    val id: String,
    val supportsReasoning: Boolean,
    val supportsImages: Boolean,
    val contextLength: Int,
    val maxOutputTokens: Int,
)

data class OpenAiCompatibleProfile(
    val id: String,
    val name: String,
    val baseUrl: String,
    val apiKey: String,
    val selectedModels: List<String>,
    val primaryModel: String?,
)

data class LaunchApiKeyWarning(
    val shouldWarn: Boolean,
    val provider: String,
    val hasSelectedModels: Boolean = true,
)

data class GlobalDefaultModelOption(
    val provider: String,
    val modelId: String,
)

data class GatewayLaunchConfigSnapshot(
    val apiProvider: String,
    val apiKey: String,
    val selectedModel: String,
    val selectedModelEntries: List<SelectedModelConfigEntry>,
    val primaryModelId: String,
    val openAiCompatibleBaseUrl: String,
    val ollamaBaseUrl: String = "",
    val modelReasoning: Boolean,
    val modelImages: Boolean,
    val modelContext: Int,
    val modelMaxOutput: Int,
    val channelConfig: ChannelConfig,
    val braveSearchApiKey: String,
    val hasExplicitMemorySearchPrefs: Boolean,
    val memorySearchEnabled: Boolean,
    val memorySearchProvider: String,
    val memorySearchApiKey: String,
)

data class BundleUpdateFailureRecord(
    val failCountForCurrentVersion: Int,
    val lastFailAtEpochMs: Long?,
    val lastFailElapsedMs: Long?,
    val lastFailVersion: Int?,
    val lastError: String?,
    val lastFailureType: String?,
    val manualRetryUsed: Boolean,
)

data class InAppReviewEligibility(
    val eligible: Boolean,
    val gatewayHealthyRunCount: Int,
    val lastRequestAtEpochMs: Long?,
    val lastRequestVersion: Int?,
)

data class GatewaySurvivorMetadata(
    val pid: Int,
    val launchedAtEpochMs: Long,
    val wsEndpoint: String,
    val startupAttemptActive: Boolean,
    val updatedAtEpochMs: Long,
)
