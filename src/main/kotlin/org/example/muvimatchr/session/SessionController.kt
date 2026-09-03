package org.example.muvimatchr.session

import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.net.URI
import java.util.UUID

@RestController
@RequestMapping("/api/sessions")
class SessionController(private val sessionService: SessionService) {

    @PostMapping
    fun createSession(): ResponseEntity<CreateSessionResponse> {
        val session = sessionService.createSession()
        return ResponseEntity.created(URI.create("/api/sessions/${session.id}"))
            .body(CreateSessionResponse(session.id!!, session.joinCode))
    }
}

data class CreateSessionResponse(val sessionId: UUID, val joinCode: String)
