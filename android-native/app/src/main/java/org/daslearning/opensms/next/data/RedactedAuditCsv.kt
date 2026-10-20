package org.daslearning.opensms.next.data

/** Exports only an allowlist of low-risk audit fields. Entity IDs and free-form metadata are omitted. */
object RedactedAuditCsv {
    private val safeCode = Regex("[A-Z0-9_]{1,48}")

    fun encode(events: List<AuditEventEntity>): String = buildString {
        appendLine("timestamp,actor,action,result")
        events.take(200).forEach { event ->
            val actor = when {
                event.actor.startsWith("client:") -> "api_client"
                event.actor in setOf("dispatcher", "recovery", "local_admin") -> event.actor
                else -> "other"
            }
            append(event.timestamp)
            append(',')
            append(actor)
            append(',')
            append(safeValue(event.action))
            append(',')
            appendLine(safeValue(event.resultCode))
        }
    }

    private fun safeValue(value: String): String = value.takeIf(safeCode::matches) ?: "REDACTED"
}
