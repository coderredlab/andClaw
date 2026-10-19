package com.coderred.andclaw.proroot

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.os.SystemClock
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.util.JsonReader
import android.util.JsonToken
import java.io.File
import java.io.IOException
import java.io.StringReader

internal object OpenClawAgentLeaseRecovery {
    private const val OPENCLAW_STATE_SQLITE_PATH = "root/.openclaw/state/openclaw.sqlite"
    private const val LEASE_TABLE = "agent_database_leases"
    private const val GATEWAY_LEASE_TABLE = "state_leases"
    private const val GATEWAY_STATE_PATH = "/root/.openclaw/state/openclaw.sqlite"
    private const val MAX_SAFE_INTEGER = 9_007_199_254_740_991L
    private const val NANOS_PER_SECOND = 1_000_000_000L
    private const val MAX_OWNER_PID = 2_147_483_647L

    private data class Lease(val id: String, val pid: Int, val startTime: Long, val path: String)
    private data class GatewayLease(
        val owner: String,
        val payload: String,
        val expiresAt: Long?,
        val heartbeatAt: Long?,
        val createdAt: Long,
        val updatedAt: Long,
        val pid: Int,
    )

    private const val GATEWAY_LEASE_FILTER =
        "scope COLLATE BINARY = 'gateway-owner' AND lease_key COLLATE BINARY = 'global' " +
            "AND typeof(scope) = 'text' AND typeof(lease_key) = 'text' " +
            "AND typeof(owner) = 'text' AND length(owner) > 0 AND typeof(payload_json) = 'text' " +
            "AND typeof(expires_at) IN ('integer', 'null') " +
            "AND typeof(heartbeat_at) IN ('integer', 'null') " +
            "AND typeof(created_at) = 'integer' AND typeof(updated_at) = 'integer'"

    private const val DELETE_IMPOSSIBLE_LEASES_SQL =
        "DELETE FROM $LEASE_TABLE " +
            "WHERE typeof(owner_pid) = 'integer' " +
            "AND owner_pid BETWEEN 1 AND $MAX_OWNER_PID " +
            "AND typeof(owner_start_time) = 'integer' " +
            "AND owner_start_time > ?"

