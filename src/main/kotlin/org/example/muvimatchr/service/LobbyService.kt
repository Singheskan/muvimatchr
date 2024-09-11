package org.example.muvimatchr.service

import org.example.muvimatchr.model.Lobby
import org.example.muvimatchr.model.Participant
import org.springframework.stereotype.Service

@Service
class LobbyService {

    private val lobby = Lobby(host = "Host")

    fun getLobby(): Lobby {
        return lobby
    }

    fun addParticipant(name: String) {
        if (lobby.participants.none { it.name == name }) {
            lobby.participants.add(Participant(name))
        }
    }

    fun markReady(name: String) {
        val participant = lobby.participants.find { it.name == name }
        if (participant != null) {
            participant.isReady = true
        }
    }

    fun isEveryoneReady(): Boolean {
        return lobby.participants.all { it.isReady }
    }
}
