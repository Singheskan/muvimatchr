package org.example.muvimatchr.session

import org.example.muvimatchr.support.PostgresTestSupport
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.transaction.support.TransactionTemplate

class SessionRepositoryTest : PostgresTestSupport() {

    @Autowired
    lateinit var sessionRepository: SessionRepository

    @Autowired
    lateinit var transactionTemplate: TransactionTemplate

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

    @Test
    fun `a session saved with no explicit region reads back with region DE`() {
        val saved = sessionRepository.save(Session(joinCode = "REGDEF"))

        val found = sessionRepository.findById(saved.id!!)

        assertTrue(found.isPresent)
        assertEquals(DEFAULT_REGION, found.get().region)
    }

    @Test
    fun `a session saved with region US reads back with region US`() {
        val saved = sessionRepository.save(Session(joinCode = "REGUS1", region = "US"))

        val found = sessionRepository.findById(saved.id!!)

        assertTrue(found.isPresent)
        assertEquals("US", found.get().region)
    }

    @Test
    fun `a session saved with no explicit provider selection reads back with an empty provider id list`() {
        val saved = sessionRepository.save(Session(joinCode = "PROVDEF"))

        val found = sessionRepository.findById(saved.id!!)

        assertTrue(found.isPresent)
        assertEquals(emptyList<Int>(), found.get().providerIds)
    }

    @Test
    fun `a session saved with provider ids 8 and 9 reads back with exactly those two ids as integers`() {
        val saved = sessionRepository.save(Session(joinCode = "PROV89", providerIds = listOf(8, 9)))

        val found = sessionRepository.findById(saved.id!!)

        assertTrue(found.isPresent)
        assertEquals(listOf(8, 9), found.get().providerIds)
    }

    @Test
    fun `a session's provider selection can be replaced with a different set including an empty set`() {
        val saved = sessionRepository.save(Session(joinCode = "PROVRPL", providerIds = listOf(1, 2, 3)))

        val reloaded = sessionRepository.findById(saved.id!!).get()
        reloaded.providerIds = listOf(9, 10)
        sessionRepository.save(reloaded)

        val afterFirstReplace = sessionRepository.findById(saved.id!!).get()
        assertEquals(listOf(9, 10), afterFirstReplace.providerIds)

        afterFirstReplace.providerIds = emptyList()
        sessionRepository.save(afterFirstReplace)

        val afterClear = sessionRepository.findById(saved.id!!).get()
        assertEquals(emptyList<Int>(), afterClear.providerIds)
    }

    @Test
    fun `reading a session through a fresh persistence context yields the stored region and provider ids`() {
        val savedId = transactionTemplate.execute {
            sessionRepository.save(Session(joinCode = "FRESHPC", region = "GB", providerIds = listOf(8, 337))).id!!
        }

        // A second, independent transaction/persistence context — not the identity-map hit that
        // re-reading inside the same transaction as the write would produce.
        val reloaded = transactionTemplate.execute {
            sessionRepository.findById(savedId).get()
        }

        assertEquals("GB", reloaded.region)
        assertEquals(listOf(8, 337), reloaded.providerIds)
    }
}