    fun recover(
        rootfsDir: File,
        foreignPrivateOwner: ((Int, String) -> Boolean)? = null,
        sampleBootUpperBoundTicks: () -> Long? = ::readBootUpperBoundTicks,
    ): Int {
        val databaseFile = File(rootfsDir.canonicalFile, OPENCLAW_STATE_SQLITE_PATH)
        if (!databaseFile.isFile) return 0

        SQLiteDatabase.openDatabase(databaseFile.path, null, SQLiteDatabase.OPEN_READWRITE).use { database ->
            database.beginTransaction()
            try {
                val gatewayLease = if (foreignPrivateOwner != null &&
                    databaseFile.absoluteFile == databaseFile.canonicalFile &&
                    hasLeaseTable(database, GATEWAY_LEASE_TABLE)
                ) {
                    readGatewayLease(database)?.takeIf {
                        foreignPrivateOwner(it.pid, GATEWAY_STATE_PATH)
                    }
                } else {
                    null
                }
                val hasAgentLeases = hasLeaseTable(database, LEASE_TABLE)
                // Sample only after this held write transaction has snapshotted sqlite_master:
                // no newer lease can appear while its recovery cutoff is being chosen.
                val upperBoundTicks = if (hasAgentLeases) sampleBootUpperBoundTicks() else null
                var deleted = if (hasAgentLeases && upperBoundTicks != null && upperBoundTicks > 0) {
                    database.compileStatement(DELETE_IMPOSSIBLE_LEASES_SQL).use { statement ->
                        statement.bindLong(1, upperBoundTicks)
                        statement.executeUpdateDelete()
                    }
                } else {
                    0
                }
                if (hasAgentLeases && foreignPrivateOwner != null) {
                    val candidates = mutableListOf<Lease>()
                    database.rawQuery(
                        "SELECT lease_id, owner_pid, owner_start_time, path FROM $LEASE_TABLE " +
                            "WHERE typeof(lease_id) = 'text' AND typeof(path) = 'text' " +
                            "AND typeof(owner_pid) = 'integer' AND owner_pid BETWEEN 1 AND $MAX_OWNER_PID " +
                            "AND typeof(owner_start_time) = 'integer' AND owner_start_time > 0",
                        null,
                    ).use { cursor ->
                        while (cursor.moveToNext()) {
                            val pid = cursor.getInt(1)
                            val path = cursor.getString(3)
                            if (foreignPrivateOwner(pid, path)) {
                                candidates.add(Lease(cursor.getString(0), pid, cursor.getLong(2), path))
                            }
                        }
                    }
                    // Finish the cursor before mutating its result set. The transaction still
                    // fences registration, including a new process reusing one of these PIDs.
                    if (candidates.isNotEmpty()) {
                        database.compileStatement(
                            "DELETE FROM $LEASE_TABLE WHERE lease_id = ? AND owner_pid = ? " +
                                "AND owner_start_time = ? AND path = ?",
                        ).use { statement ->
                            for (lease in candidates) {
                                if (!foreignPrivateOwner(lease.pid, lease.path)) return 0
                                statement.bindString(1, lease.id)
                                statement.bindLong(2, lease.pid.toLong())
                                statement.bindLong(3, lease.startTime)
                                statement.bindString(4, lease.path)
                                deleted += statement.executeUpdateDelete()
                            }
                        }
                    }
                }
                if (gatewayLease != null && foreignPrivateOwner != null) {
                    // Recheck native custody after every other callback, then compare every
                    // captured field byte-for-byte. A successor or renewed lease is not ours.
                    if (!foreignPrivateOwner(gatewayLease.pid, GATEWAY_STATE_PATH)) return 0
                    database.compileStatement(
                        "DELETE FROM $GATEWAY_LEASE_TABLE WHERE $GATEWAY_LEASE_FILTER " +
                            "AND owner COLLATE BINARY = ? AND payload_json COLLATE BINARY = ? " +
                            "AND expires_at IS ? AND heartbeat_at IS ? " +
                            "AND created_at = ? AND updated_at = ?",
                    ).use { statement ->
                        statement.bindString(1, gatewayLease.owner)
                        statement.bindString(2, gatewayLease.payload)
                        gatewayLease.expiresAt?.let { statement.bindLong(3, it) } ?: statement.bindNull(3)
                        gatewayLease.heartbeatAt?.let { statement.bindLong(4, it) } ?: statement.bindNull(4)
                        statement.bindLong(5, gatewayLease.createdAt)
                        statement.bindLong(6, gatewayLease.updatedAt)
                        val reclaimed = statement.executeUpdateDelete()
                        if (reclaimed != 1) return 0
                        deleted += reclaimed
                    }
                }
                database.setTransactionSuccessful()
                return deleted
            } finally {
                database.endTransaction()
            }
        }
    }

    fun foreignPrivateOwnerProbe(context: Context, rootfsDir: File): ((Int, String) -> Boolean)? {
        val proof = OpenClawPrivateStoreOwner.prove(context, rootfsDir) ?: return null
        try {
            val root = proof.root
            val stateFile = File(root, OPENCLAW_STATE_SQLITE_PATH)
            val configFile = File(root, "root/.openclaw/openclaw.json")
            if (!proof.isCanonicalPrivateRegularFile(stateFile) ||
                !proof.isCanonicalPrivateRegularFile(configFile)
            ) return null
            val openClawHome = File(root, "root/.openclaw").canonicalFile
            if (!openClawHome.path.startsWith(root.path + File.separator)) return null

            return { pid, guestPath ->
                try {
                    val agentFile = File(root, guestPath.removePrefix("/"))
                    if (pid <= 0 || rootfsDir.canonicalFile != root ||
                        !guestPath.startsWith("/root/.openclaw/") ||
                        !agentFile.canonicalPath.startsWith(openClawHome.path + File.separator) ||
                        !proof.isCanonicalPrivateRegularFile(stateFile) ||
                        !proof.isCanonicalPrivateRegularFile(configFile) ||
                        !proof.isCanonicalPrivateRegularFile(agentFile)
                    ) {
                        false
                    } else {
                        proof.isForeignOwner(pid)
                    }
                } catch (_: IOException) {
                    false
                } catch (_: android.system.ErrnoException) {
                    false
                } catch (_: SecurityException) {
                    false
                }
            }
        } catch (_: IOException) {
            return null
        } catch (_: android.system.ErrnoException) {
            return null
        } catch (_: SecurityException) {
            return null
        }
    }

