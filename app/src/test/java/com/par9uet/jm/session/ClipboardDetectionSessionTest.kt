package com.par9uet.jm.session

import org.junit.Assert.*
import org.junit.Test

class ClipboardDetectionSessionTest {
    @Test fun `cancelling a recognized code suppresses the same number even if the surrounding text changes`() {
        val session = ClipboardDetectionSession()
        val id = "JM1484063".filter { it.isDigit() }.toInt()
        assertTrue(session.shouldPrompt(id))
        session.dismiss(id)
        assertFalse(session.shouldPrompt("推荐 1484063".filter { it.isDigit() }.toInt()))
        assertFalse(session.shouldPrompt("001484063".toInt()))
        assertTrue(session.shouldPrompt(1484064))
    }

    @Test fun `dismissing another comic never forgets earlier cancellations`() {
        val session = ClipboardDetectionSession()
        session.dismiss(1484063)
        session.dismiss(1484064)
        session.dismiss(1484063)
        assertFalse(session.shouldPrompt(1484063))
        assertFalse(session.shouldPrompt(1484064))
    }

    @Test fun `a cold process starts with no rejected codes`() {
        val oldProcess = ClipboardDetectionSession()
        oldProcess.dismiss(1484063)
        assertFalse(oldProcess.shouldPrompt(1484063))
        assertTrue(ClipboardDetectionSession().shouldPrompt(1484063))
    }

    @Test fun `only explicitly dismissed valid codes are suppressed`() {
        val session = ClipboardDetectionSession()
        assertTrue(session.shouldPrompt(1484063))
        assertTrue(session.shouldPrompt(1484063))
        assertFalse(session.shouldPrompt(0))
        assertFalse(session.shouldPrompt(-1))
    }
}
