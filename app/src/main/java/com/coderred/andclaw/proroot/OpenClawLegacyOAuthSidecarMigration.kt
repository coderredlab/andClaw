package com.coderred.andclaw.proroot

/** Recover retained OAuth material before the current Doctor rejects retired sidecar names. */
internal fun buildOpenClawLegacyOAuthSidecarMigrationScript(): String = """
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import crypto from "node:crypto";
import { isDeepStrictEqual } from "node:util";
import { pathToFileURL } from "node:url";

// Imported diagnostics may contain credential values. Only this program's count-only result escapes.
const report = process.stdout.write.bind(process.stdout);
const reportError = process.stderr.write.bind(process.stderr);
const suppressOutput = function(_chunk, encoding, callback) {
  const done = typeof encoding === "function" ? encoding : callback;
  if (typeof done === "function") queueMicrotask(done);
  return true;
};
process.stdout.write = suppressOutput;
process.stderr.write = suppressOutput;
const DIST_DIR = process.env.OPENCLAW_DIST_DIR?.trim() || "/usr/local/lib/node_modules/openclaw/dist";
const ID_PATTERN = /^[a-f0-9]{32}$/;
const FILE_PATTERN = /^[a-f0-9]{32}\.json$/;
const isRecord = (value) => value !== null && typeof value === "object" && !Array.isArray(value);
const nonBlank = (value) => typeof value === "string" && value.trim() ? value.trim() : undefined;
const digest = (bytes) => crypto.createHash("sha256").update(bytes).digest("hex");
const clone = (value) => structuredClone(value);
const lockOptions = { retries: { retries: 0 }, stale: 30000, staleRecovery: "fail-closed" };
let phase = "module-discovery";

function fail() { throw new Error("legacy OAuth recovery unavailable"); }
function sameIdentity(left, right) {
  return left.dev === right.dev && left.ino === right.ino;
}
function inspectPath(filePath) {
  try {
    const entry = fs.lstatSync(filePath, { bigint: true });
    const target = entry.isSymbolicLink() ? fs.statSync(filePath, { bigint: true }) : entry;
    if (!target.isFile()) fail();
    return { canonical: fs.realpathSync(filePath), stat: target };
  } catch (error) {
    if (error?.code !== "ENOENT") throw error;
    // A dangling link or unavailable ancestor is not an absent source.
    let current = filePath;
    while (true) {
      try {
        const entry = fs.lstatSync(current);
        if (current === filePath || !fs.statSync(current).isDirectory()) fail();
        if (entry.isSymbolicLink()) fs.realpathSync(current);
        return null;
      } catch (missing) {
        if (missing?.code !== "ENOENT") throw missing;
      }
      const parent = path.dirname(current);
      if (parent === current) fail();
      current = parent;
    }
  }
}
function snapshotFile(filePath, allowSymlink = true) {
  const before = inspectPath(filePath);
  if (!before) return null;
  if (!allowSymlink && fs.lstatSync(filePath).isSymbolicLink()) fail();
  const fd = fs.openSync(filePath, fs.constants.O_RDONLY | (allowSymlink ? 0 : fs.constants.O_NOFOLLOW));
  try {
    const opened = fs.fstatSync(fd, { bigint: true });
    if (!opened.isFile() || !sameIdentity(before.stat, opened)) fail();
    const bytes = fs.readFileSync(fd);
    const after = inspectPath(filePath);
    const finished = fs.fstatSync(fd, { bigint: true });
    if (!after || before.canonical !== after.canonical || !sameIdentity(opened, after.stat) ||
        !sameIdentity(opened, finished) || opened.size !== finished.size || opened.mtimeNs !== finished.mtimeNs ||
        opened.ctimeNs !== finished.ctimeNs || BigInt(bytes.length) !== finished.size) fail();
    return { path: filePath, canonical: before.canonical, stat: finished, bytes, sha256: digest(bytes) };
  } finally { fs.closeSync(fd); }
}
function assertSnapshot(snapshot) {
  const current = snapshotFile(snapshot.path, false);
  if (!current || current.canonical !== snapshot.canonical || !sameIdentity(current.stat, snapshot.stat) ||
      current.sha256 !== snapshot.sha256) fail();
}
function syncDirectory(directory) {
  const fd = fs.openSync(directory, fs.constants.O_RDONLY | fs.constants.O_DIRECTORY);
  try { fs.fsyncSync(fd); } finally { fs.closeSync(fd); }
}
function writeExclusive(filePath, bytes) {
  const fd = fs.openSync(filePath, fs.constants.O_WRONLY | fs.constants.O_CREAT | fs.constants.O_EXCL | fs.constants.O_NOFOLLOW, 0o600);
  try {
    fs.fchmodSync(fd, 0o600);
    let offset = 0;
    while (offset < bytes.length) {
      const written = fs.writeSync(fd, bytes, offset, bytes.length - offset);
      if (written <= 0) fail();
      offset += written;
    }
    fs.fsyncSync(fd);
  } finally { fs.closeSync(fd); }
  syncDirectory(path.dirname(filePath));
}

// Preservation includes every historical sidecar, not only those Doctor still considers referenced.
function listSidecarPaths(directory) {
  let entries;
  try {
    entries = fs.readdirSync(directory);
  } catch (error) {
    if (error?.code !== "ENOENT") throw error;
    let ancestor = directory;
    while (true) {
      try { fs.lstatSync(ancestor); break; } catch (missing) {
        if (missing?.code !== "ENOENT") throw missing;
        const parent = path.dirname(ancestor);
        if (parent === ancestor) throw error;
        ancestor = parent;
      }
    }
    if (ancestor === directory || !fs.statSync(ancestor).isDirectory()) throw error;
    return [];
  }
  return entries.filter((name) => FILE_PATTERN.test(name))
    .map((name) => path.join(directory, name)).sort();
}

// Follow source-level named/alias exports, accepting identical reexports but never two implementations.
async function loadExports(prefix, names, accepts = (value) => typeof value === "function") {
  const found = new Map();
  const files = fs.readdirSync(DIST_DIR).filter((name) => name.startsWith(prefix) && /\.(mjs|js)$/.test(name)).sort();
  for (const name of files) {
    const modulePath = path.join(DIST_DIR, name);
    const source = fs.readFileSync(modulePath, "utf8");
    const aliases = new Map();
    for (const match of source.matchAll(/export\s*\{([^}]+)\}/g)) {
      for (const entry of match[1].split(",")) {
        const parts = entry.trim().split(/\s+as\s+/);
        if (names.includes(parts[0])) aliases.set(parts[0], parts[1] || parts[0]);
      }
    }
    for (const target of names) {
      if (new RegExp("export\\s+(?:async\\s+)?function\\s+" + target + "\\b").test(source)) aliases.set(target, target);
    }
    if (aliases.size === 0) continue;
    const loaded = await import(pathToFileURL(modulePath).href);
    for (const [target, alias] of aliases) {
      const candidate = loaded[alias];
      if (!accepts(candidate) || found.has(target) && found.get(target) !== candidate) fail();
      found.set(target, candidate);
    }
  }
  if (names.some((name) => !found.has(name))) fail();
  return Object.fromEntries(found);
}

function normalizeMaterial(raw) {
  if (!isRecord(raw)) return null;
  const material = {};
  for (const field of ["access", "refresh", "idToken"]) {
    const value = nonBlank(raw[field]);
    if (value) material[field] = value;
  }
  return Object.keys(material).length ? material : null;
}
function usable(profile) {
  return isRecord(profile) && profile.type === "oauth" && Boolean(nonBlank(profile.provider)) &&
    Boolean(nonBlank(profile.access)) && Boolean(nonBlank(profile.refresh)) &&
    typeof profile.expires === "number" && Number.isFinite(profile.expires) && profile.expires > 0;
}
function knownRef(ref) {
  return isRecord(ref) && ref.source === "openclaw-credentials" && ref.provider === "openai-codex" &&
    typeof ref.id === "string" && ID_PATTERN.test(ref.id);
}
function inside(root, candidate) {
  const relative = path.relative(root, path.resolve(candidate));
  return relative === "" || relative !== ".." && !relative.startsWith(".." + path.sep) && !path.isAbsolute(relative);
}
function readSeeds(env, stateDir, cfg) {
  const seeds = [];
  const add = (value) => { const seed = nonBlank(value); if (seed && !seeds.includes(seed)) seeds.push(seed); };
  add(env.OPENCLAW_AUTH_PROFILE_SECRET_KEY);
  add(cfg.env?.vars?.OPENCLAW_AUTH_PROFILE_SECRET_KEY);
  add(cfg.env?.OPENCLAW_AUTH_PROFILE_SECRET_KEY);
  const home = nonBlank(env.HOME) || os.homedir();
  const root = nonBlank(env.XDG_CONFIG_HOME);
  const candidates = [root ? path.join(root, "openclaw", "auth-profile-secret-key") : null,
    path.join(home, ".config", "openclaw", "auth-profile-secret-key"),
    path.join(home, ".openclaw-auth-profile-secrets", "auth-profile-secret-key")].filter(Boolean);
  const stateCanonical = fs.existsSync(stateDir) ? fs.realpathSync(stateDir) : path.resolve(stateDir);
  for (const candidate of new Set(candidates)) {
    if (inside(stateDir, candidate)) continue;
    try {
      const canonical = fs.realpathSync(candidate);
      if (inside(stateCanonical, canonical) || !fs.statSync(canonical).isFile()) continue;
      add(fs.readFileSync(canonical, "utf8"));
    } catch { /* An unavailable historical key is an unrecovered credential, not lost data. */ }
  }
  return seeds;
}
function decodeSidecar(sidecar, seeds) {
  let raw;
  try { raw = JSON.parse(sidecar.bytes.toString("utf8")); } catch { return null; }
  if (!isRecord(raw) || raw.version !== 1 || !nonBlank(raw.profileId) || raw.provider !== "openai-codex") return null;
  let material;
  const encrypted = raw.encrypted;
  // As in 9.7, a recognized encrypted envelope takes precedence over plaintext fields.
  if (isRecord(encrypted) && encrypted.algorithm === "aes-256-gcm" &&
      [encrypted.iv, encrypted.tag, encrypted.ciphertext].every((value) => typeof value === "string")) {
    for (const seed of seeds) {
      try {
        const key = crypto.createHash("sha256").update("openclaw:auth-profile-oauth:" + seed).digest();
        const decipher = crypto.createDecipheriv("aes-256-gcm", key, Buffer.from(encrypted.iv, "base64url"));
        decipher.setAAD(Buffer.from(sidecar.id + "\0" + raw.profileId + "\0" + raw.provider, "utf8"));
        decipher.setAuthTag(Buffer.from(encrypted.tag, "base64url"));
        const plaintext = Buffer.concat([decipher.update(Buffer.from(encrypted.ciphertext, "base64url")), decipher.final()]);
        material = normalizeMaterial(JSON.parse(plaintext.toString("utf8")));
        if (material) break;
      } catch { /* AES authentication must succeed; never substitute or guess token material. */ }
    }
  } else material = normalizeMaterial(raw);
  return material ? { profileId: raw.profileId, provider: raw.provider, material } : null;
}
function walkReferences(raw, visit) {
  if (!raw || typeof raw !== "object") return;
  if (isRecord(raw) && Object.hasOwn(raw, "oauthRef")) visit(raw);
  for (const value of Object.values(raw)) walkReferences(value, visit);
}
function profileEntries(source) {
  if (source.kind === "store" || source.kind === "auth-profiles" || isRecord(source.raw?.profiles)) {
    return isRecord(source.raw?.profiles) ? Object.entries(source.raw.profiles) : [];
  }
  if (source.kind === "legacy-auth" || source.kind === "legacy-oauth") {
    return Object.entries(source.raw).map(([provider, profile]) => [provider.trim() + ":default", profile, provider]);
  }
  return [];
}
function planSource(source, sidecars, parseLegacyCredentialEntry) {
  const updated = clone(source.raw);
  const target = { ...source, raw: updated };
  const handled = new Set();
  let hydrated = 0;
  let unresolved = 0;
  for (const [profileId, profile, fallbackProvider] of profileEntries(target)) {
    if (!isRecord(profile) || !Object.hasOwn(profile, "oauthRef")) continue;
    handled.add(profile);
    const ref = profile.oauthRef;
    const sidecar = knownRef(ref) ? sidecars.get(ref.id) : undefined;
    if (sidecar) sidecar.referenced = true;
    const effective = fallbackProvider === undefined ? profile : parseLegacyCredentialEntry(profile, fallbackProvider);
    if (!knownRef(ref) || effective?.type !== "oauth") { unresolved++; continue; }
    // A usable inline credential is authoritative even when its historical file is undecodable.
    if (usable(effective)) { delete profile.oauthRef; continue; }
    if (effective.provider !== ref.provider) { unresolved++; continue; }
    const decoded = sidecar?.decoded;
    if (!decoded || decoded.profileId !== profileId || decoded.provider !== effective.provider) { unresolved++; continue; }
    const hydratedProfile = { ...effective, ...decoded.material };
    if (!usable(hydratedProfile)) { unresolved++; continue; }
    Object.assign(profile, decoded.material);
    delete profile.oauthRef;
    hydrated++;
  }
  walkReferences(updated, (profile) => {
    const ref = profile.oauthRef;
    if (isRecord(ref) && typeof ref.id === "string" && sidecars.has(ref.id)) sidecars.get(ref.id).referenced = true;
    if (!handled.has(profile)) unresolved++;
  });
  return { updated, changed: !isDeepStrictEqual(source.raw, updated), hydrated, unresolved };
}

try {
  const dotenv = await loadExports("dotenv-", ["loadDotEnv"]);
  phase = "dotenv-loading";
  dotenv.loadDotEnv({ env: process.env, quiet: true });
  phase = "module-discovery";
  const configApi = await loadExports("io.read-helpers-", ["normalizeConfigIoDeps", "resolveConfigPathForDeps",
    "readConfigFileIfPresent", "parseConfigJson5", "resolveConfigIncludesForRead", "resolveConfigForRead"]);
  const paths = await loadExports("paths-", ["resolveOAuthDir"]);
  const statePaths = await loadExports("state-dir-", ["resolveStateDir"]);
  const inheritance = await loadExports("legacy-inherited-auth-dir-", ["resolveLegacyInheritedAuthAgentDir"]);
  const deletions = await loadExports("agent-deletion-journal.read-", ["readAgentDatabaseDeletionSnapshot"]);
  const retained = await loadExports("agent-deletion-discovery-", ["createRetainedAgentDatabaseMatcherFromSnapshot"]);
  const stateDatabasePaths = await loadExports("openclaw-state-db.paths-", ["resolveOpenClawStateSqlitePath"]);
  const sqliteFilePaths = await loadExports("sqlite-files-", ["resolveSqliteDatabaseFilePaths"]);
  const sqlite = await loadExports("sqlite-", ["resolveLegacyAuthProfileSourceCandidates", "resolveAuthProfileDatabasePath",
    "resolveAuthProfileDatabaseOwnerId", "assertAuthProfileStoreAgentOwner", "inspectPersistedAuthProfileStoreRaw",
    "inspectPersistedAuthProfileStateRaw", "inspectPersistedSharedAuthProfileStoreRaw", "inspectPersistedSharedAuthProfileStateRaw",
    "runAuthProfileWriteTransaction", "writePersistedAuthProfileStoreRaw"]);
  const shared = await loadExports("path-resolve-", ["resolveSharedMainAuthAgentDir", "resolveSharedAuthStorePath",
    "reloadSharedAuthStoreOwnership"]);
  const roster = await loadExports("agent-roster-", ["listAgentIds", "listAgentEntries", "readAgentRosterProperty"]);
  const agentIds = await loadExports("agent-id-", ["normalizeAgentId"]);
  const agents = await loadExports("agent-scope-config-", ["resolveAgentDir"]);
  const userPaths = await loadExports("home-dir-", ["resolveUserPath"]);
  const locks = await loadExports("file-lock-", ["withFileLock"]);
  const json = await loadExports("json-file-", ["writeJsonTarget"]);
  const readPool = await loadExports("sqlite-read-pool-", ["closeAuthProfileReadPool"]);
  const databaseFiles = await loadExports("stale-auth-order-store-", ["inspectAuthDatabaseFiles"]);
  const legacyCredentials = await loadExports("legacy-source-diagnostic-", ["parseLegacyCredentialEntry"]);
  const nodeSqlite = await loadExports("node-sqlite-", ["openNodeSqliteDatabase"]);
  const schemaRead = await loadExports("openclaw-agent-db-schema-read-", ["assertSupportedAgentSchemaVersion"]);
  const schemaContract = await loadExports("openclaw-agent-db-contract-", ["OPENCLAW_AGENT_SCHEMA_VERSION"],
    (value) => Number.isSafeInteger(value) && value > 0);
  const agentMaintenance = await loadExports("openclaw-agent-db-maintenance-", ["assertOpenClawAgentDatabaseOwner",
    "migrateOpenClawAgentDatabaseForMaintenance"]);
  const maintenanceLease = await loadExports("openclaw-agent-db-maintenance-lease-", ["withAgentDatabaseMaintenanceLease"]);
  const doctorLock = await loadExports("doctor-sqlite-maintenance-lock-", ["withDoctorSqliteMaintenanceLock"]);
  const migrationBackup = await loadExports("doctor-migration-backup-", ["backupDoctorMigrationDatabases"]);

  phase = "config-inspection";
  const env = { ...process.env };
  const deps = configApi.normalizeConfigIoDeps({ env, observe: false });
  const configPath = configApi.resolveConfigPathForDeps(deps);
  const configSnapshot = snapshotFile(configPath);
  const configRaw = configApi.readConfigFileIfPresent(deps, configPath);
  if (configSnapshot === null && configRaw !== undefined || configSnapshot && configRaw !== configSnapshot.bytes.toString("utf8")) fail();
  const parsed = configRaw === undefined ? { ok: true, parsed: {} } : configApi.parseConfigJson5(configRaw, deps.json5);
  if (!parsed.ok || !isRecord(parsed.parsed)) fail();
  const includePaths = new Set();
  const includes = configApi.resolveConfigIncludesForRead(parsed.parsed, configPath, deps, {}, {}, includePaths);
  const resolved = configApi.resolveConfigForRead(includes, env);
  if (!isRecord(resolved.resolvedConfigRaw)) fail();
  const cfg = resolved.resolvedConfigRaw;
  const configFiles = [configSnapshot, ...[...includePaths].map((file) => snapshotFile(file))].filter(Boolean);
  if (includePaths.size && configFiles.length !== includePaths.size + (configSnapshot ? 1 : 0)) fail();
  Object.assign(process.env, env);
  const stateDir = statePaths.resolveStateDir(env);
  const sidecarDir = path.join(paths.resolveOAuthDir(env, stateDir), "auth-profiles");
  const initialPaths = listSidecarPaths(sidecarDir);
  let counts = { archived: 0, hydrated: 0, preserved: 0, unrecovered: 0 };

  function assertConfig() {
    if (!configSnapshot && inspectPath(configPath)) fail();
    for (const snapshot of configFiles) {
      const current = snapshotFile(snapshot.path);
      if (!current || snapshot.canonical !== current.canonical || !sameIdentity(snapshot.stat, current.stat) ||
          snapshot.sha256 !== current.sha256) fail();
    }
  }
  function inspectAgentSchema(filePath, agentId) {
    const database = nodeSqlite.openNodeSqliteDatabase(filePath, { readOnly: true });
    try {
      const metadata = agentMaintenance.assertOpenClawAgentDatabaseOwner(database, { agentId, pathname: filePath });
      const version = schemaRead.assertSupportedAgentSchemaVersion(database, filePath);
      if (metadata.schemaVersion !== version) fail();
      return { agentId: metadata.agentId, version };
    } finally { database.close(); }
  }
  function directoryIdentity(directory) {
    try { return fs.realpathSync(directory); } catch (error) {
      if (error?.code !== "ENOENT") throw error;
      // Check ancestors before treating an unprovisioned configured directory as empty.
      inspectPath(path.join(directory, "openclaw-agent.sqlite"));
      return path.resolve(directory);
    }
  }
  function census() {
    phase = "source-census";
    // Missing optional credential variables are not a reason to block a healthy or recoverable startup.
    // Unresolved variables that select credential owners/directories, however, make the census ambiguous.
    for (const warning of resolved.envWarnings) {
      const configField = warning.configPath || "";
      if (configField.startsWith("agents.") &&
          (configField.endsWith(".agentDir") || configField.endsWith(".id") || configField.endsWith(".authInheritance.agentId"))) fail();
      for (const key of ["HOME", "OPENCLAW_HOME", "OPENCLAW_STATE_DIR", "OPENCLAW_AGENT_DIR", "PI_CODING_AGENT_DIR", "OPENCLAW_OAUTH_DIR"]) {
        if ((configField === "env." + key || configField === "env.vars." + key) &&
            typeof env[key] === "string" && env[key].includes("$" + "{")) fail();
      }
    }
    assertConfig();
    readPool.closeAuthProfileReadPool();
    const ownership = shared.reloadSharedAuthStoreOwnership(env);
    const configuredOwners = new Map();
    const authoredRoster = roster.readAgentRosterProperty(cfg);
    if (authoredRoster && (authoredRoster.kind === "entries" ? !isRecord(authoredRoster.value) : !Array.isArray(authoredRoster.value))) fail();
    if (authoredRoster) {
      const entries = authoredRoster.kind === "entries" ? Object.values(authoredRoster.value) : authoredRoster.value;
      if (entries.some((entry) => !isRecord(entry))) fail();
      const ids = new Set();
      for (const entry of roster.listAgentEntries(cfg)) {
        if (!nonBlank(entry.id) || entry.agentDir !== undefined && typeof entry.agentDir !== "string") fail();
        const id = agentIds.normalizeAgentId(entry.id);
        if (ids.has(id)) fail();
        ids.add(id);
      }
    }
    const configured = roster.listAgentIds(cfg).map((id) => ({ agentId: id, path: agents.resolveAgentDir(cfg, id, env) }));
    const deletionSnapshot = deletions.readAgentDatabaseDeletionSnapshot(env);
    if (!deletionSnapshot) {
      // No shared database means no persisted deletion journal, not permission to omit credential sources.
      const family = sqliteFilePaths.resolveSqliteDatabaseFilePaths(stateDatabasePaths.resolveOpenClawStateSqlitePath(env));
      if (family.some((file) => inspectPath(file) !== null)) fail();
    } else if (!deletionSnapshot.retainedDeletions ||
        deletionSnapshot.retainedDeletions.status === "unavailable") fail();
    const classify = deletionSnapshot
      ? retained.createRetainedAgentDatabaseMatcherFromSnapshot(env, () => configured, deletionSnapshot, { kind: "agent-directory" })
      : () => undefined;
    const isRetained = (directory) => {
      const classification = classify(directory);
      if (classification === "unavailable") fail();
      return classification !== undefined;
    };
    for (const target of configured) {
      if (isRetained(target.path)) continue;
      const physical = directoryIdentity(target.path);
      if (configuredOwners.has(physical) && configuredOwners.get(physical) !== target.agentId) fail();
      configuredOwners.set(physical, target.agentId);
    }
    const candidateMap = new Map();
    const addCandidate = (agentDir) => {
      const resolvedDir = agentDir ? userPaths.resolveUserPath(agentDir, env) : undefined;
      const directory = resolvedDir || shared.resolveSharedMainAuthAgentDir(env);
      if (isRetained(directory)) return;
      const authPath = path.join(directory, "auth-profiles.json");
      const key = path.resolve(authPath);
      if (!candidateMap.has(key) || agentDir === undefined) candidateMap.set(key, { agentDir: resolvedDir, authPath });
    };
    addCandidate(undefined);
    addCandidate(inheritance.resolveLegacyInheritedAuthAgentDir(cfg, env));
    const ambient = nonBlank(env.OPENCLAW_AGENT_DIR) || nonBlank(env.PI_CODING_AGENT_DIR);
    if (ambient) addCandidate(ambient);
    for (const target of configured) addCandidate(target.path);
    const root = path.join(stateDir, "agents");
    let entries;
    try { entries = fs.readdirSync(root, { withFileTypes: true }); } catch (error) {
      if (error?.code !== "ENOENT") throw error;
      // Distinguish an unprovisioned root from a dangling or inaccessible source directory.
      inspectPath(path.join(root, ".andclaw-census"));
      entries = [];
    }
    for (const entry of entries) {
      if (!entry.isDirectory() && !entry.isSymbolicLink()) continue;
      const directory = path.join(root, entry.name, "agent");
      if (isRetained(directory)) continue;
      try { if (!fs.statSync(directory).isDirectory()) fail(); } catch (error) {
        if (error?.code !== "ENOENT") throw error;
        try { fs.lstatSync(directory); fail(); } catch (missing) {
          if (missing?.code !== "ENOENT") throw missing;
        }
        if (!fs.statSync(path.dirname(directory)).isDirectory()) fail();
        continue;
      }
      addCandidate(directory);
    }
    const candidates = [...candidateMap.values()];
    const targets = new Map();
    const addDatabase = (databasePath, agentDir) => {
      const file = inspectPath(databasePath);
      const key = file?.canonical || path.resolve(databasePath);
      if (!targets.has(key)) targets.set(key, { path: databasePath, agentDir, identity: file });
    };
    addDatabase(shared.resolveSharedAuthStorePath(env), undefined);
    for (const directory of candidates.map((candidate) => candidate.agentDir).filter(Boolean)) {
      if (databaseFiles.inspectAuthDatabaseFiles(directory) === "unreadable") fail();
      addDatabase(sqlite.resolveAuthProfileDatabasePath(directory), directory);
    }
    const sources = [];
    for (const [key, target] of targets) {
      const ownerDir = target.agentDir || (ownership.location === "legacy-main" ? shared.resolveSharedMainAuthAgentDir(env) : undefined);
      phase = "database-owner";
      let schema = null;
      if (ownerDir && target.identity) {
        const expected = configuredOwners.get(directoryIdentity(ownerDir)) || sqlite.resolveAuthProfileDatabaseOwnerId(ownerDir);
        sqlite.assertAuthProfileStoreAgentOwner(ownerDir, expected);
        schema = inspectAgentSchema(target.path, expected);
      }
      phase = "database-inspection";
      const store = target.agentDir ? sqlite.inspectPersistedAuthProfileStoreRaw(target.agentDir) : sqlite.inspectPersistedSharedAuthProfileStoreRaw(env);
      const rotation = target.agentDir ? sqlite.inspectPersistedAuthProfileStateRaw(target.agentDir) : sqlite.inspectPersistedSharedAuthProfileStateRaw(env);
      if (store.status === "unreadable" || rotation.status === "unreadable" ||
          store.status === "readable" && (!isRecord(store.raw) || !isRecord(store.raw.profiles)) ||
          rotation.status === "readable" && !isRecord(rotation.raw)) fail();
      if (!target.identity && (store.status !== "missing" || store.reason !== "database" || rotation.status !== "missing" || rotation.reason !== "database")) fail();
      sources.push({ key: "db:" + key, kind: "store", ...target, schema, store, rotation, raw: store.status === "readable" ? store.raw : null });
    }
    const files = new Map();
    for (const candidate of candidates) {
      for (const legacy of sqlite.resolveLegacyAuthProfileSourceCandidates({ agentDir: candidate.agentDir, env })) {
        phase = "legacy-json-inspection";
        const snapshot = snapshotFile(legacy.path);
        const key = snapshot?.canonical || path.resolve(legacy.path);
        if (files.has(key)) continue;
        const raw = snapshot ? JSON.parse(snapshot.bytes.toString("utf8")) : null;
        if (snapshot && (!isRecord(raw) || legacy.kind === "auth-profiles" && !isRecord(raw.profiles))) fail();
        files.set(key, { key: "json:" + key, kind: legacy.kind, path: legacy.path, snapshot, raw });
      }
    }
    sources.push(...files.values());
    return { ownership, sources, candidates: candidates.map((candidate) => path.resolve(candidate.authPath)).sort() };
  }
  function assertCensus(expected, actual) {
    if (!isDeepStrictEqual(expected.ownership, actual.ownership) || !isDeepStrictEqual(expected.candidates, actual.candidates) ||
        expected.sources.length !== actual.sources.length) fail();
    const byKey = new Map(actual.sources.map((source) => [source.key, source]));
    for (const source of expected.sources) {
      const next = byKey.get(source.key);
      if (!next || !isDeepStrictEqual(source.raw, next.raw)) fail();
      if (source.key.startsWith("db:")) {
        if (!isDeepStrictEqual(source.schema, next.schema) || !isDeepStrictEqual(source.store, next.store) || !isDeepStrictEqual(source.rotation, next.rotation) ||
            Boolean(source.identity) !== Boolean(next.identity) || source.identity &&
            (!sameIdentity(source.identity.stat, next.identity.stat) || source.identity.canonical !== next.identity.canonical)) fail();
      } else if (Boolean(source.snapshot) !== Boolean(next.snapshot) || source.snapshot &&
          (source.snapshot.sha256 !== next.snapshot.sha256 || !sameIdentity(source.snapshot.stat, next.snapshot.stat))) fail();
    }
  }
  async function withSourceLocks(pathsToLock, operation, index = 0) {
    if (index === pathsToLock.length) return operation();
    return locks.withFileLock(pathsToLock[index], lockOptions, () => withSourceLocks(pathsToLock, operation, index + 1));
  }
  function ensureArchiveDirectory() {
    const directory = path.join(sidecarDir, ".andclaw-retired");
    try { fs.mkdirSync(directory, { mode: 0o700 }); syncDirectory(sidecarDir); } catch (error) {
      if (error?.code !== "EEXIST") throw error;
    }
    const entry = fs.lstatSync(directory);
    if (!entry.isDirectory() || entry.isSymbolicLink() || (entry.mode & 0o7777) !== 0o700) fail();
    return directory;
  }
  function verifyArchive(filePath, sidecar) {
    const archived = snapshotFile(filePath, false);
    if (!archived || archived.sha256 !== sidecar.sha256 || !archived.bytes.equals(sidecar.bytes) ||
        (archived.stat.mode & 0o7777n) !== 0o600n) fail();
    const fd = fs.openSync(filePath, fs.constants.O_RDONLY | fs.constants.O_NOFOLLOW);
    try {
      if (!sameIdentity(fs.fstatSync(fd, { bigint: true }), archived.stat)) fail();
      fs.fsyncSync(fd);
    } finally { fs.closeSync(fd); }
    return archived;
  }
  function publishArchive(sidecar, directory) {
    if (inspectPath(sidecar.archivePath)) return verifyArchive(sidecar.archivePath, sidecar);
    // Publish an already-synced complete file exclusively; a crash cannot leave a partial final archive.
    const temporary = path.join(directory, ".andclaw-pending-" + crypto.randomUUID());
    try {
      writeExclusive(temporary, sidecar.bytes);
      fs.linkSync(temporary, sidecar.archivePath);
      syncDirectory(directory);
    } catch (error) {
      if (error?.code !== "EEXIST") throw error;
      verifyArchive(sidecar.archivePath, sidecar);
    } finally {
      try { fs.unlinkSync(temporary); syncDirectory(directory); } catch (error) {
        if (error?.code !== "ENOENT") throw error;
      }
    }
    return verifyArchive(sidecar.archivePath, sidecar);
  }

  if (initialPaths.length) {
    phase = "migration-lock";
    await locks.withFileLock(path.join(sidecarDir, ".andclaw-oauth-sidecar-migration"), lockOptions, async () => {
      if (!isDeepStrictEqual(initialPaths, listSidecarPaths(sidecarDir))) fail();
      const baseline = census();
      const sidecars = new Map();
      const seeds = readSeeds(env, stateDir, cfg);
      for (const filePath of initialPaths) {
        if (!FILE_PATTERN.test(path.basename(filePath))) fail();
        const snapshot = snapshotFile(filePath, false);
        if (!snapshot) fail();
        const sidecar = { ...snapshot, id: path.basename(filePath, ".json"), referenced: false };
        sidecar.decoded = decodeSidecar(sidecar, seeds);
        sidecars.set(sidecar.id, sidecar);
      }
      const planned = baseline.sources.filter((source) => source.raw !== null).map((source) => ({
        source, ...planSource(source, sidecars, legacyCredentials.parseLegacyCredentialEntry)
      }));
      // Config and rotation state can reference retained material, but are not credential writers.
      for (const raw of [cfg, ...baseline.sources.map((source) => source.rotation?.raw).filter(Boolean)]) {
        walkReferences(raw, (profile) => {
          const ref = profile.oauthRef;
          if (isRecord(ref) && sidecars.has(ref.id)) sidecars.get(ref.id).referenced = true;
          counts.unrecovered++;
        });
      }
      const sourceLocks = [...new Set(baseline.sources.filter((source) => source.snapshot).map((source) => source.snapshot.canonical))].sort();
      phase = "source-locks";
      await withSourceLocks(sourceLocks, async () => {
        assertCensus(baseline, census());
        for (const sidecar of sidecars.values()) assertSnapshot(sidecar);
        // The existing app cache must not skip Doctor after retirement or an interrupted retry.
        phase = "maintenance-cache";
        const maintenanceMarker = nonBlank(env.ANDCLAW_STARTUP_MAINTENANCE_MARKER) ||
          path.join(stateDir, ".andclaw-startup-maintenance");
        try {
          fs.unlinkSync(maintenanceMarker);
          syncDirectory(path.dirname(maintenanceMarker));
        } catch (error) { if (error?.code !== "ENOENT") throw error; }
        phase = "archive-publication";
        const archiveDirectory = ensureArchiveDirectory();
        // Archive every original before publishing any credential change. Never overwrite an archive.
        for (const sidecar of sidecars.values()) {
          sidecar.archivePath = path.join(archiveDirectory, sidecar.id + "-" + sidecar.sha256 + ".json");
          publishArchive(sidecar, archiveDirectory);
        }
        syncDirectory(archiveDirectory);
        const publish = async (assertCurrent) => {
        assertCurrent?.();
        for (const item of planned) {
          const { source, updated, changed } = item;
          counts.hydrated += item.hydrated;
          counts.unrecovered += item.unresolved;
          if (!changed) continue;
          assertCurrent?.();
          assertConfig();
          for (const sidecar of sidecars.values()) assertSnapshot(sidecar);
          if (source.key.startsWith("db:")) {
            phase = "database-write";
            sqlite.runAuthProfileWriteTransaction(source.agentDir, (database, owner) => {
              const current = sqlite.inspectPersistedAuthProfileStoreRaw(source.agentDir, database);
              const rotation = sqlite.inspectPersistedAuthProfileStateRaw(source.agentDir, database);
              if (!isDeepStrictEqual(current, source.store) || !isDeepStrictEqual(rotation, source.rotation) ||
                  !source.identity || fs.realpathSync(owner.databasePath) !== source.identity.canonical ||
                  !sameIdentity(fs.statSync(owner.databasePath, { bigint: true }), source.identity.stat)) fail();
              sqlite.writePersistedAuthProfileStoreRaw(updated, source.agentDir, database);
              const verified = sqlite.inspectPersistedAuthProfileStoreRaw(source.agentDir, database);
              if (verified.status !== "readable" || !isDeepStrictEqual(verified.raw, updated) ||
                  !isDeepStrictEqual(sqlite.inspectPersistedAuthProfileStateRaw(source.agentDir, database), source.rotation)) fail();
            }, { env });
            source.store = { status: "readable", raw: updated };
          } else {
            const current = snapshotFile(source.path);
            if (!current || current.sha256 !== source.snapshot.sha256 || !sameIdentity(current.stat, source.snapshot.stat)) fail();
            phase = "legacy-json-write";
            json.writeJsonTarget(source.path, updated);
            const fd = fs.openSync(source.path, fs.constants.O_RDONLY);
            try { fs.fsyncSync(fd); } finally { fs.closeSync(fd); }
            syncDirectory(path.dirname(source.snapshot.canonical));
            source.snapshot = snapshotFile(source.path);
            if (!source.snapshot || !isDeepStrictEqual(JSON.parse(source.snapshot.bytes.toString("utf8")), updated)) fail();
          }
          source.raw = updated;
        }
        assertCensus(baseline, census());
        assertCurrent?.();
        if (!isDeepStrictEqual(initialPaths, listSidecarPaths(sidecarDir))) fail();
        for (const sidecar of sidecars.values()) {
          assertSnapshot(sidecar);
          verifyArchive(sidecar.archivePath, sidecar);
        }
        phase = "source-retirement";
        const retired = [];
        try {
          for (const sidecar of sidecars.values()) {
            assertCurrent?.();
            assertSnapshot(sidecar);
            verifyArchive(sidecar.archivePath, sidecar);
            fs.unlinkSync(sidecar.path);
            retired.push(sidecar);
            syncDirectory(sidecarDir);
            assertCurrent?.();
          }
          if (listSidecarPaths(sidecarDir).length) fail();
        } catch (error) {
          // A cleanup failure must not discard the historical source. Never overwrite a concurrent writer.
          for (const sidecar of retired) {
            try { if (!inspectPath(sidecar.path)) writeExclusive(sidecar.path, sidecar.bytes); } catch { /* The private durable copy remains. */ }
          }
          throw error;
        }
        counts.archived = sidecars.size;
        counts.preserved = [...sidecars.values()].filter((sidecar) => !sidecar.referenced || !sidecar.decoded).length;
        };
        const changedDatabases = planned.filter((item) => item.changed && item.source.key.startsWith("db:"));
        if (changedDatabases.length === 0) {
          await publish();
        } else {
          phase = "schema-maintenance-admission";
          try {
          await doctorLock.withDoctorSqliteMaintenanceLock({
            env,
            operation: "legacy OAuth credential schema preparation",
            run: async (authority) => {
              const olderAgents = changedDatabases.filter((item) =>
                item.source.schema && item.source.schema.version < schemaContract.OPENCLAW_AGENT_SCHEMA_VERSION);
              const prepareSchemas = async (maintenance) => {
                authority.assertCurrent();
                assertCensus(baseline, census());
                for (const { source } of olderAgents) {
                  phase = "agent-schema-maintenance";
                  maintenance.assertOwned();
                  authority.assertCurrent();
                  for (const sidecar of sidecars.values()) assertSnapshot(sidecar);
                  const identity = inspectPath(source.path);
                  if (!identity || !sameIdentity(identity.stat, source.identity.stat) ||
                      identity.canonical !== source.identity.canonical) fail();
                  readPool.closeAuthProfileReadPool();
                  await agentMaintenance.migrateOpenClawAgentDatabaseForMaintenance({
                    agentId: source.schema.agentId,
                    pathname: source.path,
                  }, maintenance);
                  maintenance.assertOwned();
                  authority.assertCurrent();
                  source.schema = inspectAgentSchema(source.path, source.schema.agentId);
                  if (source.schema.version !== schemaContract.OPENCLAW_AGENT_SCHEMA_VERSION) fail();
                  // Legitimate schema maintenance may change session/storage structures, never auth material.
                  assertCensus(baseline, census());
                }
                authority.assertCurrent();
              };
              if (olderAgents.length) {
                // Canonical schema migration can intentionally reinterpret historical observations.
                // Keep the verified original database generation before any targeted schema mutation.
                authority.assertCurrent();
                assertCensus(baseline, census());
                phase = "agent-schema-backup";
                const backups = await migrationBackup.backupDoctorMigrationDatabases({
                  env,
                  pendingDatabasePaths: olderAgents.map(({ source }) => source.path),
                  databasePaths: baseline.sources.filter((source) => source.key.startsWith("db:") && source.identity)
                    .map((source) => source.path),
                });
                authority.assertCurrent();
                assertCensus(baseline, census());
                if (backups.warnings.length) {
                  report("[andClaw] Verified original database backups retained; cleanup registration unavailable.\n");
                }
                readPool.closeAuthProfileReadPool();
                await maintenanceLease.withAgentDatabaseMaintenanceLease({ env }, prepareSchemas);
              }
              // Runtime auth connections must open only after the inner schema authority has ended.
              // The outer stopped-writer lock stays held across revalidation, commit, and retirement.
              authority.assertCurrent();
              assertCensus(baseline, census());
              await publish(() => authority.assertCurrent());
              authority.assertCurrent();
            },
          });
          } catch (error) {
            // Final admission/lease-release failures must retain the old source too, without clobbering new data.
            for (const sidecar of sidecars.values()) {
              try { if (!inspectPath(sidecar.path)) writeExclusive(sidecar.path, sidecar.bytes); } catch { /* Durable private data remains. */ }
            }
            throw error;
          }
        }
      });
    });
  }
  readPool.closeAuthProfileReadPool();
  report("[andClaw] Legacy OAuth sidecar recovery completed: archived=" + counts.archived +
    " hydrated=" + counts.hydrated + " preserved=" + counts.preserved + " unrecovered=" + counts.unrecovered + ".\n");
  if (counts.unrecovered || counts.preserved) {
    report("[andClaw] Preserved historical OAuth data may remain unavailable; use app Settings to reauthenticate if required.\n");
  }
} catch {
  reportError("[andClaw] Legacy OAuth sidecar recovery failed safely; original or privately archived data retained. phase=" + phase + ".\n");
  process.exitCode = 1;
}
""".trimIndent()
