package org.daslearning.opensms.next

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.net.HttpURLConnection
import java.net.URL

@RunWith(AndroidJUnit4::class)
class GatewayHealthServerTest {
    @Test
    fun servesHealthOnLoopbackAndStops() {
        val server = GatewayHealthServer(port = 18181)
        server.start()

        try {
            val connection = URL("http://localhost:18181/health").openConnection() as HttpURLConnection
            connection.connectTimeout = 5_000
            connection.readTimeout = 5_000
            try {
                assertEquals(200, connection.responseCode)
                assertEquals("application/json", connection.contentType.substringBefore(';'))
                assertEquals("{\"ok\":true}", connection.inputStream.bufferedReader().use { it.readText() })
            } finally {
                connection.disconnect()
            }
        } finally {
            server.stop()
        }
    }
}
