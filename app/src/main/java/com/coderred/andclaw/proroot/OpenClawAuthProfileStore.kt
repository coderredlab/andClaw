package com.coderred.andclaw.proroot

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.StandardCopyOption
import org.json.JSONArray
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

internal object OpenClawAuthProfileStore {
    private const val AGENT_AUTH_PROFILES_PATH = "root/.openclaw/agents/main/agent/auth-profiles.json"
    private const val AGENT_AUTH_SQLITE_PATH = "root/.openclaw/agents/main/agent/openclaw-agent.sqlite"
    private const val SHARED_AUTH_SQLITE_PATH = "root/.openclaw/state/openclaw.sqlite"
    private const val LEGACY_CODEX_OAUTH_PROVIDER = "openai-codex"
    private const val OPENAI_PROVIDER = "openai"
    private const val CANONICAL_CODEX_PROFILE_ID = "openai:codex"
    private const val CANONICAL_API_KEY_PROFILE_ID = "openai:api-key"
    private const val PRIMARY_ROW_KEY = "primary"
    private const val SHARED_STORE_ROW_KEY = "authProfiles.store"
    private const val SHARED_STATE_ROW_KEY = "authProfiles.state"
    private const val SHARED_OWNERSHIP_ROW_KEY = "auth.sharedStore"
    private const val AGENT_ID = "main"
    private const val AGENT_SCHEMA_VERSION = 1
    private val operationMutex = Mutex()

    enum class CredentialType {
        OAUTH,
        API_KEY,
    }

    data class RawAuthSnapshot(
        val jsonStore: JSONObject?,
        val sqliteStore: JSONObject?,
        val sqliteState: JSONObject?,
        val preferenceApiKey: String?,
    )

    class OpenAiApiKeySnapshot internal constructor(
        internal val credential: JSONObject?,
        internal val wasActive: Boolean,
    )

    class OpenAiSelectionSnapshot internal constructor(
        internal val lastGood: SelectionContainerSnapshot,
        internal val order: SelectionContainerSnapshot,
    )

    internal data class SelectionContainerSnapshot(
        val wasPresent: Boolean,
        val values: JSONObject,
    )

    data class CredentialInventory(
        val hasUsableCodexOAuth: Boolean,
        val hasUsableOpenAiApiKey: Boolean,
        val activeCredentialTypeHint: CredentialType?,
        val hasLegacyActiveReference: Boolean,
    )

    data class MigrationResult(
        val changed: Boolean,
        val activeCredentialType: CredentialType?,
        val activeProfileId: String?,
    )

    data class ResetResult(
        val changed: Boolean,
        val removedProfileIds: Set<String>,
        val keptProfileIds: Set<String>,
    )

    internal data class MigrationHooks(
        val afterStoreUpsert: (() -> Unit)? = null,
        val beforeJsonReplace: (() -> Unit)? = null,
    )

    private enum class CandidateSource {
        SQLITE,
        JSON,
        PREFERENCE,
    }

    private data class CredentialCandidate(
        val source: CandidateSource,
        val profileId: String,
        val credential: JSONObject,
    )

    private data class CanonicalPayload(
        val store: JSONObject,
        val state: JSONObject,
        val activeCredentialType: CredentialType?,
        val activeProfileId: String?,
    )

    private data class CanonicalMutation<T>(
        val payload: CanonicalPayload?,
        val result: (changed: Boolean) -> T,
    )

    private data class AuthSqliteRowSpec(
        val table: String,
        val keyColumn: String,
        val jsonColumn: String,
        val rowKey: String,
    )

    private data class AuthSqliteStorage(
        val file: File,
        val store: AuthSqliteRowSpec,
        val state: AuthSqliteRowSpec,
        val timestampColumn: String,
        val shared: Boolean,
    )

    private data class StoredAuthRow(
        val rawJson: String,
        val payload: JSONObject,
        val updatedAt: Long,
    )

    fun authProfilesJsonFile(rootfsDir: File): File {
        return File(rootfsDir, AGENT_AUTH_PROFILES_PATH)
    }

    fun authProfilesSqliteFile(rootfsDir: File): File {
        return File(rootfsDir, AGENT_AUTH_SQLITE_PATH)
    }
    private fun legacyAuthStorage(rootfsDir: File): AuthSqliteStorage =
        AuthSqliteStorage(
            file = authProfilesSqliteFile(rootfsDir),
            store = AuthSqliteRowSpec(
                table = "auth_profile_store",
                keyColumn = "store_key",
                jsonColumn = "store_json",
                rowKey = PRIMARY_ROW_KEY,
            ),
            state = AuthSqliteRowSpec(
                table = "auth_profile_state",
                keyColumn = "state_key",
                jsonColumn = "state_json",
                rowKey = PRIMARY_ROW_KEY,
            ),
            timestampColumn = "updated_at",
            shared = false,
        )

    private fun sharedAuthStorage(rootfsDir: File): AuthSqliteStorage {
        val store = AuthSqliteRowSpec(
            table = "config_machine_state",
            keyColumn = "state_key",
            jsonColumn = "value_json",
            rowKey = SHARED_STORE_ROW_KEY,
        )
        return AuthSqliteStorage(
            file = File(rootfsDir, SHARED_AUTH_SQLITE_PATH),
            store = store,
            state = store.copy(rowKey = SHARED_STATE_ROW_KEY),
            timestampColumn = "updated_at_ms",
            shared = true,
        )
    }

    private fun resolveAuthStorage(rootfsDir: File): AuthSqliteStorage {
        val shared = sharedAuthStorage(rootfsDir)
        if (!shared.file.isFile) return legacyAuthStorage(rootfsDir)
        val ownsSharedAuth = runCatching {
            SQLiteDatabase.openDatabase(
                shared.file.path,
                null,
                SQLiteDatabase.OPEN_READONLY,
            ).use { database ->
                database.rawQuery(
                    "SELECT value_json FROM config_machine_state WHERE state_key = ?",
                    arrayOf(SHARED_OWNERSHIP_ROW_KEY),
                ).use { cursor ->
                    cursor.moveToFirst() &&
                        JSONObject(cursor.getString(0)).optString("location") == "state-db"
                }
            }
        }.getOrDefault(false)
        return if (ownsSharedAuth) shared else legacyAuthStorage(rootfsDir)
    }

    private fun prepareAuthStorage(database: SQLiteDatabase, storage: AuthSqliteStorage) {
        if (!storage.shared) {
            ensureAgentSchema(database)
            return
        }
        check(
            database.rawQuery(
                "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?",
                arrayOf(storage.store.table),
            ).use { it.moveToFirst() },
        ) {
            "OpenClaw shared auth state table is missing."
        }
    }

    private fun <T> withOperationLock(block: () -> T): T {
        return runBlocking {
            operationMutex.withLock { block() }
        }
    }

    /** Reads each persistence source before any merge so profile-id collisions remain observable. */
    fun readRawSnapshot(rootfsDir: File, preferenceApiKey: String? = null): RawAuthSnapshot {
        return withOperationLock { readRawSnapshotUnlocked(rootfsDir, preferenceApiKey) }
    }
    internal fun reconcileSharedAuthRelocationConflict(rootfsDir: File): Boolean =
        withOperationLock {
            val legacy = legacyAuthStorage(rootfsDir)
            val shared = sharedAuthStorage(rootfsDir)
            if (!legacy.file.isFile || !shared.file.isFile) return@withOperationLock false

            val sourceStore = readStoredAuthRow(legacy, legacy.store)
            val sourceState = readStoredAuthRow(legacy, legacy.state)
            if (sourceStore == null && sourceState == null) return@withOperationLock false
            val targetStore = readStoredAuthRow(shared, shared.store)
            val targetState = readStoredAuthRow(shared, shared.state)

            val storeConflict = sourceStore != null && targetStore != null &&
                !storedAuthRowsMatch(sourceStore, targetStore)
            val stateConflict = sourceState != null && targetState != null &&
                !storedAuthRowsMatch(sourceState, targetState)
            if (!storeConflict && !stateConflict) return@withOperationLock false

            val reconciledStore = if (storeConflict) {
                mergeAuthStores(sourceStore!!.payload, targetStore!!.payload)
            } else {
                sourceStore?.payload ?: targetStore?.payload
            }
            val reconciledState = if (stateConflict) {
                mergeAuthStates(sourceState!!.payload, targetState!!.payload)
            } else {
                sourceState?.payload ?: targetState?.payload
            }
            if (reconciledStore != null && reconciledState != null) {
                pruneStateToExistingProfiles(
                    reconciledState,
                    reconciledStore.optJSONObject("profiles") ?: JSONObject(),
                )
            }
            val timestamp = maxOf(
                sourceStore?.updatedAt ?: 0L,
                sourceState?.updatedAt ?: 0L,
                targetStore?.updatedAt ?: 0L,
                targetState?.updatedAt ?: 0L,
                System.currentTimeMillis(),
            )

            writeReconciledRows(
                storage = shared,
                store = reconciledStore?.takeIf { sourceStore != null && targetStore != null },
                state = reconciledState?.takeIf { sourceState != null && targetState != null },
                timestamp = timestamp,
            )
            writeReconciledRows(
                storage = legacy,
                store = reconciledStore?.takeIf { sourceStore != null && targetStore != null },
                state = reconciledState?.takeIf { sourceState != null && targetState != null },
                timestamp = timestamp,
            )
            if (reconciledStore != null && reconciledState != null) {
                val canonicalStorage = resolveAuthStorage(rootfsDir)
                if (canonicalStorage.shared) {
                    retireLegacyJsonStore(rootfsDir, MigrationHooks())
                } else {
                    writeJsonStoreAtomically(
                        rootfsDir,
                        buildJsonMirror(reconciledStore, reconciledState),
                        MigrationHooks(),
                    )
                }
            }
            true
        }

