package org.daslearning.opensms.next.data

import androidx.room.Database
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.withTransaction
import androidx.room.Index
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import javax.inject.Inject
import javax.inject.Singleton

@Entity(tableName = "local_settings")
data class LocalSettingEntity(
    @PrimaryKey val key: String,
    val value: String,
)

@Dao
interface LocalSettingDao {
    @Query("SELECT value FROM local_settings WHERE `key` = :key LIMIT 1")
    suspend fun get(key: String): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(setting: LocalSettingEntity)
}

@Database(
    entities = [
        LocalSettingEntity::class,
        ApiClientEntity::class,
        MessageEntity::class,
        MessagePartEntity::class,
        IdempotencyEntity::class,
        DispatchAttemptEntity::class,
        AuditEventEntity::class,
        RateBucketEntity::class,
    ],
    version = 3,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun localSettingDao(): LocalSettingDao
    abstract fun gatewayDao(): GatewayDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Older builds incorrectly promoted a delivery-report failure to a send failure.
                // Repair only rows whose every SMS part has a successful sent callback.
                db.execSQL(
                    """UPDATE messages
                       SET status = 'SENT', errorCode = NULL
                       WHERE status = 'FAILED'
                         AND errorCode = 'SMS_CALLBACK_FAILURE'
                         AND EXISTS (
                           SELECT 1 FROM message_parts p
                           WHERE p.messageId = messages.id AND p.deliveryStatus = 'FAILED'
                         )
                         AND (
                           SELECT COUNT(*) FROM message_parts p
                           WHERE p.messageId = messages.id AND p.sentStatus = 'SENT'
                         ) = messages.partsTotal""",
                )
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // This Infinix XOS build emits resultCode=0 for sent callbacks despite
                // the Messages app recording the SMS as sent. Zero is not an SmsManager
                // failure code. Keep a delivery callback with zero as UNKNOWN because its
                // status-report PDU was not persisted by the old receiver.
                db.execSQL(
                    """UPDATE message_parts
                       SET sentStatus = 'SENT'
                       WHERE sentStatus = 'FAILED' AND sentResultCode = 0
                         AND EXISTS (
                           SELECT 1 FROM messages m
                           WHERE m.id = message_parts.messageId
                             AND m.status = 'FAILED' AND m.errorCode = 'SMS_CALLBACK_FAILURE'
                         )""",
                )
                db.execSQL(
                    """UPDATE message_parts
                       SET deliveryStatus = 'UNKNOWN'
                       WHERE deliveryStatus = 'FAILED' AND deliveryResultCode = 0
                         AND EXISTS (
                           SELECT 1 FROM messages m
                           WHERE m.id = message_parts.messageId
                             AND m.status = 'FAILED' AND m.errorCode = 'SMS_CALLBACK_FAILURE'
                         )""",
                )
                db.execSQL(
                    """UPDATE messages
                       SET status = CASE
                             WHEN requestDeliveryReport = 1 AND NOT EXISTS (
                               SELECT 1 FROM message_parts p
                               WHERE p.messageId = messages.id AND p.deliveryStatus != 'CONFIRMED'
                             ) THEN 'DELIVERED'
                             ELSE 'SENT'
                           END,
                           deliveryStatus = CASE
                             WHEN requestDeliveryReport = 0 THEN 'UNAVAILABLE'
                             WHEN NOT EXISTS (
                               SELECT 1 FROM message_parts p
                               WHERE p.messageId = messages.id AND p.deliveryStatus != 'CONFIRMED'
                             ) THEN 'CONFIRMED'
                             WHEN EXISTS (
                               SELECT 1 FROM message_parts p
                               WHERE p.messageId = messages.id AND p.deliveryStatus = 'FAILED'
                             ) THEN 'FAILED'
                             WHEN EXISTS (
                               SELECT 1 FROM message_parts p
                               WHERE p.messageId = messages.id AND p.deliveryStatus = 'UNKNOWN'
                             ) THEN 'UNKNOWN'
                             ELSE 'PENDING'
                           END,
                           errorCode = NULL
                       WHERE status = 'FAILED'
                         AND errorCode = 'SMS_CALLBACK_FAILURE'
                         AND (SELECT COUNT(*) FROM message_parts p
                              WHERE p.messageId = messages.id AND p.sentStatus = 'SENT') = partsTotal
                         AND EXISTS (SELECT 1 FROM message_parts p WHERE p.messageId = messages.id)""",
                )
            }
        }
    }
}

@Singleton
class SafeEventLogger @Inject constructor() {
    /** Log only controlled event names and reason codes; never pass SMS or credential data. */
    fun record(event: SafeEvent, reasonCode: String? = null) {
        val safeReason = reasonCode?.takeIf { it.matches(Regex("[A-Z0-9_]{1,48}")) }
        android.util.Log.i("OpenSms", "event=${event.name} reason=${safeReason ?: "NONE"}")
    }
}

enum class SafeEvent { APP_OPENED, ONBOARDING_COMPLETED, GATEWAY_STARTED, GATEWAY_STOPPED, GATEWAY_ERROR }
