package com.hyperion.grabber

import com.google.flatbuffers.FlatBufferBuilder
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder

// Flatbuffers union type constants matching hyperion_request.fbs
// union Command { Color=1, Image=2, Clear=3, Register=4 }
// union ImageType { RawImage=1 }
private const val COMMAND_IMAGE: Byte    = 2
private const val COMMAND_REGISTER: Byte = 4
private const val IMAGE_TYPE_RAW: Byte   = 1

private const val TIMEOUT_MS = 5000

class HyperionClient(private val host: String, private val port: Int, private val priority: Int = 150) {
    private var socket: Socket? = null
    private var output: OutputStream? = null
    private var input: InputStream? = null

    fun connect(): Boolean = try {
        // Bound the connect itself, not just reads — Socket(host, port) blocks for
        // the OS default (up to minutes) on a routed-but-dead address.
        val s = Socket().apply {
            connect(InetSocketAddress(host, port), TIMEOUT_MS)
            soTimeout = TIMEOUT_MS
        }
        socket = s
        output = s.getOutputStream()
        input  = s.getInputStream()
        sendBuffer(buildRegister("hyperion-grabber-desktop", priority))
        // Hyperion rejects a bad registration (e.g. a reserved priority) with an
        // error reply and never registers the source; treat that as a failure
        // instead of showing RUNNING while every frame is silently dropped.
        val error = readReply()
        if (error != null) {
            disconnect()
            false
        } else true
    } catch (e: Exception) {
        disconnect()
        false
    }

    fun disconnect() {
        runCatching { socket?.close() }
        socket = null; output = null; input = null
    }

    fun sendFrame(rgb: ByteArray, width: Int, height: Int): Boolean = try {
        drainReplies()
        sendBuffer(buildImageFrame(rgb, width, height))
        true
    } catch (e: Exception) {
        false
    }

    // Hyperion replies to every message, including each Image frame. Discard
    // pending replies so the receive buffer never fills up.
    private val drainBuf = ByteArray(1024)
    private fun drainReplies() {
        val inp = input ?: return
        while (inp.available() > 0) {
            if (inp.read(drainBuf) < 0) throw EOFException()
        }
    }

    // --- flatbuffers builders ---

    // internal for FlatbufferWireTest — verifies the handcrafted layout
    internal fun buildRegister(origin: String, priority: Int): ByteArray {
        val fbb = FlatBufferBuilder(256)
        val originOff = fbb.createString(origin)
        // Register { origin:string[0], priority:int[1] }
        fbb.startTable(2)
        fbb.addOffset(0, originOff, 0)
        fbb.addInt(1, priority, 0)
        val regOff = fbb.endTable()
        // Request { command_type:ubyte[0]=Register(4), command:offset[1] }
        fbb.startTable(2)
        fbb.addByte(0, COMMAND_REGISTER, 0)
        fbb.addOffset(1, regOff, 0)
        val reqOff = fbb.endTable()
        fbb.finish(reqOff)
        return fbb.sizedByteArray()
    }

    internal fun buildImageFrame(rgb: ByteArray, width: Int, height: Int): ByteArray {
        val fbb = FlatBufferBuilder(rgb.size + 256)
        val dataOff = fbb.createByteVector(rgb)
        // RawImage { data:[ubyte][0], width:int=-1[1], height:int=-1[2] }
        // The schema default is -1, so pass it as the default: a real 0 is then
        // written to the wire, and any value equal to -1 is correctly omitted.
        fbb.startTable(3)
        fbb.addOffset(0, dataOff, 0)
        fbb.addInt(1, width, -1)
        fbb.addInt(2, height, -1)
        val rawOff = fbb.endTable()
        // Image { data_type:ubyte[0]=RawImage(1), data:offset[1], duration:int[2]=-1 }
        fbb.startTable(3)
        fbb.addByte(0, IMAGE_TYPE_RAW, 0)
        fbb.addOffset(1, rawOff, 0)
        // duration default is -1, so addInt(2,-1,-1) would be omitted — use forceDefaults trick
        fbb.forceDefaults(true)
        fbb.addInt(2, -1, -1)
        fbb.forceDefaults(false)
        val imgOff = fbb.endTable()
        // Request { command_type:ubyte[0]=Image(2), command:offset[1] }
        fbb.startTable(2)
        fbb.addByte(0, COMMAND_IMAGE, 0)
        fbb.addOffset(1, imgOff, 0)
        val reqOff = fbb.endTable()
        fbb.finish(reqOff)
        return fbb.sizedByteArray()
    }

    // --- wire I/O ---

    private fun sendBuffer(buf: ByteArray) {
        val out = output ?: error("Not connected")
        val hdr = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(buf.size).array()
        out.write(hdr)
        out.write(buf)
        out.flush()
    }

    // Reads the Register reply. Returns Hyperion's error string if registration
    // was rejected, or null on success.
    private fun readReply(): String? {
        val inp = input ?: return "not connected"
        val hdr = ByteArray(4).also { readFully(inp, it) }
        val size = ByteBuffer.wrap(hdr).order(ByteOrder.BIG_ENDIAN).int
        if (size !in 1..65536) throw IOException("implausible reply size $size")
        val body = ByteArray(size).also { readFully(inp, it) }
        return parseReplyError(body)
    }

    // Minimal flatbuffer reader for Reply { error:string[0], video:int[1],
    // registered:int[2] } (hyperion_reply.fbs). Returns the error string if the
    // field is present, else null. internal for FlatbufferWireTest.
    internal fun parseReplyError(body: ByteArray): String? {
        val bb = ByteBuffer.wrap(body).order(ByteOrder.LITTLE_ENDIAN)
        val root = bb.getInt(0)
        val vtable = root - bb.getInt(root)
        val vtableSize = bb.getShort(vtable).toInt() and 0xFFFF
        val slot = 4 + 2 * 0 // field id 0 = error
        if (slot >= vtableSize) return null
        val fieldOff = bb.getShort(vtable + slot).toInt() and 0xFFFF
        if (fieldOff == 0) return null
        val strPos = root + fieldOff + bb.getInt(root + fieldOff)
        val len = bb.getInt(strPos)
        return String(body, strPos + 4, len, Charsets.UTF_8)
    }

    private fun readFully(stream: InputStream, buf: ByteArray) {
        var off = 0
        while (off < buf.size) {
            val n = stream.read(buf, off, buf.size - off)
            if (n < 0) throw EOFException()
            off += n
        }
    }
}
