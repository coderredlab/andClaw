package com.coderred.andclaw.proroot

import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.time.Instant
import org.json.JSONObject

internal object OpenClawLegacyGatewayLockRecovery {
    const val CONFIG_PATH = "/root/.openclaw/openclaw.json"
    // SHA-256(CONFIG_PATH), first eight hex digits; verified against the installed engine.
    const val LOCK_NAME = "gateway.a504a3cd.lock"
    private const val MAX_LOCK_BYTES = 16 * 1024

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

    private fun boundedRead(path: Path): Pair<String, Any?>? {
        val before = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        if ((Files.getAttribute(path, "unix:nlink", NOFOLLOW_LINKS) as Number).toLong() != 1L) return null
        if (!before.isRegularFile || before.size() !in 1..MAX_LOCK_BYTES.toLong()) return null
        val bytes = ByteArray(before.size().toInt() + 1)
        val count = Files.newInputStream(path, NOFOLLOW_LINKS).use { input ->
            var count = 0
            while (count < bytes.size) {
                val read = input.read(bytes, count, bytes.size - count)
                if (read < 0) break
                count += read
            }
            count
        }
        if (count != before.size().toInt()) return null
        val after = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        if (!after.isRegularFile || (Files.getAttribute(path, "unix:nlink", NOFOLLOW_LINKS) as Number).toLong() != 1L ||
            before.fileKey() == null || before.fileKey() != after.fileKey() ||
            before.size() != after.size() ||
            before.lastModifiedTime() != after.lastModifiedTime()
        ) return null
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return decoder.decode(ByteBuffer.wrap(bytes, 0, count)).toString() to after.fileKey()
    }

    private fun positiveInteger(value: Any?, max: Long): Long? {
        if (value !is Int && value !is Long) return null
        val number = (value as Number).toLong()
        return number.takeIf { it in 1..max }
    }

    private fun validPayload(raw: String): Int? {
        val payload = JSONObject(raw)
        val pid = positiveInteger(payload.opt("pid"), Int.MAX_VALUE.toLong())?.toInt() ?: return null
        if (payload.opt("configPath") != CONFIG_PATH) return null
        val createdAt = payload.opt("createdAt") as? String ?: return null
        if (runCatching { Instant.parse(createdAt) }.isFailure) return null
        if (payload.has("startTime") && positiveInteger(payload.opt("startTime"), 9_007_199_254_740_991L) == null) return null
        if (payload.has("role") && payload.opt("role") != "gateway") return null
        return pid
    }

    fun capture(rootfsDir: File, uid: Int, foreignPrivateOwner: ((Int, String) -> Boolean)?): JSONObject? {
        if (uid <= 0 || foreignPrivateOwner == null) return null
        return runCatching {
            val root = rootfsDir.canonicalFile.toPath()
            val guestPath = "/tmp/openclaw-$uid/$LOCK_NAME"
            val config = safeFile(root, CONFIG_PATH.removePrefix("/")) ?: return@runCatching null
            val lock = safeFile(root, guestPath.removePrefix("/")) ?: return@runCatching null
            if (!Files.isRegularFile(config, NOFOLLOW_LINKS)) return@runCatching null
            val (raw, identity) = boundedRead(lock) ?: return@runCatching null
            val pid = validPayload(raw) ?: return@runCatching null
            if (!foreignPrivateOwner(pid, guestPath)) return@runCatching null
            if (safeFile(root, guestPath.removePrefix("/")) != lock) return@runCatching null
            val (after, afterIdentity) = boundedRead(lock) ?: return@runCatching null
            if (after != raw || afterIdentity != identity) return@runCatching null
            JSONObject().put("uid", uid).put("raw", raw)
        }.getOrNull()
    }
}

