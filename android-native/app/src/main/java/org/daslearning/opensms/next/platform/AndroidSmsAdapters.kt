package org.daslearning.opensms.next.platform

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import android.os.Build
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import org.daslearning.opensms.next.domain.SmsPart
import org.daslearning.opensms.next.domain.SmsSegmenter
import org.daslearning.opensms.next.domain.SmsPartEncoder
import org.daslearning.opensms.next.domain.SmsSender
import org.daslearning.opensms.next.domain.SubscriptionInfo
import org.daslearning.opensms.next.domain.SubscriptionProvider

@Singleton
class AndroidSmsAdapters @Inject constructor(
    @ApplicationContext private val context: Context,
) : SmsSender, SubscriptionProvider, SmsSegmenter, SmsPartEncoder {
    override fun activeSmsSubscriptions(): List<SubscriptionInfo> =
        context.getSystemService(SubscriptionManager::class.java)
            .also { check(ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED) { "SIM_PERMISSION_REQUIRED" } }
            .activeSubscriptionInfoList.orEmpty()
            .filter { it.subscriptionId >= 0 }
            .map { SubscriptionInfo(it.subscriptionId, it.simSlotIndex, "SIM ${it.simSlotIndex + 1}") }

    override fun defaultSmsSubscriptionId(): Int? = SmsManager.getDefaultSmsSubscriptionId()
        .takeIf { it != SubscriptionManager.INVALID_SUBSCRIPTION_ID }

    override fun countSegments(body: String): Int = smsManager(defaultSmsSubscriptionId() ?: SubscriptionManager.INVALID_SUBSCRIPTION_ID)
        .divideMessage(body).size

    override fun split(body: String, subscriptionId: Int): List<String> =
        smsManager(subscriptionId).divideMessage(body).toList()

    override suspend fun send(parts: List<SmsPart>) {
        require(parts.isNotEmpty()) { "EMPTY_SMS_PARTS" }
        require(parts.map { it.messageId }.distinct().size == 1) { "MIXED_MESSAGE_PARTS" }
        require(parts.map { it.subscriptionId }.distinct().size == 1) { "MIXED_SMS_SUBSCRIPTIONS" }
        require(parts.map { it.destination }.distinct().size == 1) { "MIXED_SMS_DESTINATIONS" }

        val first = parts.first()
        val manager = smsManager(first.subscriptionId)
        val sentIntents = parts.map { part -> callbackIntent(part, SmsCallbackReceiver.ACTION_SENT) }
        val deliveryIntents = if (first.requestDeliveryReport) {
            parts.map { part -> callbackIntent(part, SmsCallbackReceiver.ACTION_DELIVERED) }
        } else {
            null
        }
        if (parts.size == 1) {
            manager.sendTextMessage(first.destination, null, first.body, sentIntents.first(), deliveryIntents?.firstOrNull())
        } else {
            manager.sendMultipartTextMessage(
                first.destination,
                null,
                ArrayList(parts.map { it.body }),
                ArrayList(sentIntents),
                deliveryIntents?.let { ArrayList(it) },
            )
        }
    }

    @Suppress("DEPRECATION")
    private fun smsManager(subscriptionId: Int): SmsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        context.getSystemService(SmsManager::class.java).createForSubscriptionId(subscriptionId)
    } else {
        SmsManager.getSmsManagerForSubscriptionId(subscriptionId)
    }

    private fun callbackIntent(part: SmsPart, action: String): PendingIntent {
        val intent = Intent(context, SmsCallbackReceiver::class.java)
            .setAction(action)
            .setData("opensms-next://callback/${part.messageId}/${part.partIndex}/$action".toUri())
            .putExtra(SmsCallbackReceiver.EXTRA_MESSAGE_ID, part.messageId)
            .putExtra(SmsCallbackReceiver.EXTRA_PART_INDEX, part.partIndex)
        val requestCode = (part.messageId.hashCode() * 31 + part.partIndex * 7 + action.hashCode())
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
