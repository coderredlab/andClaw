package com.coderred.andclaw.data.transfer
import com.coderred.andclaw.data.PreferencesManager
import com.coderred.andclaw.proroot.OpenAiCanonicalMigrationCoordinator

import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.util.zip.ZipInputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class TransferPreferencesRestoreData(
    val values: Map<String, String>,
)

interface TransferPreferencesRestorer {
    suspend fun snapshotCurrentState(): TransferPreferencesRestoreData
    suspend fun restore(restoreData: TransferPreferencesRestoreData)
}

fun interface TransferCanonicalMigration {
    suspend fun migrate()
}

internal class OpenAiCanonicalTransferMigrationAdapter(
    private val preferencesManager: PreferencesManager,
    private val rootfsDir: File,
) : TransferCanonicalMigration {
    override suspend fun migrate() {
        OpenAiCanonicalMigrationCoordinator(
            preferencesManager = preferencesManager,
            rootfsDir = rootfsDir,
        ).migrateIfNeeded(force = true)
    }
}

interface TransferGatewayQuiesceController {
    suspend fun quiesceForRestore(): Boolean

    suspend fun verifyRestoredGatewayStartable(wasGatewayActiveBeforeRestore: Boolean): TransferGatewayRestoreVerification

    suspend fun restorePreRestoreGatewayState(wasGatewayActiveBeforeRestore: Boolean)
}

sealed class TransferGatewayRestoreVerification {
    data object Success : TransferGatewayRestoreVerification()

    data class StructuralFailure(
        val message: String,
        val cause: Throwable? = null,
    ) : TransferGatewayRestoreVerification()

    data class TransientRuntimeFailure(
        val message: String,
        val cause: Throwable? = null,
    ) : TransferGatewayRestoreVerification()
}

class TransferRestoreRequest(
    val artifactFile: File,
    val password: CharArray,
    val expectedVersion: TransferVersionExpectation,
    val rootfsDir: File,
    val preferencesRestorer: TransferPreferencesRestorer,
    val gatewayController: TransferGatewayQuiesceController,
    val canonicalMigration: TransferCanonicalMigration,
    val allowVersionMismatch: Boolean = false,
) {
    override fun toString(): String = "TransferRestoreRequest(artifactFile=${artifactFile.name}, password=***)"
}

data class TransferRestoreResult(
    val restoredOpenClawFiles: List<String>,
    val restoredPreferenceKeys: List<String>,
    val warningMessage: String? = null,
)

class TransferRestoreException(
    message: String,
    cause: Throwable? = null,
) : IllegalArgumentException(message, cause)

class TransferRestoreRuntimeStartException(
    message: String,
    cause: Throwable? = null,
    val partialResult: TransferRestoreResult? = null,
) : IllegalStateException(message, cause)

