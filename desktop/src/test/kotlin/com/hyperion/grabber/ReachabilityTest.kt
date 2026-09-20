package com.hyperion.grabber

import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Guards the "cannot reach Hyperion" fix: the connect must try every address a
// host resolves to (the C++ client's getaddrinfo loop does), and the
// reachability probe must report the flatbuffers port, not the web port.
class ReachabilityTest {

    private fun listener(): ServerSocket =
        ServerSocket().apply { bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0)) }

    // A port nobody is listening on, found by opening and immediately closing one.
    private fun deadPort(): Int = ServerSocket(0).use { it.localPort }

    @Test
    fun connectsToAListeningPort() {
        listener().use { srv ->
            assertNotNull(HyperionJsonClient.openSocket("127.0.0.1", srv.localPort, 1000)).close()
        }
    }

    @Test
    fun returnsNullWhenNothingIsListening() {
        assertNull(HyperionJsonClient.openSocket("127.0.0.1", deadPort(), 500))
    }

    @Test
    fun returnsNullForAnUnresolvableHost() {
        assertNull(HyperionJsonClient.openSocket("no-such-host.invalid", 19400, 500))
    }

    // The regression: "localhost" resolves to ::1 and 127.0.0.1, and Hyperion
    // (like this listener) may only be bound to IPv4. Committing to the first
    // address made a reachable server look dead.
    @Test
    fun fallsBackToTheNextAddressWhenTheFirstRefuses() {
        listener().use { srv ->
            val addrs = InetAddress.getAllByName("localhost")
            assertTrue(addrs.isNotEmpty(), "localhost must resolve")
            assertNotNull(HyperionJsonClient.openSocket("localhost", srv.localPort, 1000)).close()
        }
    }

    @Test
    fun probeReportsTheFlatbuffersPort() {
        listener().use { srv ->
            val r = HyperionJsonClient.probe("127.0.0.1", srv.localPort)
            assertTrue(r.flatbuffers, "flatbuffers port should be reported reachable")
            assertTrue(r.anything)
        }
    }

    @Test
    fun probeReportsNothingWhenTheHostIsSilent() {
        val r = HyperionJsonClient.probe("127.0.0.1", deadPort())
        assertEquals(false, r.flatbuffers)
        // 19444/8090 may legitimately be in use on a dev box running Hyperion,
        // so only the flatbuffers result is asserted here.
    }
}
