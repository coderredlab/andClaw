package com.coderred.andclaw.proroot

import android.content.Context
import android.os.Process
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.IOException

/** Native Android identity and private-file evidence; never trusts the guest's emulated root UID. */
internal object OpenClawPrivateStoreOwner {
    private val WHITESPACE = Regex("\\s+")
    private val APP_SELINUX_CONTEXT = Regex("u:r:untrusted_app(?:_\\d+)?:s0(?::c\\d+(?:,c\\d+)*)?")

    fun foreignPrivateGatewayLockOwnerProbe(
        context: Context,
        rootfsDir: File,
    ): ((Int, String) -> Boolean)? {
        val proof = prove(context, rootfsDir) ?: return null
        val guestLockPath = "/tmp/openclaw-${proof.uid}/${OpenClawLegacyGatewayLockRecovery.LOCK_NAME}"
        val configFile = File(proof.root, OpenClawLegacyGatewayLockRecovery.CONFIG_PATH.removePrefix("/"))
        val lockFile = File(proof.root, guestLockPath.removePrefix("/"))
        try {
            // The guest and native host must resolve the same regular files without a symlink.
            if (rootfsDir.canonicalFile != proof.root ||
                !proof.isCanonicalPrivateRegularFile(configFile) ||
                !proof.isCanonicalPrivateRegularFile(lockFile)
            ) return null
        } catch (_: IOException) {
            return null
        } catch (_: ErrnoException) {
            return null
        } catch (_: SecurityException) {
            return null
        }
        return { pid, guestPath ->
            try {
                guestPath == guestLockPath &&
                    rootfsDir.canonicalFile == proof.root &&
                    proof.isCanonicalPrivateRegularFile(configFile) &&
                    proof.isCanonicalPrivateRegularFile(lockFile) &&
                    proof.isForeignOwner(pid)
            } catch (_: IOException) {
                false
            } catch (_: ErrnoException) {
                false
            } catch (_: SecurityException) {
                false
            }
        }
    }

    class Proof internal constructor(val root: File, val privateDir: File, val uid: Int) {
        fun isPrivateRegularFile(file: File): Boolean {
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

        fun isCanonicalPrivateRegularFile(file: File): Boolean =
            file.absoluteFile == file.canonicalFile &&
                file.path.startsWith(root.path + File.separator) &&
                isPrivateRegularFile(file)

        fun isForeignOwner(pid: Int): Boolean {
            if (pid <= 0) return false
            // A live same-UID process succeeds even if /proc is unreadable. EACCES stays unknown.
            return try {
                Os.kill(pid, 0)
                false
            } catch (error: ErrnoException) {
                error.errno == OsConstants.EPERM
            }
        }
    }

    fun prove(context: Context, rootfsDir: File): Proof? {
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
            return Proof(root, privateDir, uid)
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
}