object TransferRestoreManager {
    private const val ENTRY_MANIFEST = "manifest.json"
    private const val ENTRY_PREFERENCES = "preferences.json"
    private const val ENTRY_CHECKSUMS = "checksums.json"
    suspend fun restore(request: TransferRestoreRequest): TransferRestoreResult {
        val tempRoot = createTempDirectory(request.artifactFile.parentFile ?: request.rootfsDir, "transfer-restore")
        val decryptTempFile = File(tempRoot, "decrypted.zip")
        val stageDir = File(tempRoot, "stage")
        val liveSnapshotDir = File(tempRoot, "live-snapshot")

        var snapshot: LiveStateSnapshot? = null
        var wasGatewayActiveBeforeRestore = false
        var preRestoreGatewayStateNeedsRestore = false
        try {
            decryptTempFile.parentFile?.mkdirs()
            decryptArtifactToFile(request, decryptTempFile)

            extractZipToStage(
                zipFile = decryptTempFile,
                stageDir = stageDir,
            )

            val validatedPayload = validateStagedPayload(
                stageDir = stageDir,
                expectedVersion = request.expectedVersion,
                allowVersionMismatch = request.allowVersionMismatch,
            )

            wasGatewayActiveBeforeRestore = request.gatewayController.quiesceForRestore()
            preRestoreGatewayStateNeedsRestore = wasGatewayActiveBeforeRestore

            snapshot = createLiveSnapshot(
                rootfsDir = request.rootfsDir,
                preferencesRestorer = request.preferencesRestorer,
                snapshotDir = liveSnapshotDir,
            )

            try {
                swapStagedStateIntoLive(
                    stageDir = stageDir,
                    stagedOpenClawPaths = validatedPayload.restorableOpenClawPaths,
                    restoredPreferences = validatedPayload.preferences,
                    rootfsDir = request.rootfsDir,
                    preferencesRestorer = request.preferencesRestorer,
                )
            } catch (cause: Throwable) {
                val currentSnapshot = snapshot
                    ?: throw TransferRestoreException("Restore snapshot is unavailable for rollback", cause)
                rollback(
                    rootfsDir = request.rootfsDir,
                    snapshot = currentSnapshot,
                    preferencesRestorer = request.preferencesRestorer,
                )
                request.gatewayController.restorePreRestoreGatewayState(wasGatewayActiveBeforeRestore)
                preRestoreGatewayStateNeedsRestore = false
                throw TransferRestoreException("Failed to apply staged restore", cause)
            }

            try {
                request.canonicalMigration.migrate()
            } catch (cause: Throwable) {
                val currentSnapshot = snapshot
                    ?: throw TransferRestoreException("Restore snapshot is unavailable for rollback", cause)
                val rollbackFailure = rollbackAppliedState(
                    rootfsDir = request.rootfsDir,
                    snapshot = currentSnapshot,
                    preferencesRestorer = request.preferencesRestorer,
                    gatewayController = request.gatewayController,
                    wasGatewayActiveBeforeRestore = wasGatewayActiveBeforeRestore,
                )
                preRestoreGatewayStateNeedsRestore = false
                rollbackFailure?.let(cause::addSuppressed)
                if (cause is CancellationException) throw cause
                throw TransferRestoreException("Failed to migrate restored OpenAI state", cause)
            }

            val verification = try {
                request.gatewayController.verifyRestoredGatewayStartable(wasGatewayActiveBeforeRestore)
            } catch (cause: Throwable) {
                val currentSnapshot = snapshot
                    ?: throw TransferRestoreException("Restore snapshot is unavailable for rollback", cause)
                val rollbackFailure = rollbackAppliedState(
                    rootfsDir = request.rootfsDir,
                    snapshot = currentSnapshot,
                    preferencesRestorer = request.preferencesRestorer,
                    gatewayController = request.gatewayController,
                    wasGatewayActiveBeforeRestore = wasGatewayActiveBeforeRestore,
                )
                preRestoreGatewayStateNeedsRestore = false
                rollbackFailure?.let(cause::addSuppressed)
                if (cause is CancellationException) throw cause
                throw TransferRestoreException("Failed to verify restored gateway startup", cause)
            }
            when (verification) {
                TransferGatewayRestoreVerification.Success -> Unit
                is TransferGatewayRestoreVerification.StructuralFailure -> {
                    val currentSnapshot = snapshot
                        ?: throw TransferRestoreException(
                            "Restore snapshot is unavailable for rollback",
                            verification.cause,
                        )
                    val rollbackFailure = rollbackAppliedState(
                        rootfsDir = request.rootfsDir,
                        snapshot = currentSnapshot,
                        preferencesRestorer = request.preferencesRestorer,
                        gatewayController = request.gatewayController,
                        wasGatewayActiveBeforeRestore = wasGatewayActiveBeforeRestore,
                    )
                    preRestoreGatewayStateNeedsRestore = false
                    val failure = TransferRestoreException(
                        "Failed to verify restored gateway startup: ${verification.message}",
                        verification.cause,
                    )
                    rollbackFailure?.let(failure::addSuppressed)
                    throw failure
                }
                is TransferGatewayRestoreVerification.TransientRuntimeFailure -> {
                    if (verification.message.contains("Gateway stop timed out after verification")) {
                        preRestoreGatewayStateNeedsRestore = false
                        return TransferRestoreResult(
                            restoredOpenClawFiles = validatedPayload.restorableOpenClawPaths.sorted(),
                            restoredPreferenceKeys = validatedPayload.preferences.keys.sorted(),
                            warningMessage = verification.message,
                        )
                    }

                    request.gatewayController.restorePreRestoreGatewayState(wasGatewayActiveBeforeRestore)
                    preRestoreGatewayStateNeedsRestore = false
                    throw TransferRestoreRuntimeStartException(
                        "Restored data applied but gateway startup verification failed transiently: ${verification.message}",
                        verification.cause,
                    )
                }
            }

            preRestoreGatewayStateNeedsRestore = false

            return TransferRestoreResult(
                restoredOpenClawFiles = validatedPayload.restorableOpenClawPaths.sorted(),
                restoredPreferenceKeys = validatedPayload.preferences.keys.sorted(),
            )
        } catch (cause: Throwable) {
            if (preRestoreGatewayStateNeedsRestore) {
                runCatching {
                    request.gatewayController.restorePreRestoreGatewayState(wasGatewayActiveBeforeRestore)
                }
            }
            throw cause
        } finally {
            request.password.fill('\u0000')
            tempRoot.deleteRecursively()
        }
    }

