package org.daslearning.opensms.next.data

import androidx.room.withTransaction
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.daslearning.opensms.next.domain.AuthenticatedClient
import org.daslearning.opensms.next.domain.ClientAuthenticator
import org.daslearning.opensms.next.domain.IssuedApiKey
import org.daslearning.opensms.next.domain.TokenVerifier

@Singleton
class ClientRepository @Inject constructor(
    private val database: AppDatabase,
    private val tokenVerifier: TokenVerifier,
) : ClientAuthenticator {
    val clients: Flow<List<ApiClientSummary>> = database.gatewayDao().observeClients().map { rows ->
        rows.map { ApiClientSummary(it.id, it.name, it.scopes.split(',').toSet(), it.createdAt, it.revokedAt != null) }
    }

    suspend fun createClient(name: String, scopes: Set<String>, now: Long): IssuedApiKey {
        require(name.isNotBlank() && name.length <= 60) { "INVALID_CLIENT_NAME" }
        require(scopes.isNotEmpty() && scopes.all { it in ALLOWED_SCOPES }) { "INVALID_SCOPES" }
        val token = tokenVerifier.createToken()
        val clientId = UUID.randomUUID().toString()
        database.withTransaction {
            database.gatewayDao().insertClient(
                ApiClientEntity(
                    id = clientId,
                    name = name.trim(),
                    tokenVerifier = tokenVerifier.verifier(token),
                    scopes = scopes.sorted().joinToString(","),
                    createdAt = now,
                ),
            )
        }
        return IssuedApiKey(clientId, name.trim(), token, scopes)
    }

    suspend fun rotateClientKey(clientId: String, now: Long): IssuedApiKey? = database.withTransaction {
        val dao = database.gatewayDao()
        val client = dao.findActiveClient(clientId) ?: return@withTransaction null
        val token = tokenVerifier.createToken()
        if (dao.rotateClientVerifier(clientId, tokenVerifier.verifier(token)) == 0) return@withTransaction null
        dao.insertAudit(
            AuditEventEntity(
                id = UUID.randomUUID().toString(),
                timestamp = now,
                actor = "local_admin",
                action = "API_KEY_ROTATED",
                entityId = clientId,
                resultCode = "ROTATED",
                redactedMetadata = "",
            ),
        )
        IssuedApiKey(client.id, client.name, token, client.scopes.split(',').toSet())
    }

    suspend fun revokeClient(clientId: String, now: Long): Boolean =
        database.withTransaction {
            val dao = database.gatewayDao()
            val revoked = dao.revokeClient(clientId, now) > 0
            if (revoked) {
                dao.insertAudit(
                    AuditEventEntity(
                        id = UUID.randomUUID().toString(),
                        timestamp = now,
                        actor = "local_admin",
                        action = "API_KEY_REVOKED",
                        entityId = clientId,
                        resultCode = "REVOKED",
                        redactedMetadata = "",
                    ),
                )
            }
            revoked
        }

    override suspend fun authenticate(token: String?): AuthenticatedClient? {
        if (token.isNullOrBlank() || token.length > 128) return null
        val verifier = tokenVerifier.verifier(token)
        val client = database.gatewayDao().findActiveClientByVerifier(verifier) ?: return null
        database.gatewayDao().updateLastUsed(client.id, System.currentTimeMillis())
        return AuthenticatedClient(client.id, client.scopes.split(',').filter(String::isNotBlank).toSet())
    }

    private companion object {
        val ALLOWED_SCOPES = setOf("messages:write", "messages:read", "system:read")
    }
}

data class ApiClientSummary(
    val id: String,
    val name: String,
    val scopes: Set<String>,
    val createdAt: Long,
    val revoked: Boolean,
)

