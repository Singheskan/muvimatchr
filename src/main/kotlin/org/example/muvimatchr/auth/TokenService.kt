package org.example.muvimatchr.auth

import org.springframework.stereotype.Component
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

@Component
class TokenService {
    private val random = SecureRandom()

    fun issue(): IssuedToken {
        val bytes = ByteArray(32) // 256 bits
        random.nextBytes(bytes)
        val rawToken = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        return IssuedToken(rawToken, hash(rawToken))
    }

    fun hash(rawToken: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(rawToken.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) } // 64 hex chars
    }
}

data class IssuedToken(val rawToken: String, val tokenHash: String)
