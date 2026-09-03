package org.example.muvimatchr.session

import org.example.muvimatchr.support.PostgresTestSupport
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

class SessionServiceTest : PostgresTestSupport() {

    @Autowired
    lateinit var sessionService: SessionService

    @Test
    fun `two back-to-back createSession calls never return the same joinCode`() {
        val first = sessionService.createSession()
        val second = sessionService.createSession()

        assertNotEquals(first.joinCode, second.joinCode)
    }

    @Test
    fun `createSession returns a non-blank joinCode exactly 6 characters long and within VARCHAR(16)`() {
        val session = sessionService.createSession()

        assertTrue(session.joinCode.isNotBlank())
        assertTrue(session.joinCode.length == 6)
        assertTrue(session.joinCode.length <= 16)
    }
}
