package org.daslearning.opensms.next.api

import android.util.Base64
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.header
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.utils.io.readBuffer
import kotlinx.io.readByteArray
import java.time.Instant
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONException
import org.json.JSONObject
import org.daslearning.opensms.next.data.ClientRepository
import org.daslearning.opensms.next.data.MessageRepository
import org.daslearning.opensms.next.domain.AuthenticatedClient
import org.daslearning.opensms.next.domain.EnqueueInput
import org.daslearning.opensms.next.domain.EnqueueResult
import org.daslearning.opensms.next.domain.GatewayServer
import org.daslearning.opensms.next.domain.GatewayStatusSource
import org.daslearning.opensms.next.domain.MessageValidationException
import org.daslearning.opensms.next.domain.PersistentRateLimiter
import org.daslearning.opensms.next.domain.SmsPartEncoder
import org.daslearning.opensms.next.domain.SubscriptionProvider
import org.daslearning.opensms.next.domain.TrustedNetworkPolicy
import org.daslearning.opensms.next.domain.FieldCipher

@Singleton
class OpenSmsApiServer @Inject constructor(
    private val clients: ClientRepository,
    private val messages: MessageRepository,
    private val subscriptions: SubscriptionProvider,
    private val partEncoder: SmsPartEncoder,
    private val rateLimiter: PersistentRateLimiter,
    private val statusSource: GatewayStatusSource,
    private val trustedNetworkPolicy: TrustedNetworkPolicy,
    private val cipher: FieldCipher,
) : GatewayServer {
    private var server: EmbeddedServer<*, *>? = null
    private val lifecycleMutex = Mutex()
    @Volatile private var loopbackOnly = true

    override suspend fun start(host: String, port: Int) {
        lifecycleMutex.withLock {
            check(server == null) { "GATEWAY_ALREADY_RUNNING" }
            require(port in 1024..65535) { "INVALID_PORT" }
            val loopback = host == "127.0.0.1" || host == "localhost" || host == "::1"
            require(loopback || (trustedNetworkPolicy.allowLanListener() && trustedNetworkPolicy.localWifiAddress() == host)) {
                "UNTRUSTED_BIND_ADDRESS"
            }
            loopbackOnly = loopback
            server = embeddedServer(CIO, host = host, port = port, module = { openSmsModule() })
                .also { it.start(wait = false) }
        }
    }

    override suspend fun stop() {
        lifecycleMutex.withLock {
            server?.stop(gracePeriodMillis = 300, timeoutMillis = 1_000)
            server = null
            loopbackOnly = true
        }
    }

    private fun Application.openSmsModule() {
        routing {
            get("/v1/health") {
                if (call.allowPeer()) call.respondJson(HttpStatusCode.OK, JSONObject().put("ok", true))
            }
            post("/v1/messages") { handleEnqueueMessage(call) }
            get("/v1/messages/{message_id}") { handleGetMessage(call) }
            get("/v1/messages") { handleListMessages(call) }
            get("/v1/sims") { handleListSims(call) }
            get("/v1/status") { handleGetStatus(call) }
        }
    }

    private suspend fun handleEnqueueMessage(call: ApplicationCall) {
        if (!call.allowPeer()) return
        val client = call.requireClient("messages:write") ?: return
        val idempotencyKey = call.request.header("Idempotency-Key")
        if (idempotencyKey == null || !IDEMPOTENCY_KEY.matches(idempotencyKey)) {
            call.respondError(HttpStatusCode.BadRequest, "INVALID_IDEMPOTENCY_KEY")
            return
        }
        val contentLength = call.request.header("Content-Length")?.toLongOrNull()
        if (contentLength != null && contentLength > MAX_REQUEST_BYTES) {
            call.respondError(HttpStatusCode.PayloadTooLarge, "PAYLOAD_TOO_LARGE")
            return
        }
        if (call.request.header("Content-Type")?.startsWith("application/json", ignoreCase = true) != true) {
            call.respondError(HttpStatusCode.UnsupportedMediaType, "UNSUPPORTED_MEDIA_TYPE")
            return
        }
        val requestBytes = call.receiveChannel().readBuffer(MAX_REQUEST_BYTES + 1L).readByteArray()
        if (requestBytes.size > MAX_REQUEST_BYTES) {
            call.respondError(HttpStatusCode.PayloadTooLarge, "PAYLOAD_TOO_LARGE")
            return
        }
        val request = try {
            parseCreateRequest(requestBytes.toString(Charsets.UTF_8))
        } catch (exception: RequestValidationException) {
            call.respondError(HttpStatusCode.UnprocessableEntity, exception.reason)
            return
        } catch (_: Exception) {
            call.respondError(HttpStatusCode.BadRequest, "INVALID_JSON")
            return
        }
        val idempotencyProbe = EnqueueInput(
            clientId = client.id,
            idempotencyKey = idempotencyKey,
            destination = request.to,
            body = request.text,
            subscriptionId = request.subscriptionId,
            requestedSubscriptionId = request.subscriptionId,
            requestDeliveryReport = request.requestDeliveryReport,
            partsTotal = 1,
            clientReference = request.clientReference,
        )
        val priorRequest = try {
            messages.checkIdempotency(idempotencyProbe)
        } catch (_: Exception) {
            call.respondError(HttpStatusCode.ServiceUnavailable, "QUEUE_UNAVAILABLE")
            return
        }
        when (priorRequest) {
            EnqueueResult.Conflict -> {
                call.respondError(HttpStatusCode.Conflict, "IDEMPOTENCY_CONFLICT")
                return
            }
            is EnqueueResult.Replay -> {
                val message = messages.getMessage(priorRequest.messageId)
                if (message == null) call.respondError(HttpStatusCode.ServiceUnavailable, "QUEUE_UNAVAILABLE")
                else {
                    call.response.headers.append("Idempotent-Replay", "true")
                    call.respondJson(HttpStatusCode.OK, createResponse(message))
                }
                return
            }
            null, is EnqueueResult.Accepted, EnqueueResult.CapacityExceeded -> Unit
        }
        val selectedSubscription = try {
            val active = subscriptions.activeSmsSubscriptions()
            if (active.isEmpty()) {
                call.respondError(HttpStatusCode.ServiceUnavailable, "SIM_UNAVAILABLE")
                return
            }
            val selectedId = request.subscriptionId ?: subscriptions.defaultSmsSubscriptionId()
            val selected = active.firstOrNull { it.subscriptionId == selectedId }
            if (selectedId == null || selected == null) {
                call.respondError(HttpStatusCode.UnprocessableEntity, "INVALID_SIM_ID")
                return
            }
            selected.subscriptionId
        } catch (_: SecurityException) {
            call.respondError(HttpStatusCode.ServiceUnavailable, "SIM_PERMISSION_REQUIRED")
            return
        } catch (_: Exception) {
            call.respondError(HttpStatusCode.ServiceUnavailable, "SIM_UNAVAILABLE")
            return
        }
        val partCount = try {
            if (!E164.matches(request.to)) throw RequestValidationException("INVALID_RECIPIENT")
            if (request.text.isBlank()) throw RequestValidationException("EMPTY_MESSAGE")
            if (request.text.length > MAX_TEXT_LENGTH) throw RequestValidationException("MESSAGE_TOO_LONG")
            partEncoder.split(request.text, selectedSubscription).size.also {
                if (it !in 1..MAX_SEGMENTS) throw RequestValidationException("TOO_MANY_SEGMENTS")
            }
        } catch (exception: RequestValidationException) {
            call.respondError(HttpStatusCode.UnprocessableEntity, exception.reason)
            return
        } catch (_: Exception) {
            call.respondError(HttpStatusCode.ServiceUnavailable, "SMS_CAPABILITY_UNAVAILABLE")
            return
        }
        if (!rateLimiter.allow(client.id)) {
            call.respondError(HttpStatusCode.TooManyRequests, "RATE_LIMITED")
            return
        }
        val result = try {
            messages.enqueue(
                EnqueueInput(
                    clientId = client.id,
                    idempotencyKey = idempotencyKey,
                    destination = request.to,
                    body = request.text,
                    subscriptionId = selectedSubscription,
                    requestedSubscriptionId = request.subscriptionId,
                    requestDeliveryReport = request.requestDeliveryReport,
                    partsTotal = partCount,
                    clientReference = request.clientReference,
                ),
            )
        } catch (_: Exception) {
            call.respondError(HttpStatusCode.ServiceUnavailable, "QUEUE_UNAVAILABLE")
            return
        }
        when (result) {
            is EnqueueResult.Conflict -> call.respondError(HttpStatusCode.Conflict, "IDEMPOTENCY_CONFLICT")
            EnqueueResult.CapacityExceeded -> call.respondError(HttpStatusCode.ServiceUnavailable, "QUEUE_FULL")
            is EnqueueResult.Accepted -> {
                val message = messages.getMessage(result.messageId) ?: run {
                    call.respondError(HttpStatusCode.ServiceUnavailable, "QUEUE_UNAVAILABLE")
                    return
                }
                call.respondJson(HttpStatusCode.Accepted, createResponse(message))
            }
            is EnqueueResult.Replay -> {
                val message = messages.getMessage(result.messageId) ?: run {
                    call.respondError(HttpStatusCode.ServiceUnavailable, "QUEUE_UNAVAILABLE")
                    return
                }
                call.response.headers.append("Idempotent-Replay", "true")
                call.respondJson(HttpStatusCode.OK, createResponse(message))
            }
        }
    }

    private suspend fun handleGetMessage(call: ApplicationCall) {
        if (!call.allowPeer()) return
        val client = call.requireClient("messages:read") ?: return
        val id = call.parameters["message_id"]
        if (id.isNullOrBlank() || id.length > 64) {
            call.respondError(HttpStatusCode.NotFound, "NOT_FOUND")
            return
        }
        val message = messages.getOwnedMessage(client.id, id)
        if (message == null) {
            call.respondError(HttpStatusCode.NotFound, "NOT_FOUND")
            return
        }
        val parts = messages.parts(id)
        call.respondJson(HttpStatusCode.OK, detailResponse(message, parts))
    }

    private suspend fun handleListMessages(call: ApplicationCall) {
        if (!call.allowPeer()) return
        val client = call.requireClient("messages:read") ?: return
        val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 20
        if (limit !in 1..100) {
            call.respondError(HttpStatusCode.BadRequest, "INVALID_LIMIT")
            return
        }
        val offset = decodeCursor(call.request.queryParameters["cursor"])
        if (offset == null) {
            call.respondError(HttpStatusCode.BadRequest, "INVALID_CURSOR")
            return
        }
        val status = call.request.queryParameters["status"]?.uppercase()
        if (status != null && status !in MESSAGE_STATUSES) {
            call.respondError(HttpStatusCode.BadRequest, "INVALID_STATUS")
            return
        }
        val simId = call.request.queryParameters["sim_id"]?.let { value ->
            value.toIntOrNull()?.takeIf { it >= 0 } ?: run {
                call.respondError(HttpStatusCode.BadRequest, "INVALID_SIM_ID")
                return
            }
        }
        val createdFrom = parseDateQuery(call, "created_from") ?: if (call.request.queryParameters["created_from"] != null) return else null
        val createdTo = parseDateQuery(call, "created_to") ?: if (call.request.queryParameters["created_to"] != null) return else null
        if (createdFrom != null && createdTo != null && createdFrom > createdTo) {
            call.respondError(HttpStatusCode.BadRequest, "INVALID_DATE_RANGE")
            return
        }
        val rows = messages.listOwnedMessages(client.id, limit + 1, offset, status, simId, createdFrom, createdTo)
        val hasMore = rows.size > limit
        val items = rows.take(limit).map { summaryResponse(it) }
        val next = if (hasMore) encodeCursor(offset + limit) else JSONObject.NULL
        call.respondJson(HttpStatusCode.OK, JSONObject().put("items", org.json.JSONArray(items)).put("next_cursor", next))
    }

    private suspend fun handleListSims(call: ApplicationCall) {
        if (!call.allowPeer()) return
        if (call.requireClient("system:read") == null) return
        val active = try {
            subscriptions.activeSmsSubscriptions()
        } catch (_: SecurityException) {
            call.respondError(HttpStatusCode.Forbidden, "SIM_PERMISSION_REQUIRED")
            return
        }
        val defaultId = subscriptions.defaultSmsSubscriptionId()
        val items = org.json.JSONArray()
        active.forEach { sim ->
            items.put(
                JSONObject()
                    .put("sim_id", sim.subscriptionId)
                    .put("slot_index", sim.slotIndex)
                    .put("display_name", sim.displayName.take(40))
                    .put("is_default", sim.subscriptionId == defaultId)
                    .put("is_available", true),
            )
        }
        call.respondJson(HttpStatusCode.OK, JSONObject().put("items", items))
    }

    private suspend fun handleGetStatus(call: ApplicationCall) {
        if (!call.allowPeer()) return
        if (call.requireClient("system:read") == null) return
        val state = statusSource.snapshot.first()
        val simsReady = try { subscriptions.activeSmsSubscriptions().isNotEmpty() } catch (_: Exception) { false }
        call.respondJson(
            HttpStatusCode.OK,
            JSONObject()
                .put("server", state.server.name)
                .put("sms_ready", simsReady)
                .put("pending_count", messages.queueSize())
                .put("network", state.network.name)
                .put("version", org.daslearning.opensms.next.BuildConfig.VERSION_NAME),
        )
    }

    private suspend fun ApplicationCall.requireClient(scope: String): AuthenticatedClient? {
        val authorization = request.header("Authorization")
        val token = authorization?.takeIf { it.startsWith("Bearer ", ignoreCase = false) }
            ?.removePrefix("Bearer ")
        val client = clients.authenticate(token)
        if (client == null) {
            respondError(HttpStatusCode.Unauthorized, "UNAUTHORIZED")
            return null
        }
        if (scope !in client.scopes) {
            respondError(HttpStatusCode.Forbidden, "FORBIDDEN")
            return null
        }
        return client
    }

    private suspend fun ApplicationCall.allowPeer(): Boolean {
        if (loopbackOnly || trustedNetworkPolicy.isAllowedPeer(request.local.remoteHost)) return true
        respondError(HttpStatusCode.Forbidden, "NETWORK_NOT_TRUSTED")
        return false
    }

    private suspend fun ApplicationCall.respondError(status: HttpStatusCode, code: String) {
        val requestId = UUID.randomUUID().toString()
        val body = JSONObject().put(
            "error",
            JSONObject()
                .put("code", code)
                .put("message", ERROR_MESSAGES[code] ?: "Request could not be completed")
                .put("request_id", requestId)
                .put("details", JSONObject()),
        )
        respondJson(status, body)
    }

    private suspend fun ApplicationCall.respondJson(status: HttpStatusCode, body: JSONObject) {
        respondText(body.toString(), ContentType.Application.Json, status)
    }

    private suspend fun parseDateQuery(call: ApplicationCall, name: String): Long? {
        val value = call.request.queryParameters[name] ?: return null
        return try {
            Instant.parse(value).toEpochMilli()
        } catch (_: Exception) {
            call.respondError(HttpStatusCode.BadRequest, "INVALID_DATE")
            null
        }
    }

    private fun parseCreateRequest(json: String): CreateRequest {
        if (json.toByteArray(Charsets.UTF_8).size > MAX_REQUEST_BYTES) throw RequestValidationException("PAYLOAD_TOO_LARGE")
        val objectValue = try { JSONObject(json) } catch (_: JSONException) { throw RequestValidationException("INVALID_JSON") }
        val allowed = setOf("to", "text", "sim_id", "request_delivery_report", "client_reference")
        val keys = objectValue.keys()
        while (keys.hasNext()) if (keys.next() !in allowed) throw RequestValidationException("UNKNOWN_PROPERTY")
        val destination = objectValue.opt("to") as? String ?: throw RequestValidationException("INVALID_RECIPIENT")
        val body = objectValue.opt("text") as? String ?: throw RequestValidationException("EMPTY_MESSAGE")
        val simId = when {
            !objectValue.has("sim_id") || objectValue.isNull("sim_id") -> null
            objectValue.opt("sim_id") is Number -> objectValue.optInt("sim_id")
            else -> throw RequestValidationException("INVALID_SIM_ID")
        }
        val delivery = when {
            !objectValue.has("request_delivery_report") -> true
            objectValue.opt("request_delivery_report") is Boolean -> objectValue.optBoolean("request_delivery_report")
            else -> throw RequestValidationException("INVALID_DELIVERY_REPORT")
        }
        val reference = when {
            !objectValue.has("client_reference") || objectValue.isNull("client_reference") -> null
            objectValue.opt("client_reference") is String -> objectValue.optString("client_reference")
            else -> throw RequestValidationException("INVALID_CLIENT_REFERENCE")
        }
        if (reference != null && reference.length > 100) throw RequestValidationException("INVALID_CLIENT_REFERENCE")
        return CreateRequest(destination, body, simId, delivery, reference)
    }

    private fun createResponse(message: org.daslearning.opensms.next.data.MessageEntity): JSONObject =
        JSONObject()
            .put("message_id", message.id)
            .put("status", message.status)
            .put("delivery_status", message.deliveryStatus)
            .put("created_at", Instant.ofEpochMilli(message.createdAt).toString())
            .put("parts_total", message.partsTotal)
            .put("links", JSONObject().put("self", "/v1/messages/${message.id}"))

    private fun summaryResponse(message: org.daslearning.opensms.next.data.MessageEntity): JSONObject =
        JSONObject()
            .put("message_id", message.id)
            .put("to_masked", maskDestination(message.destinationEncrypted))
            .put("status", message.status)
            .put("delivery_status", message.deliveryStatus)
            .put("created_at", Instant.ofEpochMilli(message.createdAt).toString())

    private fun detailResponse(
        message: org.daslearning.opensms.next.data.MessageEntity,
        parts: List<org.daslearning.opensms.next.data.MessagePartEntity>,
    ): JSONObject = JSONObject()
        .put("message_id", message.id)
        .put("to_masked", maskDestination(message.destinationEncrypted))
        .put("status", message.status)
        .put("delivery_status", message.deliveryStatus)
        .put("sim_id", message.simId ?: JSONObject.NULL)
        .put("parts_total", message.partsTotal)
        .put("parts_sent", parts.count { it.sentStatus == "SENT" })
        .put("parts_delivered", parts.count { it.deliveryStatus == "CONFIRMED" })
        .put("client_reference", message.clientReference ?: JSONObject.NULL)
        .put("created_at", Instant.ofEpochMilli(message.createdAt).toString())
        .put("sent_at", message.sentAt?.let { Instant.ofEpochMilli(it).toString() } ?: JSONObject.NULL)
        .put("delivered_at", message.deliveredAt?.let { Instant.ofEpochMilli(it).toString() } ?: JSONObject.NULL)
        .put("last_error", message.errorCode ?: JSONObject.NULL)

    private fun maskDestination(encryptedDestination: String): String = runCatching {
        val destination = cipher.decrypt(encryptedDestination)
        if (destination.length < 6) "••••" else "${destination.take(3)}******${destination.takeLast(2)}"
    }.getOrDefault("••••••")

    private fun encodeCursor(offset: Int): String = Base64.encodeToString(
        offset.toString().toByteArray(Charsets.UTF_8),
        Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
    )

    private fun decodeCursor(cursor: String?): Int? {
        if (cursor == null) return 0
        if (cursor.length > 24) return null
        return runCatching {
            Base64.decode(cursor, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
                .toString(Charsets.UTF_8).toInt().takeIf { it in 0..1_000_000_000 }
        }.getOrNull()
    }

    private data class CreateRequest(
        val to: String,
        val text: String,
        val subscriptionId: Int?,
        val requestDeliveryReport: Boolean,
        val clientReference: String?,
    )

    private class RequestValidationException(val reason: String) : IllegalArgumentException(reason)

    private companion object {
        val IDEMPOTENCY_KEY = Regex("^[A-Za-z0-9._:-]{16,128}$")
        val E164 = Regex("^\\+[1-9][0-9]{1,14}$")
        const val MAX_REQUEST_BYTES = 12_288
        const val MAX_TEXT_LENGTH = 1_600
        const val MAX_SEGMENTS = 10
        val MESSAGE_STATUSES = setOf("QUEUED", "DISPATCHING", "SENT", "DELIVERED", "FAILED", "UNKNOWN", "CANCELED")
        val ERROR_MESSAGES = mapOf(
            "UNAUTHORIZED" to "API key is missing, invalid, or revoked",
            "FORBIDDEN" to "API key does not have permission for this operation",
            "SIM_UNAVAILABLE" to "No active SMS subscription is available",
            "QUEUE_FULL" to "The message queue is full",
            "IDEMPOTENCY_CONFLICT" to "Idempotency key was previously used with a different request",
            "RATE_LIMITED" to "Request rate limit exceeded",
        )
    }
}
