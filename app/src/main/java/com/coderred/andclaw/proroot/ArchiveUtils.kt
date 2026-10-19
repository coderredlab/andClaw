package com.coderred.andclaw.proroot

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import android.util.Log
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Paths
import kotlin.coroutines.coroutineContext

object ArchiveUtils {

    private const val TAG = "ArchiveUtils"

    /**
     * tar.gz 파일을 지정 디렉토리에 압축 해제한다.
     * symlink, hardlink, 파일 퍼미션을 보존한다.
     * zip-slip 공격을 방지한다.
     *
     * @param tarGzFile 압축 파일
     * @param destDir 추출 대상 디렉토리
     * @param stripComponents 경로에서 제거할 선행 컴포넌트 수 (tar --strip-components와 동일)
     * @param onProgress 진행률 콜백 (추출된 엔트리 수)
     */
    suspend fun extractTarGz(
        tarGzFile: File,
        destDir: File,
        stripComponents: Int = 0,
        onProgress: (extractedEntries: Int) -> Unit = {},
    ) = withContext(Dispatchers.IO) {
        destDir.mkdirs()
        val destCanonical = destDir.canonicalPath
        var entryCount = 0

        GzipCompressorInputStream(
            BufferedInputStream(FileInputStream(tarGzFile), 65536)
        ).use { gzIn ->
            TarArchiveInputStream(gzIn).use { tarIn ->
                while (true) {
                    coroutineContext.ensureActive()
                    val entry: TarArchiveEntry = tarIn.nextEntry ?: break

                    // 경로 정규화
                    var name = entry.name
                        .removePrefix("./")
                        .removePrefix("/")

                    // strip-components 적용
                    if (stripComponents > 0) {
                        val parts = name.split("/")
                        if (parts.size <= stripComponents) continue
                        name = parts.drop(stripComponents).joinToString("/")
                    }

                    if (name.isEmpty() || name == ".") continue

                    val outFile = File(destDir, name)

                    // Zip-slip 방지
                    if (!outFile.canonicalPath.startsWith(destCanonical)) {
                        continue
                    }

                    when {
                        entry.isDirectory -> {
                            if (!outFile.exists()) {
                                val created = outFile.mkdirs()
                                if (!created && !outFile.isDirectory) {
                                    // 경로 충돌: 같은 이름의 파일이 디렉토리 생성을 막는 경우 제거 후 재시도
                                    if (outFile.exists()) {
                                        Log.w(TAG, "Path conflict: ${outFile.path} exists as file, removing for directory")
                                        outFile.delete()
                                        outFile.mkdirs()
                                    }
                                }
                            }
                        }

                        entry.isSymbolicLink -> {
                            ensureParentDir(outFile)
                            // 기존 파일 삭제 후 심볼릭 링크 생성
                            outFile.delete()
                            try {
                                Files.createSymbolicLink(
                                    outFile.toPath(),
                                    Paths.get(entry.linkName),
                                )
                            } catch (_: Exception) {
                                // 심볼릭 링크 생성 실패 시 무시 (일부 Android 파일시스템 제한)
                            }
                        }

                        entry.isLink -> {
                            ensureParentDir(outFile)
                            val normalizedLinkName = entry.linkName
                                .removePrefix("./")
                                .removePrefix("/")
                            val linkTarget = File(destDir, normalizedLinkName)

                            // tar 입력 중복/자기참조 hardlink가 들어온 경우(예: path -> same path),
                            // 기존 파일을 지우면 원본까지 사라지므로 no-op 처리한다.
                            val isSelfHardLink = outFile.absoluteFile.toPath().normalize() ==
                                linkTarget.absoluteFile.toPath().normalize()
                            if (!isSelfHardLink) {
                                outFile.delete()
                                try {
                                    Files.createLink(outFile.toPath(), linkTarget.toPath())
                                } catch (_: Exception) {
                                    // 하드 링크 실패 시 파일 복사로 폴백
                                    if (linkTarget.exists()) {
                                        linkTarget.copyTo(outFile, overwrite = true)
                                    }
                                }
                            }
                        }

                        else -> {
                            ensureParentDir(outFile)
                            // 기존 파일/심볼릭 링크가 있으면 삭제 (덮어쓰기 보장)
                            if (outFile.exists() || Files.isSymbolicLink(outFile.toPath())) {
                                outFile.delete()
                            }
                            FileOutputStream(outFile).use { output ->
                                val buffer = ByteArray(32768)
                                var len: Int
                                while (tarIn.read(buffer).also { len = it } != -1) {
                                    output.write(buffer, 0, len)
                                }
                            }
                        }
                    }

                    // 퍼미션 설정 (심볼릭 링크 제외)
                    if (!entry.isSymbolicLink && outFile.exists()) {
                        setFilePermissions(outFile, entry.mode)
                    }

                    entryCount++
                    if (entryCount % 200 == 0) {
                        onProgress(entryCount)
                    }
                }
            }
        }

        onProgress(entryCount)
        entryCount
    }

