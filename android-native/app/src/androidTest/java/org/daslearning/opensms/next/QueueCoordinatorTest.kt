package org.daslearning.opensms.next

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.daslearning.opensms.next.data.AppDatabase
import org.daslearning.opensms.next.data.MessageRepository
import org.daslearning.opensms.next.data.RoomQueueCoordinator
import org.daslearning.opensms.next.domain.EnqueueInput
import org.daslearning.opensms.next.domain.EnqueueResult
import org.daslearning.opensms.next.domain.FieldCipher
import org.daslearning.opensms.next.domain.SmsPart
import org.daslearning.opensms.next.domain.SmsPartEncoder
import org.daslearning.opensms.next.domain.SmsSender
import org.daslearning.opensms.next.domain.SubscriptionInfo
import org.daslearning.opensms.next.domain.SubscriptionProvider
import org.daslearning.opensms.next.domain.TokenVerifier
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class QueueCoordinatorTest {
    private lateinit var database: AppDatabase
    private lateinit var messages: MessageRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        messages = MessageRepository(database, TestCipher, TestTokenVerifier)
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun callbackMovesQueuedMessageToSentWithoutClaimingDelivery() = runBlocking {
        val messageId = (messages.enqueue(testInput()) as EnqueueResult.Accepted).messageId
        val sender = CallbackSender(messages)
        val coordinator = coordinator(sender)
        assertTrue(coordinator.dispatchNext())
        val message = database.gatewayDao().findMessage(messageId)!!
        assertEquals("SENT", message.status)
        assertEquals("PENDING", message.deliveryStatus)
        assertEquals(1, sender.sentCount.get())
        assertFalse(coordinator.dispatchNext())
    }

    @Test
    fun interruptedDispatchIsMarkedUnknownAndNeverAutomaticallyResent() = runBlocking {
        val messageId = (messages.enqueue(testInput()) as EnqueueResult.Accepted).messageId
        messages.claimNextQueued()
        messages.reconcileInFlightToUnknown()
        val sender = CallbackSender(messages)
        val coordinator = coordinator(sender)
        assertFalse(coordinator.dispatchNext())
        assertEquals("UNKNOWN", database.gatewayDao().findMessage(messageId)!!.status)
        assertEquals(0, sender.sentCount.get())
    }

    private fun coordinator(sender: CallbackSender) = RoomQueueCoordinator(
        messages = messages,
        cipher = TestCipher,
        sender = sender,
        partEncoder = SmsPartEncoder { body, _ -> listOf(body) },
        subscriptions = object : SubscriptionProvider {
            override fun activeSmsSubscriptions() = listOf(SubscriptionInfo(4, 0, "test"))
            override fun defaultSmsSubscriptionId() = 4
        },
    )

    private fun testInput() = EnqueueInput(
        clientId = "client-a",
        idempotencyKey = "queue-test-message-key-0001",
        destination = "+628123456789",
        body = "hello",
        subscriptionId = 4,
        requestDeliveryReport = true,
        partsTotal = 1,
        clientReference = null,
    )

    private class CallbackSender(private val messages: MessageRepository) : SmsSender {
        val sentCount = AtomicInteger()
        override suspend fun send(parts: List<SmsPart>) {
            sentCount.incrementAndGet()
            parts.forEach { part ->
                messages.recordPartCallback(part.messageId, part.partIndex, delivery = false, success = true, resultCode = 0)
            }
        }
    }

    private object TestCipher : FieldCipher {
        override fun encrypt(value: String) = "enc:$value"
        override fun decrypt(value: String) = value.removePrefix("enc:")
    }

    private object TestTokenVerifier : TokenVerifier {
        override fun createToken() = "test-token"
        override fun verifier(token: String) = "verifier:$token"
        override fun fingerprintPayload(payload: String) = "fingerprint:$payload"
    }
}