    private fun mergeAuthStores(source: JSONObject, target: JSONObject): JSONObject {
        val merged = target.deepCopy()
        for (key in source.keys().asSequence().toList()) {
            if (key == "profiles") continue
            merged.put(key, deepCopyJsonValue(source.get(key)))
        }
        val profiles = target.optJSONObject("profiles")?.deepCopy() ?: JSONObject()
        val sourceProfiles = source.optJSONObject("profiles")
        if (sourceProfiles != null) {
            for (profileId in sourceProfiles.keys().asSequence().toList()) {
                profiles.put(profileId, deepCopyJsonValue(sourceProfiles.get(profileId)))
            }
        }
        merged.put("profiles", profiles)
        if (!merged.has("version")) merged.put("version", 1)
        return merged
    }

    private fun mergeAuthStates(source: JSONObject, target: JSONObject): JSONObject {
        val merged = target.deepCopy()
        for (key in source.keys().asSequence().toList()) {
            val sourceValue = source.get(key)
            val targetValue = merged.opt(key)
            if (sourceValue is JSONObject && targetValue is JSONObject) {
                val mergedObject = targetValue.deepCopy()
                for (nestedKey in sourceValue.keys().asSequence().toList()) {
                    mergedObject.put(nestedKey, deepCopyJsonValue(sourceValue.get(nestedKey)))
                }
                merged.put(key, mergedObject)
            } else {
                merged.put(key, deepCopyJsonValue(sourceValue))
            }
        }
        return merged
    }

    private fun storedAuthRowsMatch(left: StoredAuthRow, right: StoredAuthRow): Boolean =
        left.updatedAt == right.updatedAt && left.rawJson == right.rawJson

    private fun readStoredAuthRow(
        storage: AuthSqliteStorage,
        row: AuthSqliteRowSpec,
    ): StoredAuthRow? {
        if (!storage.file.isFile) return null
        return SQLiteDatabase.openDatabase(
            storage.file.path,
            null,
            SQLiteDatabase.OPEN_READONLY,
        ).use { database ->
            database.rawQuery(
                """
                SELECT ${row.jsonColumn}, ${storage.timestampColumn}
                FROM ${row.table}
                WHERE ${row.keyColumn} = ?
                """.trimIndent(),
                arrayOf(row.rowKey),
            ).use { cursor ->
                if (!cursor.moveToFirst()) return null
                val rawJson = cursor.getString(0)
                StoredAuthRow(
                    rawJson = rawJson,
                    payload = JSONObject(rawJson),
                    updatedAt = cursor.getLong(1),
                )
            }
        }
    }

    private fun writeReconciledRows(
        storage: AuthSqliteStorage,
        store: JSONObject?,
        state: JSONObject?,
        timestamp: Long,
    ) {
        if (store == null && state == null) return
        SQLiteDatabase.openDatabase(
            storage.file.path,
            null,
            SQLiteDatabase.OPEN_READWRITE,
        ).use { database ->
            database.rawQuery("PRAGMA busy_timeout = 5000", null).use { it.moveToFirst() }
            database.beginTransaction()
            try {
                if (store != null) upsertJsonRow(database, storage, storage.store, store, timestamp)
                if (state != null) upsertJsonRow(database, storage, storage.state, state, timestamp)
                database.setTransactionSuccessful()
            } finally {
                database.endTransaction()
            }
        }
    }

    private fun readRawSnapshotUnlocked(
        rootfsDir: File,
        preferenceApiKey: String? = null,
    ): RawAuthSnapshot {
        val storage = resolveAuthStorage(rootfsDir)
        return RawAuthSnapshot(
            jsonStore = readJsonStore(rootfsDir)?.deepCopy(),
            sqliteStore = readSqliteRow(storage, storage.store)?.deepCopy(),
            sqliteState = readSqliteRow(storage, storage.state)?.deepCopy(),
            preferenceApiKey = preferenceApiKey?.trim()?.takeIf { it.isNotEmpty() },
        )
    }

    private fun readRawSnapshotInTransaction(
        rootfsDir: File,
        db: SQLiteDatabase,
        storage: AuthSqliteStorage,
        preferenceApiKey: String?,
    ): RawAuthSnapshot {
        return RawAuthSnapshot(
            jsonStore = readJsonStore(rootfsDir)?.deepCopy(),
            sqliteStore = readSqliteRow(db, storage, storage.store)?.deepCopy(),
            sqliteState = readSqliteRow(db, storage, storage.state)?.deepCopy(),
            preferenceApiKey = preferenceApiKey?.trim()?.takeIf { it.isNotEmpty() },
        )
    }

    private fun <T> performCanonicalMutation(
        rootfsDir: File,
        preferenceApiKey: String? = null,
        hooks: MigrationHooks,
        mutation: (RawAuthSnapshot) -> CanonicalMutation<T>,
    ): T {
        val storage = resolveAuthStorage(rootfsDir)
        val databaseFile = storage.file
        databaseFile.parentFile?.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(databaseFile, null).use { db ->
            db.rawQuery("PRAGMA busy_timeout = 5000", null).use { it.moveToFirst() }
            db.execSQL("PRAGMA synchronous = NORMAL")
            db.beginTransaction()
            var snapshot: RawAuthSnapshot? = null
            var mirrorPersisted = false
            try {
                prepareAuthStorage(db, storage)
                val currentSnapshot = readRawSnapshotInTransaction(
                    rootfsDir,
                    db,
                    storage,
                    preferenceApiKey,
                )
                snapshot = currentSnapshot
                val operation = mutation(currentSnapshot)
                val changed = operation.payload?.let { payload ->
                    val persisted = persistCanonicalPayloadInTransaction(
                        rootfsDir = rootfsDir,
                        db = db,
                        storage = storage,
                        snapshot = currentSnapshot,
                        payload = payload,
                        hooks = hooks,
                    )
                    mirrorPersisted = true
                    persisted
                } ?: false
                val result = operation.result(changed)
                db.setTransactionSuccessful()
                return result
            } catch (failure: Throwable) {
                if (mirrorPersisted) {
                    try {
                        restoreJsonSnapshot(rootfsDir, snapshot?.jsonStore)
                    } catch (rollbackFailure: Throwable) {
                        failure.addSuppressed(rollbackFailure)
                    }
                }
                throw failure
            } finally {
                db.endTransaction()
            }
        }
    }

    private fun <T> performConsistentRead(
        rootfsDir: File,
        preferenceApiKey: String? = null,
        read: (RawAuthSnapshot) -> T,
    ): T {
        val storage = resolveAuthStorage(rootfsDir)
        val databaseFile = storage.file
        databaseFile.parentFile?.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(databaseFile, null).use { db ->
            db.rawQuery("PRAGMA busy_timeout = 5000", null).use { it.moveToFirst() }
            db.beginTransaction()
            try {
                prepareAuthStorage(db, storage)
                val result = read(
                    readRawSnapshotInTransaction(rootfsDir, db, storage, preferenceApiKey),
                )
                db.setTransactionSuccessful()
                return result
            } finally {
                db.endTransaction()
            }
        }
    }

    private fun <T> performNonOpenAiProfileMutation(
        rootfsDir: File,
        hooks: MigrationHooks,
        mutation: (store: JSONObject, state: JSONObject) -> T,
    ): T {
        val storage = resolveAuthStorage(rootfsDir)
        val databaseFile = storage.file
        databaseFile.parentFile?.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(databaseFile, null).use { db ->
            db.rawQuery("PRAGMA busy_timeout = 5000", null).use { it.moveToFirst() }
            db.execSQL("PRAGMA synchronous = NORMAL")
            db.beginTransaction()
            var snapshot: RawAuthSnapshot? = null
            var mirrorPersisted = false
            try {
                prepareAuthStorage(db, storage)
                val currentSnapshot = readRawSnapshotInTransaction(rootfsDir, db, storage, null)
                snapshot = currentSnapshot
                val store = selectSelectionRestoreStoreBase(currentSnapshot)
                if (!store.has("version")) store.put("version", 1)
                if (store.optJSONObject("profiles") == null) store.put("profiles", JSONObject())
                val state = selectStateBase(currentSnapshot)
                val result = mutation(store, state)
                persistSelectionOnlyInTransaction(
                    rootfsDir = rootfsDir,
                    db = db,
                    storage = storage,
                    snapshot = currentSnapshot,
                    store = store,
                    state = state,
                    hooks = hooks,
                )
                mirrorPersisted = true
                val after = readRawSnapshotInTransaction(rootfsDir, db, storage, null)
                check(jsonContentEquals(after.sqliteStore, store)) {
                    "OpenClaw non-OpenAI auth profile store read-back mismatch."
                }
                check(jsonContentEquals(after.sqliteState, state)) {
                    "OpenClaw non-OpenAI auth profile state read-back mismatch."
                }
                db.setTransactionSuccessful()
                return result
            } catch (failure: Throwable) {
                if (mirrorPersisted) {
                    try {
                        restoreJsonSnapshot(rootfsDir, snapshot?.jsonStore)
                    } catch (rollbackFailure: Throwable) {
                        failure.addSuppressed(rollbackFailure)
                    }
                }
                throw failure
            } finally {
                db.endTransaction()
            }
        }
    }

