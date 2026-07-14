package com.coderred.andclaw.proroot

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

internal object OpenClawPluginInstallStateStore {
    data class MergeResult(
        val changed: Boolean,
        val mergedRecords: Int,
        val conflictingManagedPluginIds: List<String>,
        val staleManagedPluginIds: List<String>,
    )

    data class Diagnostic(
        val json: JSONObject,
        val summaryLine: String,
    )

    private const val OPENCLAW_STATE_SQLITE_PATH = "root/.openclaw/state/openclaw.sqlite"
    private const val LEGACY_INSTALLS_PATH = "root/.openclaw/plugins/installs.json"
    private const val BUNDLED_INSTALLS_TEMPLATE_PATH =
        "root/.openclaw/andclaw-bundled-plugins/install-records.json"
    private const val INSTALLED_PLUGIN_INDEX_TABLE = "installed_plugin_index"
    private const val INSTALLED_PLUGIN_INDEX_KEY = "installed-plugin-index"
    private val managedPluginIds = listOf("whatsapp", "discord", "codex", "brave", "zai")

    fun mergeBundledInstallRecords(
        rootfsDir: File,
        template: JSONObject,
        nowEpochMs: Long,
    ): MergeResult {
        val databaseFile = File(rootfsDir, OPENCLAW_STATE_SQLITE_PATH)
        if (!databaseFile.isFile) {
            return MergeResult(
                changed = false,
                mergedRecords = 0,
                conflictingManagedPluginIds = emptyList(),
                staleManagedPluginIds = emptyList(),
            )
        }

        SQLiteDatabase.openDatabase(databaseFile.path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            if (!sqliteTableExists(db, INSTALLED_PLUGIN_INDEX_TABLE)) {
                return MergeResult(
                    changed = false,
                    mergedRecords = 0,
                    conflictingManagedPluginIds = emptyList(),
                    staleManagedPluginIds = emptyList(),
                )
            }

            var installRecordsRaw: String? = null
            var pluginsRaw: String? = null
            db.rawQuery(
                """
                SELECT install_records_json, plugins_json
                  FROM installed_plugin_index
                 WHERE index_key = ?
                """.trimIndent(),
                arrayOf(INSTALLED_PLUGIN_INDEX_KEY),
            ).use { cursor ->
                if (!cursor.moveToFirst()) {
                    return MergeResult(
                        changed = false,
                        mergedRecords = 0,
                        conflictingManagedPluginIds = emptyList(),
                        staleManagedPluginIds = emptyList(),
                    )
                }
                installRecordsRaw = cursor.getString(0)
                pluginsRaw = cursor.getString(1)
            }

            val installRecords = JSONObject(installRecordsRaw ?: "{}")
            val existingPlugins = JSONArray(pluginsRaw ?: "[]")
            val templateRecords = template.optJSONObject("installRecords")
                ?: throw SetupException("Bundled OpenClaw plugin install records are missing installRecords")
            val beforeState = ManagedPluginState(
                installPaths = managedInstallPaths(installRecords),
                rootDirs = managedPluginRootDirs(existingPlugins),
            )
            val templateState = ManagedPluginState(
                installPaths = managedInstallPaths(templateRecords),
                rootDirs = managedPluginRootDirs(template.optJSONArray("plugins") ?: JSONArray()),
            )
            val staleBefore = staleManagedPluginIds(beforeState, templateState)

            val beforeInstallRecords = installRecords.toString()
            val beforePlugins = existingPlugins.toString()
            val mergedRecords = mergeManagedInstallRecords(installRecords, templateRecords)
            val mergedPlugins = mergeManagedPluginEntries(existingPlugins, template)
            val changed = beforeInstallRecords != installRecords.toString() ||
                beforePlugins != mergedPlugins.toString()

            if (changed) {
                db.beginTransaction()
                try {
                    val values = ContentValues().apply {
                        put("install_records_json", installRecords.toString())
                        put("plugins_json", mergedPlugins.toString())
                        put("updated_at_ms", nowEpochMs)
                    }
                    val updated = db.update(
                        INSTALLED_PLUGIN_INDEX_TABLE,
                        values,
                        "index_key = ?",
                        arrayOf(INSTALLED_PLUGIN_INDEX_KEY),
                    )
                    if (updated > 0) {
                        db.setTransactionSuccessful()
                    }
                } finally {
                    db.endTransaction()
                }
            }

            return MergeResult(
                changed = changed,
                mergedRecords = mergedRecords,
                conflictingManagedPluginIds = emptyList(),
                staleManagedPluginIds = staleBefore,
            )
        }
    }

    fun mergeManagedInstallRecords(
        installRecords: JSONObject,
        templateRecords: JSONObject,
    ): Int {
        var merged = 0
        managedPluginIds.forEach { pluginId ->
            val record = templateRecords.optJSONObject(pluginId) ?: return@forEach
            installRecords.put(pluginId, JSONObject(record.toString()))
            merged += 1
        }
        return merged
    }