    private fun decryptArtifactToFile(request: TransferRestoreRequest, outputFile: File) {
        runCatching {
            TransferCrypto.decryptToFile(
                inputFile = request.artifactFile,
                outputFile = outputFile,
                password = request.password,
            )
        }.getOrElse { cause ->
            if (cause is IllegalArgumentException) {
                throw TransferRestoreException(cause.message ?: "Failed to decrypt transfer artifact", cause)
            }
            throw TransferRestoreException("Failed to decrypt transfer artifact", cause)
        }
    }

    private fun extractZipToStage(zipFile: File, stageDir: File) {
        stageDir.mkdirs()
        val stageCanonical = stageDir.canonicalFile
        var totalExtractedBytes = 0L

        ZipInputStream(zipFile.inputStream().buffered()).use { zipInput ->
            while (true) {
                val entry = zipInput.nextEntry ?: break
                val normalizedName = normalizeZipEntryName(entry.name)
                if (normalizedName == null) {
                    zipInput.closeEntry()
                    continue
                }

                val outFile = File(stageDir, normalizedName)
                val outCanonical = outFile.canonicalFile
                val isInsideDestination = outCanonical.path == stageCanonical.path ||
                    outCanonical.path.startsWith(stageCanonical.path + File.separator)
                if (!isInsideDestination) {
                    zipInput.closeEntry()
                    continue
                }

                if (entry.isDirectory) {
                    outFile.mkdirs()
                    zipInput.closeEntry()
                    continue
                }

                outFile.parentFile?.mkdirs()
                FileOutputStream(outFile).use { output ->
                    val buffer = ByteArray(32 * 1024)
                    while (true) {
                        val read = zipInput.read(buffer)
                        if (read < 0) break
                        totalExtractedBytes += read
                        if (totalExtractedBytes > TransferSizeLimits.MAX_TRANSFER_PAYLOAD_BYTES) {
                            throw TransferRestoreException(
                                "Transfer payload expands beyond the allowed size limit " +
                                    "(${TransferSizeLimits.MAX_TRANSFER_PAYLOAD_BYTES / 1024 / 1024}MB)"
                            )
                        }
                        output.write(buffer, 0, read)
                    }
                }
                zipInput.closeEntry()
            }
        }
    }

    private fun normalizeZipEntryName(rawName: String): String? {
        val trimmed = rawName
            .trim()
            .removePrefix("./")
            .removePrefix("/")
        if (trimmed.isEmpty()) return null

        val parts = trimmed
            .replace('\\', '/')
            .split('/')
            .filter { it.isNotEmpty() && it != "." }
        if (parts.isEmpty()) return null
        if (parts.any { it == ".." }) return null
        return parts.joinToString("/")
    }

