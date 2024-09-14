package org.example.muvimatchr.controller

import jakarta.servlet.http.HttpSession
import org.example.muvimatchr.service.LobbyService
import org.example.muvimatchr.service.MovieService
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam

@Suppress("UNCHECKED_CAST")
@Controller
class MovieVoteController(
    val movieService: MovieService,
    //private val lobbyService: LobbyService,
) {

    // A map to store aggregated votes for all users in the lobby (lobbyId -> movie -> votes)
    //private val aggregatedVotes = mutableMapOf<String, MutableMap<String, Int>>()

    @GetMapping("/vote")
    fun showMovie(session: HttpSession, model: Model): String {
        val movieTitles = movieService.getMovieTitles()
        //val lobbyId = session.getAttribute("lobbyId") as? String ?: return "error" // Redirect if no lobby
        val username = session.getAttribute("username") as? String ?: return "error"

        // Get the user's current progress
        val currentMovieIndex = session.getAttribute("currentMovieIndex-$username") as? Int ?: 0

        // If we've gone through all movies, show the results page
        if (currentMovieIndex >= movieTitles.size) {
            return "redirect:/results"
        }

        // Get the current movie title and cover URL
        val movieTitle = movieTitles[currentMovieIndex]
        val movieCoverUrl = movieService.getMovieCover(movieTitle)

        // Pass the movie data to the template
        model.addAttribute("movie", mapOf("title" to movieTitle, "coverUrl" to movieCoverUrl))
        return "vote"
    }

    @PostMapping("/submitVote")
    fun submitVote(@RequestParam("vote") vote: String, @RequestParam("movieTitle") movieTitle: String, session: HttpSession): String {
        val username = session.getAttribute("username") as? String ?: return "error"
        val currentMovieIndex = session.getAttribute("currentMovieIndex-$username") as? Int ?: 0

        val userVotes = session.getAttribute("userVotes-$username") as? MutableMap<String, String> ?: mutableMapOf()
        userVotes[movieTitle] = vote

        session.setAttribute("userVotes-$username", userVotes)
        session.setAttribute("currentMovieIndex-$username", currentMovieIndex + 1)

        return "redirect:/vote"
    }

    @GetMapping("/results")
    fun showResults(session: HttpSession, model: Model): String {
        //val lobbyId = session.getAttribute("lobbyId") as? String ?: return "error"

        // Aggregate votes here and show the results
        val username = session.getAttribute("username") as? String ?: return "error"
        val userVotes = session.getAttribute("userVotes-$username") as? MutableMap<String, String> ?: mutableMapOf()

        model.addAttribute("votes", userVotes)
        return "results"
    }
}
