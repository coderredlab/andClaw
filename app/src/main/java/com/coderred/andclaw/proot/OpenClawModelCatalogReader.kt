package com.coderred.andclaw.proot

import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONArray
import org.json.JSONObject

object OpenClawModelCatalogReader {
    // Test hook: allow tests to override the server loader to avoid network IO
    var testLoaderOverride: ((baseUrl: String) -> List<ModelEntry>)? = null
    private const val OLLAMA_DEFAULT_CONTEXT_WINDOW = 128_000
    private const val OLLAMA_DEFAULT_MAX_TOKENS = 8_192
    const val LEGACY_BUILTIN_MODELS_PATH =
        "usr/local/lib/node_modules/openclaw/node_modules/@mariozechner/pi-ai/dist/models.generated.js"
    const val RUNTIME_MODEL_CATALOG_DIR = "usr/local/lib/node_modules/openclaw/dist"
    private const val RUNTIME_MODEL_CATALOG_ENTRYPOINT = "entry.js"
    private const val RUNTIME_MODEL_CATALOG_FALLBACK_ENTRYPOINT = "index.js"

    private val runtimeModelCatalogRegex = Regex("""^model-catalog(?:-.+)?\.js$""")
    private val runtimeRelativeImportRegex = Regex(
        """(?:import|export)\s+(?:[^"']*?from\s+)?["'](\./[^"']+\.js)["']|import\(\s*["'](\./[^"']+\.js)["']\s*\)""",
    )
    private val stringConstRegex = Regex("""const\s+([A-Z0-9_]+)\s*=\s*"((?:\\.|[^"])*)";""")
    data class ModelEntry(
        val id: String,
        val name: String,
        val provider: String,
        val contextWindow: Int,
        val maxTokens: Int,
        val supportsReasoning: Boolean,
        val supportsImages: Boolean,
    )

    private data class OllamaModelDetails(
        val contextWindow: Int?,
        val supportsTools: Boolean,
        val supportsReasoning: Boolean?,
        val supportsImages: Boolean?,
    )

    fun loadOllamaModelsFromServer(baseUrl: String, apiKey: String = ""): List<ModelEntry> {
        // If a test hook is provided, use it to bypass network calls
        testLoaderOverride?.let { override -> return override(baseUrl) }
        val apiBase = resolveOllamaApiBase(baseUrl)
        val endpoint = "$apiBase/api/tags"
        val url = URL(endpoint)
        val conn = url.openConnection() as HttpURLConnection
        return try {
            conn.requestMethod = "GET"
            conn.connectTimeout = 15_000
            conn.readTimeout = 15_000
            conn.setRequestProperty("Accept", "application/json")
            if (apiKey.isNotBlank()) {
                conn.setRequestProperty("Authorization", "Bearer $apiKey")
            }
            val status = conn.responseCode
            if (status == 401 || status == 403) {
                throw OllamaAuthException("Authentication failed (HTTP $status). Check your API key.")
            }
            if (status !in 200..299) {
                return emptyList()
            }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(body)
            val dataArray: JSONArray = json.optJSONArray("models") ?: JSONArray()

            val results = mutableListOf<ModelEntry>()
            for (i in 0 until dataArray.length()) {
                val item = dataArray.optJSONObject(i) ?: continue
                val id = item.optString("name").trim().ifBlank {
                    item.optString("model", "").trim()
                }
                if (id.isBlank()) continue
                val details = item.optJSONObject("details")
                val detailsFamily = details?.optString("family").orEmpty().lowercase()
                val detailsFamilies = details?.optJSONArray("families")
                val families = buildList {
                    if (detailsFamily.isNotBlank()) add(detailsFamily)
                    if (detailsFamilies != null) {
                        for (idx in 0 until detailsFamilies.length()) {
                            val family = detailsFamilies.optString(idx).trim().lowercase()
                            if (family.isNotBlank()) add(family)
                        }
                    }
                }
                val lowercaseId = id.lowercase()
                val showDetails = queryOllamaModelDetails(apiBase, id, apiKey)
                if (showDetails == null || !showDetails.supportsTools) continue
                val contextWindow = showDetails?.contextWindow ?: OLLAMA_DEFAULT_CONTEXT_WINDOW
                val maxTokens = OLLAMA_DEFAULT_MAX_TOKENS
                val supportsReasoning = showDetails?.supportsReasoning ?: (lowercaseId.contains("reason") || lowercaseId.contains("think") || lowercaseId.contains("r1"))
                val supportsImages = showDetails?.supportsImages ?: (lowercaseId.contains("vision") || lowercaseId.contains("vl") || lowercaseId.contains("image") || families.any {
                    it.contains("vision") || it.contains("vl") || it.contains("image")
                })

                results += ModelEntry(
                    id = id,
                    name = id,
                    provider = "ollama",
                    contextWindow = contextWindow,
                    maxTokens = maxTokens,
                    supportsReasoning = supportsReasoning,
                    supportsImages = supportsImages,
                )
            }
            results
        } catch (e: OllamaAuthException) {
            throw e
        } catch (_: Exception) {
            emptyList()
        } finally {
            conn.disconnect()
        }
    }

