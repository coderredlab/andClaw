package com.coderred.andclaw.proroot

import androidx.annotation.Keep
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Path

/** Public native fstat retains nanoseconds on every supported Android API, including API 26. */
@Keep
internal object OpenClawLockSnapshotReader {
    const val MAX_LOCK_BYTES = 16 * 1024

    data class Snapshot(
        val raw: String,
        val dev: String,
        val ino: String,
        val mtimeNs: String,
        val ctimeNs: String,
    )

    init {
        val explicitLibrary = System.getProperty("andclaw.gatewayLockSnapshotLibrary")
        if (explicitLibrary == null) System.loadLibrary("gateway-lock-snapshot")
        else System.load(explicitLibrary)
    }

    private external fun nativeRead(root: ByteArray, relative: ByteArray, maxBytes: Int): Array<Any>?

    fun read(root: Path, relative: String): Snapshot? {
        // JNI's modified UTF-8 cannot represent arbitrary filesystem paths. Pass ordinary UTF-8 bytes.
        val result = nativeRead(root.toString().toByteArray(Charsets.UTF_8), relative.toByteArray(Charsets.UTF_8), MAX_LOCK_BYTES)
            ?: return null
        val bytes = result[0] as ByteArray
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return Snapshot(
            decoder.decode(ByteBuffer.wrap(bytes)).toString(),
            result[1] as String,
            result[2] as String,
            result[3] as String,
            result[4] as String,
        )
    }
}
