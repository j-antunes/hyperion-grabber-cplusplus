package com.hyperion.grabber

import java.io.File
import java.util.zip.CRC32

// JNI bridge to the native DXGI Desktop Duplication capturer (dxgi_jni.cpp).
// The .dll is bundled in the MSI under /native and extracted at startup. On
// non-Windows platforms — or if the library/D3D device is unavailable (e.g. a
// GPU-less host) — isAvailable stays false and ScreenGrabber falls back to Robot.
object WindowsCapture {

    private const val LIB_NAME = "hyperion_capture_jni"

    val isAvailable: Boolean = loadNative()

    // Returns a native handle (>0) or 0 on failure (no GPU / headless).
    external fun nativeInit(): Long

    // Returns dstW*dstH*3 RGB bytes for a fresh frame, or null when there is
    // nothing new (desktop unchanged, or duplication being rebuilt after the
    // lock screen / a mode change). Caller reuses the previous frame.
    external fun nativeCapture(handle: Long, dstW: Int, dstH: Int): ByteArray?

    // Console display power state (GUID_CONSOLE_DISPLAY_STATE): false while
    // the monitor is off. Windows twin of Android's SCREEN_OFF and X11 DPMS.
    external fun nativeIsDisplayOn(handle: Long): Boolean

    external fun nativeDestroy(handle: Long)

    private fun loadNative(): Boolean {
        if (!System.getProperty("os.name", "").lowercase().contains("windows")) return false
        return try {
            val bytes = WindowsCapture::class.java.getResourceAsStream("/native/$LIB_NAME.dll")
                ?.use { it.readBytes() } ?: return false
            System.load(extract(bytes).absolutePath)
            true
        } catch (e: Throwable) {
            false
        }
    }

    // Extract to a stable, content-addressed path under %LOCALAPPDATA% instead
    // of a fresh temp file per launch: Windows refuses to delete a DLL that is
    // still loaded, so File.deleteOnExit() silently leaked one copy per start.
    // The CRC in the file name means an upgraded build gets its own file and
    // an already-extracted matching copy is reused as-is.
    private fun extract(bytes: ByteArray): File {
        val crc = CRC32().apply { update(bytes) }.value
        val dir = nativeDir()
        val target = File(dir, "$LIB_NAME-${java.lang.Long.toHexString(crc)}.dll")
        if (target.isFile && target.length() == bytes.size.toLong()) return target
        dir.mkdirs()
        val tmp = File(dir, "${target.name}.${ProcessHandle.current().pid()}.tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(target)) {
            // Another instance won the race; ours is redundant.
            tmp.delete()
            if (!target.isFile) throw IllegalStateException("could not place $target")
        }
        return target
    }

    private fun nativeDir(): File {
        val base = System.getenv("LOCALAPPDATA")?.takeIf { it.isNotBlank() }
            ?: System.getProperty("java.io.tmpdir")
        return File(base, "HyperionGrabber/native")
    }
}
