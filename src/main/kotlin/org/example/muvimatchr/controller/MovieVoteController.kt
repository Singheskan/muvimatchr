package org.example.muvimatchr.controller

import org.example.muvimatchr.service.MovieService
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam

@Controller
class MovieVoteController(val movieService: MovieService) {

    // Simulate saved votes (in a real app, use a database or session)
    private val userVotes = mutableMapOf<String, String>()  // Map of movie title -> vote (like/dislike)
    private var currentMovieIndex = 0

    @GetMapping("/vote")
    fun showMovie(model: Model): String {
        val movieTitles = movieService.getMovieTitles()

        if (currentMovieIndex >= movieTitles.size) {
            // If we've gone through all movies, show a results page
            model.addAttribute("votes", userVotes)
            return "results"
        }

        // Get the current movie title and cover URL
        val movieTitle = movieTitles[currentMovieIndex]
        val movieCoverUrl = movieService.getMovieCover(movieTitle)

        // Pass the movie data to the template
        model.addAttribute("movie", mapOf("title" to movieTitle, "coverUrl" to movieCoverUrl))
        return "vote"  // Returns the vote page template
    }

    @PostMapping("/submitVote")
    fun submitVote(@RequestParam("vote") vote: String, @RequestParam("movieTitle") movieTitle: String): String {
        // Save the user's vote
        userVotes[movieTitle] = vote

        // Move to the next movie
        currentMovieIndex++

        // Redirect back to the voting page
        return "redirect:/vote"
    }
}

