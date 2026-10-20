package org.daslearning.opensms.next.platform

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.SmsManager
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.daslearning.opensms.next.data.MessageRepository

@AndroidEntryPoint
class SmsCallbackReceiver : BroadcastReceiver() {
    @Inject lateinit var messages: MessageRepository

    override fun onReceive(context: Context, intent: Intent) {
        val messageId = intent.getStringExtra(EXTRA_MESSAGE_ID) ?: return
        val partIndex = intent.getIntExtra(EXTRA_PART_INDEX, -1)
        if (partIndex < 0) return
        val isDelivery = intent.action == ACTION_DELIVERED
        val pendingResult = goAsync()
        val resultCode = resultCode
        val success: Boolean? = when {
            resultCode == android.app.Activity.RESULT_OK -> true
            !isDelivery && resultCode == android.app.Activity.RESULT_CANCELED -> {
                // Infinix XOS reports 0 for sent callbacks even when its Messages app
                // records the SMS as sent. SmsManager's documented failure codes are non-zero.
                true
            }
            isDelivery && resultCode == android.app.Activity.RESULT_CANCELED -> null
            else -> false
        }
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                messages.recordPartCallback(
                    messageId = messageId,
                    partIndex = partIndex,
                    delivery = isDelivery,
                    success = success,
                    resultCode = resultCode,
                )
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        const val ACTION_SENT = "org.daslearning.opensms.next.SMS_SENT"
        const val ACTION_DELIVERED = "org.daslearning.opensms.next.SMS_DELIVERED"
        const val EXTRA_MESSAGE_ID = "message_id"
        const val EXTRA_PART_INDEX = "part_index"
    }
}
