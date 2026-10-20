package org.daslearning.opensms.next.domain

data class SmsPart(
    val messageId: String,
    val partIndex: Int,
    val destination: String,
    val body: String,
    val subscriptionId: Int,
    val requestDeliveryReport: Boolean,
)

interface SmsSender {
    suspend fun send(parts: List<SmsPart>)
}

interface SubscriptionProvider {
    fun activeSmsSubscriptions(): List<SubscriptionInfo>
    fun defaultSmsSubscriptionId(): Int?
}

fun interface SmsPartEncoder {
    fun split(body: String, subscriptionId: Int): List<String>
}

data class SubscriptionInfo(val subscriptionId: Int, val slotIndex: Int, val displayName: String)

interface QueueCoordinator {
    suspend fun dispatchNext(): Boolean
    suspend fun recoverInFlight(): Int
}