    fun inspectCredentialInventory(
        rootfsDir: File,
        preferenceApiKey: String? = null,
    ): CredentialInventory {
        return withOperationLock {
            inspectCredentialInventoryUnlocked(rootfsDir, preferenceApiKey)
        }
    }

    private fun inspectCredentialInventoryUnlocked(
        rootfsDir: File,
        preferenceApiKey: String?,
    ): CredentialInventory {
        val snapshot = readRawSnapshotUnlocked(rootfsDir, preferenceApiKey)
        val candidates = collectCredentialCandidates(snapshot)
        val activeProfileIds = legacyActiveProfileIds(snapshot)
        val activeCredentialTypeHint = activeProfileIds.asSequence()
            .mapNotNull { activeProfileId ->
                candidates.firstOrNull {
                    it.source == CandidateSource.SQLITE && it.profileId == activeProfileId
                } ?: candidates.firstOrNull { it.profileId == activeProfileId }
            }
            .mapNotNull { credentialType(it.credential) }
            .firstOrNull()
        val selectedState = snapshot.sqliteState
            ?: extractInlineState(snapshot.sqliteStore)
            ?: extractInlineState(snapshot.jsonStore)
        val hasLegacyProviderState =
            selectedState?.optJSONObject("lastGood")?.has(LEGACY_CODEX_OAUTH_PROVIDER) == true ||
                selectedState?.optJSONObject("order")?.has(LEGACY_CODEX_OAUTH_PROVIDER) == true
        val hasLegacyActiveReference = hasLegacyProviderState || activeProfileIds.any { activeProfileId ->
            activeProfileId == "openai:default" ||
                activeProfileId.startsWith("$LEGACY_CODEX_OAUTH_PROVIDER:") ||
                candidates.firstOrNull { it.profileId == activeProfileId }
                    ?.credential
                    ?.optString("provider")
                    ?.trim()
                    ?.lowercase() == LEGACY_CODEX_OAUTH_PROVIDER
        }
        return CredentialInventory(
            hasUsableCodexOAuth = candidates.any {
                credentialType(it.credential) == CredentialType.OAUTH && isUsableOAuth(it.credential)
            },
            hasUsableOpenAiApiKey = candidates.any {
                credentialType(it.credential) == CredentialType.API_KEY && isUsableApiKey(it.credential)
            } || !snapshot.preferenceApiKey.isNullOrBlank(),
            activeCredentialTypeHint = activeCredentialTypeHint,
            hasLegacyActiveReference = hasLegacyActiveReference,
        )
    }

    fun hasUsableCanonicalCodexOAuth(rootfsDir: File): Boolean {
        return withOperationLock {
            val snapshot = readRawSnapshotUnlocked(rootfsDir)
            listOf(snapshot.sqliteStore, snapshot.jsonStore).any { store ->
                val credential = store
                    ?.optJSONObject("profiles")
                    ?.optJSONObject(CANONICAL_CODEX_PROFILE_ID)
                    ?: return@any false
                credential.optString("provider").trim().lowercase() == OPENAI_PROVIDER &&
                    credentialType(credential) == CredentialType.OAUTH &&
                    isUsableOAuth(credential)
            }
        }
    }

    internal fun hasUsableCanonicalSqliteSelection(
        rootfsDir: File,
        profileId: String,
        expectedCredentialType: CredentialType,
    ): Boolean {
        require(profileId.isNotBlank()) { "OpenAI auth profile id must not be blank." }
        return withOperationLock {
            val storage = resolveAuthStorage(rootfsDir)
            if (!storage.file.isFile) return@withOperationLock false
            val sqliteSnapshot = runCatching {
                SQLiteDatabase.openDatabase(
                    storage.file.path,
                    null,
                    SQLiteDatabase.OPEN_READONLY,
                ).use { db ->
                    val store = readSqliteRow(db, storage, storage.store)
                    val state = readSqliteRow(db, storage, storage.state)
                    if (store == null || state == null) null else store to state
                }
            }.getOrNull() ?: return@withOperationLock false
            val (store, state) = sqliteSnapshot
            if (state.optJSONObject("lastGood")?.optString(OPENAI_PROVIDER) != profileId) {
                return@withOperationLock false
            }
            val order = state.optJSONObject("order")?.optJSONArray(OPENAI_PROVIDER)
                ?: return@withOperationLock false
            if (order.length() != 1 || order.optString(0) != profileId) {
                return@withOperationLock false
            }
            val credential = store.optJSONObject("profiles")?.optJSONObject(profileId)
                ?: return@withOperationLock false
            if (credential.optString("provider").trim().lowercase() != OPENAI_PROVIDER) {
                return@withOperationLock false
            }
            if (credentialType(credential) != expectedCredentialType) {
                return@withOperationLock false
            }
            when (expectedCredentialType) {
                CredentialType.OAUTH -> isUsableOAuth(credential)
                CredentialType.API_KEY -> isUsableApiKey(credential)
            }
        }
    }

    /**
     * Migrates legacy OpenAI/Codex credentials without merging raw stores first. The completion callback
     * deliberately runs after SQLite read-back verification, atomic JSON replacement, and legacy-reference
     * cleanup verification.
     */
    fun migrateCanonicalOpenAiProfiles(
        rootfsDir: File,
        preferenceApiKey: String? = null,
        preferredCredentialType: CredentialType? = null,
        markComplete: () -> Unit,
    ): MigrationResult {
        return withOperationLock {
            migrateCanonicalOpenAiProfilesUnlocked(
                rootfsDir = rootfsDir,
                preferenceApiKey = preferenceApiKey,
                preferredCredentialType = preferredCredentialType,
                markComplete = markComplete,
                hooks = MigrationHooks(),
            )
        }
    }

    internal fun migrateCanonicalOpenAiProfiles(
        rootfsDir: File,
        preferenceApiKey: String? = null,
        preferredCredentialType: CredentialType? = null,
        markComplete: () -> Unit,
        hooks: MigrationHooks,
    ): MigrationResult {
        return withOperationLock {
            migrateCanonicalOpenAiProfilesUnlocked(
                rootfsDir = rootfsDir,
                preferenceApiKey = preferenceApiKey,
                preferredCredentialType = preferredCredentialType,
                markComplete = markComplete,
                hooks = hooks,
            )
        }
    }

    private fun migrateCanonicalOpenAiProfilesUnlocked(
        rootfsDir: File,
        preferenceApiKey: String?,
        preferredCredentialType: CredentialType?,
        markComplete: () -> Unit,
        hooks: MigrationHooks,
    ): MigrationResult {
        return performCanonicalMutation(rootfsDir, preferenceApiKey, hooks) { snapshot ->
            val payload = buildCanonicalPayload(snapshot, preferredCredentialType)
            if (preferredCredentialType != null) {
                check(payload.activeCredentialType == preferredCredentialType) {
                    "Canonical OpenAI auth state did not contain a usable $preferredCredentialType credential."
                }
            }
            CanonicalMutation(payload) { changed ->
                verifyNoLegacyActiveReferences(payload.store, payload.state)
                markComplete()
                MigrationResult(changed, payload.activeCredentialType, payload.activeProfileId)
            }
        }
    }

    fun writeCodexOAuthCredentials(
        rootfsDir: File,
        accessToken: String,
        refreshToken: String,
        expires: Long,
        accountId: String,
    ) {
        writeCodexOAuthCredentials(rootfsDir, accessToken, refreshToken, expires, accountId, MigrationHooks())
    }

    internal fun writeCodexOAuthCredentials(
        rootfsDir: File,
        accessToken: String,
        refreshToken: String,
        expires: Long,
        accountId: String,
        hooks: MigrationHooks,
    ) {
        withOperationLock {
            performCanonicalMutation(rootfsDir, hooks = hooks) { snapshot ->
                val payload = buildCredentialMutationPayload(snapshot)
                payload.store.getJSONObject("profiles").put(
                    CANONICAL_CODEX_PROFILE_ID,
                    JSONObject().apply {
                        put("type", "oauth")
                        put("provider", OPENAI_PROVIDER)
                        put("access", accessToken)
                        put("refresh", refreshToken)
                        put("expires", expires)
                        put("accountId", accountId)
                    },
                )
                CanonicalMutation(payload) { Unit }
            }
        }
    }

    fun writeOpenAiApiKey(rootfsDir: File, apiKey: String) {
        writeOpenAiApiKey(rootfsDir, apiKey, MigrationHooks())
    }

    internal fun writeOpenAiApiKey(
        rootfsDir: File,
        apiKey: String,
        hooks: MigrationHooks,
    ) {
        require(apiKey.isNotBlank()) { "OpenAI API key must not be blank." }
        withOperationLock {
            performCanonicalMutation(rootfsDir, apiKey, hooks) { snapshot ->
                val payload = buildCredentialMutationPayload(snapshot)
                payload.store.getJSONObject("profiles").put(
                    CANONICAL_API_KEY_PROFILE_ID,
                    JSONObject().apply {
                        put("type", "api_key")
                        put("provider", OPENAI_PROVIDER)
                        put("key", apiKey.trim())
                    },
                )
                CanonicalMutation(payload) { Unit }
            }
        }
    }

