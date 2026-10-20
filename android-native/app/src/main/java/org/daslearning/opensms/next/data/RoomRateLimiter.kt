package org.daslearning.opensms.next.data

import androidx.room.withTransaction
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import org.daslearning.opensms.next.domain.PersistentRateLimiter

@Singleton
class RoomRateLimiter @Inject constructor(
    private val database: AppDatabase,
) : PersistentRateLimiter {
    override suspend fun allow(clientId: String): Boolean = database.withTransaction {
        val dao = database.gatewayDao()
        val now = System.currentTimeMillis()
        val windowStart = now / WINDOW_MILLIS * WINDOW_MILLIS
        val globalAllowed = incrementWithinLimit(dao, GLOBAL_BUCKET, windowStart, GLOBAL_LIMIT)
        val clientAllowed = incrementWithinLimit(dao, "client:$clientId", windowStart, CLIENT_LIMIT)
        dao.purgeRateBuckets(now - WINDOW_MILLIS * 2)
        globalAllowed && clientAllowed
    }

    private suspend fun incrementWithinLimit(
        dao: GatewayDao,
        bucketKey: String,
        windowStart: Long,
        limit: Int,
    ): Boolean {
        val existing = dao.findRateBucket(bucketKey, windowStart)
        if (existing != null) {
            if (existing.requestCount >= limit) return false
            dao.incrementRateBucket(existing.id)
            return true
        }
        val row = RateBucketEntity(UUID.randomUUID().toString(), bucketKey, windowStart, 1)
        return dao.insertRateBucket(row) != -1L
    }

    private companion object {
        const val WINDOW_MILLIS = 60_000L
        const val CLIENT_LIMIT = 30
        const val GLOBAL_LIMIT = 120
        const val GLOBAL_BUCKET = "global"
    }
}
