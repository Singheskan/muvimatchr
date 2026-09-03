package org.example.muvimatchr.session

import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.example.muvimatchr.auth.CurrentParticipant
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

@RestController
@RequestMapping("/api/sessions")
class ParticipantController(private val participantService: ParticipantService) {

    @PostMapping("/{joinCode}/participants")
    fun join(@PathVariable joinCode: String, @Valid @RequestBody request: JoinRequest): ResponseEntity<JoinResponse> {
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

    @GetMapping("/{sessionId}/participants/me")
    fun me(@PathVariable sessionId: UUID, @CurrentParticipant participant: Participant): ParticipantResponse {
        if (participant.session.id != sessionId) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "No such participant in this session")
        }
        return ParticipantResponse(participant.id!!, participant.session.id!!, participant.displayName)
    }
}

data class JoinRequest(
    @field:NotBlank
    @field:Size(max = 100)
    val displayName: String,
)

data class JoinResponse(
    val participantId: UUID,
    val sessionId: UUID,
    val displayName: String,
    val token: String,
    val resumeUrl: String,
)

data class ParticipantResponse(val participantId: UUID, val sessionId: UUID, val displayName: String)