    fun bootUpperBoundTicks(
        elapsedRealtimeNanos: Long,
        clockTicksPerSecond: Long,
    ): Long? {
        if (elapsedRealtimeNanos <= 0 || clockTicksPerSecond <= 0) return null
        return try {
            val elapsedSeconds = elapsedRealtimeNanos / NANOS_PER_SECOND
            val elapsedNanosRemainder = elapsedRealtimeNanos % NANOS_PER_SECOND
            val elapsedWholeTicks = Math.multiplyExact(elapsedSeconds, clockTicksPerSecond)
            val elapsedPartialTicks = ceilScaledTicks(elapsedNanosRemainder, clockTicksPerSecond)
            // A full tick-second covers the time between the kernel clock read and the DELETE.
            Math.addExact(
                Math.addExact(elapsedWholeTicks, elapsedPartialTicks),
                clockTicksPerSecond,
            )
        } catch (_: ArithmeticException) {
            null
        }
    }

    private fun hasLeaseTable(database: SQLiteDatabase, table: String): Boolean =
        database.rawQuery(
            "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?",
            arrayOf(table),
        ).use { it.moveToFirst() }

    private fun readGatewayLease(database: SQLiteDatabase): GatewayLease? =
        database.rawQuery(
            "SELECT owner, payload_json, expires_at, heartbeat_at, created_at, updated_at " +
                "FROM $GATEWAY_LEASE_TABLE WHERE $GATEWAY_LEASE_FILTER",
            null,
        ).use { cursor ->
            if (!cursor.moveToFirst()) return null
            val payload = cursor.getString(1)
            val pid = parseGatewayPid(payload) ?: return null
            val lease = GatewayLease(
                owner = cursor.getString(0),
                payload = payload,
                expiresAt = if (cursor.isNull(2)) null else cursor.getLong(2),
                heartbeatAt = if (cursor.isNull(3)) null else cursor.getLong(3),
                createdAt = cursor.getLong(4),
                updatedAt = cursor.getLong(5),
                pid = pid,
            )
            // A noncanonical schema with duplicate identities is ambiguous, not a candidate.
            if (cursor.moveToNext()) null else lease
        }

    private fun parseGatewayPid(payload: String): Int? =
        try {
            JsonReader(StringReader(payload)).use { reader ->
                var pid: Int? = null
                var port: Long? = null
                var mode: String? = null
                var supervisorValid = false
                var supervised = false
                reader.beginObject()
                while (reader.hasNext()) {
                    when (reader.nextName()) {
                        "owner" -> pid = readGatewayProcessOwner(reader)
                        "port" -> port = readJsonInteger(reader, 1, 65535)
                        "mode" -> mode = readJsonString(reader)
                        "supervisor" -> {
                            supervised = reader.peek() != JsonToken.NULL
                            supervisorValid = readGatewaySupervisor(reader)
                        }
                        else -> reader.skipValue()
                    }
                }
                reader.endObject()
                if (reader.peek() == JsonToken.END_DOCUMENT && port != null && supervisorValid &&
                    ((mode == "foreground" && !supervised) || (mode == "supervised" && supervised))
                ) pid else null
            }
        } catch (_: IOException) {
            null
        } catch (_: IllegalStateException) {
            null
        } catch (_: NumberFormatException) {
            null
        }