    fun mergeManagedPluginEntries(
        existingPlugins: JSONArray,
        template: JSONObject,
    ): JSONArray {
        val templatePluginsById = mutableMapOf<String, JSONObject>()
        val templatePlugins = template.optJSONArray("plugins") ?: JSONArray()
        for (index in 0 until templatePlugins.length()) {
            val plugin = templatePlugins.optJSONObject(index) ?: continue
            val pluginId = plugin.optString("pluginId").trim()
            if (pluginId in managedPluginIds) {
                templatePluginsById[pluginId] = plugin
            }
        }

        val mergedPlugins = JSONArray()
        for (index in 0 until existingPlugins.length()) {
            val plugin = existingPlugins.optJSONObject(index) ?: continue
            val pluginId = plugin.optString("pluginId").trim()
            if (pluginId !in managedPluginIds) {
                mergedPlugins.put(plugin)
            }
        }
        managedPluginIds.forEach { pluginId ->
            templatePluginsById[pluginId]?.let { mergedPlugins.put(JSONObject(it.toString())) }
        }
        return mergedPlugins
    }

    fun readDiagnostic(rootfsDir: File): Diagnostic? {
        val templateFile = File(rootfsDir, BUNDLED_INSTALLS_TEMPLATE_PATH)
        val legacyFile = File(rootfsDir, LEGACY_INSTALLS_PATH)
        val stateDbFile = File(rootfsDir, OPENCLAW_STATE_SQLITE_PATH)
        if (!templateFile.isFile && !legacyFile.isFile && !stateDbFile.isFile) return null

        val template = readJsonObject(templateFile)
        val legacy = readJsonObject(legacyFile)
        val sharedState = readSharedStateIndex(stateDbFile)
        val templateState = readManagedState(template)
        val legacyState = readManagedState(legacy)
        val sharedStateManaged = sharedState?.let {
            ManagedPluginState(
                installPaths = managedInstallPaths(it.installRecords),
                rootDirs = managedPluginRootDirs(it.plugins),
            )
        } ?: ManagedPluginState()

        val conflicts = conflictingManagedPluginIds(legacyState, sharedStateManaged)
        val stale = (staleManagedPluginIds(legacyState, templateState) +
            staleManagedPluginIds(sharedStateManaged, templateState))
            .distinct()

        val json = JSONObject().apply {
            put("managedPluginIds", jsonArray(managedPluginIds))
            put("template", describeIndexFile(templateFile, template, templateState))
            put("legacy", describeIndexFile(legacyFile, legacy, legacyState))
            put(
                "sharedState",
                JSONObject().apply {
                    put("exists", stateDbFile.isFile)
                    put("path", "/$OPENCLAW_STATE_SQLITE_PATH")
                    put("installedPluginIndexRow", sharedState != null)
                    put("managedInstallPaths", jsonObject(sharedStateManaged.installPaths))
                    put("managedPluginRootDirs", jsonObject(sharedStateManaged.rootDirs))
                    sharedState?.let {
                        put("hostContractVersion", it.hostContractVersion)
                        put("compatRegistryVersion", it.compatRegistryVersion)
                        put("generatedAtMs", it.generatedAtMs)
                        put("refreshReason", it.refreshReason)
                    }
                },
            )
            put("conflictingManagedPluginIds", jsonArray(conflicts))
            put("staleManagedPluginIds", jsonArray(stale))
        }
        return Diagnostic(
            json = json,
            summaryLine = "[andClaw][OpenClawPluginState] " +
                "legacy=${legacyFile.isFile} sqlite=${stateDbFile.isFile} " +
                "conflicts=${conflicts.ifEmpty { listOf("none") }.joinToString(",")} " +
                "stale=${stale.ifEmpty { listOf("none") }.joinToString(",")}",
        )
    }

    private data class SharedStateIndex(
        val installRecords: JSONObject,
        val plugins: JSONArray,
        val hostContractVersion: String?,
        val compatRegistryVersion: String?,
        val generatedAtMs: Long?,
        val refreshReason: String?,
    )

    private data class ManagedPluginState(
        val installPaths: Map<String, String> = emptyMap(),
        val rootDirs: Map<String, String> = emptyMap(),
    )

    private fun readJsonObject(file: File): JSONObject? {
        if (!file.isFile) return null
        return JSONObject(file.readText())
    }

