package org.example.muvimatchr.session

import org.example.muvimatchr.auth.TokenService
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException

@Service
class ParticipantService(
    private val sessionRepository: SessionRepository,
    private val participantRepository: ParticipantRepository,
    private val tokenService: TokenService,
) {
    @Transactional
    fun join(joinCode: String, displayName: String): JoinResult {
        val session = sessionRepository.findByJoinCode(joinCode)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "No session with join code $joinCode")

        val issued = tokenService.issue()
        val participant = participantRepository.save(
            Participant(session = session, displayName = displayName, tokenHash = issued.tokenHash)
        )
        return JoinResult(participant, issued.rawToken)
    }
}

data class JoinResult(val participant: Participant, val rawToken: String)
