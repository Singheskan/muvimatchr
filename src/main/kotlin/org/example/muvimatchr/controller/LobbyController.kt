package org.example.muvimatchr.controller

import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import jakarta.servlet.http.HttpSession
import org.springframework.web.bind.annotation.RequestParam
import java.util.UUID

@Controller
class LobbyController {

    // Global storage for lobbies (mapping of lobbyId -> list of users and their ready status)
    private val lobbies = mutableMapOf<String, MutableMap<String, Boolean>>()  // Lobby -> User -> Ready status

    @GetMapping("/")
    fun enterLobby(session: HttpSession, model: Model): String {
        // If there is no lobby in the session, assign a new one
        val lobbyId = session.getAttribute("lobbyId") as? String ?: generateLobbyId()
        val username = session.getAttribute("username") as? String ?: generateUsername()

        // Store the lobbyId and username in the session
        session.setAttribute("lobbyId", lobbyId)
        session.setAttribute("username", username)

        // If the lobby doesn't exist, create a new one
        lobbies.putIfAbsent(lobbyId, mutableMapOf())

        // Add the user to the lobby with a default "not ready" status
        val lobbyUsers = lobbies[lobbyId]!!
        lobbyUsers.putIfAbsent(username, false)

        // Pass the lobbyId and list of users to the model
        model.addAttribute("lobbyId", lobbyId)
        model.addAttribute("users", lobbyUsers)
        model.addAttribute("allReady", lobbyUsers.values.all { it })

        return "lobby"  // Return the lobby page
    }

    // Handle session reset by redirecting to assign a new lobby
    @GetMapping("/reset")
    fun resetSession(session: HttpSession): String {
        session.invalidate()  // Invalidate the current session
        return "redirect:/"    // Redirect to the main page where a new session will be created
    }

    // Toggle the user's ready status
    @PostMapping("/toggleReady")
    fun toggleReady(session: HttpSession): String {
        val lobbyId = session.getAttribute("lobbyId") as String
        val username = session.getAttribute("username") as String

        // Toggle the user's ready status
        val lobbyUsers = lobbies[lobbyId]!!
        lobbyUsers[username] = !(lobbyUsers[username] ?: false)

        return "redirect:/"
    }

    // Continue to movie selection if all users are ready
    @PostMapping("/continue")
    fun continueToMovies(session: HttpSession): String {
        val lobbyId = session.getAttribute("lobbyId") as String

        // Check if all users are ready in the current lobby
        val lobbyUsers = lobbies[lobbyId]!!
        if (lobbyUsers.values.all { it }) {
            return "redirect:/vote"
        }

        return "redirect:/"
    }

    @PostMapping("/switchLobby")
    fun switchLobby(@RequestParam("newLobbyId") newLobbyId: String, session: HttpSession): String {
        // Get the current username and remove the user from the current lobby
        val username = session.getAttribute("username") as String
        val currentLobbyId = session.getAttribute("lobbyId") as String
        lobbies[currentLobbyId]?.remove(username)

        // Switch the user to the new lobby
        session.setAttribute("lobbyId", newLobbyId)
        lobbies.putIfAbsent(newLobbyId, mutableMapOf())
        lobbies[newLobbyId]?.put(username, false)

        return "redirect:/"
    }

    // Generate a random lobby identifier using UUID
    private fun generateLobbyId(): String {
        return UUID.randomUUID().toString()
    }

    // Generate a random username (you can modify this to accept user input)
    private fun generateUsername(): String {
        return "User" + UUID.randomUUID().toString().take(5)
    }
}