    fun upsertNonOpenAiProfile(
        rootfsDir: File,
        profileId: String,
        credential: JSONObject,
        makeLastGood: Boolean = false,
    ) {
        upsertNonOpenAiProfile(rootfsDir, profileId, credential, makeLastGood, MigrationHooks())
    }

    internal fun upsertNonOpenAiProfile(
        rootfsDir: File,
        profileId: String,
        credential: JSONObject,
        makeLastGood: Boolean,
        hooks: MigrationHooks,
    ) {
        require(profileId.isNotBlank() && profileId == profileId.trim()) {
            "Non-OpenAI auth profile id must be non-blank and normalized."
        }
        val storedCredential = credential.deepCopy()
        val provider = storedCredential.optString("provider").trim()
        require(provider.isNotEmpty()) { "Non-OpenAI auth profile provider must not be blank." }
        require(storedCredential.optString("type").trim().isNotEmpty()) {
            "Non-OpenAI auth profile type must not be blank."
        }
        require(!isOpenAiProvider(storedCredential)) {
            "OpenAI credentials must use the canonical OpenAI auth APIs."
        }
        withOperationLock {
            performNonOpenAiProfileMutation(rootfsDir, hooks) { store, state ->
                store.getJSONObject("profiles").put(profileId, storedCredential.deepCopy())
                if (makeLastGood) {
                    val lastGood = state.optJSONObject("lastGood")
                        ?: JSONObject().also { state.put("lastGood", it) }
                    lastGood.put(provider, profileId)
                }
            }
        }
    }

    fun removeNonOpenAiProfile(rootfsDir: File, profileId: String): Boolean {
        return removeNonOpenAiProfile(rootfsDir, profileId, MigrationHooks())
    }

    internal fun removeNonOpenAiProfile(
        rootfsDir: File,
        profileId: String,
        hooks: MigrationHooks,
    ): Boolean {
        require(profileId.isNotBlank() && profileId == profileId.trim()) {
            "Non-OpenAI auth profile id must be non-blank and normalized."
        }
        return withOperationLock {
            performNonOpenAiProfileMutation(rootfsDir, hooks) { store, state ->
                val profiles = store.getJSONObject("profiles")
                val existing = profiles.optJSONObject(profileId)
                    ?: return@performNonOpenAiProfileMutation false
                require(!isOpenAiProvider(existing)) {
                    "OpenAI credentials must use the canonical OpenAI auth APIs."
                }
                profiles.remove(profileId)
                removeExactProfileReferences(state, profileId)
                true
            }
        }
    }

    fun activateOpenAiCredential(
        rootfsDir: File,
        mode: CredentialType,
        profileId: String,
    ) {
        activateOpenAiCredential(rootfsDir, mode, profileId, MigrationHooks())
    }

    internal fun activateOpenAiCredential(
        rootfsDir: File,
        mode: CredentialType,
        profileId: String,
        hooks: MigrationHooks,
    ) {
        require(profileId.isNotBlank()) { "OpenAI auth profile id must not be blank." }
        withOperationLock {
            performCanonicalMutation(rootfsDir, hooks = hooks) { snapshot ->
                val payload = buildCredentialMutationPayload(snapshot)
                val credential = payload.store.getJSONObject("profiles").optJSONObject(profileId)
                    ?: error("OpenAI auth profile $profileId does not exist.")
                check(isOpenAiProvider(credential)) { "Auth profile $profileId is not an OpenAI credential." }
                check(credentialType(credential) == mode) {
                    "OpenAI auth profile $profileId does not match requested credential mode $mode."
                }
                check(
                    when (mode) {
                        CredentialType.OAUTH -> isUsableOAuth(credential)
                        CredentialType.API_KEY -> isUsableApiKey(credential)
                    },
                ) { "OpenAI auth profile $profileId is not usable." }
                selectActiveProfile(payload.state, profileId)
                CanonicalMutation(
                    payload.copy(activeCredentialType = mode, activeProfileId = profileId),
                ) { Unit }
            }
        }
    }

    fun resetCodexOAuthProfiles(rootfsDir: File): ResetResult {
        return resetCodexOAuthProfiles(rootfsDir, MigrationHooks())
    }

    internal fun resetCodexOAuthProfiles(rootfsDir: File, hooks: MigrationHooks): ResetResult {
        return withOperationLock {
            performCanonicalMutation(rootfsDir, hooks = hooks) { snapshot ->
                val payload = buildCredentialMutationPayload(snapshot)
                val profiles = payload.store.getJSONObject("profiles")
                val removed = collectCredentialCandidates(snapshot)
                    .asSequence()
                    .filter { credentialType(it.credential) == CredentialType.OAUTH }
                    .map { it.profileId }
                    .filterTo(linkedSetOf()) { profileId ->
                        profileId.substringAfter(':', "default") in setOf("default", "codex")
                    }
                if (profiles.remove(CANONICAL_CODEX_PROFILE_ID) != null) {
                    removed += CANONICAL_CODEX_PROFILE_ID
                }
                if (removed.isEmpty()) {
                    CanonicalMutation(null) {
                        ResetResult(false, emptySet(), profiles.keys().asSequence().toSet())
                    }
                } else {
                    val kept = profiles.keys().asSequence().toSet()
                    val activeProfileRemoved = activeProfileId(payload.state) in removed
                    pruneProfileReferences(payload.state, removed, kept)
                    if (activeProfileRemoved) clearOpenAiSelection(payload.state)
                    CanonicalMutation(payload) { ResetResult(true, removed, kept) }
                }
            }
        }
    }

    fun removeOpenAiApiKey(rootfsDir: File): ResetResult {
        return removeOpenAiApiKey(rootfsDir, MigrationHooks())
    }

    internal fun removeOpenAiApiKey(rootfsDir: File, hooks: MigrationHooks): ResetResult {
        return withOperationLock {
            performCanonicalMutation(rootfsDir, hooks = hooks) { snapshot ->
                removeCredentialTypeMutation(snapshot, CredentialType.API_KEY)
            }
        }
    }

    fun captureOpenAiApiKeySnapshot(rootfsDir: File): OpenAiApiKeySnapshot {
        return withOperationLock {
            performConsistentRead(rootfsDir) { snapshot ->
                val payload = buildCredentialMutationPayload(snapshot)
                OpenAiApiKeySnapshot(
                    credential = payload.store
                        .getJSONObject("profiles")
                        .optJSONObject(CANONICAL_API_KEY_PROFILE_ID)
                        ?.deepCopy(),
                    wasActive = activeProfileId(payload.state) == CANONICAL_API_KEY_PROFILE_ID,
                )
            }
        }
    }

    fun restoreOpenAiApiKeySnapshot(
        rootfsDir: File,
        previous: OpenAiApiKeySnapshot,
    ) {
        withOperationLock {
            performCanonicalMutation(rootfsDir, hooks = MigrationHooks()) { snapshot ->
                val payload = buildCredentialMutationPayload(snapshot)
                val profiles = payload.store.getJSONObject("profiles")
                if (previous.credential == null) {
                    profiles.remove(CANONICAL_API_KEY_PROFILE_ID)
                } else {
                    profiles.put(CANONICAL_API_KEY_PROFILE_ID, previous.credential.deepCopy())
                }
                if (previous.wasActive && previous.credential != null) {
                    selectActiveProfile(payload.state, CANONICAL_API_KEY_PROFILE_ID)
                } else if (activeProfileId(payload.state) == CANONICAL_API_KEY_PROFILE_ID) {
                    clearOpenAiSelection(payload.state)
                }
                pruneStateToExistingProfiles(payload.state, profiles)
                CanonicalMutation(payload) { Unit }
            }
        }
    }

    fun captureOpenAiSelectionSnapshot(rootfsDir: File): OpenAiSelectionSnapshot {
        return withOperationLock {
            performConsistentRead(rootfsDir, read = ::captureOpenAiSelectionSnapshotUnlocked)
        }
    }

    fun restoreOpenAiSelectionSnapshot(
        rootfsDir: File,
        previous: OpenAiSelectionSnapshot,
    ) {
        withOperationLock {
            val storage = resolveAuthStorage(rootfsDir)
            val databaseFile = storage.file
            databaseFile.parentFile?.mkdirs()
            SQLiteDatabase.openOrCreateDatabase(databaseFile, null).use { db ->
                db.rawQuery("PRAGMA busy_timeout = 5000", null).use { it.moveToFirst() }
                db.execSQL("PRAGMA synchronous = NORMAL")
                db.beginTransaction()
                var before: RawAuthSnapshot? = null
                var mirrorPersisted = false
                try {
                    prepareAuthStorage(db, storage)
                    val currentSnapshot = readRawSnapshotInTransaction(rootfsDir, db, storage, null)
                    before = currentSnapshot
                    val store = selectSelectionRestoreStoreBase(currentSnapshot)
                    val state = selectStateBase(currentSnapshot)
                    restoreSelectionContainer(state, "lastGood", previous.lastGood)
                    restoreSelectionContainer(state, "order", previous.order)
                    persistSelectionOnlyInTransaction(
                        rootfsDir,
                        db,
                        storage,
                        currentSnapshot,
                        store,
                        state,
                    )
                    mirrorPersisted = true
                    val after = readRawSnapshotInTransaction(rootfsDir, db, storage, null)
                    check(selectionSnapshotEquals(captureOpenAiSelectionSnapshotUnlocked(after), previous)) {
                        "OpenClaw OpenAI auth selection read-back mismatch."
                    }
                    check(jsonContentEquals(after.sqliteStore, store)) {
                        "OpenClaw credential payload changed while restoring OpenAI selection."
                    }
                    db.setTransactionSuccessful()
                } catch (failure: Throwable) {
                    if (mirrorPersisted) {
                        try {
                            restoreJsonSnapshot(rootfsDir, before?.jsonStore)
                        } catch (rollbackFailure: Throwable) {
                            failure.addSuppressed(rollbackFailure)
                        }
                    }
                    throw failure
                } finally {
                    db.endTransaction()
                }
            }
        }
    }

