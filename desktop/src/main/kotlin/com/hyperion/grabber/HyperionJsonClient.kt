package com.hyperion.grabber

import java.net.HttpURLConnection
import java.net.URL

object HyperionJsonClient {
    fun queryResolution(host: String): Pair<Int, Int>? = try {
        val conn = URL("http://$host:8090/json-rpc").openConnection() as HttpURLConnection
        conn.connectTimeout = 3000
        conn.readTimeout    = 3000
        conn.requestMethod  = "POST"
        conn.doOutput       = true
        conn.setRequestProperty("Content-Type", "application/json")
        conn.outputStream.write("""{"command":"serverinfo","subscribe":[]}""".toByteArray())
        val resp = conn.inputStream.bufferedReader().readText()
        conn.disconnect()

        val count = resp.split("\"hmin\"").size - 1
        if (count <= 0) null
        else {
            val w = ((count / 4) and 0.inv().xor(7)).coerceIn(64, 256)
            val h = (w * 9 / 16).coerceIn(36, 144)
            w to h
        }
    } catch (e: Exception) { null }

    fun setBrightness(host: String, brightness: Int) {
        if (host.isBlank()) return
        runCatching {
            val conn = URL("http://$host:8090/json-rpc").openConnection() as HttpURLConnection
            conn.connectTimeout = 3000
            conn.requestMethod  = "POST"
            conn.doOutput       = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.write(
                """{"command":"adjustment","adjustment":{"brightness":$brightness,"id":"default"}}""".toByteArray()
            )
            conn.responseCode
            conn.disconnect()
        }
    }
}
