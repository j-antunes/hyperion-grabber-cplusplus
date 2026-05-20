package com.hyperion.grabber

import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder

class HyperionProtoClient(private val host: String, private val port: Int) {

    private var socket: Socket? = null
    private var output: OutputStream? = null

    companion object {
        private const val PRIORITY = 50
        private const val TIMEOUT_MS = 3_000
    }

    fun connect(): Boolean = runCatching {
        val s = Socket()
        s.connect(InetSocketAddress(host, port), TIMEOUT_MS)
        s.soTimeout = 0
        socket = s
        output = s.getOutputStream().buffered()
        true
    }.getOrDefault(false)

    fun sendFrame(width: Int, height: Int, rgb: ByteArray): Boolean {
        val out = output ?: return false
        return runCatching {
            val msg = encodeHyperionRequest(width, height, rgb)
            val header = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN)
                .putInt(msg.size).array()
            out.write(header)
            out.write(msg)
            out.flush()
            true
        }.getOrDefault(false)
    }

    fun disconnect() {
        runCatching { socket?.close() }
        socket = null
        output = null
    }

    // HyperionRequest { command=IMAGE(2), imageRequest { imagewidth, imageheight, imagedata, priority } }
    private fun encodeHyperionRequest(width: Int, height: Int, rgb: ByteArray): ByteArray {
        val imageReq = proto {
            varint(1, width)       // imagewidth
            varint(2, height)      // imageheight
            bytes(3, rgb)          // imagedata
            varint(4, PRIORITY)    // priority
        }
        return proto {
            varint(1, 2)           // command = IMAGE
            bytes(3, imageReq)     // imageRequest (field 3, not 2 — colorRequest is field 2)
        }
    }
}

// Minimal proto encoder — only the subset we need (varint fields, bytes fields)

private fun proto(block: ProtoWriter.() -> Unit): ByteArray =
    ProtoWriter().apply(block).toByteArray()

private class ProtoWriter {
    private val buf = ByteArrayOutputStream()

    fun varint(field: Int, value: Int) = varint(field, value.toLong())

    fun varint(field: Int, value: Long) {
        tag(field, 0)
        encodeVarint(value)
    }

    fun bytes(field: Int, data: ByteArray) {
        tag(field, 2)
        encodeVarint(data.size.toLong())
        buf.write(data)
    }

    private fun tag(field: Int, wireType: Int) = encodeVarint(((field shl 3) or wireType).toLong())

    private fun encodeVarint(v: Long) {
        var n = v
        while (n and 0x7F.toLong().inv() != 0L) {
            buf.write(((n and 0x7F) or 0x80).toInt())
            n = n ushr 7
        }
        buf.write(n.toInt())
    }

    fun toByteArray(): ByteArray = buf.toByteArray()
}
