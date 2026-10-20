package org.daslearning.opensms.next.domain

data class IssuedApiKey(val clientId: String, val clientName: String, val token: String, val scopes: Set<String>)

interface TokenVerifier {
    fun createToken(): String
    fun verifier(token: String): String
    fun fingerprintPayload(payload: String): String
}

data class AuthenticatedClient(val id: String, val scopes: Set<String>)

interface ClientAuthenticator {
    suspend fun authenticate(token: String?): AuthenticatedClient?
}

fun interface PersistentRateLimiter {
    suspend fun allow(clientId: String): Boolean
}

