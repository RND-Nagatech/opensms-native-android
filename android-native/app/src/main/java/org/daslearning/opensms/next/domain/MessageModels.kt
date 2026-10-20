package org.daslearning.opensms.next.domain

enum class MessageStatus { QUEUED, DISPATCHING, SENT, DELIVERED, FAILED, UNKNOWN, CANCELED }
enum class DeliveryStatus { PENDING, CONFIRMED, UNAVAILABLE, FAILED, UNKNOWN }

data class EnqueueInput(
    val clientId: String,
    val idempotencyKey: String,
    val destination: String,
    val body: String,
    val subscriptionId: Int?,
    val requestedSubscriptionId: Int? = subscriptionId,
    val requestDeliveryReport: Boolean,
    val partsTotal: Int,
    val clientReference: String?,
)

sealed interface EnqueueResult {
    data class Accepted(val messageId: String, val createdAt: Long) : EnqueueResult
    data class Replay(val messageId: String, val createdAt: Long) : EnqueueResult
    data object Conflict : EnqueueResult
    data object CapacityExceeded : EnqueueResult
}

interface Clock {
    fun nowMillis(): Long
}

object SystemClock : Clock {
    override fun nowMillis(): Long = System.currentTimeMillis()
}

interface FieldCipher {
    fun encrypt(value: String): String
    fun decrypt(value: String): String
}

class MessageValidationException(val reason: String) : IllegalArgumentException(reason)

class MessageValidator(private val segmenter: SmsSegmenter) {
    fun validate(destination: String, body: String): Int {
        if (!E164.matches(destination)) throw MessageValidationException("INVALID_RECIPIENT")
        if (body.isBlank()) throw MessageValidationException("EMPTY_MESSAGE")
        if (body.length > MAX_TEXT_LENGTH) throw MessageValidationException("MESSAGE_TOO_LONG")
        val parts = segmenter.countSegments(body)
        if (parts !in 1..MAX_SEGMENTS) throw MessageValidationException("TOO_MANY_SEGMENTS")
        return parts
    }

    private companion object {
        val E164 = Regex("^\\+[1-9][0-9]{1,14}$")
        const val MAX_TEXT_LENGTH = 1_600
        const val MAX_SEGMENTS = 10
    }
}

fun interface SmsSegmenter {
    fun countSegments(body: String): Int
}

class MessageStateMachine {
    fun canTransition(from: MessageStatus, to: MessageStatus): Boolean = to in TRANSITIONS[from].orEmpty()

    fun requireTransition(from: MessageStatus, to: MessageStatus) {
        require(canTransition(from, to)) { "INVALID_STATE_TRANSITION" }
    }

    private companion object {
        val TRANSITIONS = mapOf(
            MessageStatus.QUEUED to setOf(MessageStatus.DISPATCHING, MessageStatus.CANCELED),
            MessageStatus.DISPATCHING to setOf(MessageStatus.SENT, MessageStatus.FAILED, MessageStatus.UNKNOWN),
            MessageStatus.SENT to setOf(MessageStatus.DELIVERED, MessageStatus.FAILED, MessageStatus.UNKNOWN),
            MessageStatus.UNKNOWN to setOf(MessageStatus.SENT, MessageStatus.DELIVERED, MessageStatus.FAILED),
        )
    }
}
