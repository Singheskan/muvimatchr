package org.example.muvimatchr.support

import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource

// Fake credential registered into the test Spring context so tests exercise a real credential
// path (Authorization header present, format correct) without needing a real TMDB token.
const val FAKE_TMDB_TOKEN = "fake-tmdb-read-access-token-for-tests"

// Same singleton discipline as PostgresTestSupport, and for the same reason: a JUnit-extension
// -managed per-class lifecycle would stop and restart the server between test classes while
// Spring's cached ApplicationContext still holds the first server's base URL, breaking every
// test class after the first when the full suite runs.
abstract class TmdbMockServerSupport : PostgresTestSupport() {

    companion object {
        @JvmStatic
        val tmdbServer: MockWebServer = MockWebServer().apply { start() }

        @JvmStatic
        @DynamicPropertySource
        fun tmdbProperties(registry: DynamicPropertyRegistry) {
            registry.add("tmdb.api.base-url") { tmdbServer.url("/3").toString().removeSuffix("/") }
            registry.add("tmdb.api.read-access-token") { FAKE_TMDB_TOKEN }
        }
    }

    fun enqueueJson(body: String, status: Int = 200) {
        tmdbServer.enqueue(
            MockResponse.Builder()
                .code(status)
                .addHeader("Content-Type", "application/json")
                .body(body)
                .build()
        )
    }

    fun takeRecordedRequest(): RecordedRequest = tmdbServer.takeRequest()

    // The server is a JVM-wide singleton, so absolute counts are not stable across test classes
    // — callers must capture this at the start of a test and assert on the delta.
    fun currentRequestCount(): Int = tmdbServer.requestCount
}
