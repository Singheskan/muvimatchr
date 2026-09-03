package org.example.muvimatchr.session

import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import java.security.SecureRandom

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
    fun createSession(): Session {
        repeat(MAX_JOIN_CODE_ATTEMPTS) {
            val candidate = generateJoinCode()
            try {
                return sessionRepository.save(Session(joinCode = candidate))
            } catch (e: DataIntegrityViolationException) {
                // collision on uq_session_join_code — retry with a new candidate
            }
        }
        throw IllegalStateException("Could not allocate a unique join code after $MAX_JOIN_CODE_ATTEMPTS attempts")
    }

    private fun generateJoinCode(): String =
        (1..JOIN_CODE_LENGTH).map { JOIN_CODE_ALPHABET[random.nextInt(JOIN_CODE_ALPHABET.length)] }.joinToString("")
}
