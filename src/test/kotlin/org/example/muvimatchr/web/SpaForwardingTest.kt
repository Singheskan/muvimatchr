package org.example.muvimatchr.web

import org.example.muvimatchr.support.PostgresTestSupport
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID

// NOTE: @AutoConfigureMockMvc runs against a mocked servlet environment (no embedded container),
// so a "forward:" view's target is never actually re-dispatched and served -- MockMvc's own
// documented pattern for asserting a forward is forwardedUrl(), not a real fetch of the forwarded
// resource's body/content-type. The real end-to-end proof (an actual served index.html with a
// text/html Content-Type from the running JAR) is Task 4's human-check against a live
// `./gradlew bootRun`, once frontend/dist actually exists. This test proves SpaForwardingConfig's
// routing decision in isolation: these four paths, and no others, forward to /index.html.
@AutoConfigureMockMvc
class SpaForwardingTest : PostgresTestSupport() {

    @Autowired
    lateinit var mockMvc: MockMvc

    @Test
    fun `GET s code forwards to the SPA shell`() {
        mockMvc.perform(get("/s/ABC123"))
            .andExpect(status().isOk)
            .andExpect(forwardedUrl("/index.html"))
    }

    @Test
    fun `GET s code swipe forwards to the SPA shell`() {
        mockMvc.perform(get("/s/ABC123/swipe"))
            .andExpect(status().isOk)
            .andExpect(forwardedUrl("/index.html"))
    }

    @Test
    fun `GET s code wait forwards to the SPA shell`() {
        mockMvc.perform(get("/s/ABC123/wait"))
            .andExpect(status().isOk)
            .andExpect(forwardedUrl("/index.html"))
    }

    @Test
    fun `GET s code results forwards to the SPA shell`() {
        mockMvc.perform(get("/s/ABC123/results"))
            .andExpect(status().isOk)
            .andExpect(forwardedUrl("/index.html"))
    }

    @Test
    fun `unauthenticated deck request still returns 401 -- SPA forwarding does not shadow the API namespace`() {
        mockMvc.perform(get("/api/sessions/${UUID.randomUUID()}/deck"))
            .andExpect(status().isUnauthorized)
    }
}
