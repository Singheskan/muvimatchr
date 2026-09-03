package org.example.muvimatchr.session

import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api/sessions")
class ParticipantController(private val participantService: ParticipantService) {

    @PostMapping("/{joinCode}/participants")
    fun join(@PathVariable joinCode: String, @RequestBody request: JoinRequest): ResponseEntity<JoinResponse> {
        val result = participantService.join(joinCode, request.displayName)
        val participant = result.participant
        val resumeUrl = "/session/${participant.session.id}?token=${result.rawToken}"
        return ResponseEntity.status(HttpStatus.CREATED).body(
            JoinResponse(
                participantId = participant.id!!,
                sessionId = participant.session.id!!,
                displayName = participant.displayName,
                token = result.rawToken,
                resumeUrl = resumeUrl,
            )
        )
    }
}

data class JoinRequest(val displayName: String)

data class JoinResponse(
    val participantId: UUID,
    val sessionId: UUID,
    val displayName: String,
    val token: String,
    val resumeUrl: String,
)
