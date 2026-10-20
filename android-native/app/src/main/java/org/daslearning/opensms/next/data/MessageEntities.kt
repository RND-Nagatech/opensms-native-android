package org.daslearning.opensms.next.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "api_clients", indices = [Index(value = ["tokenVerifier"], unique = true)])
data class ApiClientEntity(
    @PrimaryKey val id: String,
    val name: String,
    val tokenVerifier: String,
    val scopes: String,
    val createdAt: Long,
    val revokedAt: Long? = null,
    val lastUsedAt: Long? = null,
)

@Entity(
    tableName = "messages",
    indices = [Index("clientId", "createdAt"), Index("status", "createdAt")],
)
data class MessageEntity(
    @PrimaryKey val id: String,
    val clientId: String,
    val destinationEncrypted: String,
    val bodyEncrypted: String,
    val simId: Int?,
    val status: String,
    val deliveryStatus: String,
    val partsTotal: Int,
    val createdAt: Long,
    val updatedAt: Long,
    val dispatchedAt: Long? = null,
    val sentAt: Long? = null,
    val deliveredAt: Long? = null,
    val errorCode: String? = null,
    val clientReference: String? = null,
    val payloadHash: String,
    val requestDeliveryReport: Boolean,
)

@Entity(
    tableName = "message_parts",
    indices = [Index(value = ["messageId", "partIndex"], unique = true)],
)
data class MessagePartEntity(
    @PrimaryKey val id: String,
    val messageId: String,
    val partIndex: Int,
    val sentStatus: String,
    val deliveryStatus: String,
    val sentResultCode: Int? = null,
    val deliveryResultCode: Int? = null,
    val updatedAt: Long,
)

@Entity(
    tableName = "idempotency_keys",
    indices = [Index(value = ["clientId", "keyDigest"], unique = true)],
)
data class IdempotencyEntity(
    @PrimaryKey val id: String,
    val clientId: String,
    val keyDigest: String,
    val payloadHash: String,
    val messageId: String,
    val createdAt: Long,
    val expiresAt: Long,
)

@Entity(tableName = "dispatch_attempts", indices = [Index("messageId")])
data class DispatchAttemptEntity(
    @PrimaryKey val id: String,
    val messageId: String,
    val startedAt: Long,
    val completedAt: Long? = null,
    val leaseToken: String,
    val invocationState: String,
    val errorCode: String? = null,
)

@Entity(tableName = "audit_events", indices = [Index("timestamp")])
data class AuditEventEntity(
    @PrimaryKey val id: String,
    val timestamp: Long,
    val actor: String,
    val action: String,
    val entityId: String?,
    val resultCode: String,
    val redactedMetadata: String,
)

@Entity(tableName = "rate_buckets", indices = [Index(value = ["bucketKey", "windowStart"], unique = true)])
data class RateBucketEntity(
    @PrimaryKey val id: String,
    val bucketKey: String,
    val windowStart: Long,
    val requestCount: Int,
)
