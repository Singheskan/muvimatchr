package org.example.muvimatchr.catalog

import org.example.muvimatchr.support.TmdbMockServerSupport
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit

private const val GENRE_FIXTURE = """
{ "genres": [ {"id": 28, "name": "Action"}, {"id": 35, "name": "Comedy"} ] }
"""

private const val PROVIDERS_DE_FIXTURE = """
{ "results": [ {"provider_id": 8, "provider_name": "Netflix", "logo_path": "/netflix.jpg", "display_priority": 1} ] }
"""

private const val PROVIDERS_US_FIXTURE = """
{ "results": [ {"provider_id": 9, "provider_name": "Prime Video", "logo_path": "/prime.jpg", "display_priority": 2} ] }
"""

class CatalogReferenceServiceTest : TmdbMockServerSupport() {

    @Autowired
    lateinit var catalogReferenceService: CatalogReferenceService

    @Autowired
    lateinit var genreRepository: GenreRepository

    @Autowired
    lateinit var watchProviderRepository: WatchProviderRepository

    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

    // Unlike the deck cache, these tables are populated lazily -- a leftover row from another test
    // would mask a missing fetch, so both reference tables are cleared before every test.
    @BeforeEach
    fun clearReferenceTables() {
        genreRepository.deleteAll()
        watchProviderRepository.deleteAll()
    }

    private fun ageGenres(olderThan: Duration) {
        val agedInstant = Instant.now().minus(olderThan).minus(1, ChronoUnit.MINUTES)
        jdbcTemplate.update("UPDATE genre SET fetched_at = ?", Timestamp.from(agedInstant))
    }

    @Test
    fun `first genre read with empty table triggers exactly one upstream request and stores one row per genre`() {
        val before = requestCount()
        enqueueJson(GENRE_FIXTURE)

        val genres = catalogReferenceService.genres()

        assertEquals(1, requestCount() - before)
        assertEquals(2, genres.size)
    }

    @Test
    fun `a second genre read immediately afterwards triggers zero further upstream requests`() {
        enqueueJson(GENRE_FIXTURE)
        catalogReferenceService.genres()

        val before = requestCount()
        catalogReferenceService.genres()

        assertEquals(0, requestCount() - before)
    }

    @Test
    fun `a genre read whose stored rows have aged past the reference TTL triggers exactly one further request and updates rows in place`() {
        enqueueJson(GENRE_FIXTURE)
        catalogReferenceService.genres()
        assertEquals(2, genreRepository.findAll().size)

        ageGenres(Duration.ofHours(168))

        val before = requestCount()
        enqueueJson(GENRE_FIXTURE)
        val genres = catalogReferenceService.genres()

        assertEquals(1, requestCount() - before)
        assertEquals(2, genres.size)
    }

    @Test
    fun `provider reads for two distinct regions each trigger their own single request and store rows tagged with their region, and a repeat read triggers none`() {
        val before = requestCount()
        enqueueJson(PROVIDERS_DE_FIXTURE)
        enqueueJson(PROVIDERS_US_FIXTURE)

        val de = catalogReferenceService.watchProviders("DE")
        val us = catalogReferenceService.watchProviders("US")

        assertEquals(2, requestCount() - before)
        assertEquals(1, de.size)
        assertEquals(1, us.size)
        assertEquals("DE", de[0].region)
        assertEquals("US", us[0].region)

        val beforeRepeat = requestCount()
        catalogReferenceService.watchProviders("DE")
        assertEquals(0, requestCount() - beforeRepeat)
    }

    @Test
    fun `reading providers for a region returns only that region's rows`() {
        enqueueJson(PROVIDERS_DE_FIXTURE)
        enqueueJson(PROVIDERS_US_FIXTURE)
        catalogReferenceService.watchProviders("DE")
        catalogReferenceService.watchProviders("US")

        val de = catalogReferenceService.watchProviders("DE")

        assertEquals(1, de.size)
        assertEquals(8, de[0].tmdbId)
    }
}
