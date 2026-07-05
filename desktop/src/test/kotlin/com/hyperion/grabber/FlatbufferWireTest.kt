package com.hyperion.grabber

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

// Minimal flatbuffer reader so the test doesn't depend on generated code.
// Verifies the handcrafted buffers in HyperionClient against the layout
// defined by hyperion_request.fbs (Hyperion.ng 2.2.1):
//   union Command { Color=1, Image=2, Clear=3, Register=4 }
//   union ImageType { RawImage=1 }
private class Fb(bytes: ByteArray) {
    private val buf: ByteBuffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

    fun rootTable(): Int = buf.getInt(0)

    // Absolute position of a field's value, or null if absent (default)
    fun fieldPos(table: Int, id: Int): Int? {
        val vtable = table - buf.getInt(table)
        val vtableSize = buf.getShort(vtable).toInt() and 0xFFFF
        val slot = 4 + 2 * id
        if (slot >= vtableSize) return null
        val off = buf.getShort(vtable + slot).toInt() and 0xFFFF
        return if (off == 0) null else table + off
    }

    fun byteField(table: Int, id: Int): Int? = fieldPos(table, id)?.let { buf.get(it).toInt() }
    fun intField(table: Int, id: Int): Int?  = fieldPos(table, id)?.let { buf.getInt(it) }

    // Follow an offset field (string / table / vector) to its target position
    fun offsetField(table: Int, id: Int): Int? = fieldPos(table, id)?.let { it + buf.getInt(it) }

    fun string(pos: Int): String = String(bytesAt(pos))
    fun byteVector(pos: Int): ByteArray = bytesAt(pos)

    private fun bytesAt(pos: Int): ByteArray {
        val len = buf.getInt(pos)
        return ByteArray(len) { buf.get(pos + 4 + it) }
    }
}

class FlatbufferWireTest {

    private val client = HyperionClient("localhost", 19400)

    @Test
    fun `register buffer has Command_Register discriminant, origin and priority`() {
        val fb = Fb(client.buildRegister("test-origin", 150))
        val req = fb.rootTable()

        assertEquals(4, fb.byteField(req, 0), "command_type must be Command.Register (4)")
        val reg = assertNotNull(fb.offsetField(req, 1), "command table missing")
        assertEquals("test-origin", fb.string(assertNotNull(fb.offsetField(reg, 0))))
        assertEquals(150, fb.intField(reg, 1))
    }

    @Test
    fun `image buffer nests RawImage in ImageType union with explicit duration`() {
        val rgb = byteArrayOf(10, 20, 30, 40, 50, 60) // 2×1 RGB
        val fb = Fb(client.buildImageFrame(rgb, 2, 1))
        val req = fb.rootTable()

        assertEquals(2, fb.byteField(req, 0), "command_type must be Command.Image (2)")
        val img = assertNotNull(fb.offsetField(req, 1), "Image table missing")

        assertEquals(1, fb.byteField(img, 0), "data_type must be ImageType.RawImage (1)")
        val raw = assertNotNull(fb.offsetField(img, 1), "RawImage table missing")
        assertContentEquals(rgb, fb.byteVector(assertNotNull(fb.offsetField(raw, 0))))
        assertEquals(2, fb.intField(raw, 1), "width")
        assertEquals(1, fb.intField(raw, 2), "height")

        // duration = -1 equals the schema default; it must still be written
        // explicitly (forceDefaults) so older servers read it consistently
        assertEquals(-1, fb.intField(img, 2), "duration must be present and -1")
    }

    @Test
    fun `empty pixel data still produces a valid frame`() {
        val fb = Fb(client.buildImageFrame(ByteArray(0), 0, 0))
        val req = fb.rootTable()
        assertEquals(2, fb.byteField(req, 0))
        val img = assertNotNull(fb.offsetField(req, 1))
        val raw = assertNotNull(fb.offsetField(img, 1))
        assertEquals(0, fb.byteVector(assertNotNull(fb.offsetField(raw, 0))).size)
        // The schema default is -1, so a real width/height of 0 must be written to
        // the wire (not omitted as it would be if the default were 0).
        assertEquals(0, fb.intField(raw, 1), "width 0 must be present, not omitted")
        assertEquals(0, fb.intField(raw, 2), "height 0 must be present, not omitted")
    }

    @Test
    fun `reply parser extracts a registration error string`() {
        // Build a Reply { error:string } the way Hyperion would on a rejected
        // registration, then confirm the client surfaces it.
        val fbb = com.google.flatbuffers.FlatBufferBuilder(64)
        val errOff = fbb.createString("Register rejected: priority not allowed")
        fbb.startTable(3)          // Reply { error[0], video[1], registered[2] }
        fbb.addOffset(0, errOff, 0)
        val replyOff = fbb.endTable()
        fbb.finish(replyOff)
        assertEquals(
            "Register rejected: priority not allowed",
            client.parseReplyError(fbb.sizedByteArray())
        )
    }

    @Test
    fun `reply parser returns null when there is no error`() {
        // Reply with only registered set — a successful registration.
        val fbb = com.google.flatbuffers.FlatBufferBuilder(64)
        fbb.startTable(3)
        fbb.addInt(2, 150, -1)     // registered = priority
        val replyOff = fbb.endTable()
        fbb.finish(replyOff)
        assertNull(client.parseReplyError(fbb.sizedByteArray()))
    }
}
