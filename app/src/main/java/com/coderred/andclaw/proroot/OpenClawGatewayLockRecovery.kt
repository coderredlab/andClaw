package com.coderred.andclaw.proroot

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Instant
import org.json.JSONArray
import org.json.JSONObject

/** Recovery is limited to the app's managed default state tree and its historical config lock. */
internal object OpenClawGatewayLockRecovery {
    const val STATE_DIR = "/root/.openclaw"
    const val CONFIG_PATH = "$STATE_DIR/openclaw.json"
    const val DATABASE_PATH = "$STATE_DIR/state/openclaw.sqlite"
    private const val MAX_SAFE_INTEGER = 9_007_199_254_740_991L
    private val roles = setOf("gateway", "agent-embedded", "skill-workshop-apply", "sqlite-maintenance")
    private val payloadKeys = setOf(
        "pid", "ownerId", "stateOwnerKind", "cronOwnerProjection", "createdAt", "configPath",
        "port", "role", "stateDir", "startTime", "processNamespace",
    )
    private val uuid = Regex("[a-fA-F0-9]{8}(?:-[a-fA-F0-9]{4}){3}-[a-fA-F0-9]{12}")
    private val configLockName = "gateway.${hash(CONFIG_PATH, 8)}.lock"
    private val stateOwnerName = "state.${hash(DATABASE_PATH, 16)}.lock"