    private fun captureOpenAiSelectionSnapshotUnlocked(
        snapshot: RawAuthSnapshot,
    ): OpenAiSelectionSnapshot {
        val state = selectStateBase(snapshot)
        return OpenAiSelectionSnapshot(
            lastGood = captureSelectionContainer(state, "lastGood"),
            order = captureSelectionContainer(state, "order"),
        )
    }

    private fun captureSelectionContainer(
        state: JSONObject,
        containerName: String,
    ): SelectionContainerSnapshot {
        val source = state.optJSONObject(containerName)
        val values = JSONObject()
        if (source != null) {
            for (provider in listOf(OPENAI_PROVIDER, LEGACY_CODEX_OAUTH_PROVIDER)) {
                if (source.has(provider)) {
                    values.put(provider, deepCopyJsonValue(source.get(provider)))
                }
            }
        }
        return SelectionContainerSnapshot(
            wasPresent = state.has(containerName) && source != null,
            values = values,
        )
    }

    private fun restoreSelectionContainer(
        state: JSONObject,
        containerName: String,
        previous: SelectionContainerSnapshot,
    ) {
        var container = state.optJSONObject(containerName)
        if (container == null && previous.wasPresent) {
            container = JSONObject().also { state.put(containerName, it) }
        }
        if (container != null) {
            for (provider in listOf(OPENAI_PROVIDER, LEGACY_CODEX_OAUTH_PROVIDER)) {
                if (previous.values.has(provider)) {
                    container.put(provider, deepCopyJsonValue(previous.values.get(provider)))
                } else {
                    container.remove(provider)
                }
            }
            if (!previous.wasPresent && container.length() == 0) {
                state.remove(containerName)
            }
        }
    }

    private fun selectionSnapshotEquals(
        left: OpenAiSelectionSnapshot,
        right: OpenAiSelectionSnapshot,
    ): Boolean {
        return left.lastGood.wasPresent == right.lastGood.wasPresent &&
            left.order.wasPresent == right.order.wasPresent &&
            jsonContentEquals(left.lastGood.values, right.lastGood.values) &&
            jsonContentEquals(left.order.values, right.order.values)
    }

    private fun removeCredentialTypeMutation(
        snapshot: RawAuthSnapshot,
        type: CredentialType,
    ): CanonicalMutation<ResetResult> {
        val payload = buildCredentialMutationPayload(snapshot)
        val profiles = payload.store.getJSONObject("profiles")
        val removed = collectCredentialCandidates(snapshot)
            .filter { credentialType(it.credential) == type }
            .mapTo(linkedSetOf()) { it.profileId }
        for (profileId in profiles.keys().asSequence().toList()) {
            val credential = profiles.optJSONObject(profileId) ?: continue
            if (credentialType(credential) == type && isOpenAiProvider(credential)) {
                profiles.remove(profileId)
                removed += profileId
            }
        }
        if (removed.isEmpty()) {
            return CanonicalMutation(null) {
                ResetResult(false, emptySet(), profiles.keys().asSequence().toSet())
            }
        }

        val kept = profiles.keys().asSequence().toSet()
        val activeProfileRemoved = activeProfileId(payload.state) in removed
        pruneProfileReferences(payload.state, removed, kept)
        if (activeProfileRemoved) clearOpenAiSelection(payload.state)
        return CanonicalMutation(payload) { ResetResult(true, removed, kept) }
    }

    private fun buildCredentialMutationPayload(snapshot: RawAuthSnapshot): CanonicalPayload {
        val canonical = buildCanonicalPayload(snapshot, null)
        val state = selectStateBase(snapshot)
        removeLegacyProviderState(state)
        pruneStateToExistingProfiles(state, canonical.store.getJSONObject("profiles"))
        val activeProfileId = activeProfileId(state)
        return canonical.copy(
            state = state,
            activeCredentialType = credentialType(
                canonical.store.getJSONObject("profiles").optJSONObject(activeProfileId),
            ),
            activeProfileId = activeProfileId,
        )
    }

    private fun activeProfileId(state: JSONObject): String? {
        state.optJSONObject("lastGood")
            ?.optString(OPENAI_PROVIDER)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let { return it }
        val order = state.optJSONObject("order")?.optJSONArray(OPENAI_PROVIDER)
        return order?.optString(0)?.trim()?.takeIf { it.isNotEmpty() }
    }

    private fun buildCanonicalPayload(
        snapshot: RawAuthSnapshot,
        preferredCredentialType: CredentialType?,
    ): CanonicalPayload {
        val candidates = collectCredentialCandidates(snapshot)
        val allOAuthCandidates = candidates.filter {
            credentialType(it.credential) == CredentialType.OAUTH
        }
        val oauthCandidates = allOAuthCandidates.filter { isUsableOAuth(it.credential) }
        val profileApiKeyCandidates = candidates.filter {
            credentialType(it.credential) == CredentialType.API_KEY && isUsableApiKey(it.credential)
        }
        val apiKeyCandidates = if (profileApiKeyCandidates.isNotEmpty()) {
            profileApiKeyCandidates
        } else {
            snapshot.preferenceApiKey?.let { key ->
                listOf(
                    CredentialCandidate(
                        source = CandidateSource.PREFERENCE,
                        profileId = CANONICAL_API_KEY_PROFILE_ID,
                        credential = JSONObject().apply {
                            put("type", "api_key")
                            put("provider", OPENAI_PROVIDER)
                            put("key", key)
                        },
                    ),
                )
            }.orEmpty()
        }

        val profiles = mergePreservedProfiles(snapshot)
        val store = JSONObject().put("version", 1).put("profiles", profiles)
        val state = selectStateBase(snapshot)
        removeLegacyProviderState(state)

        val oauthByIdentity = allOAuthCandidates.groupBy(::oauthIdentity)
        val bestOAuthByIdentity = oauthByIdentity.mapValues { (_, identityCandidates) ->
            identityCandidates.maxWithOrNull(oauthCandidateComparator())!!
        }
        val usableOAuthByIdentity = oauthCandidates.groupBy(::oauthIdentity).mapValues { (_, identityCandidates) ->
            identityCandidates.maxWithOrNull(oauthCandidateComparator())!!
        }
        val activeOAuth = chooseActiveOAuth(snapshot, oauthCandidates, usableOAuthByIdentity)
        if (activeOAuth != null) {
            profiles.put(CANONICAL_CODEX_PROFILE_ID, canonicalCredential(activeOAuth.credential))
        }
        preserveNamedOAuthProfiles(
            profiles = profiles,
            candidatesByIdentity = oauthByIdentity,
            bestByIdentity = bestOAuthByIdentity,
            usableIdentities = usableOAuthByIdentity.keys,
            activeOAuth = activeOAuth,
        )

        val activeApiKey = apiKeyCandidates.maxWithOrNull(apiKeyCandidateComparator())
        if (activeApiKey != null) {
            profiles.put(CANONICAL_API_KEY_PROFILE_ID, canonicalCredential(activeApiKey.credential))
        }

        val selectedType = resolveActiveCredentialType(
            snapshot = snapshot,
            preferred = preferredCredentialType,
            hasOAuth = activeOAuth != null,
            hasApiKey = activeApiKey != null,
            candidates = oauthCandidates + profileApiKeyCandidates,
        )
        val activeProfileId = when (selectedType) {
            CredentialType.OAUTH -> CANONICAL_CODEX_PROFILE_ID
            CredentialType.API_KEY -> CANONICAL_API_KEY_PROFILE_ID
            null -> null
        }
        if (activeProfileId == null) clearOpenAiSelection(state) else selectActiveProfile(state, activeProfileId)
        pruneStateToExistingProfiles(state, profiles)
        return CanonicalPayload(store, state, selectedType, activeProfileId)
    }

    private fun collectCredentialCandidates(snapshot: RawAuthSnapshot): List<CredentialCandidate> {
        val candidates = mutableListOf<CredentialCandidate>()
        collectCredentialCandidates(snapshot.sqliteStore, CandidateSource.SQLITE, candidates)
        collectCredentialCandidates(snapshot.jsonStore, CandidateSource.JSON, candidates)
        return candidates
    }

    private fun collectCredentialCandidates(
        store: JSONObject?,
        source: CandidateSource,
        destination: MutableList<CredentialCandidate>,
    ) {
        val profiles = store?.optJSONObject("profiles") ?: return
        for (profileId in profiles.keys().asSequence().toList()) {
            val credential = profiles.optJSONObject(profileId) ?: continue
            if (!isOpenAiProvider(credential)) continue
            if (credentialType(credential) == null) continue
            destination += CredentialCandidate(source, profileId, credential.deepCopy())
        }
    }

