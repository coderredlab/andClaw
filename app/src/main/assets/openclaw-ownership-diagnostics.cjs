'use strict';

// Observe the original ownership worker; never renew a lease or alter admission.
if (process.env.ANDCLAW_OPENCLAW_OWNERSHIP_DIAGNOSTICS === '1' &&
    !globalThis[Symbol.for('andclaw.ownershipDiagnostics.v1')]) {
  globalThis[Symbol.for('andclaw.ownershipDiagnostics.v1')] = true;
  const threads = require('node:worker_threads');
  if (!threads.isMainThread && !(threads.workerData?.locks &&
      threads.workerData.lastBeat instanceof SharedArrayBuffer)) return;
  const fs = require('node:fs');
  const path = require('node:path');
  const { errorMonitor } = require('node:events');
  const { syncBuiltinESMExports } = require('node:module');
  const { createHash } = require('node:crypto');
  const raw = Object.fromEntries([
    'openSync', 'closeSync', 'fstatSync', 'lstatSync', 'mkdirSync', 'writeSync',
    'ftruncateSync', 'statSync', 'readSync', 'futimesSync'
  ].map(name => [name, fs[name].bind(fs)]));
  const startMono = process.hrtime.bigint();
  const root = path.resolve(process.env.OPENCLAW_STATE_DIR ||
    path.join(process.env.HOME || '/root', '.openclaw'));
  const logParent = path.join(root, 'logs');
  const maxBytes = 512 * 1024;
  let directoryFd = null;
  let outputFd = null;
  let outputBytes = 0;
  let slot = 0;
  let sequence = 0;
  let loggerUnavailable = false;
  const monoMs = () => Number(process.hrtime.bigint() - startMono) / 1e6;
  const errorCode = error => typeof error?.code === 'string' &&
    /^(?:EACCES|EPERM|ENOENT|EBADF|EIO|EAGAIN|EINTR|ENOSPC|EFBIG|EMFILE|ENFILE|ELOOP|EINVAL)$/.test(error.code)
    ? error.code : 'UNKNOWN';
  const sameIdentity = (a, b) => a.dev === b.dev && a.ino === b.ino;
  function closeOutput() {
    if (outputFd !== null) {
      raw.closeSync(outputFd);
      outputFd = null;
    }
  }
  function openOutput(replace) {
    if (directoryFd === null) {
      raw.mkdirSync(logParent, { recursive: true, mode: 0o700 });
      const parent = raw.lstatSync(logParent, { bigint: true });
      if (!parent.isDirectory() || parent.isSymbolicLink()) {
        const error = new Error('Diagnostic log parent is not a real directory');
        error.code = 'EACCES';
        throw error;
      }
      const parentFd = raw.openSync(logParent,
        fs.constants.O_RDONLY | fs.constants.O_DIRECTORY | fs.constants.O_NOFOLLOW);
      try {
        if (!sameIdentity(parent, raw.fstatSync(parentFd, { bigint: true }))) {
          const error = new Error('Diagnostic log parent changed');
          error.code = 'EAGAIN';
          throw error;
        }
        const pinnedDirectory = `/proc/self/fd/${parentFd}/andclaw-ownership`;
        try { raw.mkdirSync(pinnedDirectory, { mode: 0o700 }); }
        catch (error) { if (error.code !== 'EEXIST') throw error; }
        const named = raw.lstatSync(pinnedDirectory, { bigint: true });
        if (!named.isDirectory() || named.isSymbolicLink() || (named.mode & 0o077n) !== 0n) {
          const error = new Error('Diagnostic directory is not private');
          error.code = 'EACCES';
          throw error;
        }
        directoryFd = raw.openSync(pinnedDirectory,
          fs.constants.O_RDONLY | fs.constants.O_DIRECTORY | fs.constants.O_NOFOLLOW);
        if (!sameIdentity(named, raw.fstatSync(directoryFd, { bigint: true }))) {
          const error = new Error('Diagnostic directory changed');
          error.code = 'EAGAIN';
          throw error;
        }
      } finally {
        raw.closeSync(parentFd);
      }
    }
    const name = `andclaw-ownership-${process.pid}-${threads.threadId}-${slot}.jsonl`;
    outputFd = raw.openSync(`/proc/self/fd/${directoryFd}/${name}`,
      fs.constants.O_WRONLY | fs.constants.O_APPEND | fs.constants.O_CREAT |
      fs.constants.O_NOFOLLOW | fs.constants.O_NONBLOCK, 0o600);
    const stat = raw.fstatSync(outputFd, { bigint: true });
    if (!stat.isFile() || stat.nlink !== 1n || (stat.mode & 0o077n) !== 0n) {
      const error = new Error('Diagnostic file is not private');
      error.code = 'EACCES';
      throw error;
    }
    if (replace) raw.ftruncateSync(outputFd, 0);
    outputBytes = replace ? 0 : Number(stat.size);
  }
  function echo(row) {
    if (process.stderr.writableLength < 65536) {
      process.stderr.write('[andclaw-ownership] ' + JSON.stringify(row) + '\n');
    }
  }
  function record(event, fields, important = false) {
    const row = {
      v: 1, event, ts: Date.now(), pid: process.pid, threadId: threads.threadId,
      monoMs: monoMs(), sequence: ++sequence, ...fields
    };
    try {
      if (!loggerUnavailable) {
        const bytes = Buffer.from(JSON.stringify(row) + '\n');
        if (outputFd === null) openOutput(false);
        if (outputBytes + bytes.length > maxBytes) {
          closeOutput();
          slot ^= 1;
          openOutput(true);
        }
        let written = 0;
        while (written < bytes.length) {
          const count = raw.writeSync(outputFd, bytes, written, bytes.length - written);
          if (count <= 0) {
            const error = new Error('Diagnostic write made no progress');
            error.code = 'EIO';
            throw error;
          }
          written += count;
        }
        outputBytes += written;
      }
      if (important) echo(row);
    } catch (error) {
      if (!loggerUnavailable) {
        loggerUnavailable = true;
        try { closeOutput(); } catch (_) { /* Diagnostic descriptors only. */ }
        if (directoryFd !== null) {
          try { raw.closeSync(directoryFd); } catch (_) { /* Diagnostic descriptor only. */ }
          directoryFd = null;
        }
        try {
          echo({ v: 1, event: 'logger-error', ts: Date.now(), pid: process.pid,
            threadId: threads.threadId, phase: 'error', code: errorCode(error) });
        } catch (_) { /* Logging must not replace the original worker outcome. */ }
      }
    }
  }
  function lockInfo(lockPath) {
    const name = path.basename(lockPath);
    const lockKind = /^state\.[a-f0-9]{16}\.lock$/.test(name) ? 'state'
      : /^gateway\.[a-f0-9]{8}\.lock$/.test(name) ? 'config'
      : name === 'gateway.state.lock' ? 'projection'
      : /^schema\./.test(name) ? 'schema' : 'unknown';
    return { lockKind, lockId: createHash('sha256').update(lockPath).digest('hex').slice(0, 16) };
  }
  // Keep only SDK cancellation provenance, weakly owned by the original signal.
  // Normal scope drains stay silent until an SDK consumer actually observes abort.
  const { getCallSites, types } = require('node:util');
  if (typeof getCallSites === 'function') {
    const controllerPrototype = AbortController.prototype;
    const signalPrototype = AbortSignal.prototype;
    const abortDescriptor = Object.getOwnPropertyDescriptor(controllerPrototype, 'abort');
    const throwDescriptor = Object.getOwnPropertyDescriptor(signalPrototype, 'throwIfAborted');
    const controllerSignal = Object.getOwnPropertyDescriptor(controllerPrototype, 'signal').get;
    const signalAborted = Object.getOwnPropertyDescriptor(signalPrototype, 'aborted').get;
    const domExceptionName = Object.getOwnPropertyDescriptor(DOMException.prototype, 'name').get;
    const signalStates = new WeakMap();
    let nextSignalId = 0;
    const sdkModule = /\/node_modules\/openclaw\/dist\/(async-work-scope|gateway-scheduler|session-row-projection|session-row-prepared-read|session-event-prepared-row|server-runtime-subscriptions)(?:-[A-Za-z0-9_-]{1,64})?\.mjs$/;
    const functions = new Set([
      'beginClose', 'drain', 'runWhenAllIdle', 'run', 'track', 'enter', 'registerWork',
      'stop', 'close', 'abort', 'closeWork', 'withPreparedRows', 'assertActive',
      'withReadySessionRows', 'select', 'consumeCurrent', 'withPreparedSessionRows',
      'consumePreparedSessionRows', 'withReadyRows', 'broadcastSessionActivitySummary', 'onChanged'
    ]);
    function safeFunction(name) {
      for (const prefix of ['', 'AsyncWorkScope.', 'GatewayScheduler.', 'Object.']) {
        if (name.startsWith(prefix) && functions.has(name.slice(prefix.length))) {
          return name.slice(prefix.length);
        }
      }
      return 'unknown';
    }
    function safeFrames() {
      const frames = [];
      // Structured locations avoid Error.prepareStackTrace, arbitrary stack strings,
      // and source maps that could substitute user-controlled paths or function names.
      for (const site of getCallSites(32, { sourceMap: false })) {
        const match = typeof site.scriptName === 'string' && sdkModule.exec(site.scriptName);
        if (!match || !Number.isSafeInteger(site.lineNumber) ||
            !Number.isSafeInteger(site.columnNumber) || site.lineNumber <= 0 ||
            site.lineNumber > 10000000 || site.columnNumber <= 0 || site.columnNumber > 10000000) continue;
        frames.push({ module: match[1],
          function: typeof site.functionName === 'string' ? safeFunction(site.functionName) : 'unknown',
          line: site.lineNumber, column: site.columnNumber });
        if (frames.length === 8) break;
      }
      return frames;
    }
    function stage(frames) {
      const module = frames[0].module;
      return module === 'async-work-scope' ? 'scope-close'
        : module === 'gateway-scheduler' ? 'scheduler-close'
        : module === 'session-row-projection' || module === 'session-row-prepared-read'
          ? 'row-preparation' : 'publication';
    }
    function reasonCategory(reason) {
      if (reason === null) return 'null';
      const type = typeof reason;
      if (type !== 'object' && type !== 'function') return type;
      // Call native brand-checking accessors only; never inspect reason.name,
      // message, stack, toString, or any property on a user-supplied reason.
      try {
        const name = Reflect.apply(domExceptionName, reason, []);
        return name === 'AbortError' ? 'abort-error'
          : name === 'TimeoutError' ? 'timeout-error' : 'dom-exception';
      } catch (_) { /* Not a native DOMException. */ }
      return types.isNativeError(reason) ? 'error' : type;
    }
    function stateFor(signal) {
      let state = signalStates.get(signal);
      if (!state && nextSignalId < Number.MAX_SAFE_INTEGER) {
        state = { signalId: ++nextSignalId, observed: false };
        signalStates.set(signal, state);
      }
      return state;
    }
    const cancellationLimit = 128;
    const cancellationWindowMs = 60000;
    let cancellationWindowStart = monoMs();
    let cancellationRows = 0;
    let cancellationLimited = false;
    function admitCancellation(rows) {
      const now = monoMs();
      if (now - cancellationWindowStart >= cancellationWindowMs) {
        cancellationWindowStart = now;
        cancellationRows = 0;
        cancellationLimited = false;
      }
      if (cancellationRows + rows <= cancellationLimit) {
        cancellationRows += rows;
        return true;
      }
      if (!cancellationLimited) {
        cancellationLimited = true;
        record('abort-diagnostics-limited', { phase: 'limited', operation: 'cancellation',
          limit: cancellationLimit, windowMs: cancellationWindowMs });
      }
      return false;
    }
    Object.defineProperty(controllerPrototype, 'abort', { ...abortDescriptor,
      value: { abort() {
        try {
          const frames = safeFrames();
          if (frames.length) {
            const signal = Reflect.apply(controllerSignal, this, []);
            if (!Reflect.apply(signalAborted, signal, [])) {
              const state = stateFor(signal);
              if (state) {
                const reasonKind = arguments[0] === undefined ? 'default' : 'explicit';
                state.issuance = { signalId: state.signalId, issuedMonoMs: monoMs(), frames,
                  stage: stage(frames), reasonKind,
                  reasonCategory: reasonKind === 'default' ? 'abort-error' : reasonCategory(arguments[0]) };
              }
            }
          }
        } catch (_) { /* Diagnosis must never replace the original abort outcome. */ }
        return Reflect.apply(abortDescriptor.value, this, arguments);
      } }.abort
    });
    Object.defineProperty(signalPrototype, 'throwIfAborted', { ...throwDescriptor,
      value: { throwIfAborted() {
        try {
          return Reflect.apply(throwDescriptor.value, this, arguments);
        } catch (reason) {
          try {
            const frames = safeFrames();
            if (frames.length && !types.isProxy(this)) {
              if (Reflect.apply(signalAborted, this, [])) {
                const state = stateFor(this);
                if (state && !state.observed) {
                  state.observed = true;
                  const issuance = state.issuance;
                  // Free captured frames after the first observation, even when
                  // rate-limited. No listener, timer, scope, or reason is retained.
                  delete state.issuance;
                  if (admitCancellation(issuance ? 2 : 1)) {
                    if (issuance) record('abort-issued', { operation: 'AbortController.abort',
                      phase: 'issue', ...issuance });
                    record('abort-observed', { operation: 'AbortSignal.throwIfAborted', phase: 'throw',
                      signalId: state.signalId, stage: stage(frames), frames,
                      reasonKind: issuance?.reasonKind || 'unobserved',
                      reasonCategory: reasonCategory(reason),
                      ...(issuance ? { issuedMonoMs: issuance.issuedMonoMs } : {}) });
                  }
                }
              }
            }
          } catch (_) { /* Keep the exact native cancellation reason, including non-Errors. */ }
          throw reason;
        }
      } }.throwIfAborted
    });
  }
  const heartbeatData = threads.workerData;
  if (!threads.isMainThread && heartbeatData?.locks &&
      heartbeatData.lastBeat instanceof SharedArrayBuffer &&
      Number.isFinite(heartbeatData.intervalMs) && Number.isFinite(heartbeatData.failureMs)) {
    const targets = new Map();
    function targetInfo(file) {
      if (typeof file !== 'string') return undefined;
      if (!Object.hasOwn(heartbeatData.locks, file)) {
        targets.delete(file);
        return undefined;
      }
      let info = targets.get(file);
      if (!info) {
        if (targets.size >= 64) targets.clear();
        info = lockInfo(file);
        targets.set(file, info);
      }
      return info;
    }
    const descriptors = new Map();
    const beat = new BigInt64Array(heartbeatData.lastBeat);
    function stage(operation, info, invoke, complete) {
      const begun = monoMs();
      record('stage-begin', { operation, phase: 'begin', ...info });
      let value;
      try {
        value = invoke();
      } catch (error) {
        record('stage-end', { operation, phase: 'error', durationMs: monoMs() - begun,
          code: errorCode(error), ...info }, true);
        throw error;
      }
      const durationMs = monoMs() - begun;
      let result = {};
      try {
        if (complete) result = complete(value);
      } catch (error) {
        record('logger-error', { phase: 'error', code: errorCode(error) }, true);
      }
      record('stage-end', { operation, phase: 'end', durationMs,
        beatAgeMs: Date.now() - Number(Atomics.load(beat, 0)), ...info, ...result }, durationMs >= 250);
      return value;
    }
    fs.openSync = function(file, ...args) {
      const info = targetInfo(file);
      if (!info) return raw.openSync(file, ...args);
      return stage('openSync', info, () => raw.openSync(file, ...args), fd => {
        descriptors.set(fd, info);
        return { fd };
      });
    };
    const metadata = stat => ({ dev: String(stat.dev), ino: String(stat.ino), nlink: String(stat.nlink) });
    fs.fstatSync = function(fd, ...args) {
      const info = descriptors.get(fd);
      return info ? stage('fstatSync', { fd, ...info }, () => raw.fstatSync(fd, ...args), metadata)
        : raw.fstatSync(fd, ...args);
    };
    fs.statSync = function(file, ...args) {
      const info = targetInfo(file);
      return info ? stage('statSync', info, () => raw.statSync(file, ...args), metadata)
        : raw.statSync(file, ...args);
    };
    fs.readSync = function(fd, ...args) {
      const info = descriptors.get(fd);
      return info ? stage('readSync', { fd, ...info }, () => raw.readSync(fd, ...args), bytes => ({ bytes }))
        : raw.readSync(fd, ...args);
    };
    fs.futimesSync = function(fd, ...args) {
      const info = descriptors.get(fd);
      return info ? stage('futimesSync', { fd, ...info }, () => raw.futimesSync(fd, ...args))
        : raw.futimesSync(fd, ...args);
    };
    fs.closeSync = function(fd, ...args) {
      const info = descriptors.get(fd);
      return info ? stage('closeSync', { fd, ...info }, () => raw.closeSync(fd, ...args), () => {
        descriptors.delete(fd);
        return {};
      }) : raw.closeSync(fd, ...args);
    };
    syncBuiltinESMExports();
    record('process-start', { intervalMs: heartbeatData.intervalMs, failureMs: heartbeatData.failureMs }, true);
  } else if (threads.isMainThread) {
    const Worker = threads.Worker;
    threads.Worker = class extends Worker {
      constructor(file, options) {
        super(file, options);
        const data = options?.workerData;
        if (!/\/gateway-state-owner-heartbeat\.worker\.(?:js|mjs)$/.test(String(file)) ||
            !(data?.lastBeat instanceof SharedArrayBuffer)) return;
        const workerThreadId = this.threadId;
        const beat = new BigInt64Array(data.lastBeat);
        const clock = data.clock === 'epoch' ? 'epoch' : 'unknown';
        record('worker-created', { workerThreadId, intervalMs: data.intervalMs,
          failureMs: data.failureMs, clock }, true);
        let previousWall = Date.now();
        let previousMono = monoMs();
        const sample = () => {
          const wall = Date.now();
          const mono = monoMs();
          const lastBeatMs = Number(Atomics.load(beat, 0));
          const beatAgeMs = clock === 'epoch' ? wall - lastBeatMs : undefined;
          record('heartbeat-sample', {
            workerThreadId, workerAlive: this.threadId !== -1, lastBeatMs, beatAgeMs,
            intervalMs: data.intervalMs, failureMs: data.failureMs, clock,
            wallDeltaMs: wall - previousWall, monoDeltaMs: mono - previousMono,
            monitorLagMs: Math.max(0, mono - previousMono - 5000)
          }, beatAgeMs >= 15000 || mono - previousMono >= 10000);
          previousWall = wall;
          previousMono = mono;
        };
        const timer = setInterval(sample, 5000);
        timer.unref();
        // errorMonitor observes without consuming or masking an unhandled error.
        this.on(errorMonitor, error => record('worker-error', {
          workerThreadId, phase: 'error', code: errorCode(error)
        }, true));
        this.once('exit', exitCode => {
          clearInterval(timer);
          record('worker-exit', { workerThreadId, exitCode, workerAlive: false,
            beatAgeMs: clock === 'epoch' ? Date.now() - Number(Atomics.load(beat, 0)) : undefined }, true);
        });
      }
    };
    syncBuiltinESMExports();
    record('process-start', {}, true);
    process.once('exit', exitCode => record('process-exit', { exitCode }, true));
  }
}
