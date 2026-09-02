package org.example.muvimatchr.session

import org.example.muvimatchr.support.PostgresTestSupport
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.dao.DataIntegrityViolationException

class SessionRepositoryTest : PostgresTestSupport() {

    @Autowired
    lateinit var sessionRepository: SessionRepository

    @Test
    fun `saved session is found by generated id with matching join code`() {
        val saved = sessionRepository.save(Session(joinCode = "ROUND1"))

        val found = sessionRepository.findById(saved.id!!)

        assertTrue(found.isPresent)
        assertEquals("ROUND1", found.get().joinCode)
    }

    @Test
    fun `duplicate join code is rejected by the database on flush`() {
        sessionRepository.saveAndFlush(Session(joinCode = "DUPE01"))

        assertThrows(DataIntegrityViolationException::class.java) {
            sessionRepository.saveAndFlush(Session(joinCode = "DUPE01"))
        }
    }
}
