package org.example.muvimatchr.catalog

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpHeaders
import org.springframework.http.client.reactive.ReactorClientHttpConnector
import org.springframework.web.reactive.function.client.WebClient
import reactor.netty.http.client.HttpClient
import java.time.Duration

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
    // WR-02: without a bounded connect/response timeout, a TMDB connection that is accepted but
    // never answered leaves the Mono (and the runBlocking/awaitSingle wrapping it) hanging
    // indefinitely -- the retry policy never even gets a chance to run because there is nothing
    // to retry yet. Both timeouts are set: connectTimeout bounds the TCP handshake, responseTimeout
    // bounds the wait for a complete response once connected.
    @Bean
    fun tmdbWebClient(builder: WebClient.Builder): WebClient {
        val httpClient = HttpClient.create()
            .option(io.netty.channel.ChannelOption.CONNECT_TIMEOUT_MILLIS, 5_000)
            .responseTimeout(Duration.ofSeconds(10))
        return builder.baseUrl(baseUrl)
            .clientConnector(ReactorClientHttpConnector(httpClient))
            .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer $token")
            .build()
    }
}
