package com.hyperion.grabber

import java.nio.ByteBuffer

object HyperionNative {
    init {
        System.loadLibrary("hyperion_grabber")
    }

    external fun create(
        host: String, port: Int,
        srcW: Int, srcH: Int,
        dstW: Int, dstH: Int,
        fps: Int
    ): Long

    external fun sendFrame(handle: Long, buffer: ByteBuffer, rowStride: Int): Boolean

    external fun destroy(handle: Long)

    external fun sendKeepalive(handle: Long): Boolean

    // Sends solid red/green/blue test frames without MediaProjection.
    // Returns null on success, error string on failure.
    external fun testConnection(host: String, port: Int): String?
}
