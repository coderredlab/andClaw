package com.coderred.andclaw.proroot

import java.io.File
import org.json.JSONObject

object OpenClawCodexModelScope {
    const val OPENAI_PROVIDER = "openai"
    const val LEGACY_PROVIDER = "openai-codex"
    const val LEGACY_CODEX_PROVIDER = "codex"
    const val CODEX_PROVIDER = OPENAI_PROVIDER

    private const val CODEX_APP_SERVER_OPENAI_AUTH_VERSION = "2026.6.1"
    private const val DEFAULT_MODEL = "gpt-5.6-sol"
    private val LEGACY_OPENAI_PROVIDERS = setOf(
        OPENAI_PROVIDER,
        LEGACY_PROVIDER,
        LEGACY_CODEX_PROVIDER,
    )

    fun readInstalledOpenClawVersion(rootfsDir: File?): String? {
        val packageJson = rootfsDir
            ?.resolve("usr/local/lib/node_modules/openclaw/package.json")
            ?: return null
        if (!packageJson.isFile) return null
        return runCatching {
            JSONObject(packageJson.readText()).optString("version").trim().ifBlank { null }
        }.getOrNull()
    }

    fun providerForInstalledVersion(version: String?): String = OPENAI_PROVIDER

    fun usesOpenAiAppServerAuth(version: String?): Boolean {
        return isAtLeast(version, CODEX_APP_SERVER_OPENAI_AUTH_VERSION)
    }

    fun providerForRootfs(rootfsDir: File?): String {
        return providerForInstalledVersion(readInstalledOpenClawVersion(rootfsDir))
    }

    fun defaultBareModelId(version: String?): String = DEFAULT_MODEL

    fun bareModelId(modelId: String): String {
        val trimmed = modelId.trim()
        val slashIndex = trimmed.indexOf('/')
        if (slashIndex <= 0) return trimmed
        val provider = trimmed.substring(0, slashIndex).lowercase()
        return if (provider in LEGACY_OPENAI_PROVIDERS) {
            trimmed.substring(slashIndex + 1).trim()
        } else {
            trimmed
        }
    }

    fun normalizedBareModelId(modelId: String): String {
        return bareModelId(modelId).lowercase()
    }

    fun scopedModelId(
        version: String?,
        modelId: String,
        availableBareModelIds: Set<String> = emptySet(),
    ): String {
        val bareModelId = resolveBareModelId(version, modelId, availableBareModelIds)
        return "$OPENAI_PROVIDER/$bareModelId"
    }

    fun preferredBareModelId(
        availableModels: List<OpenClawModelCatalogReader.ModelEntry>,
    ): String {
        val availableByNormalizedId = availableModels
            .asSequence()
            .filter { it.id.isNotBlank() }
            .associateBy { normalizedBareModelId(it.id) }
        return availableModels
            .firstOrNull { it.isDefault && it.id.isNotBlank() }
            ?.id
            ?.let(::bareModelId)
            ?: availableByNormalizedId["gpt-5.6-sol"]?.id?.let(::bareModelId)
            ?: availableByNormalizedId["gpt-5.5"]?.id?.let(::bareModelId)
            ?: availableModels.firstOrNull { it.id.isNotBlank() }?.id?.let(::bareModelId)
            ?: DEFAULT_MODEL
    }

    fun preferredBareModelId(version: String?, availableBareModelIds: Set<String>): String {
        val availableByNormalizedId = availableBareModelIds
            .mapNotNull { availableModelId ->
                val bareModelId = bareModelId(availableModelId)
                bareModelId.takeIf { it.isNotBlank() }?.let { normalizedBareModelId(it) to it }
            }
            .toMap()
        return availableByNormalizedId["gpt-5.6-sol"]
            ?: availableByNormalizedId["gpt-5.5"]
            ?: availableBareModelIds.firstOrNull { it.isNotBlank() }?.let(::bareModelId)
            ?: DEFAULT_MODEL
    }

    fun resolveBareModelId(
        version: String?,
        modelId: String,
        availableBareModelIds: Set<String>,
    ): String {
        val requestedBareModelId = bareModelId(modelId)
            .takeUnless { it.isBlank() || it.contains("/") }
            ?: return preferredBareModelId(version, availableBareModelIds)

        val availableByNormalizedId = availableBareModelIds
            .mapNotNull { availableModelId ->
                val bareModelId = bareModelId(availableModelId)
                bareModelId.takeIf { it.isNotBlank() }?.let { normalizedBareModelId(it) to it }
            }
            .toMap()
        if (availableByNormalizedId.isEmpty()) return requestedBareModelId
        return availableByNormalizedId[normalizedBareModelId(requestedBareModelId)]
            ?: preferredBareModelId(version, availableBareModelIds)
    }

    fun scopedModelIdForProvider(provider: String, modelId: String): String {
        val normalizedProvider = provider.trim().lowercase()
            .takeUnless { it in LEGACY_OPENAI_PROVIDERS || it.isBlank() }
            ?: OPENAI_PROVIDER
        val bareModelId = bareModelId(modelId)
            .takeUnless { it.isBlank() || it.contains("/") }
            ?: DEFAULT_MODEL
        return "$normalizedProvider/$bareModelId"
    }

    private fun isAtLeast(version: String?, minimum: String): Boolean {
        val currentParts = parseComparableVersion(version) ?: return false
        val minimumParts = parseComparableVersion(minimum) ?: return false
        val maxSize = maxOf(currentParts.size, minimumParts.size)
        for (index in 0 until maxSize) {
            val currentPart = currentParts.getOrElse(index) { 0 }
            val minimumPart = minimumParts.getOrElse(index) { 0 }
            if (currentPart > minimumPart) return true
            if (currentPart < minimumPart) return false
        }
        return true
    }

    private fun parseComparableVersion(version: String?): List<Int>? {
        if (version.isNullOrBlank()) return null
        val parts = version.trim().split(".").map { segment ->
            val digits = segment.takeWhile(Char::isDigit)
            if (digits.isEmpty()) return null
            digits.toIntOrNull() ?: return null
        }
        return parts.takeIf { it.isNotEmpty() }
    }
}