    /**
     * 부모 디렉토리가 존재하는지 확인하고, 없으면 생성한다.
     * mkdirs() 실패 시 경로 충돌을 해소하고, 그래도 실패하면 진단 정보와 함께 IOException을 던진다.
     */
    private fun ensureParentDir(outFile: File) {
        val parent = outFile.parentFile ?: return
        if (parent.isDirectory) return

        if (!parent.mkdirs() && !parent.isDirectory) {
            // 경로 충돌: 부모 경로에 파일이 디렉토리 대신 존재할 수 있다
            val conflicting = findPathConflict(parent)
            if (conflicting != null) {
                Log.w(TAG, "Path conflict at ${conflicting.path} (isFile=${conflicting.isFile}, " +
                    "isSymlink=${Files.isSymbolicLink(conflicting.toPath())}), removing for directory")
                conflicting.delete()
                parent.mkdirs()
            }

            if (!parent.isDirectory) {
                val diag = buildString {
                    append("Cannot create parent directory: ${parent.path}")
                    append(" (exists=${parent.exists()}, isFile=${parent.isFile}")
                    var p: File? = parent
                    while (p != null && !p.exists()) p = p.parentFile
                    if (p != null && p != parent) {
                        append(", firstExisting=${p.path}, isDir=${p.isDirectory}")
                    }
                    val usable = parent.parentFile?.usableSpace ?: -1
                    if (usable >= 0) append(", usableSpace=${usable / 1024 / 1024}MB")
                    append(")")
                }
                Log.e(TAG, diag)
                throw IOException(diag)
            }
        }
    }

    /**
     * 경로에서 디렉토리가 아닌 첫 번째 컴포넌트를 찾는다.
     * 예: /a/b/c 에서 b가 파일이면 b를 반환.
     */
    private fun findPathConflict(dir: File): File? {
        val parts = mutableListOf<File>()
        var current: File? = dir
        while (current != null) {
            parts.add(current)
            current = current.parentFile
        }
        parts.reverse()
        for (part in parts) {
            if (part.exists() && !part.isDirectory) return part
        }
        return null
    }

    private fun setFilePermissions(file: File, mode: Int) {
        val isDirectory = file.isDirectory
        if (isDirectory && (mode and 0x200) != 0) {
            // Retain extraction's 0755 minimum while preserving sticky/shared bits.
            Files.setAttribute(file.toPath(), "unix:mode", (mode and 0x3FF) or 0x1ED, LinkOption.NOFOLLOW_LINKS)
            return
        }
        // owner/group/other execute bit: 0111 = 0x49
        val isExecutable = isDirectory || (mode and 73) != 0 // 0111 in octal = 73 in decimal
        file.setReadable(true, false)
        file.setExecutable(isExecutable, false)
        file.setWritable(true, true)
    }
}
