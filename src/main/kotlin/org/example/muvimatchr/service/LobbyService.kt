package org.example.muvimatchr.service

import org.example.muvimatchr.model.Lobby
import org.example.muvimatchr.model.Participant
import org.springframework.stereotype.Service

@Service
class LobbyService {
    // Map of lobbies and the ready status of users (lobbyId -> user -> ready status)
    val lobbies = mutableMapOf<String, MutableMap<String, Boolean>>()

    // Map of aggregated votes for each lobby (lobbyId -> movie -> vote count)
    val aggregatedVotes = mutableMapOf<String, MutableMap<String, Int>>()
}
