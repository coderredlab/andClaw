package com.coderred.andclaw.proroot

import android.content.Context
import android.util.Log
import com.coderred.andclaw.data.PreferencesManager
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/**
 * proroot 바이너리, rootfs, Node.js 경로를 관리하고
 * proroot 명령어를 구성하는 핵심 매니저.
 *
 * proroot 바이너리는 APK의 jniLibs/arm64-v8a/libproroot.so 로 패키징되어
 * 설치 시 nativeLibraryDir에 자동 추출된다.
 */
class ProrootManager(
    private val context: Context,
    private val runtimeProvider: (() -> ExecutionRuntime)? = null,
) {
    @Volatile
    private var activeRuntime: ExecutionRuntime = ExecutionRuntime.PROROOT

    data class CommandResult(
        val exitCode: Int,
        val output: String,
        val timedOut: Boolean = false,
    )

    data class OsReleaseRepairResult(
        val changed: Boolean,
        val etcOsReleaseBytesBefore: Long?,
        val usrLibOsReleaseBytesBefore: Long?,
        val lsbReleaseBytesBefore: Long?,
        val alpineReleaseBytesBefore: Long?,
        val etcOsReleaseBytesAfter: Long,
        val usrLibOsReleaseBytesAfter: Long,
        val lsbReleaseBytesAfter: Long,
        val alpineReleaseExistsAfter: Boolean,
    ) {
        fun diagnosticSummary(): String {
            fun bytes(value: Long?): String = value?.let { "${it}B" } ?: "missing"
            return "osReleaseFix=${if (changed) "changed" else "ok"} " +
                "etcBefore=${bytes(etcOsReleaseBytesBefore)} " +
                "usrBefore=${bytes(usrLibOsReleaseBytesBefore)} " +
                "lsbBefore=${bytes(lsbReleaseBytesBefore)} " +
                "alpineBefore=${bytes(alpineReleaseBytesBefore)} " +
                "etcAfter=${etcOsReleaseBytesAfter}B " +
                "usrAfter=${usrLibOsReleaseBytesAfter}B " +
                "lsbAfter=${lsbReleaseBytesAfter}B " +
                "alpineAfter=${if (alpineReleaseExistsAfter) "present" else "missing"}"
        }
    }

    companion object {
        // Ubuntu 24.04 LTS arm64 base rootfs (bundled in assets)
        const val ROOTFS_ASSET = "rootfs.tar.gz.bin"

        // Node.js 24 LTS arm64 linux (bundled in assets)
        const val NODEJS_VERSION = "v24.2.0"
        const val NODEJS_ASSET = "node-arm64.tar.gz.bin"
        const val NODEJS_DIR_NAME = "node-$NODEJS_VERSION-linux-arm64"

        // 3분할 번들 에셋
        const val SYSTEM_TOOLS_ASSET = "system-tools-arm64.tar.gz.bin"
        const val OPENCLAW_ASSET = "openclaw-arm64.tar.gz.bin"
        const val PLAYWRIGHT_ASSET = "playwright-chromium-arm64.tar.gz.bin"
        private const val PLAYWRIGHT_CHROME_MARKER = ".playwright_chrome_path"
        const val OPENCLAW_NODE_BIN = "/usr/local/bin/node"
        const val OPENCLAW_ENTRYPOINT = "/usr/local/lib/node_modules/openclaw/openclaw.mjs"
        private const val CODEX_PLUGINS_BASE = "/root/.openclaw/andclaw-bundled-plugins/npm/node_modules"
        private const val CODEX_VENDOR_SUFFIX = "@openai/codex-linux-arm64/vendor/aarch64-unknown-linux-musl"
        const val OPENCLAW_CODEX_APP_SERVER_BIN_FALLBACK = "$CODEX_PLUGINS_BASE/$CODEX_VENDOR_SUFFIX/codex/codex"
        const val OPENCLAW_CODEX_APP_SERVER_PATH_DIR_FALLBACK = "$CODEX_PLUGINS_BASE/$CODEX_VENDOR_SUFFIX/path"
        const val OPENCLAW_CODEX_APP_SERVER_WRAPPER = "/root/.openclaw/andclaw-codex-app-server.sh"
        const val CODEX_RUST_LOG_PATH = "/root/.openclaw/agents/main/agent/codex-home/codex-rust.log"
        const val CODEX_RUST_LOG_FILTER =
            "info,codex_api::endpoint::responses=debug,codex_api::sse::responses=debug,codex_app_server=debug"
        const val GUEST_HOOK_LIB_PATH = "/root/.proroot/libproroot-runtime.so"

        const val GUEST_VFORK_SHIM_PATH = "/root/.proroot/libvfork_shim.so"

        const val PROROOT_LAUNCHER_LIB = "libproroot.so"
        const val PROROOT_RUNTIME_LIB = "libproroot-runtime.so"
        const val PROROOT_BRIDGE_LIB = "libproroot-bridge.so"
        const val PROROOT_LINKER_LIB = "libproroot-linker.so"

        internal val BUNDLED_ROOTFS_OS_RELEASE = """
            PRETTY_NAME="Ubuntu 24.04.3 LTS"
            NAME="Ubuntu"
            VERSION_ID="24.04"
            VERSION="24.04.3 LTS (Noble Numbat)"
            VERSION_CODENAME=noble
            ID=ubuntu
            ID_LIKE=debian
            HOME_URL="https://www.ubuntu.com/"
            SUPPORT_URL="https://help.ubuntu.com/"
            BUG_REPORT_URL="https://bugs.launchpad.net/ubuntu/"
            PRIVACY_POLICY_URL="https://www.ubuntu.com/legal/terms-and-policies/privacy-policy"
            UBUNTU_CODENAME=noble
            LOGO=ubuntu-logo
        """.trimIndent() + "\n"

        internal val BUNDLED_ROOTFS_LSB_RELEASE = """
            DISTRIB_ID=Ubuntu
            DISTRIB_RELEASE=24.04
            DISTRIB_CODENAME=noble
            DISTRIB_DESCRIPTION="Ubuntu 24.04.3 LTS"
        """.trimIndent() + "\n"
    }

    // ── 경로 ──

    /** nativeLibraryDir 의 proroot direct-exec binary (APK에서 추출됨) */
    private val nativeProrootPath: String
        get() = File(context.applicationInfo.nativeLibraryDir, PROROOT_LAUNCHER_LIB).absolutePath

    /** nativeLibraryDir 의 legacy proot binary */
    private val nativeProotPath: String
        get() = File(context.applicationInfo.nativeLibraryDir, "libproot.so").absolutePath

    /** nativeLibraryDir 의 legacy libtalloc */
    private val nativeTallocPath: String
        get() = File(context.applicationInfo.nativeLibraryDir, "libtalloc.so").absolutePath

    /** nativeLibraryDir 의 legacy proot loader */
    private val nativeProotLoaderPath: String
        get() = File(context.applicationInfo.nativeLibraryDir, "libproot-loader.so").absolutePath

    /** nativeLibraryDir 의 legacy 32-bit proot loader */
    private val nativeProotLoader32Path: String
        get() = File(context.applicationInfo.nativeLibraryDir, "libproot-loader32.so").absolutePath

    /** nativeLibraryDir 의 proroot hook library */
    private val nativeHookLibPath: String
        get() = File(context.applicationInfo.nativeLibraryDir, PROROOT_RUNTIME_LIB).absolutePath


    /**
     * rootfs linker가 접근 가능한 경로에 복사된 hook library.
     * nativeLibraryDir는 SELinux로 rootfs linker가 접근 불가하므로
     * filesDir로 복사해서 LD_PRELOAD에 사용한다.
     */
    val hookLibPath: File
        get() = File(context.filesDir, "libproroot-runtime.so")

    val guestHookLibHostPath: File
        get() = File(rootfsDir, "root/.proroot/libproroot-runtime.so")


    val guestVforkShimHostPath: File
        get() = File(rootfsDir, "root/.proroot/libvfork_shim.so")

    private data class CodexPaths(val bin: String, val pathDir: String)

    private fun resolveCodexPaths(): CodexPaths {
        val base = "root/.openclaw/andclaw-bundled-plugins/npm/node_modules"
        val vendor = "@openai/codex-linux-arm64/vendor/aarch64-unknown-linux-musl"
        val candidates = listOf(
            CodexPaths("$CODEX_PLUGINS_BASE/@openclaw/codex/node_modules/$vendor/bin/codex",
                       "$CODEX_PLUGINS_BASE/@openclaw/codex/node_modules/$vendor/codex-path"),
            CodexPaths("$CODEX_PLUGINS_BASE/$vendor/bin/codex",
                       "$CODEX_PLUGINS_BASE/$vendor/codex-path"),
            CodexPaths("$CODEX_PLUGINS_BASE/@openclaw/codex/node_modules/$vendor/codex/codex",
                       "$CODEX_PLUGINS_BASE/@openclaw/codex/node_modules/$vendor/path"),
            CodexPaths(OPENCLAW_CODEX_APP_SERVER_BIN_FALLBACK,
                       OPENCLAW_CODEX_APP_SERVER_PATH_DIR_FALLBACK),
        )
        for (c in candidates) {
            val hostBin = File(rootfsDir, c.bin.removePrefix("/"))
            if (hostBin.exists()) return c
        }
        return candidates.first()
    }

    val codexAppServerBin: String get() = resolveCodexPaths().bin
    val codexAppServerPathDir: String get() = resolveCodexPaths().pathDir

    /** 실행용 바이너리 디렉토리 (legacy proot용) */
    private val binDir: File
        get() = File(context.filesDir, "bin")

    /** 실행용 라이브러리 디렉토리 (legacy proot용) */
    val libLinksDir: File
        get() = File(context.filesDir, "lib")

    /** 실제 실행할 proroot 바이너리 경로 (nativeLibraryDir 에서 직접 실행) */
    val prorootBinaryPath: String
        get() = nativeProrootPath

    val prootBinaryPath: String
        get() = nativeProotPath

    val selectedRuntime: ExecutionRuntime
        get() = runtimeProvider?.invoke() ?: runCatching {
            runBlocking { ExecutionRuntime.fromStorageValue(PreferencesManager(context).executionRuntime.first()) }
        }.getOrDefault(ExecutionRuntime.PROROOT)

    val currentRuntime: ExecutionRuntime
        get() = activeRuntime

    val activeRuntimeBinaryPath: String
        get() = when (selectedRuntime) {
            ExecutionRuntime.PROOT -> prootBinaryPath
            ExecutionRuntime.PROROOT -> prorootBinaryPath
        }

    val rootfsDir: File
        get() = File(context.filesDir, "rootfs")

    internal fun foreignPrivateLeaseOwnerProbe(): ((Int, String) -> Boolean)? =
        OpenClawAgentLeaseRecovery.foreignPrivateOwnerProbe(context, rootfsDir)

    val cacheDir: File
        get() = context.cacheDir

    private val playwrightCacheDir: File
        get() = File(rootfsDir, "root/.cache/ms-playwright")

    private val playwrightChromeMarkerFile: File
        get() = File(rootfsDir, PLAYWRIGHT_CHROME_MARKER)

    val homeDir: File
        get() = File(rootfsDir, "root")

    // ── 상태 확인 ──

    val isProrootAvailable: Boolean
        get() {
            val requiredFiles = listOf(
                File(nativeProrootPath),
                File(nativeHookLibPath),
                File(nativeLinkerPath),
                File(nativeTrampolinePath),
            )
            return requiredFiles.all { it.exists() && it.canExecute() }
        }

    val isProotAvailable: Boolean
        get() {
            val requiredFiles = listOf(
                File(nativeProotPath),
                File(nativeTallocPath),
                File(nativeProotLoaderPath),
                File(nativeProotLoader32Path),
            )
            return requiredFiles.all { it.exists() && it.canExecute() }
        }

    fun isRuntimeAvailable(runtime: ExecutionRuntime = selectedRuntime): Boolean = when (runtime) {
        ExecutionRuntime.PROOT -> isProotAvailable
        ExecutionRuntime.PROROOT -> isProrootAvailable
    }

    val isRootfsInstalled: Boolean
        get() = File(rootfsDir, "bin/sh").exists()

    val isNodeInstalled: Boolean
        get() = File(rootfsDir, "usr/local/bin/node").exists()

    val isSystemToolsInstalled: Boolean
        get() = File(rootfsDir, "usr/bin/git").exists()

    val isOpenClawInstalled: Boolean
        get() = File(rootfsDir, "usr/local/lib/node_modules/openclaw").exists() &&
            File(rootfsDir, "usr/local/bin/openclaw").exists()

    val isChromiumInstalled: Boolean
        get() {
            val markerPath = readChromiumMarkerPath()
            if (markerPath != null) {
                val markerBinary = File(markerPath)
                if (markerBinary.exists() && markerBinary.canExecute()) {
                    return true
                }
            }

            val detectedPath = detectChromiumExecutablePath()
            if (detectedPath != null) {
                writeChromiumMarkerPath(detectedPath)
                return true
            }

            clearChromiumMarkerPath()
            return false
        }

    val isFullySetup: Boolean
        get() = isRuntimeAvailable() && isRootfsInstalled && isNodeInstalled && isOpenClawInstalled

    fun refreshChromiumExecutableMarker(): Boolean {
        val detectedPath = detectChromiumExecutablePath() ?: run {
            clearChromiumMarkerPath()
            return false
        }

        writeChromiumMarkerPath(detectedPath)
        return true
    }

    fun detectChromiumExecutableProotPath(): String? {
        val detectedPath = detectChromiumExecutablePath() ?: return null
        return "/" + File(detectedPath).toRelativeString(rootfsDir).replace('\\', '/')
    }

    /**
     * Chromium wrapper 스크립트를 생성하고 proroot 내부 경로를 반환한다.
     * OpenClaw의 browser 스키마가 .strict()라서 extraArgs를 무시하므로,
     * --no-zygote --single-process --disable-dev-shm-usage 등은
     * wrapper 스크립트로 주입해야 한다.
     */
    fun ensureChromiumWrapper(extraArgs: List<String>): String? {
        val rawPath = detectChromiumExecutablePath() ?: return null
        val rawProotPath = "/" + File(rawPath).toRelativeString(rootfsDir).replace('\\', '/')
        val wrapperName = "chromium-proroot-wrapper.sh"
        val wrapperFile = File(rawPath).parentFile?.let { File(it, wrapperName) } ?: return null
        val argsStr = extraArgs.joinToString(" ") { "\"$it\"" }
        val chromeDir = "/" + File(rawPath).parentFile!!.toRelativeString(rootfsDir).replace('\\', '/')
        val script = "#!/bin/sh\nexport LD_LIBRARY_PATH=\"$chromeDir:\${LD_LIBRARY_PATH:-}\"\nexec $rawProotPath $argsStr \"\$@\"\n"
        val existing = if (wrapperFile.exists()) wrapperFile.readText() else ""
        if (existing != script) {
            wrapperFile.writeText(script)
            wrapperFile.setExecutable(true)
        }
        return "/" + wrapperFile.toRelativeString(rootfsDir).replace('\\', '/')
    }

    private fun detectChromiumExecutablePath(): String? {
        val browserRoot = playwrightCacheDir
        val browserDirs = browserRoot.listFiles()
            ?.filter { it.isDirectory && it.name.startsWith("chromium") }
            ?.sortedWith(
                compareByDescending<File> { it.name.startsWith("chromium_headless_shell") }
                    .thenByDescending { it.name },
            )
            ?: return null

        for (browserDir in browserDirs) {
            val headlessShell = File(browserDir, "chrome-linux/headless_shell")
            if (headlessShell.exists() && headlessShell.canExecute()) {
                return headlessShell.absolutePath
            }

            val chrome = File(browserDir, "chrome-linux/chrome")
            if (chrome.exists() && chrome.canExecute()) {
                return chrome.absolutePath
            }
        }

        return null
    }

    private fun readChromiumMarkerPath(): String? {
        return runCatching {
            if (!playwrightChromeMarkerFile.exists()) return null
            playwrightChromeMarkerFile.readText().trim().takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    private fun writeChromiumMarkerPath(path: String) {
        runCatching {
            playwrightChromeMarkerFile.writeText(path)
        }
    }

    private fun clearChromiumMarkerPath() {
        if (playwrightChromeMarkerFile.exists()) {
            playwrightChromeMarkerFile.delete()
        }
    }

    // ── hook library 준비 ──

    /**
     * libproroot-runtime.so를 nativeLibraryDir에서 filesDir로 복사한다.
     * rootfs 안쪽의 guest loader가 접근하려면 SELinux 접근 가능한 경로가 필요.
     */
    fun setupHookLibrary() {
        val src = File(nativeHookLibPath)
        val dst = hookLibPath
        Log.d("ProrootManager", "setupHookLibrary src=${src.absolutePath} srcHash=${src.sha256OrNull()}")
        // 항상 복사 — 크기가 같아도 내용이 다를 수 있음 (빌드 업데이트 시)
        if (src.exists()) {
            dst.delete()
            src.inputStream().use { input ->
                dst.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            dst.setReadable(true, false)
            dst.setExecutable(true, false)
            Log.d("ProrootManager", "setupHookLibrary dst=${dst.absolutePath} dstHash=${dst.sha256OrNull()}")

            val guestDst = guestHookLibHostPath
            guestDst.parentFile?.mkdirs()
            runCatching {
                src.inputStream().use { input ->
                    guestDst.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                guestDst.setReadable(true, false)
                guestDst.setExecutable(true, false)
                Log.d("ProrootManager", "setupHookLibrary guestDst=${guestDst.absolutePath} guestHash=${guestDst.sha256OrNull()}")
            }


            val vforkShimSrc = File(context.filesDir, "libvfork_shim.so")
            if (vforkShimSrc.exists()) {
                val vforkShimGuestDst = guestVforkShimHostPath
                vforkShimGuestDst.parentFile?.mkdirs()
                runCatching {
                    vforkShimSrc.inputStream().use { input ->
                        vforkShimGuestDst.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                    vforkShimGuestDst.setReadable(true, false)
                    vforkShimGuestDst.setExecutable(true, false)
                    Log.d("ProrootManager", "setupHookLibrary vforkShim=${vforkShimGuestDst.absolutePath} hash=${vforkShimGuestDst.sha256OrNull()}")
                }
            }
        }
    }

    fun setupNativeLibLinks() {
        binDir.mkdirs()
        libLinksDir.mkdirs()

        val nativeProot = File(nativeProotPath)
        val localProot = File(binDir, "proot")
        if (nativeProot.exists() && (!localProot.exists() || localProot.length() != nativeProot.length())) {
            nativeProot.inputStream().use { input ->
                localProot.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            localProot.setExecutable(true, false)
            localProot.setReadable(true, false)
        }

        val nativeTalloc = File(nativeTallocPath)
        val localTalloc = File(libLinksDir, "libtalloc.so.2")
        val isSymlink = localTalloc.exists() && java.nio.file.Files.isSymbolicLink(localTalloc.toPath())
        val needsCopy = !localTalloc.exists() || isSymlink || localTalloc.length() != nativeTalloc.length()

        if (nativeTalloc.exists() && needsCopy) {
            localTalloc.delete()
            nativeTalloc.inputStream().use { input ->
                localTalloc.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            localTalloc.setReadable(true, false)
            localTalloc.setExecutable(true, false)
        }
    }

    fun prepareRuntime(runtime: ExecutionRuntime) {
        activeRuntime = runtime
        when (runtime) {
            ExecutionRuntime.PROOT -> setupNativeLibLinks()
            ExecutionRuntime.PROROOT -> setupHookLibrary()
        }
    }

    fun prepareSelectedRuntime() {
        prepareRuntime(selectedRuntime)
    }

    fun repairRootfsOsReleaseFiles(): OsReleaseRepairResult {
        val etcDir = File(rootfsDir, "etc").apply { mkdirs() }
        val usrLibDir = File(rootfsDir, "usr/lib").apply { mkdirs() }
        val etcOsRelease = File(etcDir, "os-release")
        val usrLibOsRelease = File(usrLibDir, "os-release")
        val lsbRelease = File(etcDir, "lsb-release")
        val alpineRelease = File(etcDir, "alpine-release")

        val etcBefore = etcOsRelease.readTextOrNull()
        val usrBefore = usrLibOsRelease.readTextOrNull()
        val lsbBefore = lsbRelease.readTextOrNull()
        val alpineBytesBefore = alpineRelease.sizeOrNull()
        val alpineExistedBefore = alpineRelease.exists() || Files.isSymbolicLink(alpineRelease.toPath())
        val changed = etcBefore != BUNDLED_ROOTFS_OS_RELEASE ||
            usrBefore != BUNDLED_ROOTFS_OS_RELEASE ||
            lsbBefore != BUNDLED_ROOTFS_LSB_RELEASE ||
            alpineExistedBefore

        usrLibOsRelease.writePlainTextFile(BUNDLED_ROOTFS_OS_RELEASE)
        etcOsRelease.writePlainTextFile(BUNDLED_ROOTFS_OS_RELEASE)
        lsbRelease.writePlainTextFile(BUNDLED_ROOTFS_LSB_RELEASE)

        if (alpineExistedBefore && !alpineRelease.delete() && (alpineRelease.exists() || Files.isSymbolicLink(alpineRelease.toPath()))) {
            throw IOException("Cannot remove stale Alpine release file: ${alpineRelease.absolutePath}")
        }

        return OsReleaseRepairResult(
            changed = changed,
            etcOsReleaseBytesBefore = etcBefore?.toByteArray()?.size?.toLong(),
            usrLibOsReleaseBytesBefore = usrBefore?.toByteArray()?.size?.toLong(),
            lsbReleaseBytesBefore = lsbBefore?.toByteArray()?.size?.toLong(),
            alpineReleaseBytesBefore = alpineBytesBefore,
            etcOsReleaseBytesAfter = etcOsRelease.length(),
            usrLibOsReleaseBytesAfter = usrLibOsRelease.length(),
            lsbReleaseBytesAfter = lsbRelease.length(),
            alpineReleaseExistsAfter = alpineRelease.exists() || Files.isSymbolicLink(alpineRelease.toPath()),
        )
    }

    fun ldLibraryPath(): String {
        return "${libLinksDir.absolutePath}:${context.applicationInfo.nativeLibraryDir}"
    }

    private fun File.readTextOrNull(): String? {
        return runCatching {
            if (!exists() && !Files.isSymbolicLink(toPath())) return null
            readText()
        }.getOrNull()
    }

    private fun File.sizeOrNull(): Long? {
        return runCatching {
            if (!exists() && !Files.isSymbolicLink(toPath())) return null
            length()
        }.getOrNull()
    }

    private fun File.writePlainTextFile(content: String) {
        parentFile?.mkdirs()
        if ((exists() || Files.isSymbolicLink(toPath())) && !delete()) {
            throw IOException("Cannot replace file: $absolutePath")
        }
        writeText(content)
    }

    private fun File.sha256OrNull(): String? {
        if (!exists()) return null
        return runCatching {
            val digest = MessageDigest.getInstance("SHA-256")
            inputStream().use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    digest.update(buffer, 0, read)
                }
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        }.getOrNull()
    }

    // ── 명령어 구성 ──

    /** /dev/shm 에뮬레이션 디렉토리 (Chromium shared memory 용) */
    val shmDir: File
        get() = File(context.cacheDir, "shm").apply { mkdirs() }

    fun buildProrootCommand(
        command: String,
        runtime: ExecutionRuntime = currentRuntime,
    ): List<String> {
        if (runtime == ExecutionRuntime.PROOT) {
            return listOf(
                prootBinaryPath,
                "-r", rootfsDir.absolutePath,
                "-b", "/dev",
                "-b", "/proc",
                "-b", "/sys",
                "-b", "/dev/urandom:/dev/random",
                "-b", "${shmDir.absolutePath}:/dev/shm",
                "-0",
                "-w", "/root",
                "--link2symlink",
                "/bin/sh", "-c", command,
            )
        }
        return listOf(
            prorootBinaryPath,
            "-r", rootfsDir.absolutePath,
            "-b", "/dev:/dev",
            "-b", "/proc:/proc",
            "-b", "/sys:/sys",
            "-b", "/dev/urandom:/dev/random",
            "-b", "${shmDir.absolutePath}:/dev/shm",
            "-w", "/root",
            "--link2symlink",    // Android 파일시스템에서 hardlink→symlink 변환
            "/bin/sh", "-c", command,
        )
    }

    fun buildProrootArgvCommand(
        argv: List<String>,
        runtime: ExecutionRuntime = currentRuntime,
    ): List<String> {
        require(argv.isNotEmpty()) { "argv must not be empty" }
        if (runtime == ExecutionRuntime.PROOT) {
            return buildList {
                add(prootBinaryPath)
                add("-r")
                add(rootfsDir.absolutePath)
                add("-b")
                add("/dev")
                add("-b")
                add("/proc")
                add("-b")
                add("/sys")
                add("-b")
                add("/dev/urandom:/dev/random")
                add("-b")
                add("${shmDir.absolutePath}:/dev/shm")
                add("-0")
                add("-w")
                add("/root")
                add("--link2symlink")
                addAll(argv)
            }
        }
        return buildList {
            add(prorootBinaryPath)
            add("-r")
            add(rootfsDir.absolutePath)
            add("-b")
            add("/dev:/dev")
            add("-b")
            add("/proc:/proc")
            add("-b")
            add("/sys:/sys")
            add("-b")
            add("/dev/urandom:/dev/random")
            add("-b")
            add("${shmDir.absolutePath}:/dev/shm")
            add("-w")
            add("/root")
            add("--link2symlink")
            addAll(argv)
        }
    }

    fun buildOpenClawNodeCommand(
        vararg args: String,
        runtime: ExecutionRuntime = currentRuntime,
    ): List<String> {
        return buildProrootArgvCommand(
            listOf(OPENCLAW_NODE_BIN, OPENCLAW_ENTRYPOINT) + args.toList(),
            runtime = runtime,
        )
    }

    internal fun buildHostShellWrappedCommand(command: List<String>): List<String> {
        fun quote(arg: String): String = "'" + arg.replace("'", "'\\''") + "'"
        return listOf(
            "/system/bin/sh",
            "-c",
            command.joinToString(" ") { quote(it) },
        )
    }

    fun buildGatewayCommand(runtime: ExecutionRuntime = currentRuntime): List<String> {
        return buildProrootCommand(
                "export UV_USE_IO_URING=0 && " +
                "export RUST_LOG='$CODEX_RUST_LOG_FILTER' && " +
                "export RUST_BACKTRACE=1 && " +
                "export PATH=$codexAppServerPathDir:/usr/local/bin:/usr/bin:/bin && " +
                "export OPENCLAW_CODEX_APP_SERVER_BIN=$OPENCLAW_CODEX_APP_SERVER_WRAPPER && " +
                "export OPENCLAW_CODEX_APP_SERVER_ARGS='app-server --listen stdio://' && " +
                "export OPENCLAW_CODEX_APP_SERVER_MODE=yolo && " +
                "export OPENCLAW_CODEX_APP_SERVER_APPROVAL_POLICY=never && " +
                "export OPENCLAW_CODEX_APP_SERVER_SANDBOX=danger-full-access && " +
                "export OPENCLAW_CODEX_DISCOVERY_LIVE=0 && " +
                "export NODE_OPTIONS='--require /root/.openclaw-patch.js' && " +
                "export NODE_COMPILE_CACHE=/root/.cache/node-compile-cache && " +
                "mkdir -p /root/.openclaw/workspace && " +
                "cd /root/.openclaw/workspace && " +
                "openclaw gateway run",
            runtime = runtime,
        )
    }

    val isOpenClawConfigured: Boolean
        get() = File(rootfsDir, "root/.openclaw/openclaw.json").exists()

    /** nativeLibraryDir의 clean-room linker — guest execve용 */
    private val nativeLinkerPath: String
        get() = File(context.applicationInfo.nativeLibraryDir, PROROOT_LINKER_LIB).absolutePath

    /** nativeLibraryDir의 static trampoline — seccomp-safe child exec용 */
    private val nativeTrampolinePath: String
        get() = File(context.applicationInfo.nativeLibraryDir, PROROOT_BRIDGE_LIB).absolutePath

    fun buildEnvironment(
        extra: Map<String, String> = emptyMap(),
        runtime: ExecutionRuntime = currentRuntime,
    ): Map<String, String> {
        if (runtime == ExecutionRuntime.PROOT) {
            val nativeDir = context.applicationInfo.nativeLibraryDir
            return buildMap {
                put("HOME", homeDir.absolutePath)
                put("LD_LIBRARY_PATH", ldLibraryPath())
                put("PROOT_TMP_DIR", File(context.cacheDir, "proot_tmp").apply { mkdirs() }.absolutePath)
                put("PROOT_LOADER", nativeProotLoaderPath.ifBlank { "$nativeDir/libproot-loader.so" })
                put("PROOT_LOADER_32", nativeProotLoader32Path.ifBlank { "$nativeDir/libproot-loader32.so" })
                put("PLAYWRIGHT_BROWSERS_PATH", File(rootfsDir, "root/.cache/ms-playwright").absolutePath)
                putAll(extra)
            }
        }
        return buildMap {
            put("HOME", homeDir.absolutePath)
            put("PROROOT_LIB_PATH", nativeHookLibPath)
            put("PROROOT_LINKER_PATH", nativeLinkerPath)
            put("PROROOT_TRAMPOLINE_PATH", nativeTrampolinePath)
            put("PROROOT_ROOTFS", rootfsDir.absolutePath)
            put("PROROOT_GUEST_EXE", OPENCLAW_NODE_BIN)
            put("PROROOT_TMP_DIR", context.filesDir.absolutePath)
            if (guestVforkShimHostPath.exists()) {
                put("PROROOT_GUEST_LD_PRELOAD", GUEST_VFORK_SHIM_PATH)
            }
            put("PLAYWRIGHT_BROWSERS_PATH", File(rootfsDir, "root/.cache/ms-playwright").absolutePath)
            // PROROOT_TRACE set only in ProcessManager for gateway debugging
            putAll(extra)
        }
    }

    internal fun applyEnvironment(
        target: MutableMap<String, String>,
        extra: Map<String, String> = emptyMap(),
        runtime: ExecutionRuntime = currentRuntime,
    ) {
        target.clear()
        target.putAll(
            buildEnvironment(
                mapOf(
                    "HOME" to "/root",
                    "PATH" to "/usr/local/bin:/usr/bin:/bin",
                    "LANG" to "C.UTF-8",
                    "UV_USE_IO_URING" to "0",
                ) + extra,
                runtime = runtime,
            ),
        )
    }

    /**
     * proroot 안에서 명령을 실행하고 stdout 결과를 반환한다.
     */
    fun executeAndCapture(
        command: String,
        runtime: ExecutionRuntime = currentRuntime,
    ): String? {
        return try {
            val cmd = buildProrootCommand(command, runtime)
            val pb = ProcessBuilder(cmd).redirectErrorStream(false)
            applyEnvironment(pb.environment(), runtime = runtime)
            val process = pb.start()
            val output = process.inputStream.bufferedReader().readText().trim()
            process.waitFor()
            output.ifBlank { null }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * proroot 안에서 명령을 실행하고 종료코드/출력을 반환한다.
     */
    fun executeWithResult(
        command: String,
        timeoutMs: Long = 300_000,
        extraEnv: Map<String, String> = emptyMap(),
        wrapInHostShell: Boolean = false,
        captureViaTempFile: Boolean = false,
        runtime: ExecutionRuntime = currentRuntime,
        returnFailureDiagnostics: Boolean = false,
    ): CommandResult? {
        var rawCmd: List<String>? = null
        var cmd: List<String>? = null
        return try {
            rawCmd = buildProrootCommand(command, runtime)
            cmd = if (wrapInHostShell) buildHostShellWrappedCommand(rawCmd) else rawCmd
            val tempOutputFile = if (captureViaTempFile) File.createTempFile("proroot-capture-", ".log", context.cacheDir) else null
            val pb = ProcessBuilder(cmd)
                .redirectErrorStream(true)
                .redirectInput(devNull)
            applyEnvironment(pb.environment(), extraEnv, runtime)
            if (tempOutputFile != null) {
                pb.redirectOutput(tempOutputFile)
            }
            android.util.Log.d("ProrootManager", "exec: cmd_size=${cmd.size} cmd=${cmd.map { "[$it]" }}")
            android.util.Log.d("ProrootManager", "exec: PROROOT_LIB_PATH=${pb.environment()["PROROOT_LIB_PATH"]}")
            val process = pb.start()
            runCatching { process.outputStream.close() }

            val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            if (!finished) {
                process.destroyForcibly()
                return CommandResult(
                    exitCode = -1,
                    output = readCommandOutput(tempOutputFile, process),
                    timedOut = true,
                )
            }

            val output = readCommandOutput(tempOutputFile, process)
            android.util.Log.d("ProrootManager", "result: exit=${process.exitValue()}")
            output.lineSequence().forEach { line ->
                android.util.Log.d("ProrootManager", "  out: $line")
            }
            CommandResult(
                exitCode = process.exitValue(),
                output = output,
            )
        } catch (e: Exception) {
            Log.e("ProrootManager", "executeWithResult failed: ${e.message}", e)
            if (returnFailureDiagnostics) {
                CommandResult(
                    exitCode = -98,
                    output = buildProcessFailureDiagnostics(
                        error = e,
                        command = command,
                        rawCmd = rawCmd,
                        cmd = cmd,
                        extraEnv = extraEnv,
                        runtime = runtime,
                    ),
                )
            } else {
                null
            }
        }
    }

    fun executeArgvWithResult(
        argv: List<String>,
        timeoutMs: Long = 300_000,
        extraEnv: Map<String, String> = emptyMap(),
        wrapInHostShell: Boolean = false,
        runtime: ExecutionRuntime = currentRuntime,
    ): CommandResult? {
        return try {
            val rawCmd = buildProrootArgvCommand(argv, runtime)
            val cmd = if (wrapInHostShell) buildHostShellWrappedCommand(rawCmd) else rawCmd
            val pb = ProcessBuilder(cmd).redirectErrorStream(true)
                .redirectInput(devNull)
            applyEnvironment(pb.environment(), extraEnv, runtime)
            android.util.Log.d("ProrootManager", "exec(argv): cmd_size=${cmd.size} cmd=${cmd.map { "[$it]" }}")
            android.util.Log.d("ProrootManager", "exec(argv): PROROOT_LIB_PATH=${pb.environment()["PROROOT_LIB_PATH"]}")
            val process = pb.start()
            runCatching { process.outputStream.close() }

            val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            if (!finished) {
                process.destroyForcibly()
                return CommandResult(
                    exitCode = -1,
                    output = process.inputStream.bufferedReader().readText().trim(),
                    timedOut = true,
                )
            }

            val output = process.inputStream.bufferedReader().readText().trim()
            android.util.Log.d("ProrootManager", "result(argv): exit=${process.exitValue()}")
            output.lineSequence().forEach { line ->
                android.util.Log.d("ProrootManager", "  out(argv): $line")
            }
            CommandResult(
                exitCode = process.exitValue(),
                output = output,
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun buildProcessFailureDiagnostics(
        error: Exception,
        command: String,
        rawCmd: List<String>?,
        cmd: List<String>?,
        extraEnv: Map<String, String>,
        runtime: ExecutionRuntime,
    ): String {
        fun List<String>?.totalBytes(): Int = this?.sumOf { it.toByteArray(Charsets.UTF_8).size } ?: 0
        fun List<String>?.maxArgBytes(): Int = this?.maxOfOrNull { it.toByteArray(Charsets.UTF_8).size } ?: 0
        return buildString {
            append("processStartFailure")
            append(" runtime=").append(runtime.name)
            append(" errorClass=").append(error.javaClass.name)
            append(" message=").append(error.message ?: "")
            append(" commandBytes=").append(command.toByteArray(Charsets.UTF_8).size)
            append(" rawArgCount=").append(rawCmd?.size ?: 0)
            append(" rawArgBytes=").append(rawCmd.totalBytes())
            append(" rawMaxArgBytes=").append(rawCmd.maxArgBytes())
            append(" argCount=").append(cmd?.size ?: 0)
            append(" argBytes=").append(cmd.totalBytes())
            append(" maxArgBytes=").append(cmd.maxArgBytes())
            append(" extraEnvKeys=").append(extraEnv.keys.sorted().joinToString("|"))
            append(" stack=").append(error.stackTraceToString())
        }
    }

    private fun readCommandOutput(
        tempOutputFile: File?,
        process: Process,
    ): String {
        val tempOutput = tempOutputFile
            ?.takeIf { it.exists() }
            ?.let { file -> runCatching { file.readText().trim() }.getOrNull() }
        if (tempOutput != null) {
            return tempOutput
        }
        return runCatching { process.inputStream.bufferedReader().readText().trim() }
            .getOrElse { "outputReadFailure=${it.javaClass.name}: ${it.message ?: ""}" }
    }

    /**
     * proroot 안에서 명령을 실행하며 출력 라인을 실시간으로 전달한다.
     */
    fun executeWithStreamingOutput(
        command: String,
        onLine: (String) -> Unit,
        runtime: ExecutionRuntime = currentRuntime,
    ): CommandResult? {
        return try {
            val cmd = buildProrootCommand(command, runtime)
            val pb = ProcessBuilder(cmd).redirectErrorStream(true)
                .redirectInput(devNull)
            applyEnvironment(pb.environment(), runtime = runtime)
            val process = pb.start()
            runCatching { process.outputStream.close() }

            val output = StringBuilder()
            process.inputStream.bufferedReader().use { reader ->
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    val current = line ?: continue
                    output.append(current).append('\n')
                    onLine(current)
                }
            }

            val exitCode = process.waitFor()
            CommandResult(
                exitCode = exitCode,
                output = output.toString().trim(),
            )
        } catch (_: Exception) {
            null
        }
    }

    /**
     * proroot 안에서 명령을 실행하며 텍스트 조각을 실시간으로 전달한다.
     * (줄바꿈 없는 프롬프트/진행 출력 캡처용)
     */
    fun executeWithStreamingText(
        command: String,
        onChunk: (String) -> Unit,
    ): CommandResult? {
        return try {
            val cmd = buildProrootCommand(command)
            val pb = ProcessBuilder(cmd).redirectErrorStream(true)
                .redirectInput(devNull)
            applyEnvironment(pb.environment())
            val process = pb.start()
            runCatching { process.outputStream.close() }

            val output = StringBuilder()
            process.inputStream.bufferedReader().use { reader ->
                val buf = CharArray(1024)
                var n: Int
                while (reader.read(buf).also { n = it } != -1) {
                    val chunk = String(buf, 0, n)
                    output.append(chunk)
                    onChunk(chunk)
                }
            }

            val exitCode = process.waitFor()
            CommandResult(
                exitCode = exitCode,
                output = output.toString().trim(),
            )
        } catch (_: Exception) {
            null
        }
    }

    fun getAvailableStorageMb(): Long {
        val stat = android.os.StatFs(context.filesDir.absolutePath)
        return stat.availableBytes / (1024 * 1024)
    }

    fun hasEnoughStorage(): Boolean = getAvailableStorageMb() > 1500
}
    private val devNull: File = File("/dev/null")