    private fun readSharedStateIndex(databaseFile: File): SharedStateIndex? {
        if (!databaseFile.isFile) return null
        SQLiteDatabase.openDatabase(databaseFile.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            if (!sqliteTableExists(db, INSTALLED_PLUGIN_INDEX_TABLE)) return null
            db.rawQuery(
                """
                SELECT install_records_json, plugins_json, host_contract_version,
                       compat_registry_version, generated_at_ms, refresh_reason
                  FROM installed_plugin_index
                 WHERE index_key = ?
                """.trimIndent(),
                arrayOf(INSTALLED_PLUGIN_INDEX_KEY),
            ).use { cursor ->
                if (!cursor.moveToFirst()) return null
                return SharedStateIndex(
                    installRecords = JSONObject(cursor.getString(0) ?: "{}"),
                    plugins = JSONArray(cursor.getString(1) ?: "[]"),
                    hostContractVersion = cursor.getStringOrNull(2),
                    compatRegistryVersion = cursor.getStringOrNull(3),
                    generatedAtMs = cursor.getLongOrNull(4),
                    refreshReason = cursor.getStringOrNull(5),
                )
            }
        }
    }

    private fun readManagedState(index: JSONObject?): ManagedPluginState {
        if (index == null) return ManagedPluginState()
        return ManagedPluginState(
            installPaths = managedInstallPaths(index.optJSONObject("installRecords") ?: JSONObject()),
            rootDirs = managedPluginRootDirs(index.optJSONArray("plugins") ?: JSONArray()),
        )
    }

    private fun managedInstallPaths(installRecords: JSONObject): Map<String, String> {
        return managedPluginIds.mapNotNull { pluginId ->
            val path = installRecords.optJSONObject(pluginId)
                ?.optString("installPath")
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
            path?.let { pluginId to it }
        }.toMap()
    }

    private fun managedPluginRootDirs(plugins: JSONArray): Map<String, String> {
        val paths = linkedMapOf<String, String>()
        for (index in 0 until plugins.length()) {
            val plugin = plugins.optJSONObject(index) ?: continue
            val pluginId = plugin.optString("pluginId").trim()
            if (pluginId !in managedPluginIds) continue
            val rootDir = plugin.optString("rootDir").trim()
            if (rootDir.isNotEmpty()) paths[pluginId] = rootDir
        }
        return paths
    }

    private fun conflictingManagedPluginIds(
        legacy: ManagedPluginState,
        shared: ManagedPluginState,
    ): List<String> {
        return managedPluginIds.filter { pluginId ->
            val legacyPath = legacy.installPaths[pluginId]
            val sharedPath = shared.installPaths[pluginId]
            val legacyRoot = legacy.rootDirs[pluginId]
            val sharedRoot = shared.rootDirs[pluginId]
            (legacyPath != null && sharedPath != null && legacyPath != sharedPath) ||
                (legacyRoot != null && sharedRoot != null && legacyRoot != sharedRoot)
        }
    }

    private fun staleManagedPluginIds(
        state: ManagedPluginState,
        template: ManagedPluginState,
    ): List<String> {
        return managedPluginIds.filter { pluginId ->
            val installPath = state.installPaths[pluginId]
            val rootDir = state.rootDirs[pluginId]
            val templateInstallPath = template.installPaths[pluginId]
            val templateRootDir = template.rootDirs[pluginId]
            isLegacyManagedNpmProjectPath(pluginId, installPath) ||
                isLegacyManagedNpmProjectPath(pluginId, rootDir) ||
                (installPath != null && templateInstallPath != null && installPath != templateInstallPath) ||
                (rootDir != null && templateRootDir != null && rootDir != templateRootDir)
        }
    }

    private fun isLegacyManagedNpmProjectPath(pluginId: String, path: String?): Boolean {
        return path?.contains("/root/.openclaw/npm/projects/openclaw-$pluginId-") == true
    }

    private fun describeIndexFile(
        file: File,
        index: JSONObject?,
        state: ManagedPluginState,
    ): JSONObject {
        return JSONObject().apply {
            put("exists", file.isFile)
            put("path", "/${file.toRelativeOpenClawPath()}")
            put("parseable", index != null)
            put("managedInstallPaths", jsonObject(state.installPaths))
            put("managedPluginRootDirs", jsonObject(state.rootDirs))
        }
    }

    private fun File.toRelativeOpenClawPath(): String {
        val normalized = path.replace(File.separatorChar, '/')
        val marker = "/root/.openclaw/"
        val index = normalized.indexOf(marker)
        return if (index >= 0) {
            normalized.substring(index + 1)
        } else {
            name
        }
    }

    private fun sqliteTableExists(db: SQLiteDatabase, tableName: String): Boolean {
        db.rawQuery(
            "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?",
            arrayOf(tableName),
        ).use { cursor ->
            return cursor.moveToFirst()
        }
    }

    private fun jsonArray(values: List<String>): JSONArray {
        return JSONArray().also { array ->
            values.forEach(array::put)
        }
    }

    private fun jsonObject(values: Map<String, String>): JSONObject {
        return JSONObject().also { obj ->
            values.forEach { (key, value) -> obj.put(key, value) }
        }
    }
}

private fun android.database.Cursor.getStringOrNull(index: Int): String? {
    return if (isNull(index)) null else getString(index)
}

private fun android.database.Cursor.getLongOrNull(index: Int): Long? {
    return if (isNull(index)) null else getLong(index)
}
