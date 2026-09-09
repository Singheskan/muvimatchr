package org.example.muvimatchr.web

import org.springframework.context.annotation.Configuration
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

// D-04's SPA deep-link routes, forwarded to the React SPA's shell. Registers exactly these
// literal patterns -- no wildcard or regex path-variable pattern is added here, since that
// could also match the API namespace, the STOMP endpoint, or a static asset path (threat T-06-01).
// No mapping is registered for "/": Spring Boot's welcome-page handling serves static/index.html
// there once LobbyController is gone (Task 3 teardown).
@Configuration
class SpaForwardingConfig : WebMvcConfigurer {
    override fun addViewControllers(registry: ViewControllerRegistry) {
        registry.addViewController("/s/{code}").setViewName("forward:/index.html")
        registry.addViewController("/s/{code}/lobby").setViewName("forward:/index.html")
        registry.addViewController("/s/{code}/swipe").setViewName("forward:/index.html")
        registry.addViewController("/s/{code}/wait").setViewName("forward:/index.html")
        registry.addViewController("/s/{code}/results").setViewName("forward:/index.html")
    }
}
