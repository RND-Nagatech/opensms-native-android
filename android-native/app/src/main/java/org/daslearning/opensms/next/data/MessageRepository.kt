package org.daslearning.opensms.next.data

import androidx.room.withTransaction
import java.security.MessageDigest
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.daslearning.opensms.next.domain.Clock
import org.daslearning.opensms.next.domain.EnqueueInput
import org.daslearning.opensms.next.domain.EnqueueResult
import org.daslearning.opensms.next.domain.FieldCipher
import org.daslearning.opensms.next.domain.MessageStatus
import org.daslearning.opensms.next.domain.DeliveryStatus
import org.daslearning.opensms.next.domain.SystemClock
import org.daslearning.opensms.next.domain.TokenVerifier

@Singleton
class MessageRepository @Inject constructor(
    private val database: AppDatabase,
    private val cipher: FieldCipher,
    private val tokenVerifier: TokenVerifier,
) {
    suspend fun enqueue(input: EnqueueInput, clock: Clock = SystemClock, queueCapacity: Int = 500): EnqueueResult =
        database.withTransaction {
            val dao = database.gatewayDao()
            val keyDigest = sha256(input.idempotencyKey)
            val payloadHash = payloadHash(input)
            val previous = dao.findIdempotency(input.clientId, keyDigest)
            if (previous != null) {
                if (previous.payloadHash != payloadHash) return@withTransaction EnqueueResult.Conflict
                val oldMessage = dao.findMessage(previous.messageId)
                    ?: error("IDEMPOTENCY_RECORD_WITHOUT_MESSAGE")
                return@withTransaction EnqueueResult.Replay(oldMessage.id, oldMessage.createdAt)
            }
            if (dao.activeQueueSize() >= queueCapacity) return@withTransaction EnqueueResult.CapacityExceeded

            val now = clock.nowMillis()
            val messageId = UUID.randomUUID().toString()
            val inserted = dao.insertMessage(
                MessageEntity(
                    id = messageId,
                    clientId = input.clientId,
                    destinationEncrypted = cipher.encrypt(input.destination),
                    bodyEncrypted = cipher.encrypt(input.body),
                    simId = input.subscriptionId,
                    status = MessageStatus.QUEUED.name,
                    deliveryStatus = if (input.requestDeliveryReport) "PENDING" else "UNAVAILABLE",
                    partsTotal = input.partsTotal,
                    createdAt = now,
                    updatedAt = now,
                    payloadHash = payloadHash,
                    clientReference = input.clientReference,
                    requestDeliveryReport = input.requestDeliveryReport,
                ),
            )
            check(inserted != -1L) { "MESSAGE_INSERT_FAILED" }

            val idempotencyRecord = IdempotencyEntity(
                id = UUID.randomUUID().toString(),
                clientId = input.clientId,
                keyDigest = keyDigest,
                payloadHash = payloadHash,
                messageId = messageId,
                createdAt = now,
                expiresAt = now + IDEMPOTENCY_RETENTION_MILLIS,
            )
            check(dao.insertIdempotency(idempotencyRecord) != -1L) { "IDEMPOTENCY_RACE" }
            dao.insertParts((0 until input.partsTotal).map { index ->
                MessagePartEntity(
                    id = "$messageId:$index",
                    messageId = messageId,
                    partIndex = index,
                    sentStatus = "PENDING",
                    deliveryStatus = if (input.requestDeliveryReport) "PENDING" else "UNAVAILABLE",
                    updatedAt = now,
                )
            })
            dao.insertAudit(
                AuditEventEntity(
                    id = UUID.randomUUID().toString(),
                    timestamp = now,
                    actor = "client:${input.clientId}",
                    action = "MESSAGE_QUEUED",
                    entityId = messageId,
                    resultCode = "ACCEPTED",
                    redactedMetadata = "parts=${input.partsTotal};sim=${input.subscriptionId ?: "DEFAULT"}",
                ),
            )
            EnqueueResult.Accepted(messageId, now)
        }

    suspend fun getOwnedMessage(clientId: String, messageId: String): MessageEntity? =
        database.gatewayDao().findOwnedMessage(clientId, messageId)

    suspend fun getMessage(messageId: String): MessageEntity? = database.gatewayDao().findMessage(messageId)

    suspend fun parts(messageId: String): List<MessagePartEntity> = database.gatewayDao().messageParts(messageId)

    suspend fun checkIdempotency(input: EnqueueInput): EnqueueResult? = database.withTransaction {
        val dao = database.gatewayDao()
        val record = dao.findIdempotency(input.clientId, sha256(input.idempotencyKey)) ?: return@withTransaction null
        val payloadHash = payloadHash(input)
        if (record.payloadHash != payloadHash) return@withTransaction EnqueueResult.Conflict
        val message = dao.findMessage(record.messageId) ?: error("IDEMPOTENCY_RECORD_WITHOUT_MESSAGE")
        EnqueueResult.Replay(message.id, message.createdAt)
    }

    fun observeMessage(messageId: String): Flow<MessageEntity?> = database.gatewayDao().observeMessage(messageId)

    fun observeHistory(): Flow<List<MessageHistoryItem>> = database.gatewayDao().observeRecentMessages(100).map { rows ->
        rows.map { row ->
            MessageHistoryItem(
                id = row.id,
                destinationMasked = runCatching { maskDestination(cipher.decrypt(row.destinationEncrypted)) }.getOrDefault("••••••"),
                status = row.status,
                deliveryStatus = row.deliveryStatus,
                partsTotal = row.partsTotal,
                createdAt = row.createdAt,
                errorCode = row.errorCode,
            )
        }
    }

    suspend fun claimNextQueued(): MessageEntity? = database.withTransaction {
        val dao = database.gatewayDao()
        val message = dao.oldestQueuedMessage() ?: return@withTransaction null
        if (message.simId == null) {
            dao.updateMessage(message.copy(status = MessageStatus.FAILED.name, errorCode = "SIM_UNAVAILABLE"))
            return@withTransaction null
        }
        val now = SystemClock.nowMillis()
        val updated = message.copy(
            status = MessageStatus.DISPATCHING.name,
            updatedAt = now,
            dispatchedAt = now,
        )
        dao.updateMessage(updated)
        dao.insertAttempt(
            DispatchAttemptEntity(
                id = UUID.randomUUID().toString(),
                messageId = message.id,
                startedAt = now,
                leaseToken = UUID.randomUUID().toString(),
                invocationState = "PREPARED",
            ),
        )
        dao.insertAudit(
            AuditEventEntity(
                id = UUID.randomUUID().toString(),
                timestamp = now,
                actor = "dispatcher",
                action = "SMS_DISPATCH_PREPARED",
                entityId = message.id,
                resultCode = "DISPATCHING",
                redactedMetadata = "parts=${message.partsTotal};sim=${message.simId}",
            ),
        )
        updated
    }

    suspend fun markInvocationStarted(messageId: String) = database.withTransaction {
        val dao = database.gatewayDao()
        dao.latestAttempt(messageId)?.let { attempt ->
            dao.updateAttempt(attempt.copy(invocationState = "INVOKING"))
        }
    }

    suspend fun markPreDispatchFailure(messageId: String, code: String) = database.withTransaction {
        val dao = database.gatewayDao()
        val message = dao.findMessage(messageId) ?: return@withTransaction
        val now = SystemClock.nowMillis()
        dao.updateMessage(message.copy(status = MessageStatus.FAILED.name, updatedAt = now, errorCode = safeReason(code)))
        dao.latestAttempt(messageId)?.let { attempt ->
            dao.updateAttempt(attempt.copy(completedAt = now, invocationState = "NOT_INVOKED", errorCode = safeReason(code)))
        }
    }

    suspend fun markUnknown(messageId: String, code: String) = database.withTransaction {
        val dao = database.gatewayDao()
        val message = dao.findMessage(messageId) ?: return@withTransaction
        val now = SystemClock.nowMillis()
        dao.updateMessage(message.copy(status = MessageStatus.UNKNOWN.name, deliveryStatus = "UNKNOWN", updatedAt = now, errorCode = safeReason(code)))
        dao.latestAttempt(messageId)?.let { attempt ->
            dao.updateAttempt(attempt.copy(completedAt = now, invocationState = "AMBIGUOUS", errorCode = safeReason(code)))
        }
        dao.insertAudit(
            AuditEventEntity(
                id = UUID.randomUUID().toString(),
                timestamp = now,
                actor = "dispatcher",
                action = "SMS_OUTCOME_UNKNOWN",
                entityId = messageId,
                resultCode = safeReason(code),
                redactedMetadata = "automatic_retry=false",
            ),
        )
    }

    suspend fun reconcileInFlightToUnknown(): Int = database.withTransaction {
        val dao = database.gatewayDao()
        val pending = dao.inFlightMessages()
        val now = SystemClock.nowMillis()
        pending.forEach { message ->
            dao.updateMessage(message.copy(status = MessageStatus.UNKNOWN.name, deliveryStatus = "UNKNOWN", updatedAt = now, errorCode = "PROCESS_INTERRUPTED"))
            dao.latestAttempt(message.id)?.let { attempt ->
                dao.updateAttempt(attempt.copy(completedAt = now, invocationState = "AMBIGUOUS", errorCode = "PROCESS_INTERRUPTED"))
            }
            dao.insertAudit(
                AuditEventEntity(
                    id = UUID.randomUUID().toString(),
                    timestamp = now,
                    actor = "recovery",
                    action = "SMS_OUTCOME_UNKNOWN",
                    entityId = message.id,
                    resultCode = "PROCESS_INTERRUPTED",
                    redactedMetadata = "automatic_retry=false",
                ),
            )
        }
        pending.size
    }

    suspend fun listOwnedMessages(
        clientId: String,
        limit: Int,
        offset: Int,
        status: String? = null,
        simId: Int? = null,
        createdFrom: Long? = null,
        createdTo: Long? = null,
    ): List<MessageEntity> = database.gatewayDao().listOwnedMessages(
        clientId,
        limit.coerceIn(1, 101),
        offset.coerceAtLeast(0),
        status,
        simId,
        createdFrom,
        createdTo,
    )

    suspend fun queueSize(): Int = database.gatewayDao().activeQueueSize()

    suspend fun recordPartCallback(
        messageId: String,
        partIndex: Int,
        delivery: Boolean,
        success: Boolean?,
        resultCode: Int,
    ) = database.withTransaction {
        val dao = database.gatewayDao()
        val part = dao.findPart(messageId, partIndex) ?: return@withTransaction
        val now = SystemClock.nowMillis()
        val updatedPart = if (delivery) {
            part.copy(
                deliveryStatus = when (success) {
                    true -> DeliveryStatus.CONFIRMED.name
                    false -> DeliveryStatus.FAILED.name
                    null -> DeliveryStatus.UNKNOWN.name
                },
                deliveryResultCode = resultCode,
                updatedAt = now,
            )
        } else {
            part.copy(
                sentStatus = when (success) {
                    true -> "SENT"
                    false -> "FAILED"
                    null -> "UNKNOWN"
                },
                sentResultCode = resultCode,
                updatedAt = now,
            )
        }
        dao.updatePart(updatedPart)

        val message = dao.findMessage(messageId) ?: return@withTransaction
        val parts = dao.messageParts(messageId).map { if (it.id == updatedPart.id) updatedPart else it }
        val sentFailed = parts.any { it.sentStatus == "FAILED" }
        val sentUnknown = parts.any { it.sentStatus == "UNKNOWN" }
        val sentComplete = parts.all { it.sentStatus == "SENT" }
        val deliveryFailed = parts.any { it.deliveryStatus == DeliveryStatus.FAILED.name }
        val deliveryUnknown = parts.any { it.deliveryStatus == DeliveryStatus.UNKNOWN.name }
        val deliveryComplete = parts.all { it.deliveryStatus == DeliveryStatus.CONFIRMED.name }
        val nextStatus = when {
            sentFailed -> MessageStatus.FAILED
            deliveryComplete && sentComplete -> MessageStatus.DELIVERED
            sentComplete -> MessageStatus.SENT
            sentUnknown -> MessageStatus.UNKNOWN
            else -> MessageStatus.valueOf(message.status)
        }
        val deliveryStatus = when {
            deliveryFailed -> DeliveryStatus.FAILED
            deliveryComplete -> DeliveryStatus.CONFIRMED
            deliveryUnknown -> DeliveryStatus.UNKNOWN
            !message.requestDeliveryReport -> DeliveryStatus.UNAVAILABLE
            else -> DeliveryStatus.PENDING
        }
        dao.updateMessage(
            message.copy(
                status = nextStatus.name,
                deliveryStatus = deliveryStatus.name,
                updatedAt = now,
                sentAt = if (sentComplete && message.sentAt == null) now else message.sentAt,
                deliveredAt = if (deliveryComplete) now else message.deliveredAt,
                errorCode = if (sentFailed) "SMS_CALLBACK_FAILURE" else message.errorCode,
            ),
        )
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString(separator = "") { byte -> "%02x".format(byte) }

    private fun payloadHash(input: EnqueueInput): String = tokenVerifier.fingerprintPayload(
        listOf(
            input.destination,
            input.body,
            input.requestedSubscriptionId,
            input.requestDeliveryReport,
            input.clientReference,
        ).joinToString("\u0000"),
    )

    private fun safeReason(value: String): String = value.takeIf { it.matches(Regex("[A-Z0-9_]{1,48}")) } ?: "UNKNOWN_ERROR"

    private fun maskDestination(destination: String): String =
        if (destination.length < 6) "••••" else "${destination.take(3)}******${destination.takeLast(2)}"

    private companion object {
        const val IDEMPOTENCY_RETENTION_MILLIS = 24L * 60 * 60 * 1_000
    }
}

data class MessageHistoryItem(
    val id: String,
    val destinationMasked: String,
    val status: String,
    val deliveryStatus: String,
    val partsTotal: Int,
    val createdAt: Long,
    val errorCode: String?,
)