/** Retires only the byte-identical, native-proven foreign-owner legacy lock via fs-safe. */
internal fun buildOpenClawLegacyGatewayLockRecoveryScript(proof: JSONObject): String = """
import fs from "node:fs";
import { createHash } from "node:crypto";
import path from "node:path";
import os from "node:os";
import { pathToFileURL } from "node:url";
import { isDeepStrictEqual } from "node:util";

const proof = JSON.parse(${JSONObject.quote(proof.toString())});
const DIST_DIR = process.env.OPENCLAW_DIST_DIR || "/usr/local/lib/node_modules/openclaw/dist";
const CONFIG_PATH = "/root/.openclaw/openclaw.json";
const matchingFunctions = async (pattern, names) => {
  let resolved;
  for (const file of fs.readdirSync(DIST_DIR).filter((file) => pattern.test(file))) {
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
const gateway = await matchingFunctions(/^gateway-lock-[^/]+\.mjs$/,
  ["resolveGatewayLockPaths", "readLockPayload"]);
const fileLock = await matchingFunctions(/^file-lock-manager-[^/]+\.mjs$/,
  ["createFileLockManager"]);
if (!Number.isSafeInteger(proof.uid) || proof.uid < 0 ||
    typeof proof.raw !== "string" || Buffer.byteLength(proof.raw, "utf8") > 16 * 1024 ||
    process.getuid?.() !== proof.uid) {
  throw new Error("Legacy lock recovery proof does not match guest identity");
}
const lockDir = path.join(os.tmpdir(), "openclaw-" + proof.uid);
const paths = gateway.resolveGatewayLockPaths(process.env, lockDir);
const lockPath = paths.configLockPath;
const hash = createHash("sha256").update(CONFIG_PATH).digest("hex").slice(0, 8);
if (paths.configPath !== CONFIG_PATH ||
    lockPath !== path.join(lockDir, "gateway." + hash + ".lock")) {
  throw new Error("Legacy lock recovery path differs from native proof");
}
function currentRaw() {
  if (fs.realpathSync(lockDir) !== path.resolve(lockDir)) return null;
  const before = fs.lstatSync(lockPath);
  if (!before.isFile() || before.isSymbolicLink() || before.nlink !== 1 ||
      before.size < 1 || before.size > 16 * 1024) {
    return null;
  }
  const fd = fs.openSync(lockPath, fs.constants.O_RDONLY | fs.constants.O_NOFOLLOW);
  try {
    const opened = fs.fstatSync(fd);
    if (opened.dev !== before.dev || opened.ino !== before.ino ||
        !opened.isFile() || opened.nlink !== 1) return null;
    const buffer = Buffer.allocUnsafe(before.size + 1);
    let count = 0;
    while (count < buffer.length) {
      const read = fs.readSync(fd, buffer, count, buffer.length - count, count);
      if (read === 0) break;
      count += read;
    }
    const after = fs.lstatSync(lockPath);
    if (count !== before.size || after.dev !== before.dev || after.ino !== before.ino ||
        after.nlink !== 1 || after.size !== before.size || after.mtimeMs !== before.mtimeMs ||
        fs.realpathSync(lockDir) !== path.resolve(lockDir)) return null;
    return buffer.toString("utf8", 0, count);
  } finally {
    fs.closeSync(fd);
  }
}
const original = JSON.parse(proof.raw);
const valid = (payload) => payload && typeof payload === "object" && !Array.isArray(payload) &&
  Number.isSafeInteger(payload.pid) && payload.pid > 0 && payload.pid <= 2147483647 &&
  payload.configPath === CONFIG_PATH && typeof payload.createdAt === "string" &&
  Number.isFinite(Date.parse(payload.createdAt)) &&
  (payload.startTime === undefined || (Number.isSafeInteger(payload.startTime) && payload.startTime > 0)) &&
  (payload.role === undefined || payload.role === "gateway") &&
  isDeepStrictEqual(payload, original);
if (!valid(original) || currentRaw() !== proof.raw ||
    !isDeepStrictEqual(await gateway.readLockPayload(lockPath, true), original)) {
  throw new Error("Native-proven legacy lock has changed");
}
const manager = fileLock.createFileLockManager("andclaw.legacy-gateway-lock-recovery");
const selfStat = fs.readFileSync("/proc/self/stat", "utf8");
const selfFields = selfStat.slice(selfStat.lastIndexOf(")") + 2).trim().split(/\s+/);
const selfStartTime = Number(selfFields[19]);
if (!Number.isSafeInteger(selfStartTime) || selfStartTime <= 0) {
  throw new Error("Cannot identify legacy lock recovery process");
}
const matchesProof = (candidate) => candidate.lockPath === lockPath && valid(candidate.payload) &&
  currentRaw() === proof.raw;
const lock = await manager.acquire(lockPath, {
  lockPath,
  timeoutMs: 0,
  retry: { retries: 0 },
  staleRecovery: "remove-if-unchanged",
  payload: () => ({
    pid: process.pid,
    createdAt: new Date().toISOString(),
    configPath: CONFIG_PATH,
    role: "sqlite-maintenance",
    startTime: selfStartTime,
  }),
  parsePayload: (raw) => { try { return JSON.parse(raw); } catch { return null; } },
  shouldReclaim: matchesProof,
  shouldRemoveStaleLock: (candidate) => candidate.raw === proof.raw && matchesProof(candidate),
});
await lock.release();
if (fs.existsSync(lockPath)) throw new Error("Legacy lock changed during retirement");
console.log("[andClaw] Retired native-proven legacy Gateway lock");
""".trimIndent()
