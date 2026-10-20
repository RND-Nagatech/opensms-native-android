package org.daslearning.opensms.next.data

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import org.daslearning.opensms.next.domain.FieldCipher
import org.daslearning.opensms.next.domain.MessageStatus
import org.daslearning.opensms.next.domain.QueueCoordinator
import org.daslearning.opensms.next.domain.SmsPart
import org.daslearning.opensms.next.domain.SmsPartEncoder
import org.daslearning.opensms.next.domain.SmsSender
import org.daslearning.opensms.next.domain.SubscriptionProvider

@Singleton
class RoomQueueCoordinator @Inject constructor(
    private val messages: MessageRepository,
    private val cipher: FieldCipher,
    private val sender: SmsSender,
    private val partEncoder: SmsPartEncoder,
    private val subscriptions: SubscriptionProvider,
) : QueueCoordinator {
    private val dispatchMutex = Mutex()

    override suspend fun dispatchNext(): Boolean = dispatchMutex.withLock {
        val message = messages.claimNextQueued() ?: return false
        val subscriptionId = message.simId
        if (subscriptionId == null) {
            messages.markPreDispatchFailure(message.id, "SIM_UNAVAILABLE")
            return true
        }
        val isSubscriptionActive = runCatching { subscriptions.activeSmsSubscriptions().any { it.subscriptionId == subscriptionId } }
            .getOrDefault(false)
        if (!isSubscriptionActive) {
            messages.markPreDispatchFailure(message.id, "SIM_UNAVAILABLE")
            return true
        }

        val destination: String
        val body: String
        val bodies: List<String>
        try {
            destination = cipher.decrypt(message.destinationEncrypted)
            body = cipher.decrypt(message.bodyEncrypted)
            bodies = partEncoder.split(body, subscriptionId)
            if (bodies.size != message.partsTotal || bodies.isEmpty()) {
                messages.markPreDispatchFailure(message.id, "SEGMENTATION_CHANGED")
                return true
            }
        } catch (_: Exception) {
            messages.markPreDispatchFailure(message.id, "MESSAGE_PREPARATION_FAILED")
            return true
        }

        val parts = bodies.mapIndexed { index, partBody ->
            SmsPart(
                messageId = message.id,
                partIndex = index,
                destination = destination,
                body = partBody,
                subscriptionId = subscriptionId,
                requestDeliveryReport = message.requestDeliveryReport,
            )
        }
        messages.markInvocationStarted(message.id)
        try {
            sender.send(parts)
        } catch (exception: CancellationException) {
            withContext(NonCancellable) { messages.markUnknown(message.id, "SESSION_STOPPED_DURING_DISPATCH") }
            throw exception
        } catch (_: SecurityException) {
            messages.markPreDispatchFailure(message.id, "SEND_SMS_PERMISSION_DENIED")
            return true
        } catch (_: Exception) {
            withContext(NonCancellable) { messages.markUnknown(message.id, "SMS_INVOCATION_AMBIGUOUS") }
            return true
        }

        try {
            withTimeout(SENT_CALLBACK_TIMEOUT_MILLIS) {
                messages.observeMessage(message.id).first { row ->
                    row == null || MessageStatus.valueOf(row.status) in SENT_TERMINAL_STATES
                }
            }
        } catch (_: TimeoutCancellationException) {
            withContext(NonCancellable) { messages.markUnknown(message.id, "SENT_CALLBACK_TIMEOUT") }
        } catch (exception: CancellationException) {
            withContext(NonCancellable) { messages.markUnknown(message.id, "SESSION_STOPPED_DURING_DISPATCH") }
            throw exception
        }
        true
    }

    override suspend fun recoverInFlight(): Int = messages.reconcileInFlightToUnknown()

    private companion object {
        const val SENT_CALLBACK_TIMEOUT_MILLIS = 60_000L
        val SENT_TERMINAL_STATES = setOf(MessageStatus.SENT, MessageStatus.FAILED, MessageStatus.UNKNOWN, MessageStatus.CANCELED)
    }
}
