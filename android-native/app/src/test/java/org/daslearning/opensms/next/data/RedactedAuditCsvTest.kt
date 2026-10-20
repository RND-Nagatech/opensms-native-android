package org.daslearning.opensms.next.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RedactedAuditCsvTest {
    @Test
    fun exportIncludesOnlyAllowlistedRedactedFields() {
        val csv = RedactedAuditCsv.encode(
            listOf(
                AuditEventEntity(
                    id = "event-id",
                    timestamp = 123L,
                    actor = "client:client-id",
                    action = "MESSAGE_ACCEPTED",
                    entityId = "destination+628123456789",
                    resultCode = "ACCEPTED",
                    redactedMetadata = "body=secret sms token=Bearer secret",
                ),
            ),
        )

        assertTrue(csv.contains("123,api_client,MESSAGE_ACCEPTED,ACCEPTED"))
        listOf("event-id", "client-id", "+628123456789", "secret sms", "Bearer secret").forEach {
            assertFalse(csv.contains(it))
        }
    }
}
