package org.daslearning.opensms.next.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageDomainTest {
    @Test
    fun validatesE164AndSegmentLimitBeforeEnqueue() {
        val validator = MessageValidator(SmsSegmenter { if (it == "many") 11 else 1 })
        assertEquals(1, validator.validate("+628123456789", "hello"))
        assertEquals("INVALID_RECIPIENT", assertThrows(MessageValidationException::class.java) {
            validator.validate("08123456789", "hello")
        }.reason)
        assertEquals("TOO_MANY_SEGMENTS", assertThrows(MessageValidationException::class.java) {
            validator.validate("+628123456789", "many")
        }.reason)
    }

    @Test
    fun stateMachineDoesNotTreatSendInvocationAsSent() {
        val machine = MessageStateMachine()
        assertTrue(machine.canTransition(MessageStatus.QUEUED, MessageStatus.DISPATCHING))
        assertFalse(machine.canTransition(MessageStatus.QUEUED, MessageStatus.SENT))
        assertFalse(machine.canTransition(MessageStatus.SENT, MessageStatus.DISPATCHING))
        assertTrue(machine.canTransition(MessageStatus.DISPATCHING, MessageStatus.UNKNOWN))
    }
}