    private fun mergePreservedProfiles(snapshot: RawAuthSnapshot): JSONObject {
        val profiles = JSONObject()
        for (store in listOf(snapshot.sqliteStore, snapshot.jsonStore)) {
            val sourceProfiles = store?.optJSONObject("profiles") ?: continue
            for (profileId in sourceProfiles.keys().asSequence().toList()) {
                val credential = sourceProfiles.optJSONObject(profileId) ?: continue
                if (isOpenAiProvider(credential) && credentialType(credential) != null) continue
                if (!profiles.has(profileId)) profiles.put(profileId, credential.deepCopy())
            }
        }
        return profiles
    }

    private fun chooseActiveOAuth(
        snapshot: RawAuthSnapshot,
        oauthCandidates: List<CredentialCandidate>,
        bestByIdentity: Map<String, CredentialCandidate>,
    ): CredentialCandidate? {
        if (oauthCandidates.isEmpty()) return null
        val activeIds = legacyActiveProfileIds(snapshot)
        val stateSelectedCandidate = activeIds.asSequence()
            .mapNotNull { activeId -> oauthCandidates.firstOrNull { it.profileId == activeId } }
            .firstOrNull()
        if (stateSelectedCandidate != null) {
            return bestByIdentity.getValue(oauthIdentity(stateSelectedCandidate))
        }
        return bestByIdentity.values.maxWithOrNull(oauthCandidateComparator())
    }

    private fun preserveNamedOAuthProfiles(
        profiles: JSONObject,
        candidatesByIdentity: Map<String, List<CredentialCandidate>>,
        bestByIdentity: Map<String, CredentialCandidate>,
        usableIdentities: Set<String>,
        activeOAuth: CredentialCandidate?,
    ) {
        val activeIdentity = activeOAuth?.let(::oauthIdentity)
        for ((identity, candidate) in bestByIdentity) {
            val namedCandidate = candidatesByIdentity.getValue(identity)
                .asSequence()
                .filter { it.profileId.substringAfter(':', "default") !in setOf("default", "codex", "api-key") }
                .sortedBy { it.profileId.substringAfter(':') }
                .firstOrNull()
            if (namedCandidate == null && (identity == activeIdentity || identity !in usableIdentities)) continue
            val preferredId = if (namedCandidate != null) {
                "openai:${sanitizeProfileSuffix(namedCandidate.profileId.substringAfter(':'))}"
            } else {
                "openai:account-${sanitizeProfileSuffix(identity.substringAfter(':'))}"
            }
            val profileId = allocateProfileId(profiles, preferredId)
            profiles.put(profileId, canonicalCredential(candidate.credential))
        }
    }

    private fun resolveActiveCredentialType(
        snapshot: RawAuthSnapshot,
        preferred: CredentialType?,
        hasOAuth: Boolean,
        hasApiKey: Boolean,
        candidates: List<CredentialCandidate>,
    ): CredentialType? {
        if (preferred == CredentialType.OAUTH && hasOAuth) return CredentialType.OAUTH
        if (preferred == CredentialType.API_KEY && hasApiKey) return CredentialType.API_KEY
        if (hasOAuth && !hasApiKey) return CredentialType.OAUTH
        if (hasApiKey && !hasOAuth) return CredentialType.API_KEY
        if (!hasOAuth && !hasApiKey) return null

        val activeIds = legacyActiveProfileIds(snapshot)
        for (profileId in activeIds) {
            val sqliteType = candidates.firstOrNull {
                it.source == CandidateSource.SQLITE && it.profileId == profileId
            }?.let { credentialType(it.credential) }
            if (sqliteType != null) return sqliteType
            val anyType = candidates.firstOrNull { it.profileId == profileId }?.let { credentialType(it.credential) }
            if (anyType != null) return anyType
        }
        return CredentialType.OAUTH
    }

    private fun selectStateBase(snapshot: RawAuthSnapshot): JSONObject {
        snapshot.sqliteState?.let { return it.deepCopy() }
        extractInlineState(snapshot.sqliteStore)?.let { return it }
        extractInlineState(snapshot.jsonStore)?.let { return it }
        return JSONObject()
    }

    private fun extractInlineState(store: JSONObject?): JSONObject? {
        if (store == null) return null
        val state = JSONObject()
        for (key in listOf("order", "lastGood", "usageStats")) {
            store.optJSONObject(key)?.let { state.put(key, it.deepCopy()) }
        }
        return state.takeIf { it.length() > 0 }
    }

    private fun legacyActiveProfileIds(snapshot: RawAuthSnapshot): List<String> {
        val state = snapshot.sqliteState
            ?: extractInlineState(snapshot.sqliteStore)
            ?: extractInlineState(snapshot.jsonStore)
            ?: return emptyList()
        val ids = linkedSetOf<String>()
        val lastGood = state.optJSONObject("lastGood")
        for (provider in listOf(OPENAI_PROVIDER, LEGACY_CODEX_OAUTH_PROVIDER)) {
            lastGood?.optString(provider)?.trim()?.takeIf { it.isNotEmpty() }?.let(ids::add)
        }
        val order = state.optJSONObject("order")
        for (provider in listOf(OPENAI_PROVIDER, LEGACY_CODEX_OAUTH_PROVIDER)) {
            val profileIds = order?.optJSONArray(provider) ?: continue
            for (index in 0 until profileIds.length()) {
                profileIds.optString(index).trim().takeIf { it.isNotEmpty() }?.let(ids::add)
            }
        }
        return ids.toList()
    }

    private fun selectStoreBase(snapshot: RawAuthSnapshot): JSONObject {
        snapshot.sqliteStore?.let { return it.deepCopy() }
        snapshot.jsonStore?.let { mirror ->
            return mirror.deepCopy().apply {
                remove("lastGood")
                remove("order")
                remove("usageStats")
            }
        }
        return JSONObject()
            .put("version", 1)
            .put("profiles", JSONObject())
    }

    private fun selectSelectionRestoreStoreBase(snapshot: RawAuthSnapshot): JSONObject {
        val store = selectStoreBase(snapshot)
        if (snapshot.sqliteStore == null) return store
        val profiles = store.optJSONObject("profiles")
            ?: JSONObject().also { store.put("profiles", it) }
        val jsonProfiles = snapshot.jsonStore?.optJSONObject("profiles") ?: return store
        for (profileId in jsonProfiles.keys().asSequence().toList()) {
            if (profiles.has(profileId)) continue
            val credential = jsonProfiles.optJSONObject(profileId) ?: continue
            if (isOpenAiProvider(credential)) continue
            profiles.put(profileId, credential.deepCopy())
        }
        return store
    }

    private fun persistSelectionOnlyInTransaction(
        rootfsDir: File,
        db: SQLiteDatabase,
        storage: AuthSqliteStorage,
        snapshot: RawAuthSnapshot,
        store: JSONObject,
        state: JSONObject,
        hooks: MigrationHooks = MigrationHooks(),
    ) {
        val mirror = buildJsonMirror(store, state)
        val sqliteChanged = !jsonContentEquals(snapshot.sqliteStore, store) ||
            !jsonContentEquals(snapshot.sqliteState, state)
        val jsonChanged = if (storage.shared) {
            authProfilesJsonFile(rootfsDir).isFile
        } else {
            !jsonContentEquals(snapshot.jsonStore, mirror)
        }
        var jsonReplaced = false
        try {
            if (sqliteChanged) {
                upsertJsonRow(db, storage, storage.store, store)
                hooks.afterStoreUpsert?.invoke()
                upsertJsonRow(db, storage, storage.state, state)
            }
            verifySqlitePayload(db, storage, store, state)
            if (jsonChanged) {
                if (storage.shared) {
                    retireLegacyJsonStore(rootfsDir, hooks)
                } else {
                    writeJsonStoreAtomically(rootfsDir, mirror, hooks)
                }
                jsonReplaced = true
            }
            if (storage.shared) {
                check(!authProfilesJsonFile(rootfsDir).exists()) {
                    "Retired OpenClaw auth JSON store still exists after shared-state write."
                }
            } else {
                val verifiedMirror = readJsonStore(rootfsDir)
                check(jsonContentEquals(verifiedMirror, mirror)) {
                    "OpenClaw auth JSON mirror selection read-back mismatch."
                }
            }
        } catch (failure: Throwable) {
            if (jsonReplaced) {
                try {
                    restoreJsonSnapshot(rootfsDir, snapshot.jsonStore)
                } catch (rollbackFailure: Throwable) {
                    failure.addSuppressed(rollbackFailure)
                }
            }
            throw failure
        }
    }

