package org.daslearning.opensms.next.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface GatewayDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertMessage(message: MessageEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIdempotency(record: IdempotencyEntity): Long

    @Insert
    suspend fun insertParts(parts: List<MessagePartEntity>)

    @Insert
    suspend fun insertAudit(event: AuditEventEntity)

    @Insert
    suspend fun insertAttempt(attempt: DispatchAttemptEntity)

    @Query("SELECT * FROM dispatch_attempts WHERE messageId = :messageId ORDER BY startedAt DESC LIMIT 1")
    suspend fun latestAttempt(messageId: String): DispatchAttemptEntity?

    @Query("SELECT * FROM idempotency_keys WHERE clientId = :clientId AND keyDigest = :keyDigest LIMIT 1")
    suspend fun findIdempotency(clientId: String, keyDigest: String): IdempotencyEntity?

    @Query("SELECT * FROM messages WHERE id = :messageId LIMIT 1")
    suspend fun findMessage(messageId: String): MessageEntity?

    @Query("SELECT * FROM messages WHERE id = :messageId LIMIT 1")
    fun observeMessage(messageId: String): Flow<MessageEntity?>

    @Query("SELECT * FROM messages WHERE clientId = :clientId AND id = :messageId LIMIT 1")
    suspend fun findOwnedMessage(clientId: String, messageId: String): MessageEntity?

    @Query("SELECT * FROM messages WHERE status = 'QUEUED' ORDER BY createdAt ASC LIMIT 1")
    suspend fun oldestQueuedMessage(): MessageEntity?

    @Query("SELECT * FROM message_parts WHERE messageId = :messageId ORDER BY partIndex ASC")
    suspend fun messageParts(messageId: String): List<MessagePartEntity>

    @Query("SELECT * FROM message_parts WHERE messageId = :messageId AND partIndex = :partIndex LIMIT 1")
    suspend fun findPart(messageId: String, partIndex: Int): MessagePartEntity?

    @Query(
        """SELECT * FROM messages
            WHERE clientId = :clientId
              AND (:status IS NULL OR status = :status)
              AND (:simId IS NULL OR simId = :simId)
              AND (:createdFrom IS NULL OR createdAt >= :createdFrom)
              AND (:createdTo IS NULL OR createdAt <= :createdTo)
            ORDER BY createdAt DESC, id DESC LIMIT :limit OFFSET :offset""",
    )
    suspend fun listOwnedMessages(
        clientId: String,
        limit: Int,
        offset: Int,
        status: String?,
        simId: Int?,
        createdFrom: Long?,
        createdTo: Long?,
    ): List<MessageEntity>

    @Query("SELECT * FROM messages ORDER BY createdAt DESC, id DESC LIMIT :limit")
    fun observeRecentMessages(limit: Int): Flow<List<MessageEntity>>

    @Query("SELECT COUNT(*) FROM messages WHERE status IN ('QUEUED', 'DISPATCHING')")
    suspend fun activeQueueSize(): Int

    @Query("SELECT * FROM messages WHERE status = 'DISPATCHING'")
    suspend fun inFlightMessages(): List<MessageEntity>

    @Update
    suspend fun updateMessage(message: MessageEntity)

    @Update
    suspend fun updatePart(part: MessagePartEntity)

    @Update
    suspend fun updateAttempt(attempt: DispatchAttemptEntity)

    @Query("SELECT * FROM api_clients WHERE tokenVerifier = :verifier AND revokedAt IS NULL LIMIT 1")
    suspend fun findActiveClientByVerifier(verifier: String): ApiClientEntity?

    @Query("SELECT * FROM api_clients WHERE id = :clientId AND revokedAt IS NULL LIMIT 1")
    suspend fun findActiveClient(clientId: String): ApiClientEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertClient(client: ApiClientEntity)

    @Query("UPDATE api_clients SET tokenVerifier = :verifier WHERE id = :clientId AND revokedAt IS NULL")
    suspend fun rotateClientVerifier(clientId: String, verifier: String): Int

    @Query("SELECT * FROM api_clients ORDER BY createdAt DESC")
    fun observeClients(): Flow<List<ApiClientEntity>>

    @Query("UPDATE api_clients SET revokedAt = :revokedAt WHERE id = :clientId AND revokedAt IS NULL")
    suspend fun revokeClient(clientId: String, revokedAt: Long): Int

    @Query("UPDATE api_clients SET lastUsedAt = :now WHERE id = :clientId")
    suspend fun updateLastUsed(clientId: String, now: Long)

    @Query("SELECT COUNT(*) FROM api_clients WHERE revokedAt IS NULL")
    suspend fun activeClientCount(): Int

    @Query("SELECT * FROM rate_buckets WHERE bucketKey = :bucketKey AND windowStart = :windowStart LIMIT 1")
    suspend fun findRateBucket(bucketKey: String, windowStart: Long): RateBucketEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertRateBucket(bucket: RateBucketEntity): Long

    @Query("UPDATE rate_buckets SET requestCount = requestCount + 1 WHERE id = :id")
    suspend fun incrementRateBucket(id: String)

    @Query("DELETE FROM rate_buckets WHERE windowStart < :cutoff")
    suspend fun purgeRateBuckets(cutoff: Long)

    @Query("SELECT * FROM audit_events ORDER BY timestamp DESC LIMIT :limit")
    fun observeAudit(limit: Int): Flow<List<AuditEventEntity>>

    @Query("SELECT * FROM audit_events ORDER BY timestamp DESC LIMIT :limit")
    suspend fun recentAudit(limit: Int): List<AuditEventEntity>
}
