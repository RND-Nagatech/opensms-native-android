package org.daslearning.opensms.next

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.daslearning.opensms.next.data.AppDatabase
import org.daslearning.opensms.next.data.MessageRepository
import org.daslearning.opensms.next.domain.EnqueueInput
import org.daslearning.opensms.next.domain.EnqueueResult
import org.daslearning.opensms.next.domain.DeliveryStatus
import org.daslearning.opensms.next.domain.FieldCipher
import org.daslearning.opensms.next.domain.TokenVerifier
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MessageRepositoryTest {
    private lateinit var database: AppDatabase
    private lateinit var repository: MessageRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        repository = MessageRepository(database, TestCipher, TestTokenVerifier)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun idempotencyReplayReturnsSameRecordAndConflictingPayloadIsRejected() = runBlocking {
        val first = repository.enqueue(input(body = "hello"))
        val replay = repository.enqueue(input(body = "hello"))
        val conflict = repository.enqueue(input(body = "different"))
        assertTrue(first is EnqueueResult.Accepted)
        assertTrue(replay is EnqueueResult.Replay)
        assertEquals((first as EnqueueResult.Accepted).messageId, (replay as EnqueueResult.Replay).messageId)
        assertEquals(EnqueueResult.Conflict, conflict)
        assertEquals(1, repository.queueSize())
    }

    @Test
    fun messageFieldsAreEncryptedAtRest() = runBlocking {
        val result = repository.enqueue(input(body = "sensitive body")) as EnqueueResult.Accepted
        val row = database.gatewayDao().findMessage(result.messageId)!!
        assertNotEquals("sensitive body", row.bodyEncrypted)
        assertNotEquals("+628123456789", row.destinationEncrypted)
    }

    @Test
    fun messageNeedsAllSentPartsBeforeSentAndAllDeliveryPartsBeforeDelivered() = runBlocking {
        val result = repository.enqueue(input(body = "hello").copy(partsTotal = 2)) as EnqueueResult.Accepted
        repository.claimNextQueued()
        repository.recordPartCallback(result.messageId, 0, delivery = false, success = true, resultCode = 0)
        assertEquals("DISPATCHING", database.gatewayDao().findMessage(result.messageId)!!.status)
        repository.recordPartCallback(result.messageId, 1, delivery = false, success = true, resultCode = 0)
        assertEquals("SENT", database.gatewayDao().findMessage(result.messageId)!!.status)
        repository.recordPartCallback(result.messageId, 0, delivery = true, success = true, resultCode = 0)
        assertEquals(DeliveryStatus.PENDING.name, database.gatewayDao().findMessage(result.messageId)!!.deliveryStatus)
        repository.recordPartCallback(result.messageId, 1, delivery = true, success = true, resultCode = 0)
        assertEquals("DELIVERED", database.gatewayDao().findMessage(result.messageId)!!.status)
        assertEquals(DeliveryStatus.CONFIRMED.name, database.gatewayDao().findMessage(result.messageId)!!.deliveryStatus)
    }

    @Test
    fun partialSentFailureMarksWholeMessageFailed() = runBlocking {
        val result = repository.enqueue(input(body = "hello").copy(partsTotal = 2)) as EnqueueResult.Accepted
        repository.claimNextQueued()
        repository.recordPartCallback(result.messageId, 0, delivery = false, success = true, resultCode = 0)
        repository.recordPartCallback(result.messageId, 1, delivery = false, success = false, resultCode = 2)
        assertEquals("FAILED", database.gatewayDao().findMessage(result.messageId)!!.status)
    }

    @Test
    fun readEndpointRepositoryNeverReturnsAnotherClientsMessage() = runBlocking {
        val result = repository.enqueue(input(body = "private").copy(clientId = "client-a")) as EnqueueResult.Accepted
        assertEquals(null, repository.getOwnedMessage("client-b", result.messageId))
        assertEquals(result.messageId, repository.getOwnedMessage("client-a", result.messageId)?.id)
    }

    private fun input(body: String) = EnqueueInput(
        clientId = "client-a",
        idempotencyKey = "logical-message-key-0001",
        destination = "+628123456789",
        body = body,
        subscriptionId = 1,
        requestDeliveryReport = true,
        partsTotal = 1,
        clientReference = null,
    )

    private object TestCipher : FieldCipher {
        override fun encrypt(value: String) = "test-encrypted:$value"
        override fun decrypt(value: String) = value.removePrefix("test-encrypted:")
    }

    private object TestTokenVerifier : TokenVerifier {
        override fun createToken() = "test-token"
        override fun verifier(token: String) = "verifier:$token"
        override fun fingerprintPayload(payload: String) = "fingerprint:$payload"
    }
}
