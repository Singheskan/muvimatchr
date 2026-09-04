package org.example.muvimatchr.catalog

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpHeaders
import org.springframework.web.reactive.function.client.WebClient

// This bean is the single place the TMDB credential exists at runtime. Deliberately no
// request-logging or wiretap filter is attached here, and no filter echoes request headers —
// RESEARCH.md's Security Domain V14 flags header logging as the concrete way this credential
// leaks into application logs. The credential travels only as an Authorization header, never
// as a URL query parameter (a URL-borne credential leaks into access logs and referrers).
@Configuration
class CatalogWebClientConfig(
    @Value("\${tmdb.api.base-url}") private val baseUrl: String,
    @Value("\${tmdb.api.read-access-token}") private val token: String,
) {
    @Bean
    fun tmdbWebClient(builder: WebClient.Builder): WebClient =
        builder.baseUrl(baseUrl)
            .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer $token")
            .build()
}