    private fun hash(path: String, length: Int): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(path.toByteArray(Charsets.UTF_8))
        val hex = "0123456789abcdef"
        val result = CharArray(length)
        for (index in result.indices) {
            val value = digest[index / 2].toInt() and 0xff
            result[index] = hex[if (index % 2 == 0) value ushr 4 else value and 0x0f]
        }
        return String(result)
    }

    // These hashes use guest canonical paths, not the host's rootfs prefix.
    fun allowedLockPaths(uid: Int): Map<String, String> {
        if (uid <= 0) return emptyMap()
        val directory = "$STATE_DIR/tmp/openclaw-$uid"
        val configName = configLockName
        return linkedMapOf(
            "$directory/$stateOwnerName" to "state-owner",
            "$directory/gateway.state.lock" to "state-projection",
            "$directory/$configName" to "config",
            "/tmp/openclaw-$uid/$configName" to "legacy-config",
        )
    }

    private fun safeFile(root: Path, relative: String): Path? {
        val target = root.resolve(relative).normalize()
        if (!target.startsWith(root) || target == root || Files.isSymbolicLink(root)) return null
        var current = root
        for (part in root.relativize(target)) {
            current = current.resolve(part)
            if (Files.isSymbolicLink(current)) return null
        }
        return target
    }

    private fun boundedRead(root: Path, path: Path): OpenClawLockSnapshotReader.Snapshot? =
        OpenClawLockSnapshotReader.read(root, root.relativize(path).toString())

    private fun positiveInteger(value: Any?, max: Long): Long? {
        if (value !is Int && value !is Long) return null
        return (value as Number).toLong().takeIf { it in 1..max }
    }

    private fun validNamespace(value: Any?): Boolean {
        if (value == JSONObject.NULL) return true
        val namespace = value as? JSONObject ?: return false
        val platform = namespace.opt("platform") as? String ?: return false
        val host = namespace.opt("host") as? String ?: return false
        val identity = namespace.opt("identity") as? String ?: return false
        if (host.isEmpty()) return false
        val keys = if (platform == "linux") setOf("platform", "host", "identity", "pidNsInode")
            else setOf("platform", "host", "identity")
        if (namespace.keys().asSequence().any { it !in keys }) return false
        return when (platform) {
            "linux" -> uuid.matches(identity) &&
                (namespace.opt("pidNsInode") as? String)?.isNotEmpty() == true
            "darwin" -> uuid.matches(identity)
            "win32" -> Regex("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{7}Z").matches(identity)
            "freebsd" -> Regex("[a-f0-9]{32}").matches(identity)
            else -> false
        }
    }

    private fun validPayload(raw: String, kind: String): Int? {
        val payload = JSONObject(raw)
        if (payload.keys().asSequence().any { it !in payloadKeys }) return null
        val pid = positiveInteger(payload.opt("pid"), Int.MAX_VALUE.toLong())?.toInt() ?: return null
        if (payload.opt("configPath") != CONFIG_PATH) return null
        val createdAt = payload.opt("createdAt") as? String ?: return null
        if (runCatching { Instant.parse(createdAt) }.isFailure) return null
        if (payload.has("startTime") && positiveInteger(payload.opt("startTime"), MAX_SAFE_INTEGER) == null) return null
        if (payload.has("role")) {
            val role = payload.opt("role") as? String ?: return null
            if (role !in roles) return null
        }
        if (payload.has("stateDir") && payload.opt("stateDir") != STATE_DIR) return null
        if (payload.has("ownerId") && (payload.opt("ownerId") as? String)?.isNotEmpty() != true) return null
        if (kind == "state-owner" && (!payload.has("ownerId") || payload.opt("stateDir") != STATE_DIR)) return null
        if (payload.has("stateOwnerKind") && payload.opt("stateOwnerKind") != "schema") return null
        if (payload.has("cronOwnerProjection") && payload.opt("cronOwnerProjection") != "dynamic-default-v1") return null
        if (payload.has("port") && positiveInteger(payload.opt("port"), 65535) == null) return null
        if (payload.has("processNamespace") && !validNamespace(payload.opt("processNamespace"))) return null
        return pid
    }

    fun capture(rootfsDir: File, uid: Int, foreignPrivateOwner: ((Int, String) -> Boolean)?): JSONObject? {
        if (uid <= 0 || foreignPrivateOwner == null) return null
        return runCatching {
            val root = rootfsDir.canonicalFile.toPath()
            val config = safeFile(root, CONFIG_PATH.removePrefix("/")) ?: return@runCatching null
            val database = safeFile(root, DATABASE_PATH.removePrefix("/")) ?: return@runCatching null
            if (!Files.isRegularFile(config, NOFOLLOW_LINKS) ||
                (Files.getAttribute(config, "unix:nlink", NOFOLLOW_LINKS) as Number).toLong() != 1L ||
                Files.exists(database, NOFOLLOW_LINKS) &&
                (!Files.isRegularFile(database, NOFOLLOW_LINKS) ||
                    (Files.getAttribute(database, "unix:nlink", NOFOLLOW_LINKS) as Number).toLong() != 1L)
            ) return@runCatching null
            val captured = linkedMapOf<String, Pair<Int, OpenClawLockSnapshotReader.Snapshot>>()
            val paths = allowedLockPaths(uid)
            for ((guestPath, kind) in paths) {
                val snapshot = runCatching lockSnapshot@ {
                    val lock = safeFile(root, guestPath.removePrefix("/")) ?: return@lockSnapshot null
                    boundedRead(root, lock)
                }.getOrElse { error ->
                    if (error is LinkageError) throw error
                    null
                } ?: continue
                val pid = runCatching { validPayload(snapshot.raw, kind) }.getOrNull() ?: continue
                if (!foreignPrivateOwner(pid, guestPath)) continue
                val lock = safeFile(root, guestPath.removePrefix("/")) ?: return@runCatching null
                if (boundedRead(root, lock) != snapshot) return@runCatching null
                captured[guestPath] = pid to snapshot
            }
            if (captured.isEmpty()) return@runCatching null
            // A later callback must not invalidate an earlier member of the batch.
            for ((guestPath, entry) in captured) {
                if (!foreignPrivateOwner(entry.first, guestPath)) return@runCatching null
                val lock = safeFile(root, guestPath.removePrefix("/")) ?: return@runCatching null
                if (boundedRead(root, lock) != entry.second) return@runCatching null
            }
            for ((guestPath, entry) in captured) {
                val lock = safeFile(root, guestPath.removePrefix("/")) ?: return@runCatching null
                if (boundedRead(root, lock) != entry.second) return@runCatching null
            }
            val locks = JSONArray()
            for ((guestPath, entry) in captured) {
                val snapshot = entry.second
                locks.put(JSONObject().put("path", guestPath).put("kind", paths.getValue(guestPath))
                    .put("raw", snapshot.raw).put("dev", snapshot.dev).put("ino", snapshot.ino)
                    .put("mtimeNs", snapshot.mtimeNs))
            }
            JSONObject().put("uid", uid).put("locks", locks)
        }.getOrElse { error ->
            // A missing/broken packaged JNI reader is not an unqualified owner or a lossy fallback.
            if (error is LinkageError) throw error
            null
        }
    }
}