    private fun validateStagedPayload(
        stageDir: File,
        expectedVersion: TransferVersionExpectation,
        allowVersionMismatch: Boolean,
    ): ValidatedPayload {
        val manifestFile = File(stageDir, ENTRY_MANIFEST)
        if (!manifestFile.exists() || !manifestFile.isFile) {
            throw TransferRestoreException("Transfer manifest is missing")
        }

        val checksumsFile = File(stageDir, ENTRY_CHECKSUMS)
        if (!checksumsFile.exists() || !checksumsFile.isFile) {
            throw TransferRestoreException("Transfer checksums are missing")
        }

        val preferencesFile = File(stageDir, ENTRY_PREFERENCES)
        if (!preferencesFile.exists() || !preferencesFile.isFile) {
            throw TransferRestoreException("Transfer preferences payload is missing")
        }

        val manifest = runCatching {
            TransferManifestJson.fromJson(manifestFile.readText())
        }.getOrElse { cause ->
            throw TransferRestoreException("Transfer manifest is malformed", cause)
        }

        val versionGate = TransferManifestContract.evaluateExactVersionGate(manifest, expectedVersion)
        if (!versionGate.accepted) {
            val reason = versionGate.reason ?: "Transfer version mismatch"
            if (!allowVersionMismatch || !isOverridableVersionMismatch(reason)) {
                throw TransferRestoreException(reason)
            }
        }

        val checksumsByPath = parseChecksums(checksumsFile)
        val stagedFilesByPath = collectStagedFilesByRelativePath(stageDir)

        val reservedEntries = setOf(ENTRY_MANIFEST, ENTRY_CHECKSUMS, ENTRY_PREFERENCES)
        val restorableOpenClawPaths = stagedFilesByPath.keys
            .filter { it !in reservedEntries }
            .sorted()

        validateRequiredEntries(manifest, restorableOpenClawPaths)

        restorableOpenClawPaths.forEach { path ->
            if (!TransferManifestContract.shouldIncludeExportPath(path)) {
                throw TransferRestoreException("Disallowed path found in transfer payload: $path")
            }
        }

        stagedFilesByPath
            .filterKeys { it != ENTRY_CHECKSUMS }
            .forEach { (path, file) ->
            val expectedChecksum = checksumsByPath[path]
                ?: throw TransferRestoreException("Missing checksum for payload entry: $path")
            val actualChecksum = TransferCrypto.sha256HexStreaming(file)
            if (!actualChecksum.equals(expectedChecksum, ignoreCase = true)) {
                throw TransferRestoreException("Checksum mismatch for payload entry: $path")
            }
            }

        val preferences = parsePreferences(preferencesFile)

        return ValidatedPayload(
            restorableOpenClawPaths = restorableOpenClawPaths,
            preferences = preferences,
        )
    }

    private fun parseChecksums(checksumsFile: File): Map<String, String> {
        val root = runCatching {
            JSONObject(checksumsFile.readText(StandardCharsets.UTF_8))
        }.getOrElse { cause ->
            throw TransferRestoreException("Transfer checksums are malformed", cause)
        }

        val out = linkedMapOf<String, String>()
        val keys = root.keys()
        while (keys.hasNext()) {
            val rawKey = keys.next()
            val key = normalizeZipEntryName(rawKey) ?: continue
            val value = root.optString(rawKey).trim()
            if (value.isNotEmpty()) {
                out[key] = value
            }
        }
        if (out.isEmpty()) {
            throw TransferRestoreException("Transfer checksums are malformed")
        }
        return out
    }