    private fun persistCanonicalPayloadInTransaction(
        rootfsDir: File,
        db: SQLiteDatabase,
        storage: AuthSqliteStorage,
        snapshot: RawAuthSnapshot,
        payload: CanonicalPayload,
        hooks: MigrationHooks,
    ): Boolean {
        verifyNoLegacyActiveReferences(payload.store, payload.state)
        val sqliteChanged = !jsonContentEquals(snapshot.sqliteStore, payload.store) ||
            !jsonContentEquals(snapshot.sqliteState, payload.state)
        val mirror = buildJsonMirror(payload.store, payload.state)
        val jsonChanged = if (storage.shared) {
            authProfilesJsonFile(rootfsDir).isFile
        } else {
            !jsonContentEquals(snapshot.jsonStore, mirror)
        }
        var jsonReplaced = false
        try {
            if (sqliteChanged) {
                upsertJsonRow(db, storage, storage.store, payload.store)
                hooks.afterStoreUpsert?.invoke()
                upsertJsonRow(db, storage, storage.state, payload.state)
            }
            verifySqlitePayload(db, storage, payload.store, payload.state)
            if (jsonChanged) {
                if (storage.shared) {
                    retireLegacyJsonStore(rootfsDir, hooks)
                } else {
                    writeJsonStoreAtomically(rootfsDir, mirror, hooks)
                }
                jsonReplaced = true
            }
            if (storage.shared) {
                check(!authProfilesJsonFile(rootfsDir).exists()) {
                    "Retired OpenClaw auth JSON store still exists after shared-state write."
                }
            } else {
                val verifiedMirror = readJsonStore(rootfsDir)
                check(jsonContentEquals(verifiedMirror, mirror)) {
                    "OpenClaw auth JSON mirror read-back mismatch."
                }
                verifyCanonicalProfilesInMirror(payload.store, verifiedMirror)
            }
            return sqliteChanged || jsonChanged
        } catch (failure: Throwable) {
            if (jsonReplaced) {
                try {
                    restoreJsonSnapshot(rootfsDir, snapshot.jsonStore)
                } catch (rollbackFailure: Throwable) {
                    failure.addSuppressed(rollbackFailure)
                }
            }
            throw failure
        }
    }

    private fun restoreJsonSnapshot(rootfsDir: File, store: JSONObject?) {
        val authFile = authProfilesJsonFile(rootfsDir)
        if (store == null) {
            if (authFile.isFile) {
                check(authFile.delete()) { "Failed to remove OpenClaw auth JSON mirror during rollback." }
            }
        } else {
            writeJsonStoreAtomically(rootfsDir, store, MigrationHooks())
        }
    }


    private fun upsertJsonRow(
        db: SQLiteDatabase,
        storage: AuthSqliteStorage,
        row: AuthSqliteRowSpec,
        payload: JSONObject,
        timestamp: Long = System.currentTimeMillis(),
    ) {
        val values = ContentValues().apply {
            put(row.keyColumn, row.rowKey)
            put(row.jsonColumn, payload.toString())
            put(storage.timestampColumn, timestamp)
        }
        val rowId = db.insertWithOnConflict(
            row.table,
            null,
            values,
            SQLiteDatabase.CONFLICT_REPLACE,
        )
        check(rowId != -1L) { "OpenClaw auth SQLite upsert failed for ${row.table}." }
    }

    private fun verifySqlitePayload(
        db: SQLiteDatabase,
        storage: AuthSqliteStorage,
        store: JSONObject,
        state: JSONObject,
    ) {
        val actualStore = readSqliteRow(db, storage, storage.store)
        val actualState = readSqliteRow(db, storage, storage.state)
        check(jsonContentEquals(actualStore, store)) { "OpenClaw auth profile store read-back mismatch." }
        check(jsonContentEquals(actualState, state)) { "OpenClaw auth profile state read-back mismatch." }
        val profiles = actualStore?.optJSONObject("profiles") ?: error("OpenClaw auth profile store has no profiles.")
        val selectedIds = selectedProfileIds(actualState)
        check(selectedIds.all { profiles.optJSONObject(it) != null }) {
            "OpenClaw auth profile state references a missing profile."
        }
    }


    private fun buildJsonMirror(store: JSONObject, state: JSONObject): JSONObject {
        val mirror = store.deepCopy()
        for (key in state.keys().asSequence().toList()) {
            mirror.put(key, deepCopyJsonValue(state.get(key)))
        }
        return mirror
    }

    private fun retireLegacyJsonStore(rootfsDir: File, hooks: MigrationHooks): Boolean {
        val authFile = authProfilesJsonFile(rootfsDir)
        if (!authFile.exists()) return false
        hooks.beforeJsonReplace?.invoke()
        check(authFile.delete()) { "Failed to retire OpenClaw auth JSON store." }
        return true
    }