/** Retires only exact native-proven owners, through the shipped fs-safe reclaim admission. */
internal fun buildOpenClawGatewayLockRecoveryScript(proof: JSONObject): String = """
import fs from "node:fs";
import { createHash, randomUUID } from "node:crypto";
import path from "node:path";
import os from "node:os";
import { pathToFileURL } from "node:url";
import { isDeepStrictEqual } from "node:util";

const proof = JSON.parse(${JSONObject.quote(proof.toString())});
const DIST_DIR = process.env.OPENCLAW_DIST_DIR || "/usr/local/lib/node_modules/openclaw/dist";
const STATE_DIR = "/root/.openclaw";
const CONFIG_PATH = STATE_DIR + "/openclaw.json";
const DATABASE_PATH = STATE_DIR + "/state/openclaw.sqlite";
const MAX_BYTES = 16 * 1024;
const matchingFunctions = async (pattern, names) => {
  let resolved;
  for (const file of fs.readdirSync(DIST_DIR).filter(file => pattern.test(file))) {
    const mod = await import(pathToFileURL(path.join(DIST_DIR, file)).href);
    const functions = Object.fromEntries(names.map(name => [
      name, Object.values(mod).find(value => typeof value === "function" && value.name === name),
    ]));
    if (names.some(name => !functions[name])) continue;
    if (resolved && names.some(name => resolved[name] !== functions[name])) {
      throw new Error("Conflicting OpenClaw lock implementations");
    }
    resolved = functions;
  }
  if (!resolved) throw new Error("OpenClaw lock module contract unavailable");
  return resolved;
};
if (!Number.isSafeInteger(proof.uid) || proof.uid <= 0 || process.getuid?.() !== proof.uid ||
    !Array.isArray(proof.locks) || proof.locks.length < 1 || proof.locks.length > 4) {
  throw new Error("Gateway lock recovery proof does not match native identity");
}
const gateway = await matchingFunctions(/^gateway-lock-[^/]+\.mjs$/, ["resolveGatewayLockPaths"]);
const owners = await matchingFunctions(/^gateway-state-owner-[^/]+\.mjs$/,
  ["resolveGatewayStateOwnerPath", "parseGatewayLockPayload"]);
const fileLock = await matchingFunctions(/^file-lock-manager-[^/]+\.mjs$/, ["createFileLockManager"]);
const { root: openRoot } = await import(pathToFileURL(path.join(
  DIST_DIR, "../node_modules/@openclaw/fs-safe/dist/root.js")).href);
const lockDir = STATE_DIR + "/tmp/openclaw-" + proof.uid;
const hash = (value, length) => createHash("sha256").update(value).digest("hex").slice(0, length);
const paths = gateway.resolveGatewayLockPaths(process.env);
const configName = "gateway." + hash(CONFIG_PATH, 8) + ".lock";
const ownerPath = lockDir + "/state." + hash(DATABASE_PATH, 16) + ".lock";
if (paths.configPath !== CONFIG_PATH || paths.stateDir !== STATE_DIR ||
    paths.configLockPath !== lockDir + "/" + configName ||
    paths.stateLockPath !== lockDir + "/gateway.state.lock" ||
    paths.ownerLockPath !== ownerPath || owners.resolveGatewayStateOwnerPath(DATABASE_PATH) !== ownerPath ||
    os.tmpdir() !== "/tmp") {
  throw new Error("Gateway lock recovery paths differ from managed native proof");
}
const allowed = new Map([
  [ownerPath, "state-owner"],
  [paths.stateLockPath, "state-projection"],
  [paths.configLockPath, "config"],
  ["/tmp/openclaw-" + proof.uid + "/" + configName, "legacy-config"],
]);
function privatePath(pathname, regular = true) {
  if (!path.isAbsolute(pathname) || path.normalize(pathname) !== pathname ||
      fs.realpathSync(path.dirname(pathname)) !== path.dirname(pathname)) return false;
  for (let parent = path.dirname(pathname); parent !== "/"; parent = path.dirname(parent)) {
    const stat = fs.lstatSync(parent);
    if (!stat.isDirectory() || stat.isSymbolicLink() || stat.uid !== proof.uid) return false;
  }
  const stat = fs.lstatSync(pathname);
  return stat.uid === proof.uid && !stat.isSymbolicLink() &&
    (regular ? stat.isFile() && stat.nlink === 1 : stat.isDirectory());
}
function managedFiles() {
  if (!privatePath(CONFIG_PATH)) return false;
  try { return privatePath(DATABASE_PATH); }
  catch (error) {
    return error.code === "ENOENT" &&
      fs.realpathSync(STATE_DIR) === STATE_DIR && privatePath(STATE_DIR, false);
  }
}
function currentSnapshot(lockPath) {
  if (!managedFiles() || !privatePath(lockPath)) return null;
  const before = fs.lstatSync(lockPath, { bigint: true });
  if (before.size < 1n || before.size > BigInt(MAX_BYTES)) return null;
  const fd = fs.openSync(lockPath, fs.constants.O_RDONLY | fs.constants.O_NOFOLLOW);
  try {
    const opened = fs.fstatSync(fd, { bigint: true });
    if (opened.dev !== before.dev || opened.ino !== before.ino ||
        !opened.isFile() || opened.nlink !== 1n || opened.uid !== BigInt(proof.uid)) return null;
    const buffer = Buffer.allocUnsafe(Number(before.size) + 1);
    let count = 0;
    while (count < buffer.length) {
      const read = fs.readSync(fd, buffer, count, buffer.length - count, count);
      if (read === 0) break;
      count += read;
    }
    const after = fs.lstatSync(lockPath, { bigint: true });
    if (count !== Number(before.size) || after.dev !== before.dev || after.ino !== before.ino ||
        after.nlink !== 1n || after.size !== before.size || after.mtimeNs !== before.mtimeNs ||
        !privatePath(lockPath) || !managedFiles()) return null;
    return { raw: new TextDecoder("utf-8", { fatal: true }).decode(buffer.subarray(0, count)),
      dev: BigInt.asUintN(64, after.dev).toString(), ino: BigInt.asUintN(64, after.ino).toString(),
      mtimeNs: after.mtimeNs.toString() };
  } finally { fs.closeSync(fd); }
}
const snapshotFields = ["raw", "dev", "ino", "mtimeNs"];
const sameSnapshot = (current, expected) => current &&
  snapshotFields.every(key => current[key] === expected[key]);
const integer = (value, max = Number.MAX_SAFE_INTEGER) =>
  Number.isSafeInteger(value) && value > 0 && value <= max;
const keys = new Set(["pid", "ownerId", "stateOwnerKind", "cronOwnerProjection", "createdAt",
  "configPath", "port", "role", "stateDir", "startTime", "processNamespace"]);
function valid(payload, kind) {
  return payload && typeof payload === "object" && !Array.isArray(payload) &&
    Object.keys(payload).every(key => keys.has(key)) && integer(payload.pid, 2147483647) &&
    payload.configPath === CONFIG_PATH && typeof payload.createdAt === "string" &&
    Number.isFinite(Date.parse(payload.createdAt)) &&
    (payload.startTime === undefined || integer(payload.startTime)) &&
    (payload.stateDir === undefined || payload.stateDir === STATE_DIR) &&
    (payload.ownerId === undefined || typeof payload.ownerId === "string" && payload.ownerId.length > 0) &&
    (kind !== "state-owner" || payload.stateDir === STATE_DIR && typeof payload.ownerId === "string") &&
    isDeepStrictEqual(owners.parseGatewayLockPayload(JSON.stringify(payload)), payload);
}
function foreignOwner(pid) {
  if (!integer(pid, 2147483647) || pid === process.pid) return false;
  try { process.kill(pid, 0); return false; }
  catch (error) { return error.code === "EPERM"; }
}
const entries = proof.locks.map(entry => {
  if (!entry || allowed.get(entry.path) !== entry.kind || typeof entry.raw !== "string" ||
      Buffer.byteLength(entry.raw, "utf8") < 1 || Buffer.byteLength(entry.raw, "utf8") > MAX_BYTES ||
      !["dev", "ino", "mtimeNs"].every(key => typeof entry[key] === "string" && /^\d+$/.test(entry[key]))) {
    throw new Error("Gateway recovery contains an unsupported lock proof");
  }
  const payload = JSON.parse(entry.raw);
  if (!valid(payload, entry.kind)) throw new Error("Gateway recovery payload scope is invalid");
  return { ...entry, payload };
});
if (new Set(entries.map(entry => entry.path)).size !== entries.length) {
  throw new Error("Gateway recovery contains duplicate lock paths");
}
entries.sort((a, b) => [...allowed.keys()].indexOf(a.path) - [...allowed.keys()].indexOf(b.path));
function matches(entry) {
  try {
    const snapshot = currentSnapshot(entry.path);
    return sameSnapshot(snapshot, entry) && foreignOwner(entry.payload.pid);
  } catch { return false; }
}
if (!entries.every(matches)) throw new Error("Native-proven Gateway lock changed or owner is no longer foreign");
const selfStat = fs.readFileSync("/proc/self/stat", "utf8");
const selfFields = selfStat.slice(selfStat.lastIndexOf(")") + 2).trim().split(/\s+/);
const selfStartTime = Number(selfFields[19]);
if (!integer(selfStartTime)) throw new Error("Cannot identify Gateway lock recovery process");
const manager = fileLock.createFileLockManager("andclaw.gateway-lock-recovery");
const held = [];
try {
  for (let index = 0; index < entries.length; index++) {
    for (const acquired of held) {
      if (!await acquired.lock.verifyStillHeld()) throw new Error("Gateway recovery admission was replaced");
    }
    if (!entries.slice(index).every(matches)) throw new Error("Gateway recovery batch changed");
    const entry = entries[index];
    const root = await openRoot(path.dirname(entry.path), {
      maxBytes: MAX_BYTES, hardlinks: "reject", symlinks: "reject", mutationSymlinks: "reject", mkdir: false,
    });
    if (root.rootReal !== path.dirname(entry.path)) throw new Error("Gateway lock directory changed");
    // Keep the genuine Root and its registered native context. Fence its final
    // removals, not only fs-safe's earlier sidecar comparison/approval.
    const created = new Map();
    const guardPath = entry.path + ".reclaim";
    const targetPath = relative => {
      const target = path.resolve(root.rootReal, relative);
      if (target !== entry.path && target !== guardPath) {
        throw new Error("Gateway recovery attempted an unsupported Root mutation");
      }
      return target;
    };
    const create = root.create.bind(root);
    root.create = async (relative, data, options) => {
      const target = targetPath(relative);
      const raw = typeof data === "string" ? data :
        Buffer.isBuffer(data) ? new TextDecoder("utf-8", { fatal: true }).decode(data) : null;
      if (raw === null) throw new Error("Gateway recovery creation receipt is unavailable");
      const payload = JSON.parse(raw);
      if (payload.pid !== process.pid ||
          target === entry.path && (!valid(payload, entry.kind) ||
            payload.role !== "sqlite-maintenance" || payload.startTime !== selfStartTime ||
            typeof payload.ownerId !== "string" || payload.ownerId.length === 0)) {
        throw new Error("Gateway recovery creation does not belong to this process");
      }
      await create(relative, data, options);
      const receipt = currentSnapshot(target);
      if (!receipt || receipt.raw !== raw) {
        throw new Error("Gateway recovery creation changed before receipt capture");
      }
      created.set(target, receipt);
    };
    const remove = root.remove.bind(root);
    root.remove = (relative, options = {}) => {
      const target = targetPath(relative);
      const receipt = created.get(target);
      if (target === guardPath && !receipt) {
        throw new Error("Gateway reclaim guard has no creation receipt");
      }
      const expected = receipt ?? entry;
      const sdkAssertion = options.assertBeforeMutation;
      return remove(relative, {
        ...options,
        assertBeforeMutation: () => {
          sdkAssertion?.();
          // This assertion is synchronous. The shipped native Root performs its
          // final descriptor identity check and unlink next, without an await.
          if (process.getuid?.() !== proof.uid ||
              !sameSnapshot(currentSnapshot(target), expected) ||
              !receipt && !foreignOwner(entry.payload.pid)) {
            throw new Error("Gateway lock ownership changed at final mutation");
          }
        },
      });
    };
    let approved = false;
    const candidateMatches = candidate => candidate.lockPath === entry.path &&
      isDeepStrictEqual(candidate.payload, entry.payload) && matches(entry);
    const lock = await manager.acquire(entry.path, {
      lockPath: entry.path, lockRoot: root, timeoutMs: 0, retry: { retries: 0 }, staleMs: Infinity,
      staleRecovery: "remove-if-unchanged",
      payload: () => ({ pid: process.pid, ownerId: randomUUID(), createdAt: new Date().toISOString(),
        configPath: CONFIG_PATH, stateDir: STATE_DIR, role: "sqlite-maintenance", startTime: selfStartTime }),
      parsePayload: raw => owners.parseGatewayLockPayload(raw),
      shouldReclaim: candidateMatches,
      shouldRemoveStaleLock: candidate => {
        approved = candidate.raw === entry.raw && candidateMatches(candidate);
        return approved;
      },
    });
    held.push({ lock, path: entry.path });
    if (!approved || !await lock.verifyStillHeld()) throw new Error("Gateway lock was not retired under its proven admission");
  }
  for (const acquired of held) {
    if (!await acquired.lock.verifyStillHeld()) throw new Error("Gateway recovery admission was replaced");
  }
} finally {
  // Release projections before their state owner, retaining the admission across the whole batch.
  const failures = [];
  for (const acquired of held.reverse()) {
    try {
      if (!await acquired.lock.verifyStillHeld()) throw new Error("Gateway recovery release ownership changed");
      await acquired.lock.release();
      if (fs.existsSync(acquired.path)) throw new Error("Gateway lock changed during retirement");
    } catch (error) { failures.push(error); }
  }
  if (failures.length) throw new AggregateError(failures, "Gateway recovery release failed");
}
console.log("[andClaw] Retired " + held.length + " native-proven foreign private-store Gateway lock(s)");
""".trimIndent()
