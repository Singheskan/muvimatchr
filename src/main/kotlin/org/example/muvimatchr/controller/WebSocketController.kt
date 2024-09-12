package org.example.muvimatchr.controller

import org.springframework.messaging.handler.annotation.MessageMapping
import org.springframework.messaging.handler.annotation.SendTo
import org.springframework.stereotype.Controller

@Controller
class WebSocketController {

    data class LobbyEvent(val message: String)

    @MessageMapping("/lobby")
    @SendTo("/topic/lobbyUpdates")
    fun notifyLobby(event: LobbyEvent): LobbyEvent {
        return event
    }
}
