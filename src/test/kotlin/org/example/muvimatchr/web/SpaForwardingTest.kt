package org.example.muvimatchr.web

import org.example.muvimatchr.support.PostgresTestSupport
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID

@AutoConfigureMockMvc
class SpaForwardingTest : PostgresTestSupport() {

    @Autowired
    lateinit var mockMvc: MockMvc

    @Test
    fun `GET s code returns the SPA shell as 200 text-html`() {
        mockMvc.perform(get("/s/ABC123"))
            .andExpect(status().isOk)
            .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
    }

    @Test
    fun `GET s code swipe returns the SPA shell as 200 text-html`() {
        mockMvc.perform(get("/s/ABC123/swipe"))
            .andExpect(status().isOk)
            .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
    }

    @Test
    fun `GET s code wait returns the SPA shell as 200 text-html`() {
        mockMvc.perform(get("/s/ABC123/wait"))
            .andExpect(status().isOk)
            .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
    }

    @Test
    fun `GET s code results returns the SPA shell as 200 text-html`() {
        mockMvc.perform(get("/s/ABC123/results"))
            .andExpect(status().isOk)
            .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
    }

    @Test
    fun `unauthenticated deck request still returns 401 -- SPA forwarding does not shadow the API namespace`() {
        mockMvc.perform(get("/api/sessions/${UUID.randomUUID()}/deck"))
            .andExpect(status().isUnauthorized)
    }
}