    private fun writeJsonStoreAtomically(
        rootfsDir: File,
        store: JSONObject,
        hooks: MigrationHooks,
    ) {
        val authFile = authProfilesJsonFile(rootfsDir)
        authFile.parentFile?.mkdirs()
        val tempFile = File.createTempFile("${authFile.name}.", ".tmp", authFile.parentFile)
        try {
            FileOutputStream(tempFile).use { output ->
                output.write(store.toString(2).toByteArray(StandardCharsets.UTF_8))
                output.fd.sync()
            }
            hooks.beforeJsonReplace?.invoke()
            java.nio.file.Files.move(
                tempFile.toPath(),
                authFile.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } finally {
            if (tempFile.exists()) tempFile.delete()
        }
    }

    private fun readJsonStore(rootfsDir: File): JSONObject? {
        val authFile = authProfilesJsonFile(rootfsDir)
        if (!authFile.isFile) return null
        return runCatching { JSONObject(authFile.readText()) }.getOrNull()
    }

    private fun readSqliteRow(
        storage: AuthSqliteStorage,
        row: AuthSqliteRowSpec,
    ): JSONObject? {
        if (!storage.file.isFile) return null
        return runCatching {
            SQLiteDatabase.openDatabase(
                storage.file.path,
                null,
                SQLiteDatabase.OPEN_READONLY,
            ).use { db ->
                readSqliteRow(db, storage, row)
            }
        }.getOrNull()
    }

    private fun readSqliteRow(
        db: SQLiteDatabase,
        storage: AuthSqliteStorage,
        row: AuthSqliteRowSpec,
    ): JSONObject? {
        db.rawQuery(
            "SELECT ${row.jsonColumn} FROM ${row.table} WHERE ${row.keyColumn} = ?",
            arrayOf(row.rowKey),
        ).use { cursor ->
            if (!cursor.moveToFirst()) return null
            return JSONObject(cursor.getString(0))
        }
    }

    private fun ensureAgentSchema(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS schema_meta (
              meta_key TEXT NOT NULL PRIMARY KEY,
              role TEXT NOT NULL,
              schema_version INTEGER NOT NULL,
              agent_id TEXT,
              app_version TEXT,
              created_at INTEGER NOT NULL,
              updated_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        assertCompatibleSchemaOwner(db)
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS cache_entries (
              scope TEXT NOT NULL,
              key TEXT NOT NULL,
              value_json TEXT,
              blob BLOB,
              expires_at INTEGER,
              updated_at INTEGER NOT NULL,
              PRIMARY KEY (scope, key)
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE INDEX IF NOT EXISTS idx_agent_cache_expiry
              ON cache_entries(scope, expires_at, key)
              WHERE expires_at IS NOT NULL
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE INDEX IF NOT EXISTS idx_agent_cache_updated
              ON cache_entries(scope, updated_at DESC, key)
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS auth_profile_store (
              store_key TEXT NOT NULL PRIMARY KEY,
              store_json TEXT NOT NULL,
              updated_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS auth_profile_state (
              state_key TEXT NOT NULL PRIMARY KEY,
              state_json TEXT NOT NULL,
              updated_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        val userVersion = db.rawQuery("PRAGMA user_version", null).use { cursor ->
            check(cursor.moveToFirst())
            cursor.getInt(0)
        }
        val metadataVersion = db.rawQuery(
            "SELECT schema_version FROM schema_meta WHERE meta_key = ?",
            arrayOf(PRIMARY_ROW_KEY),
        ).use { cursor ->
            if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }
        if (userVersion > AGENT_SCHEMA_VERSION || metadataVersion > AGENT_SCHEMA_VERSION) {
            return
        }
        db.execSQL("PRAGMA user_version = $AGENT_SCHEMA_VERSION")

        val now = System.currentTimeMillis()
        val values = ContentValues().apply {
            put("meta_key", PRIMARY_ROW_KEY)
            put("role", "agent")
            put("schema_version", AGENT_SCHEMA_VERSION)
            put("agent_id", AGENT_ID)
            putNull("app_version")
            put("created_at", now)
            put("updated_at", now)
        }
        db.insertWithOnConflict("schema_meta", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    private fun assertCompatibleSchemaOwner(db: SQLiteDatabase) {
        db.rawQuery(
            "SELECT role, agent_id FROM schema_meta WHERE meta_key = ?",
            arrayOf(PRIMARY_ROW_KEY),
        ).use { cursor ->
            if (!cursor.moveToFirst()) return
            val role = cursor.getString(0)
            val agentId = if (cursor.isNull(1)) null else cursor.getString(1)
            check(role == "agent") { "OpenClaw agent database has schema role $role; expected agent." }
            check(agentId.isNullOrBlank() || agentId == AGENT_ID) {
                "OpenClaw agent database belongs to agent $agentId; expected $AGENT_ID."
            }
        }
    }

    private fun removeLegacyProviderState(state: JSONObject) {
        state.optJSONObject("lastGood")?.remove(LEGACY_CODEX_OAUTH_PROVIDER)
        state.optJSONObject("order")?.remove(LEGACY_CODEX_OAUTH_PROVIDER)
    }

    private fun selectActiveProfile(state: JSONObject, profileId: String) {
        val lastGood = state.optJSONObject("lastGood") ?: JSONObject().also { state.put("lastGood", it) }
        val order = state.optJSONObject("order") ?: JSONObject().also { state.put("order", it) }
        lastGood.remove(LEGACY_CODEX_OAUTH_PROVIDER)
        order.remove(LEGACY_CODEX_OAUTH_PROVIDER)
        lastGood.put(OPENAI_PROVIDER, profileId)
        order.put(OPENAI_PROVIDER, JSONArray().put(profileId))
    }

    private fun clearOpenAiSelection(state: JSONObject) {
        state.optJSONObject("lastGood")?.apply {
            remove(OPENAI_PROVIDER)
            remove(LEGACY_CODEX_OAUTH_PROVIDER)
        }
        state.optJSONObject("order")?.apply {
            remove(OPENAI_PROVIDER)
            remove(LEGACY_CODEX_OAUTH_PROVIDER)
        }
    }

    private fun pruneStateToExistingProfiles(state: JSONObject, profiles: JSONObject) {
        val existing = profiles.keys().asSequence().toSet()
        val referenced = selectedProfileIds(state)
        val missing = referenced - existing
        if (missing.isNotEmpty()) pruneProfileReferences(state, missing, existing)
        val usageStats = state.optJSONObject("usageStats")
        if (usageStats != null) {
            for (profileId in usageStats.keys().asSequence().toList()) {
                if (profileId !in existing) usageStats.remove(profileId)
            }
        }
    }

    private fun pruneProfileReferences(
        state: JSONObject,
        removedProfileIds: Set<String>,
        keptProfileIds: Set<String>,
    ) {
        val lastGood = state.optJSONObject("lastGood")
        if (lastGood != null) {
            for (provider in lastGood.keys().asSequence().toList()) {
                val profileId = lastGood.optString(provider).trim()
                if (profileId in removedProfileIds || profileId !in keptProfileIds) lastGood.remove(provider)
            }
        }
        val order = state.optJSONObject("order")
        if (order != null) {
            for (provider in order.keys().asSequence().toList()) {
                val values = order.optJSONArray(provider) ?: continue
                val kept = JSONArray()
                for (index in 0 until values.length()) {
                    val profileId = values.optString(index).trim()
                    if (profileId.isNotEmpty() && profileId !in removedProfileIds && profileId in keptProfileIds) {
                        kept.put(profileId)
                    }
                }
                if (kept.length() == 0) order.remove(provider) else order.put(provider, kept)
            }
        }
        state.optJSONObject("usageStats")?.let { usageStats ->
            for (profileId in removedProfileIds) usageStats.remove(profileId)
        }
    }

    private fun removeExactProfileReferences(state: JSONObject, profileId: String) {
        state.optJSONObject("lastGood")?.let { lastGood ->
            for (provider in lastGood.keys().asSequence().toList()) {
                if (lastGood.optString(provider).trim() == profileId) lastGood.remove(provider)
            }
            if (lastGood.length() == 0) state.remove("lastGood")
        }
        state.optJSONObject("order")?.let { order ->
            for (provider in order.keys().asSequence().toList()) {
                val values = order.optJSONArray(provider) ?: continue
                val kept = JSONArray()
                for (index in 0 until values.length()) {
                    val selectedId = values.optString(index).trim()
                    if (selectedId.isNotEmpty() && selectedId != profileId) kept.put(selectedId)
                }
                if (kept.length() == 0) order.remove(provider) else order.put(provider, kept)
            }
            if (order.length() == 0) state.remove("order")
        }
        state.optJSONObject("usageStats")?.let { usageStats ->
            usageStats.remove(profileId)
            if (usageStats.length() == 0) state.remove("usageStats")
        }
    }

    private fun verifyNoLegacyActiveReferences(store: JSONObject, state: JSONObject) {
        val profiles = store.optJSONObject("profiles") ?: error("OpenClaw auth store has no profiles.")
        check(profiles.keys().asSequence().none { it.startsWith("$LEGACY_CODEX_OAUTH_PROVIDER:") }) {
            "Legacy OpenAI/Codex profile remains active after migration."
        }
        check(profiles.keys().asSequence().none { it == "openai:default" }) {
            "Ambiguous OpenAI default profile remains after migration."
        }
        check(state.optJSONObject("lastGood")?.has(LEGACY_CODEX_OAUTH_PROVIDER) != true)
        check(state.optJSONObject("order")?.has(LEGACY_CODEX_OAUTH_PROVIDER) != true)
        check(selectedProfileIds(state).all { profiles.optJSONObject(it) != null })
    }

    private fun verifyCanonicalProfilesInMirror(store: JSONObject, mirror: JSONObject?) {
        val storeProfiles = store.optJSONObject("profiles") ?: error("OpenClaw auth store has no profiles.")
        val mirrorProfiles = mirror?.optJSONObject("profiles") ?: error("OpenClaw auth JSON mirror has no profiles.")
        for (profileId in storeProfiles.keys().asSequence().toList()) {
            check(jsonContentEquals(storeProfiles.optJSONObject(profileId), mirrorProfiles.optJSONObject(profileId))) {
                "OpenClaw auth JSON mirror is missing canonical profile $profileId."
            }
        }
    }

    private fun selectedProfileIds(state: JSONObject?): Set<String> {
        if (state == null) return emptySet()
        val ids = linkedSetOf<String>()
        state.optJSONObject("lastGood")?.let { lastGood ->
            for (provider in lastGood.keys().asSequence().toList()) {
                lastGood.optString(provider).trim().takeIf { it.isNotEmpty() }?.let(ids::add)
            }
        }
        state.optJSONObject("order")?.let { order ->
            for (provider in order.keys().asSequence().toList()) {
                val values = order.optJSONArray(provider) ?: continue
                for (index in 0 until values.length()) {
                    values.optString(index).trim().takeIf { it.isNotEmpty() }?.let(ids::add)
                }
            }
        }
        return ids
    }

    private fun oauthIdentity(candidate: CredentialCandidate): String {
        return candidate.credential.optString("accountId").trim().takeIf { it.isNotEmpty() }
            ?.let { "account:$it" }
            ?: "profile:${candidate.profileId}"
    }

    private fun oauthCandidateComparator(): Comparator<CredentialCandidate> {
        return compareBy<CredentialCandidate> {
            (if (it.credential.optString("refresh").isNotBlank()) 1 else 0) +
                (if (it.credential.optString("access").isNotBlank()) 1 else 0)
        }.thenBy { it.credential.optLong("expires", Long.MIN_VALUE) }
            .thenBy { if (it.source == CandidateSource.SQLITE) 1 else 0 }
    }

    private fun apiKeyCandidateComparator(): Comparator<CredentialCandidate> {
        return compareBy<CredentialCandidate> {
            when (it.source) {
                CandidateSource.SQLITE -> 2
                CandidateSource.JSON -> 1
                CandidateSource.PREFERENCE -> 0
            }
        }
    }

    private fun isUsableOAuth(credential: JSONObject): Boolean {
        if (credential.optString("refresh").isNotBlank()) return true
        return credential.optString("access").isNotBlank() &&
            credential.optLong("expires", 0L) > System.currentTimeMillis()
    }

    private fun isUsableApiKey(credential: JSONObject): Boolean {
        return credential.optString("key").isNotBlank()
    }

    private fun isOpenAiProvider(credential: JSONObject): Boolean {
        return credential.optString("provider").trim().lowercase() in
            setOf(OPENAI_PROVIDER, LEGACY_CODEX_OAUTH_PROVIDER)
    }

    private fun credentialType(credential: JSONObject?): CredentialType? {
        return when (credential?.optString("type")?.trim()?.lowercase()) {
            "oauth" -> CredentialType.OAUTH
            "api_key" -> CredentialType.API_KEY
            else -> null
        }
    }

    private fun canonicalCredential(credential: JSONObject): JSONObject {
        return credential.deepCopy().apply { put("provider", OPENAI_PROVIDER) }
    }

    private fun sanitizeProfileSuffix(value: String): String {
        val normalized = value.lowercase().map { character ->
            if (character.isLetterOrDigit() || character == '-' || character == '_') character else '-'
        }.joinToString("").trim('-')
        return normalized.ifBlank { "account" }
    }

    private fun allocateProfileId(profiles: JSONObject, preferredId: String): String {
        if (!profiles.has(preferredId)) return preferredId
        var suffix = 2
        while (profiles.has("$preferredId-$suffix")) suffix += 1
        return "$preferredId-$suffix"
    }

    private fun jsonContentEquals(left: JSONObject?, right: JSONObject?): Boolean {
        if (left == null || right == null) return left == null && right == null
        return canonicalJson(left) == canonicalJson(right)
    }

    private fun canonicalJson(value: Any?): String {
        return when (value) {
            null, JSONObject.NULL -> "null"
            is JSONObject -> value.keys().asSequence().toList().sorted().joinToString(",", "{", "}") { key ->
                JSONObject.quote(key) + ":" + canonicalJson(value.get(key))
            }
            is JSONArray -> (0 until value.length()).joinToString(",", "[", "]") { index ->
                canonicalJson(value.get(index))
            }
            is String -> JSONObject.quote(value)
            else -> value.toString()
        }
    }

    private fun JSONObject.deepCopy(): JSONObject = JSONObject(toString())

    private fun deepCopyJsonValue(value: Any): Any {
        return when (value) {
            is JSONObject -> value.deepCopy()
            is JSONArray -> JSONArray(value.toString())
            else -> value
        }
    }
}
