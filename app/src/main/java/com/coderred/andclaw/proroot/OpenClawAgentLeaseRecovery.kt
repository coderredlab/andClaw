package com.coderred.andclaw.proroot

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.os.Process
import android.os.SystemClock
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.IOException

internal object OpenClawAgentLeaseRecovery {
    private const val OPENCLAW_STATE_SQLITE_PATH = "root/.openclaw/state/openclaw.sqlite"
    private const val LEASE_TABLE = "agent_database_leases"
    private const val NANOS_PER_SECOND = 1_000_000_000L
    private const val MAX_OWNER_PID = 2_147_483_647L
    private val WHITESPACE = Regex("\\s+")
    private val APP_SELINUX_CONTEXT = Regex("u:r:untrusted_app(?:_\\d+)?:s0(?::c\\d+(?:,c\\d+)*)?")

    private data class Lease(val id: String, val pid: Int, val startTime: Long, val path: String)

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
        val databaseFile = File(rootfsDir, OPENCLAW_STATE_SQLITE_PATH)
        if (!databaseFile.isFile) return 0

        SQLiteDatabase.openDatabase(databaseFile.path, null, SQLiteDatabase.OPEN_READWRITE).use { database ->
            database.beginTransaction()
            try {
                if (!hasLeaseTable(database)) return 0
                // Sample only after this held write transaction has snapshotted sqlite_master:
                // no newer lease can appear while its recovery cutoff is being chosen.
                val upperBoundTicks = sampleBootUpperBoundTicks()
                var deleted = if (upperBoundTicks != null && upperBoundTicks > 0) {
                    database.compileStatement(DELETE_IMPOSSIBLE_LEASES_SQL).use { statement ->
                        statement.bindLong(1, upperBoundTicks)
                        statement.executeUpdateDelete()
                    }
                } else {
                    0
                }
                if (foreignPrivateOwner != null) {
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
                                statement.bindString(1, lease.id)
                                statement.bindLong(2, lease.pid.toLong())
                                statement.bindLong(3, lease.startTime)
                                statement.bindString(4, lease.path)
                                deleted += statement.executeUpdateDelete()
                            }
                        }
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
        try {
            val uid = Process.myUid()
            if (uid % 100_000 !in 10_000..19_999 ||
                context.applicationInfo.uid != uid || Os.getuid() != uid || Os.geteuid() != uid
            ) return null
            val packages = context.packageManager.getPackagesForUid(uid) ?: return null
            if (packages.size != 1 || packages[0] != context.packageName ||
                context.packageManager.getPackageInfo(context.packageName, 0).sharedUserId != null
            ) return null
            var matchingUids = false
            var emptyCapabilities = 0
            File("/proc/self/status").forEachLine { line ->
                when (line.substringBefore(':')) {
                    "Uid" -> {
                        val uids = line.substringAfter(':').trim().split(WHITESPACE)
                        matchingUids = uids.size == 4 && uids.all { it.toIntOrNull() == uid }
                    }
                    "CapInh", "CapPrm", "CapEff", "CapAmb" -> {
                        if (line.substringAfter(':').trim().toULongOrNull(16) == 0uL) emptyCapabilities++
                    }
                }
            }
            if (!matchingUids || emptyCapabilities != 4 ||
                !APP_SELINUX_CONTEXT.matches(File("/proc/self/attr/current").readText().removeSuffix("\u0000").trim())
            ) return null
            Os.kill(Process.myPid(), 0)

            val privateDir = context.dataDir.canonicalFile
            val privateStat = Os.stat(privateDir.path)
            if (!OsConstants.S_ISDIR(privateStat.st_mode) || privateStat.st_uid != uid ||
                privateStat.st_mode and (OsConstants.S_IRWXG or OsConstants.S_IRWXO) != 0
            ) return null
            val filesDir = context.filesDir.canonicalFile
            val root = rootfsDir.canonicalFile
            if (filesDir.parentFile != privateDir || root.parentFile != filesDir) return null
            val stateFile = File(root, OPENCLAW_STATE_SQLITE_PATH)
            if (!isPrivateRegularFile(stateFile, privateDir, uid)) return null
            val openClawHome = File(root, "root/.openclaw").canonicalFile
            if (!openClawHome.path.startsWith(root.path + File.separator)) return null

            return { pid, guestPath ->
                try {
                    val agentFile = File(root, guestPath.removePrefix("/"))
                    if (pid <= 0 || !guestPath.startsWith("/root/.openclaw/") ||
                        !agentFile.canonicalPath.startsWith(openClawHome.path + File.separator) ||
                        !isPrivateRegularFile(agentFile, privateDir, uid)
                    ) {
                        false
                    } else {
                        // Native Android credentials, not the guest's emulated root identity.
                        // Same-UID signal-zero succeeds even when dumpability hides /proc.
                        // Standard Android SELinux denials are EACCES, which stays unknown.
                        try {
                            Os.kill(pid, 0)
                            false
                        } catch (error: ErrnoException) {
                            error.errno == OsConstants.EPERM
                        }
                    }
                } catch (_: IOException) {
                    false
                } catch (_: ErrnoException) {
                    false
                } catch (_: SecurityException) {
                    false
                }
            }
        } catch (_: IOException) {
            return null
        } catch (_: ErrnoException) {
            return null
        } catch (_: SecurityException) {
            return null
        } catch (_: android.content.pm.PackageManager.NameNotFoundException) {
            return null
        }
    }

    private fun isPrivateRegularFile(file: File, privateDir: File, uid: Int): Boolean {
        val canonical = file.canonicalFile
        if (!canonical.path.startsWith(privateDir.path + File.separator)) return false
        val stat = Os.stat(canonical.path)
        if (!OsConstants.S_ISREG(stat.st_mode) || stat.st_uid != uid || stat.st_nlink != 1L) return false
        var parent = canonical.parentFile
        while (parent != null) {
            val parentStat = Os.stat(parent.path)
            if (!OsConstants.S_ISDIR(parentStat.st_mode) || parentStat.st_uid != uid) return false
            if (parent == privateDir) {
                return parentStat.st_mode and (OsConstants.S_IRWXG or OsConstants.S_IRWXO) == 0
            }
            parent = parent.parentFile
        }
        return false
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

    private fun hasLeaseTable(database: SQLiteDatabase): Boolean =
        database.rawQuery(
            "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?",
            arrayOf(LEASE_TABLE),
        ).use { it.moveToFirst() }

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