    private fun parsePreferences(preferencesFile: File): Map<String, String> {
        val root = runCatching {
            JSONObject(preferencesFile.readText(StandardCharsets.UTF_8))
        }.getOrElse { cause ->
            throw TransferRestoreException("Transfer preferences payload is malformed", cause)
        }

        val out = linkedMapOf<String, String>()
        val keys = root.keys()
        while (keys.hasNext()) {
            val key = keys.next().trim()
            if (key.isEmpty()) continue
            out[key] = root.optString(key, "")
        }
        return out.toMap()
    }

    private fun isOverridableVersionMismatch(reason: String): Boolean {
        return reason == "versionCode mismatch" || reason == "versionName mismatch"
    }

    private fun collectStagedFilesByRelativePath(stageDir: File): Map<String, File> {
        val stageCanonical = stageDir.canonicalFile
        val stageRootPath = stageCanonical.path

        val out = linkedMapOf<String, File>()
        stageCanonical.walkTopDown()
            .filter { it.isFile }
            .forEach { file ->
                val filePath = file.canonicalPath
                if (!filePath.startsWith(stageRootPath + File.separator)) return@forEach
                val relativePath = filePath
                    .removePrefix(stageRootPath)
                    .trimStart(File.separatorChar)
                    .replace(File.separatorChar, '/')
                val normalizedPath = normalizeZipEntryName(relativePath) ?: return@forEach
                out[normalizedPath] = file
            }
        return out.toMap()
    }

    private fun validateRequiredEntries(manifest: TransferManifest, restorableOpenClawPaths: List<String>) {
        val lowercasePaths = restorableOpenClawPaths.map { it.lowercase(java.util.Locale.US) }
        val missingRequiredPaths = manifest.includedSections.mapNotNull { section ->
            TransferManifestContract.REQUIRED_FIXED_PATHS_BY_SECTION[section]?.takeUnless { lowercasePaths.contains(it) }
        }
        if (missingRequiredPaths.isNotEmpty()) {
            throw TransferRestoreException(
                "Transfer payload is incomplete. Missing required files: ${missingRequiredPaths.joinToString()}"
            )
        }
    }

    private suspend fun createLiveSnapshot(
        rootfsDir: File,
        preferencesRestorer: TransferPreferencesRestorer,
        snapshotDir: File,
    ): LiveStateSnapshot {
        snapshotDir.mkdirs()
        val preferencesSnapshot = preferencesRestorer.snapshotCurrentState()
        val openClawFilesSnapshotDir = File(snapshotDir, "openclaw-files")
        openClawFilesSnapshotDir.mkdirs()

        collectCurrentLiveRestorablePaths(rootfsDir).forEach { relativePath ->
            val liveFile = File(rootfsDir, relativePath)
            if (!liveFile.exists() || !liveFile.isFile) return@forEach
            val snapshotFile = File(openClawFilesSnapshotDir, relativePath)
            snapshotFile.parentFile?.mkdirs()
            liveFile.copyTo(snapshotFile, overwrite = true)
        }

        return LiveStateSnapshot(
            snapshotRootDir = snapshotDir,
            preferences = preferencesSnapshot,
        )
    }

    private suspend fun swapStagedStateIntoLive(
        stageDir: File,
        stagedOpenClawPaths: List<String>,
        restoredPreferences: Map<String, String>,
        rootfsDir: File,
        preferencesRestorer: TransferPreferencesRestorer,
    ) {
        val stagedPathSetLower = stagedOpenClawPaths.map { it.lowercase(java.util.Locale.US) }.toSet()
        collectCurrentLiveRestorablePaths(rootfsDir)
            .filter { it.lowercase(java.util.Locale.US) !in stagedPathSetLower }
            .forEach { stalePath ->
                val file = File(rootfsDir, stalePath)
                file.delete()
                pruneEmptyParents(file.parentFile, rootfsDir)
            }

        stagedOpenClawPaths.forEach { relativePath ->
            val source = File(stageDir, relativePath)
            if (!source.exists() || !source.isFile) {
                throw TransferRestoreException("Missing staged payload entry: $relativePath")
            }
            val target = File(rootfsDir, relativePath)
            target.parentFile?.mkdirs()
            source.copyTo(target, overwrite = true)
        }

        preferencesRestorer.restore(
            TransferPreferencesRestoreData(
                values = restoredPreferences,
            ),
        )
    }

