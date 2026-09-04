package org.example.muvimatchr.session

import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.security.SecureRandom
import java.util.UUID

// Crockford Base32 alphabet: 10 digits + 22 letters A-Z excluding I, L, O, U
// (visually ambiguous with 1/1/0/V) — 32 characters total.
private const val JOIN_CODE_ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
private const val JOIN_CODE_LENGTH = 6
private const val MAX_JOIN_CODE_ATTEMPTS = 10

@Service
class SessionService(private val sessionRepository: SessionRepository) {
    private val random = SecureRandom()

    // Deliberately NO @Transactional here: PostgreSQL aborts the entire transaction after any
    // failed statement, so a shared-transaction retry loop would break on the second attempt.
    // Each retry calls the plain `save()` Spring Data method, which is individually transactional,
    // so each attempt gets its own transaction.
    fun createSession(region: String?, providerIds: List<Int>): Session {
        repeat(MAX_JOIN_CODE_ATTEMPTS) {
            val candidate = generateJoinCode()
            try {
                return sessionRepository.save(
                    Session(joinCode = candidate, region = region ?: DEFAULT_REGION, providerIds = providerIds)
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
    // clears the provider filter, and a null region explicitly resets to the default. This
    // method performs no check on who is calling — membership is verified in the controller,
    // and D-02 makes membership the entire authorisation rule.
    @Transactional
    fun replaceFilters(sessionId: UUID, region: String?, providerIds: List<Int>): Session {
        val session = sessionRepository.findById(sessionId)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "No session with id $sessionId") }
        session.region = region ?: DEFAULT_REGION
        session.providerIds = providerIds
        return sessionRepository.save(session)
    }

    private fun generateJoinCode(): String =
        (1..JOIN_CODE_LENGTH).map { JOIN_CODE_ALPHABET[random.nextInt(JOIN_CODE_ALPHABET.length)] }.joinToString("")
}
