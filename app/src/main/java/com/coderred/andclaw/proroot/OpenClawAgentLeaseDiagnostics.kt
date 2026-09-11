package com.coderred.andclaw.proroot

import android.database.sqlite.SQLiteDatabase
import android.os.Process
import android.system.ErrnoException
import android.system.Os
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Diagnostic evidence only: never reclaims leases or signals a process except with signal zero. */
internal object OpenClawAgentLeaseDiagnostics {
    const val RELATIVE_PATH = "tmp/andclaw-agent-lease-last.json"
    private const val MAX_OWNERS = 32
    private val PROC_WHITESPACE = Regex("\\s+")

    fun collect(
        rootfsDir: File,
        procDir: File = File("/proc"),
        signalZero: (Int) -> Int = { pid ->
            try {
                Os.kill(pid, 0)
                0
            } catch (error: ErrnoException) {
                error.errno
            }
        },
    ): JSONObject {
        val owners = JSONArray()
        val result = JSONObject()
            .put("capturedAtEpochMs", System.currentTimeMillis())
            .put("appPid", Process.myPid())
            .put("appUid", Process.myUid())
            .put("owners", owners)
        val databaseFile = File(rootfsDir, "root/.openclaw/state/openclaw.sqlite")
        if (!databaseFile.isFile) return result.put("databaseStatus", "missing")
        try {
            SQLiteDatabase.openDatabase(databaseFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { database ->
                val hasLeases = database.rawQuery(
                    "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = 'agent_database_leases'",
                    null,
                ).use { it.moveToFirst() }
                if (!hasLeases) return result.put("databaseStatus", "lease_table_missing")
                database.rawQuery(
                    "SELECT owner_pid, owner_start_time, COUNT(*) FROM agent_database_leases " +
                        "GROUP BY owner_pid, owner_start_time ORDER BY owner_pid LIMIT ${MAX_OWNERS + 1}",
                    null,
                ).use { cursor ->
                    result.put("truncated", cursor.count > MAX_OWNERS)
                    while (owners.length() < MAX_OWNERS && cursor.moveToNext()) {
                        val pid = cursor.getLong(0)
                        val owner = JSONObject()
                            .put("pid", pid)
                            .put("recordedStartTime", if (cursor.isNull(1)) JSONObject.NULL else cursor.getLong(1))
                            .put("leaseCount", cursor.getLong(2))
                        owners.put(owner)
                        if (pid !in 1..Int.MAX_VALUE.toLong()) {
                            owner.put("probeStatus", "invalid_pid")
                            continue
                        }
                        val processDir = File(procDir, pid.toString())
                        try {
                            owner.put("hostSignalZeroErrno", signalZero(pid.toInt()))
                        } catch (error: Exception) {
                            owner.put("hostSignalZeroError", error.javaClass.simpleName)
                        }
                        try {
                            val stat = File(processDir, "stat").bufferedReader().use { it.readLine() }.orEmpty()
                            val end = stat.lastIndexOf(')')
                            val fields = if (end >= 0) stat.substring(end + 1).trim().split(PROC_WHITESPACE) else emptyList()
                            owner.put("hostStartTime", fields.getOrNull(19)?.toLongOrNull() ?: JSONObject.NULL)
                            owner.put("hostState", fields.firstOrNull() ?: JSONObject.NULL)
                        } catch (error: Exception) {
                            owner.put("hostStatReadError", error.javaClass.simpleName)
                        }
                        try {
                            File(processDir, "status").useLines { lines ->
                                for (line in lines) {
                                    if (line.startsWith("Uid:")) {
                                        owner.put("hostUid", line.substringAfter(':').trim().substringBefore('\t').substringBefore(' ').toLongOrNull() ?: JSONObject.NULL)
                                    } else if (line.startsWith("Threads:")) {
                                        owner.put("hostThreads", line.substringAfter(':').trim().toIntOrNull() ?: JSONObject.NULL)
                                    }
                                }
                            }
                        } catch (error: Exception) {
                            owner.put("hostStatusReadError", error.javaClass.simpleName)
                        }
                    }
                }
            }
            result.put("databaseStatus", "read_only")
        } catch (error: Exception) {
            result.put("databaseStatus", "read_failed").put("databaseError", error.javaClass.simpleName)
        }
        return result
    }

    fun guestProbeScript(diagnostic: JSONObject): String {
        val owners = diagnostic.getJSONArray("owners")
        val pids = (0 until owners.length()).map { owners.getJSONObject(it).getLong("pid") }
            .filter { it in 1..Int.MAX_VALUE.toLong() }.distinct()
        return """
            const fs = require('node:fs');
            function probe(pid) {
              const result = { pid };
              try { process.kill(pid, 0); result.signalZero = { ok: true }; }
              catch (error) { result.signalZero = { ok: false, code: error.code, errno: error.errno }; }
              try {
                const stat = fs.readFileSync('/proc/' + pid + '/stat', 'utf8');
                const end = stat.lastIndexOf(')');
                const fields = end < 0 ? [] : stat.slice(end + 1).trim().split(/\s+/);
                const start = Number(fields[19]);
                result.startTime = Number.isSafeInteger(start) && start >= 0 ? start : null;
                result.state = fields[0] || null;
              } catch (error) { result.statReadError = error.code; }
              return result;
            }
            console.log(JSON.stringify({ self: probe(process.pid), owners: ${pids}.map(probe) }));
        """.trimIndent()
    }
}
