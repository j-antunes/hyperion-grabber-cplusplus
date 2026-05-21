package com.hyperion.grabber

import com.google.flatbuffers.FlatBufferBuilder
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder

// Flatbuffers union type constants matching hyperion_request.fbs
// union Command { Color=1, Image=2, Clear=3, Register=4 }
// union ImageType { RawImage=1 }
private const val COMMAND_IMAGE: Byte    = 2
private const val COMMAND_REGISTER: Byte = 4
private const val IMAGE_TYPE_RAW: Byte   = 1

class HyperionClient(private val host: String, private val port: Int, private val priority: Int = 150) {
    private var socket: Socket? = null
    private var output: OutputStream? = null
    private var input: InputStream? = null

    fun connect(): Boolean = try {
        val s = Socket(host, port).also { it.soTimeout = 5000 }
        socket = s
        output = s.getOutputStream()
        input  = s.getInputStream()
        sendBuffer(buildRegister("hyperion-grabber-desktop", priority))
        readReply()
        true
    } catch (e: Exception) {
        disconnect()
        false
    }

    fun disconnect() {
        runCatching { socket?.close() }
        socket = null; output = null; input = null
    }

    fun isConnected() = socket?.isConnected == true && socket?.isClosed == false

    fun sendFrame(rgb: ByteArray, width: Int, height: Int): Boolean = try {
        sendBuffer(buildImageFrame(rgb, width, height))
        true
    } catch (e: Exception) {
        false
    }

    // --- flatbuffers builders ---

    private fun buildRegister(origin: String, priority: Int): ByteArray {
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

    private fun buildImageFrame(rgb: ByteArray, width: Int, height: Int): ByteArray {
        val fbb = FlatBufferBuilder(rgb.size + 256)
        val dataOff = fbb.createByteVector(rgb)
        // RawImage { data:[ubyte][0], width:int[1], height:int[2] }
        fbb.startTable(3)
        fbb.addOffset(0, dataOff, 0)
        fbb.addInt(1, width, 0)
        fbb.addInt(2, height, 0)
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

    private fun readReply() {
        val inp = input ?: return
        val hdr = ByteArray(4).also { readFully(inp, it) }
        val size = ByteBuffer.wrap(hdr).order(ByteOrder.BIG_ENDIAN).int
        if (size in 1..65536) readFully(inp, ByteArray(size))
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