    private suspend fun rollback(
        rootfsDir: File,
        snapshot: LiveStateSnapshot,
        preferencesRestorer: TransferPreferencesRestorer,
    ) {
        collectCurrentLiveRestorablePaths(rootfsDir).forEach { relativePath ->
            val file = File(rootfsDir, relativePath)
            file.delete()
            pruneEmptyParents(file.parentFile, rootfsDir)
        }

        val snapshotOpenClawDir = File(snapshot.snapshotRootDir, "openclaw-files")
        if (snapshotOpenClawDir.exists()) {
            snapshotOpenClawDir.walkTopDown()
                .filter { it.isFile }
                .forEach { file ->
                    val relativePath = file.canonicalPath
                        .removePrefix(snapshotOpenClawDir.canonicalPath)
                        .trimStart(File.separatorChar)
                        .replace(File.separatorChar, '/')
                    val target = File(rootfsDir, relativePath)
                    target.parentFile?.mkdirs()
                    file.copyTo(target, overwrite = true)
                }
        }

        preferencesRestorer.restore(snapshot.preferences)
    }

    private suspend fun rollbackAppliedState(
        rootfsDir: File,
        snapshot: LiveStateSnapshot,
        preferencesRestorer: TransferPreferencesRestorer,
        gatewayController: TransferGatewayQuiesceController,
        wasGatewayActiveBeforeRestore: Boolean,
    ): Throwable? = withContext(NonCancellable) {
        var failure = runCatching {
            rollback(
                rootfsDir = rootfsDir,
                snapshot = snapshot,
                preferencesRestorer = preferencesRestorer,
            )
        }.exceptionOrNull()

        val gatewayFailure = runCatching {
            gatewayController.restorePreRestoreGatewayState(wasGatewayActiveBeforeRestore)
        }.exceptionOrNull()
        if (gatewayFailure != null) {
            val priorFailure = failure
            if (priorFailure == null) {
                failure = gatewayFailure
            } else {
                priorFailure.addSuppressed(gatewayFailure)
            }
        }
        failure
    }

    private fun collectCurrentLiveRestorablePaths(rootfsDir: File): List<String> {
        val rootCanonical = rootfsDir.canonicalFile
        val out = linkedSetOf<String>()

        val scanDirs = listOf(
            File(rootCanonical, "root/.openclaw"),
            File(rootCanonical, "root/.codex"),
        )

        scanDirs.forEach scanDir@{ dir ->
            if (!dir.exists() || !dir.isDirectory) return@scanDir
            dir.walkTopDown()
                .filter { it.isFile }
                .forEach eachFile@{ file ->
                    val relativePath = file.canonicalPath
                        .removePrefix(rootCanonical.path)
                        .trimStart(File.separatorChar)
                        .replace(File.separatorChar, '/')
                    if (TransferManifestContract.shouldIncludeExportPath(relativePath)) {
                        out += relativePath
                    }
                }
        }

        return out.toList().sorted()
    }


    private fun pruneEmptyParents(dir: File?, stopAt: File) {
        var current = dir
        val stopCanonical = stopAt.canonicalPath
        while (current != null && current.canonicalPath != stopCanonical) {
            val children = current.listFiles()
            if (children == null || children.isNotEmpty()) break
            current.delete()
            current = current.parentFile
        }
    }

    private fun createTempDirectory(parent: File, prefix: String): File {
        parent.mkdirs()
        return kotlin.io.path.createTempDirectory(parent.toPath(), prefix).toFile()
    }

    private data class LiveStateSnapshot(
        val snapshotRootDir: File,
        val preferences: TransferPreferencesRestoreData,
    )

    private data class ValidatedPayload(
        val restorableOpenClawPaths: List<String>,
        val preferences: Map<String, String>,
    )
}
