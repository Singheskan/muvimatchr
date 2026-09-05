package org.example.muvimatchr.session

import org.example.muvimatchr.catalog.MovieCatalogService
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.security.SecureRandom
import java.time.Instant
import java.util.UUID
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.ObjectMapper

// Crockford Base32 alphabet: 10 digits + 22 letters A-Z excluding I, L, O, U
// (visually ambiguous with 1/1/0/V) — 32 characters total.
private const val JOIN_CODE_ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
private const val JOIN_CODE_LENGTH = 6
private const val MAX_JOIN_CODE_ATTEMPTS = 10

@Service
class SessionService(
    private val sessionRepository: SessionRepository,
    private val objectMapper: ObjectMapper,
) {
    private val random = SecureRandom()

    // Deliberately NO @Transactional here: PostgreSQL aborts the entire transaction after any
    // failed statement, so a shared-transaction retry loop would break on the second attempt.
    // Each retry calls the plain `save()` Spring Data method, which is individually transactional,
    // so each attempt gets its own transaction.
    fun createSession(region: String?, providerIds: List<Int>, genreId: Int? = null): Session {
        repeat(MAX_JOIN_CODE_ATTEMPTS) {
            val candidate = generateJoinCode()
            try {
                return sessionRepository.save(
                    Session(joinCode = candidate, region = region ?: DEFAULT_REGION, providerIds = providerIds, genre = genreId)
                )
            } catch (e: DataIntegrityViolationException) {
                // CR-02: DataIntegrityViolationException is also what Postgres throws for an
                // oversized provider_ids column (VARCHAR(255)), not just a join-code collision.
                // Without this check, an oversized-but-otherwise-valid provider selection is
                // silently retried up to MAX_JOIN_CODE_ATTEMPTS times with the same oversized data
                // and then surfaces as a misleading "could not allocate a join code" error. Only
                // swallow the constraint this loop is actually designed to handle; rethrow
                // anything else so the real cause propagates.
                if (e.mostSpecificCause.message?.contains("uq_session_join_code") != true) throw e
                // collision on uq_session_join_code — retry with a new candidate
            }
        }
        throw IllegalStateException("Could not allocate a unique join code after $MAX_JOIN_CODE_ATTEMPTS attempts")
    }

    // A whole-selection replacement, not a partial merge: an empty provider list explicitly
    // clears the provider filter, a null genre explicitly clears the genre filter, and a null
    // region explicitly resets to the default. This method performs no check on who is calling —
    // membership is verified in the controller, and D-02 makes membership the entire
    // authorisation rule.
    @Transactional
    fun replaceFilters(sessionId: UUID, region: String?, providerIds: List<Int>, genreId: Int?): Session {
        // WR-02: same row lock as pinDeck/recordVote -- without it, a concurrent first-time
        // deck read (DeckController.getDeck -> pinDeck) can commit its pin using filter values
        // captured before this transaction's write lands, even though this write itself commits
        // successfully. Taking the lock here first means the two transactions can no longer
        // interleave on the same session row.
        sessionRepository.lockForUpdate(sessionId)
        val session = sessionRepository.findById(sessionId)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "No session with id $sessionId") }
        // D-02: once the deck is pinned, region/providerIds/genre together are locked. Refusing
        // loudly with 409 is the point -- silently accepting and discarding the request would leave
        // a group believing it had re-filtered a deck that never changed.
        if (session.deckPinnedAt != null) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "Session filters are locked once the deck is pinned")
        }
        session.region = region ?: DEFAULT_REGION
        session.providerIds = providerIds
        session.genre = genreId
        return sessionRepository.save(session)
    }

    // D-04: pinning is a one-time state transition triggered lazily by the first successful deck
    // read for this session (DeckController), not a separate "start voting" action. First writer
    // wins: a second call (e.g. a racing second participant's near-simultaneous first deck read)
    // is a no-op that returns the session unchanged rather than overwriting an already-pinned
    // snapshot.
    @Transactional
    fun pinDeck(sessionId: UUID, genreId: Int?, movies: List<MovieCatalogService.CachedMovie>): Session {
        // CR-01: same pattern as VoteService.recordVote -- every concurrent pinDeck() call for
        // THIS session blocks here until the previous call's transaction commits/rolls back, so
        // the "first writer wins, second call is a no-op" guarantee below is actually enforced
        // rather than merely documented.
        sessionRepository.lockForUpdate(sessionId)
        val session = sessionRepository.findById(sessionId)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "No session with id $sessionId") }
        if (session.deckPinnedAt != null) return session
        session.genre = genreId
        session.pinnedDeckJson = objectMapper.writeValueAsString(movies)
        session.deckPinnedAt = Instant.now()
        return sessionRepository.save(session)
    }

    // The single deserialization point for the pinned_deck column -- no other class parses it.
    fun pinnedMovies(session: Session): List<MovieCatalogService.CachedMovie> {
        val json = session.pinnedDeckJson ?: return emptyList()
        return objectMapper.readValue(json, object : TypeReference<List<MovieCatalogService.CachedMovie>>() {})
    }

    fun pinnedMovieIds(session: Session): Set<Long> = pinnedMovies(session).map { it.tmdbId }.toSet()

    private fun generateJoinCode(): String =
        (1..JOIN_CODE_LENGTH).map { JOIN_CODE_ALPHABET[random.nextInt(JOIN_CODE_ALPHABET.length)] }.joinToString("")
}
