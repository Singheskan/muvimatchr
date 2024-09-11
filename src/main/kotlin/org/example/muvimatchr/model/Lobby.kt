package org.example.muvimatchr.model

data class Participant(val name: String, var isReady: Boolean = false)

data class Lobby(val host: String, val participants: MutableList<Participant> = mutableListOf())
