package org.example.muvimatchr.service

import org.springframework.stereotype.Service
import org.springframework.web.client.RestTemplate

@Service
class MovieService {

    private val apiKey = "7a1b329"
    private val baseUrl = "http://www.omdbapi.com/"
    private val restTemplate = RestTemplate()

    // A mock list of movie titles (or you can fetch these dynamically)
    fun getMovieTitles(): List<String> {
        return listOf("Fight Club", "Inception", "The Matrix", "Interstellar", "Gladiator", "Titanic", "The Godfather", "Pulp Fiction", "Avatar", "The Dark Knight")
    }

    // Fetch the movie cover for a single movie
    fun getMovieCover(title: String): String {
        val url = "$baseUrl?t=$title&apikey=$apiKey"
        val response = restTemplate.getForObject(url, Map::class.java)
        val posterUrl = response?.get("Poster") as? String
        return posterUrl ?: "/images/default_cover.jpg"
    }
}

