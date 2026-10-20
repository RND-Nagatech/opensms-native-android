package org.daslearning.opensms.next

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URL
import kotlinx.coroutines.runBlocking
import org.daslearning.opensms.next.api.OpenSmsApiServer
import org.daslearning.opensms.next.data.AppDatabase
import org.daslearning.opensms.next.data.ClientRepository
import org.daslearning.opensms.next.data.MessageRepository
import org.daslearning.opensms.next.domain.FieldCipher
import org.daslearning.opensms.next.domain.GatewayRuntimeState
import org.daslearning.opensms.next.domain.PersistentRateLimiter
import org.daslearning.opensms.next.domain.SmsSegmenter
import org.daslearning.opensms.next.domain.SubscriptionInfo
import org.daslearning.opensms.next.domain.SubscriptionProvider
import org.daslearning.opensms.next.domain.TokenVerifier
import org.daslearning.opensms.next.domain.TrustedNetworkPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ApiContractTest {
    private lateinit var database: AppDatabase
    private lateinit var server: OpenSmsApiServer
    private var token = ""
    private var port = 0

    @Before
    fun setUp() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val clients = ClientRepository(database, TestTokenVerifier)
        token = clients.createClient("contract-test", setOf("messages:write"), System.currentTimeMillis()).token
        val messages = MessageRepository(database, TestCipher, TestTokenVerifier)
        server = OpenSmsApiServer(
            clients = clients,
            messages = messages,
            subscriptions = TestSubscriptions,
            partEncoder = org.daslearning.opensms.next.domain.SmsPartEncoder { _, _ -> listOf("segment") },
            rateLimiter = PersistentRateLimiter { true },
            statusSource = GatewayRuntimeState(),
            trustedNetworkPolicy = TestNetworkPolicy,
            cipher = TestCipher,
        )
        port = ServerSocket(0).use { it.localPort }
        server.start("127.0.0.1", port)
    }

    @After
    fun tearDown() = runBlocking {
        server.stop()
        database.close()
    }

    @Test
    fun healthIsPublicButProtectedRoutesRequireScopedToken() {
        val health = request("GET", "/v1/health")
        val noToken = request("GET", "/v1/status")
        val insufficientScope = request("GET", "/v1/status", bearer = token)
        assertEquals(200, health.code)
        assertTrue(health.body.contains("\"ok\":true"))
        assertEquals(401, noToken.code)
        assertEquals(403, insufficientScope.code)
    }

    @Test
    fun enqueueReplayAndConflictMatchApiContract() {
        val body = "{\"to\":\"+628123456789\",\"text\":\"hello\"}"
        val first = request("POST", "/v1/messages", token, IDEMPOTENCY_KEY, body)
        val replay = request("POST", "/v1/messages", token, IDEMPOTENCY_KEY, body)
        val conflict = request("POST", "/v1/messages", token, IDEMPOTENCY_KEY, "{\"to\":\"+628123456789\",\"text\":\"other\"}")
        assertEquals(202, first.code)
        assertTrue(first.body.contains("\"status\":\"QUEUED\""))
        assertEquals(200, replay.code)
        assertEquals("true", replay.replayHeader)
        assertEquals(409, conflict.code)
    }

    private fun request(
        method: String,
        path: String,
        bearer: String? = null,
        idempotencyKey: String? = null,
        body: String? = null,
    ): HttpResult {
        val connection = URL("http://127.0.0.1:$port$path").openConnection() as HttpURLConnection
        connection.requestMethod = method
        connection.connectTimeout = 5_000
        connection.readTimeout = 5_000
        bearer?.let { connection.setRequestProperty("Authorization", "Bearer $it") }
        idempotencyKey?.let { connection.setRequestProperty("Idempotency-Key", it) }
        if (body != null) {
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        }
        val code = connection.responseCode
        val stream = if (code < 400) connection.inputStream else connection.errorStream
        val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        val replay = connection.getHeaderField("Idempotent-Replay")
        connection.disconnect()
        return HttpResult(code, text, replay)
    }

    private data class HttpResult(val code: Int, val body: String, val replayHeader: String?)

    private object TestTokenVerifier : TokenVerifier {
        override fun createToken() = "test-api-key-with-32-characters"
        override fun verifier(token: String) = "test-verifier:$token"
        override fun fingerprintPayload(payload: String) = "test-payload:$payload"
    }

    private object TestCipher : FieldCipher {
        override fun encrypt(value: String) = "test-encrypted:$value"
        override fun decrypt(value: String) = value.removePrefix("test-encrypted:")
    }

    private object TestSubscriptions : SubscriptionProvider {
        override fun activeSmsSubscriptions() = listOf(SubscriptionInfo(4, 0, "SIM test"))
        override fun defaultSmsSubscriptionId() = 4
    }

    private object TestNetworkPolicy : TrustedNetworkPolicy {
        override fun allowLanListener() = false
        override fun localWifiAddress(): String? = null
        override fun isAllowedPeer(address: String) = false
    }

    private companion object {
        const val IDEMPOTENCY_KEY = "contract-idempotency-key-0001"
    }
}