    private fun readGatewayProcessOwner(reader: JsonReader): Int? {
        if (reader.peek() != JsonToken.BEGIN_OBJECT) {
            reader.skipValue()
            return null
        }
        var pid: Long? = null
        var host: String? = null
        var validStartedAt = false
        var validNamespace = true
        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "pid" -> pid = readJsonInteger(reader, 1, MAX_OWNER_PID)
                "host" -> host = readJsonString(reader)
                "startedAt" -> {
                    validStartedAt = if (reader.peek() == JsonToken.NULL) {
                        reader.nextNull()
                        true
                    } else {
                        readJsonInteger(reader, 0, MAX_SAFE_INTEGER) != null
                    }
                }
                "processNamespace" -> validNamespace = readGatewayNamespace(reader)
                else -> reader.skipValue()
            }
        }
        reader.endObject()
        return if (!host.isNullOrEmpty() && validStartedAt && validNamespace) pid?.toInt() else null
    }

    private fun readGatewaySupervisor(reader: JsonReader): Boolean {
        if (reader.peek() == JsonToken.NULL) {
            reader.nextNull()
            return true
        }
        if (reader.peek() != JsonToken.BEGIN_OBJECT) {
            reader.skipValue()
            return false
        }
        var kind: String? = null
        var validName = false
        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "kind" -> kind = readJsonString(reader)
                "name" -> {
                    validName = if (reader.peek() == JsonToken.NULL) {
                        reader.nextNull()
                        true
                    } else {
                        readJsonString(reader)?.isNotBlank() == true
                    }
                }
                else -> reader.skipValue()
            }
        }
        reader.endObject()
        return validName && (kind == "launchd" || kind == "systemd" || kind == "schtasks" || kind == "external")
    }

    private fun readGatewayNamespace(reader: JsonReader): Boolean {
        if (reader.peek() == JsonToken.NULL) {
            reader.nextNull()
            return true
        }
        if (reader.peek() != JsonToken.BEGIN_OBJECT) {
            reader.skipValue()
            return false
        }
        var platform: String? = null
        var host: String? = null
        var identity: String? = null
        var pidNamespace: String? = null
        var hasPidNamespace = false
        var unknownField = false
        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "platform" -> platform = readJsonString(reader)
                "host" -> host = readJsonString(reader)
                "identity" -> identity = readJsonString(reader)
                "pidNsInode" -> {
                    hasPidNamespace = true
                    pidNamespace = readJsonString(reader)
                }
                else -> {
                    unknownField = true
                    reader.skipValue()
                }
            }
        }
        reader.endObject()
        if (unknownField || host.isNullOrEmpty() || identity == null) return false
        return when (platform) {
            "linux" -> UUID_BOOT_ID.matches(identity) && !pidNamespace.isNullOrEmpty()
            "darwin" -> UUID_BOOT_ID.matches(identity) && !hasPidNamespace
            "win32" -> WINDOWS_BOOT_ID.matches(identity) && !hasPidNamespace
            "freebsd" -> FREEBSD_BOOT_ID.matches(identity) && !hasPidNamespace
            else -> false
        }
    }

    private fun readJsonString(reader: JsonReader): String? =
        if (reader.peek() == JsonToken.STRING) reader.nextString() else {
            reader.skipValue()
            null
        }

    private fun readJsonInteger(reader: JsonReader, minimum: Long, maximum: Long): Long? {
        if (reader.peek() != JsonToken.NUMBER) {
            reader.skipValue()
            return null
        }
        val number = reader.nextDouble()
        val integer = number.toLong()
        return integer.takeIf { number.isFinite() && number == it.toDouble() && it in minimum..maximum }
    }

    private val UUID_BOOT_ID = Regex(
        "[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}",
        RegexOption.IGNORE_CASE,
    )
    private val WINDOWS_BOOT_ID = Regex("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{7}Z")
    private val FREEBSD_BOOT_ID = Regex("[a-f0-9]{32}")

    private fun ceilScaledTicks(nanos: Long, clockTicksPerSecond: Long): Long {
        val ticksPerNanosecond = clockTicksPerSecond / NANOS_PER_SECOND
        val tickRemainder = clockTicksPerSecond % NANOS_PER_SECOND
        val wholeTicks = Math.multiplyExact(nanos, ticksPerNanosecond)
        val fractionalProduct = Math.multiplyExact(nanos, tickRemainder)
        val fractionalTicks = fractionalProduct / NANOS_PER_SECOND
        val roundedFractionalTicks = if (fractionalProduct % NANOS_PER_SECOND == 0L) {
            fractionalTicks
        } else {
            Math.addExact(fractionalTicks, 1L)
        }
        return Math.addExact(wholeTicks, roundedFractionalTicks)
    }

    private fun readBootUpperBoundTicks(): Long? =
        try {
            bootUpperBoundTicks(
                elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos(),
                clockTicksPerSecond = Os.sysconf(OsConstants._SC_CLK_TCK),
            )
        } catch (_: ErrnoException) {
            null
        }
}
