package org.example.muvimatchr.controller

import jakarta.servlet.http.HttpSession
import org.example.muvimatchr.service.MovieService
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam

@Controller
class MovieVoteController(val movieService: MovieService) {

    // A map to store aggregated votes for all users in the lobby (lobbyId -> movie -> votes)
    private val aggregatedVotes = mutableMapOf<String, MutableMap<String, Int>>()

    @GetMapping("/vote")
    fun showMovie(session: HttpSession, model: Model): String {
        val movieTitles = movieService.getMovieTitles()

        // Get the user's current progress
        val currentMovieIndex = session.getAttribute("currentMovieIndex") as? Int ?: 0

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
        // Get the user's current votes from the session
        val userVotes = session.getAttribute("userVotes") as? MutableMap<String, String> ?: mutableMapOf()

        // Save the user's vote
        userVotes[movieTitle] = vote
        session.setAttribute("userVotes", userVotes)

        // Increment the current movie index
        val currentMovieIndex = session.getAttribute("currentMovieIndex") as? Int ?: 0
        session.setAttribute("currentMovieIndex", currentMovieIndex + 1)

        // Redirect to the next movie
        return "redirect:/vote"
    }

    @GetMapping("/results")
    fun showResults(session: HttpSession, model: Model): String {
        val lobbyId = session.getAttribute("lobbyId") as String
        val userVotes = session.getAttribute("userVotes") as MutableMap<String, String>

        // Aggregate the user's votes with the lobby's global votes
        aggregatedVotes.putIfAbsent(lobbyId, mutableMapOf())
        val lobbyVotes = aggregatedVotes[lobbyId]!!

        userVotes.forEach { (movieTitle, vote) ->
            if (vote == "like") {
                lobbyVotes[movieTitle] = (lobbyVotes[movieTitle] ?: 0) + 1
            }
        }

        // Show the total results for the lobby
        model.addAttribute("results", lobbyVotes)

        return "totalResults"
    }
}