    class OllamaAuthException(message: String) : Exception(message)

    private fun resolveOllamaApiBase(configuredBaseUrl: String): String {
        return configuredBaseUrl.trim().removeSuffix("/").removeSuffix("/v1").ifBlank {
            "http://127.0.0.1:11434"
        }
    }

    private fun queryOllamaModelDetails(apiBase: String, modelName: String, apiKey: String = ""): OllamaModelDetails? {
        val endpoint = "$apiBase/api/show"
        val url = URL(endpoint)
        val conn = url.openConnection() as HttpURLConnection
        return try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 3_000
            conn.readTimeout = 3_000
            conn.doOutput = true
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("Content-Type", "application/json")
            if (apiKey.isNotBlank()) {
                conn.setRequestProperty("Authorization", "Bearer $apiKey")
            }
            conn.outputStream.bufferedWriter().use { writer ->
                writer.write(JSONObject().put("model", modelName).toString())
            }
            val status = conn.responseCode
            if (status !in 200..299) return null
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(body)
            val capabilities = json.optJSONArray("capabilities")
            val capabilityValues = buildList {
                if (capabilities != null) {
                    for (i in 0 until capabilities.length()) {
                        val value = capabilities.optString(i).trim().lowercase()
                        if (value.isNotBlank()) add(value)
                    }
                }
            }
            val supportsTools = capabilityValues.any {
                it == "tools" || it == "tool" || it.contains("function")
            }
            val supportsReasoning = capabilityValues.takeIf { it.isNotEmpty() }?.any {
                it == "thinking" || it == "reasoning" || it.contains("reason") || it.contains("think")
            }
            val supportsImages = capabilityValues.takeIf { it.isNotEmpty() }?.any {
                it == "vision" || it.contains("image") || it.contains("vision") || it == "multimodal"
            }
            val modelInfo = json.optJSONObject("model_info")
            val contextWindow = modelInfo?.keys()?.asSequence()?.mapNotNull { key ->
                if (!key.endsWith(".context_length")) return@mapNotNull null
                val value = modelInfo.opt(key)
                if (value is Number) value.toInt().takeIf { it > 0 } else null
            }?.firstOrNull()
            OllamaModelDetails(
                contextWindow = contextWindow,
                supportsTools = supportsTools,
                supportsReasoning = supportsReasoning,
                supportsImages = supportsImages,
            )
        } catch (_: Exception) {
            null
        } finally {
            conn.disconnect()
        }
    }

    fun findRuntimeModelCatalogFile(rootfsDir: File?): File? {
        return findRuntimeModelCatalogFiles(rootfsDir).firstOrNull()
    }

    private fun findRuntimeModelCatalogFiles(rootfsDir: File?): List<File> {
        val distDir = rootfsDir?.resolve(RUNTIME_MODEL_CATALOG_DIR) ?: return emptyList()
        if (!distDir.isDirectory) return emptyList()

        fun discoverFromEntrypoint(entryFile: File): List<File> {
            val discoveredCatalogs = linkedSetOf<File>()
            val visited = mutableSetOf<String>()

            fun visit(file: File) {
                val canonicalPath = runCatching { file.canonicalPath }.getOrElse { file.absolutePath }
                if (!visited.add(canonicalPath)) return

                val content = runCatching { file.readText() }.getOrNull() ?: return
                runtimeRelativeImportRegex.findAll(content).forEach { match ->
                    val relativePath = match.groupValues.drop(1).firstOrNull { it.isNotBlank() } ?: return@forEach
                    val importedFile = file.parentFile?.resolve(relativePath)?.normalize() ?: return@forEach
                    if (!importedFile.isFile) return@forEach
                    if (runtimeModelCatalogRegex.matches(importedFile.name)) {
                        discoveredCatalogs += importedFile
                    } else if (importedFile.parentFile == distDir) {
                        visit(importedFile)
                    }
                }
            }

            visit(entryFile)
            return discoveredCatalogs.sortedBy { it.name }
        }

        val entryFile = distDir.resolve(RUNTIME_MODEL_CATALOG_ENTRYPOINT)
        if (entryFile.isFile) {
            val discoveredFromEntry = discoverFromEntrypoint(entryFile)
            if (discoveredFromEntry.isNotEmpty()) return discoveredFromEntry
        }

        val fallbackEntryFile = distDir.resolve(RUNTIME_MODEL_CATALOG_FALLBACK_ENTRYPOINT)
        if (fallbackEntryFile.isFile) {
            val discoveredFromFallback = discoverFromEntrypoint(fallbackEntryFile)
            if (discoveredFromFallback.isNotEmpty()) return discoveredFromFallback
        }

        return distDir.listFiles()
            ?.asSequence()
            ?.filter { it.isFile && runtimeModelCatalogRegex.matches(it.name) }
            ?.sortedBy { it.name }
            ?.toList()
            .orEmpty()
    }

    fun loadProviderModels(rootfsDir: File?, provider: String): List<ModelEntry> {
        val legacyContent = readLegacyModelsContent(rootfsDir) ?: return emptyList()
        val normalizedProvider = provider.trim().lowercase()
        if (normalizedProvider.isBlank()) return emptyList()

        val baseEntries = extractProviderModelEntries(legacyContent, normalizedProvider)
        val syntheticEntries = loadSyntheticFallbackEntries(rootfsDir, normalizedProvider, baseEntries)

        return (baseEntries + syntheticEntries)
            .distinctBy { it.id.lowercase() }
    }

    fun loadOpenRouterModelIds(rootfsDir: File?): Set<String> {
        val legacyContent = readLegacyModelsContent(rootfsDir) ?: return emptySet()
        return extractProviderModelIds(legacyContent, "openrouter")
            .filter { it.contains("/") || it == "auto" }
            .toSet()
    }

    fun loadSyntheticFallbackModelIds(rootfsDir: File?, provider: String): Set<String> {
        val normalizedProvider = provider.trim().lowercase()
        if (normalizedProvider.isBlank()) return emptySet()
        return findRuntimeModelCatalogFiles(rootfsDir)
            .asSequence()
            .flatMap { runtimeFile ->
                val runtimeContent = runCatching { runtimeFile.readText() }.getOrNull() ?: return@flatMap emptySequence()
                val constants = parseStringConstants(runtimeContent)
                val fallbackSection = extractSyntheticFallbackSection(runtimeContent) ?: return@flatMap emptySequence()
                if (fallbackSection.isBlank()) return@flatMap emptySequence()

                parseSyntheticFallbackSpecs(fallbackSection).asSequence().mapNotNull { spec ->
                    val resolvedProvider = resolveToken(spec.providerToken, constants) ?: return@mapNotNull null
                    if (!resolvedProvider.equals(normalizedProvider, ignoreCase = true)) return@mapNotNull null
                    resolveToken(spec.idToken, constants)
                }
            }
            .toSet()
    }

    fun loadSyntheticFallbackEntries(
        rootfsDir: File?,
        provider: String,
        baseEntries: List<ModelEntry>,
    ): List<ModelEntry> {
        val normalizedProvider = provider.trim().lowercase()
        if (normalizedProvider.isBlank()) return emptyList()
        return readSyntheticFallbackEntries(rootfsDir, normalizedProvider, baseEntries)
            .distinctBy { it.id.lowercase() }
    }


    private fun readLegacyModelsContent(rootfsDir: File?): String? {
        val file = rootfsDir?.resolve(LEGACY_BUILTIN_MODELS_PATH) ?: return null
        if (!file.isFile) return null
        return runCatching { file.readText() }.getOrNull()
    }

    private fun readSyntheticFallbackEntries(
        rootfsDir: File?,
        provider: String,
        baseEntries: List<ModelEntry>,
    ): List<ModelEntry> {
        return findRuntimeModelCatalogFiles(rootfsDir)
            .asSequence()
            .flatMap { runtimeFile ->
                val runtimeContent = runCatching { runtimeFile.readText() }.getOrNull() ?: return@flatMap emptySequence()
                val constants = parseStringConstants(runtimeContent)
                val fallbackSection = extractSyntheticFallbackSection(runtimeContent) ?: return@flatMap emptySequence()
                if (fallbackSection.isBlank()) return@flatMap emptySequence()

                parseSyntheticFallbackSpecs(fallbackSection).asSequence().mapNotNull { spec ->
                    val resolvedProvider = resolveToken(spec.providerToken, constants) ?: return@mapNotNull null
                    if (!resolvedProvider.equals(provider, ignoreCase = true)) return@mapNotNull null

                    val resolvedId = resolveToken(spec.idToken, constants) ?: return@mapNotNull null
                    if (baseEntries.any { it.id.equals(resolvedId, ignoreCase = true) }) return@mapNotNull null

                    val templateIds = spec.templateIdTokens
                        .split(',')
                        .mapNotNull { resolveToken(it, constants) }
                    val template = templateIds
                        .asSequence()
                        .mapNotNull { templateId ->
                            baseEntries.firstOrNull { it.id.equals(templateId, ignoreCase = true) }
                        }
                        .firstOrNull()
                        ?: return@mapNotNull null

                    template.copy(id = resolvedId, name = resolvedId, provider = provider)
                }
            }
            .distinctBy { it.id.lowercase() }
            .toList()
    }

    private data class SyntheticFallbackSpec(
        val providerToken: String,
        val idToken: String,
        val templateIdTokens: String,
    )

    private fun parseSyntheticFallbackSpecs(section: String): List<SyntheticFallbackSpec> {
        val specs = mutableListOf<SyntheticFallbackSpec>()
        var index = 0
        while (index < section.length) {
            if (section[index] != '{') {
                index++
                continue
            }

            val objectEnd = findMatchingBraceIndex(section, index)
            if (objectEnd <= index) break
            val body = section.substring(index, objectEnd + 1)

            val providerToken = parseObjectTokenField(body, "provider")
            val idToken = parseObjectTokenField(body, "id")
            val templateIds = parseArrayFieldBody(body, "templateIds")
            if (providerToken != null && idToken != null && templateIds != null) {
                specs += SyntheticFallbackSpec(
                    providerToken = providerToken,
                    idToken = idToken,
                    templateIdTokens = templateIds,
                )
            }

            index = objectEnd + 1
        }
        return specs
    }

    private fun parseObjectTokenField(body: String, field: String): String? {
        val escapedField = Regex.escape(field)
        val regex = Regex("""\b$escapedField\s*:\s*([^,\n}]+)""")
        return regex.find(body)?.groupValues?.get(1)?.trim()?.trimEnd(',')
    }

    private fun parseArrayFieldBody(body: String, field: String): String? {
        val escapedField = Regex.escape(field)
        val regex = Regex("""\b$escapedField\s*:\s*\[(.*?)]""", setOf(RegexOption.DOT_MATCHES_ALL))
        return regex.find(body)?.groupValues?.get(1)?.trim()
    }

    private fun parseStringConstants(content: String): Map<String, String> {
        return stringConstRegex.findAll(content)
            .associate { match ->
                match.groupValues[1] to unescapeJsString(match.groupValues[2])
            }
    }

    private fun extractSyntheticFallbackSection(content: String): String? {
        val marker = "const SYNTHETIC_CATALOG_FALLBACKS = ["
        val markerIndex = content.indexOf(marker)
        if (markerIndex < 0) return null

        val start = content.indexOf('[', markerIndex)
        if (start < 0) return null

        var depth = 1
        var index = start + 1
        while (index < content.length && depth > 0) {
            when (content[index]) {
                '[' -> depth++
                ']' -> depth--
            }
            index++
        }
        if (depth != 0) return null
        return content.substring(start + 1, index - 1)
    }

    private fun resolveToken(token: String, constants: Map<String, String>): String? {
        val trimmed = token.trim().trimEnd(',')
        if (trimmed.isBlank()) return null
        return when {
            trimmed.startsWith('"') && trimmed.endsWith('"') && trimmed.length >= 2 -> {
                unescapeJsString(trimmed.substring(1, trimmed.length - 1))
            }
            trimmed.matches(Regex("[A-Z0-9_]+")) -> constants[trimmed]
            else -> null
        }
    }

    private fun extractProviderModelIds(content: String, sectionName: String): List<String> {
        val marker = "\"$sectionName\": {"
        val markerIndex = content.indexOf(marker)
        if (markerIndex < 0) return emptyList()

        val sectionStart = content.indexOf('{', markerIndex + marker.length - 1)
        if (sectionStart < 0) return emptyList()

        val sectionEnd = findMatchingBraceIndex(content, sectionStart)
        if (sectionEnd <= sectionStart) return emptyList()

        val result = mutableListOf<String>()
        var index = sectionStart + 1
        var depth = 1

        while (index < sectionEnd) {
            when (content[index]) {
                '{' -> depth++
                '}' -> depth--
                '"' -> {
                    val (key, endQuoteIndex) = readJsonLikeString(content, index)
                    index = endQuoteIndex
                    if (depth == 1) {
                        var cursor = skipWhitespace(content, endQuoteIndex + 1, sectionEnd)
                        if (cursor < sectionEnd && content[cursor] == ':') {
                            cursor = skipWhitespace(content, cursor + 1, sectionEnd)
                            if (cursor < sectionEnd && content[cursor] == '{') {
                                result += key
                            }
                        }
                    }
                }
            }
            index++
        }

        return result
    }

    private fun extractProviderModelEntries(content: String, sectionName: String): List<ModelEntry> {
        val marker = "\"$sectionName\": {"
        val markerIndex = content.indexOf(marker)
        if (markerIndex < 0) return emptyList()

        val sectionStart = content.indexOf('{', markerIndex + marker.length - 1)
        if (sectionStart < 0) return emptyList()

        val sectionEnd = findMatchingBraceIndex(content, sectionStart)
        if (sectionEnd <= sectionStart) return emptyList()

        val result = mutableListOf<ModelEntry>()
        var index = sectionStart + 1
        var depth = 1

        while (index < sectionEnd) {
            when (content[index]) {
                '{' -> depth++
                '}' -> depth--
                '"' -> {
                    val (key, endQuoteIndex) = readJsonLikeString(content, index)
                    index = endQuoteIndex
                    if (depth == 1) {
                        var cursor = skipWhitespace(content, endQuoteIndex + 1, sectionEnd)
                        if (cursor < sectionEnd && content[cursor] == ':') {
                            cursor = skipWhitespace(content, cursor + 1, sectionEnd)
                            if (cursor < sectionEnd && content[cursor] == '{') {
                                val objectEnd = findMatchingBraceIndex(content, cursor)
                                if (objectEnd in (cursor + 1)..sectionEnd) {
                                    val body = content.substring(cursor, objectEnd + 1)
                                    parseModelEntry(sectionName, key, body)?.let { result.add(it) }
                                }
                            }
                        }
                    }
                }
            }
            index++
        }

        return result
    }

    private fun parseModelEntry(provider: String, id: String, body: String): ModelEntry? {
        val contextWindow = parseJsNumberField(body, "contextWindow") ?: return null
        val maxTokens = parseJsNumberField(body, "maxTokens") ?: 4096
        val name = parseJsStringField(body, "name") ?: id
        val supportsReasoning = parseJsBooleanField(body, "reasoning") ?: false
        val supportsImages = parseInputSupportsImages(body)

        return ModelEntry(
            id = id,
            name = name,
            provider = provider,
            contextWindow = contextWindow,
            maxTokens = maxTokens,
            supportsReasoning = supportsReasoning,
            supportsImages = supportsImages,
        )
    }

    private fun parseJsStringField(body: String, field: String): String? {
        val escapedField = Regex.escape(field)
        val regex = Regex("\\b$escapedField\\s*:\\s*\"((?:\\\\.|[^\\\"])*)\"")
        val raw = regex.find(body)?.groupValues?.get(1) ?: return null
        return unescapeJsString(raw)
    }

    private fun parseJsBooleanField(body: String, field: String): Boolean? {
        val escapedField = Regex.escape(field)
        val regex = Regex("\\b$escapedField\\s*:\\s*(true|false)")
        return when (regex.find(body)?.groupValues?.get(1)) {
            "true" -> true
            "false" -> false
            else -> null
        }
    }

    private fun parseJsNumberField(body: String, field: String): Int? {
        val escapedField = Regex.escape(field)
        val regex = Regex("\\b$escapedField\\s*:\\s*([0-9][0-9_]*(?:\\.[0-9_]+)?(?:[eE][+-]?[0-9]+)?)")
        val raw = regex.find(body)?.groupValues?.get(1)?.replace("_", "") ?: return null
        val value = raw.toDoubleOrNull() ?: return null
        if (!value.isFinite() || value <= 0.0) return null
        val longValue = value.toLong()
        if (longValue <= 0L) return null
        return longValue.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    private fun parseInputSupportsImages(body: String): Boolean {
        val inputRegex = Regex("""\binput\s*:\s*\[(.*?)]""", setOf(RegexOption.DOT_MATCHES_ALL))
        val inputBody = inputRegex.find(body)?.groupValues?.get(1) ?: return false
        return Regex(""""(?:image|images|vision|video)"""", RegexOption.IGNORE_CASE)
            .containsMatchIn(inputBody)
    }

    private fun readJsonLikeString(content: String, startIndex: Int): Pair<String, Int> {
        val builder = StringBuilder()
        var index = startIndex + 1
        var escaped = false
        while (index < content.length) {
            val ch = content[index]
            if (escaped) {
                builder.append(
                    when (ch) {
                        'n' -> '\n'
                        'r' -> '\r'
                        't' -> '\t'
                        else -> ch
                    }
                )
                escaped = false
            } else if (ch == '\\') {
                escaped = true
            } else if (ch == '"') {
                return builder.toString() to index
            } else {
                builder.append(ch)
            }
            index++
        }
        return builder.toString() to content.lastIndex
    }

    private fun findMatchingBraceIndex(content: String, startIndex: Int): Int {
        if (startIndex !in content.indices || content[startIndex] != '{') return -1
        var depth = 0
        var index = startIndex
        var inString = false
        var escaped = false
        while (index < content.length) {
            val ch = content[index]
            if (inString) {
                if (escaped) {
                    escaped = false
                } else if (ch == '\\') {
                    escaped = true
                } else if (ch == '"') {
                    inString = false
                }
            } else {
                when (ch) {
                    '"' -> inString = true
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) return index
                    }
                }
            }
            index++
        }
        return -1
    }

    private fun skipWhitespace(content: String, startIndex: Int, endExclusive: Int): Int {
        var index = startIndex
        while (index < endExclusive && content[index].isWhitespace()) {
            index++
        }
        return index
    }

    private fun unescapeJsString(raw: String): String {
        return raw
            .replace("\\\"", "\"")
            .replace("\\n", "\n")
            .replace("\\r", "\r")
            .replace("\\t", "\t")
            .replace("\\\\", "\\")
    }
}
