package org.example.muvimatchr.realtime

import org.springframework.context.annotation.Configuration
import org.springframework.messaging.support.ChannelInterceptor
import org.springframework.messaging.Message
import org.springframework.messaging.MessageChannel
import org.springframework.messaging.simp.config.ChannelRegistration
import org.springframework.messaging.simp.config.MessageBrokerRegistry
import org.springframework.messaging.simp.stomp.StompCommand
import org.springframework.messaging.simp.stomp.StompHeaderAccessor
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker
import org.springframework.web.socket.config.annotation.StompEndpointRegistry
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer

// This server is push-only: SessionEventPublisher is the only thing that ever writes to the
// broker, and there is no @MessageMapping handler anywhere in this package. The
// ChannelInterceptor below exists because a simple broker enabled on /topic otherwise lets any
// connected client SEND straight to a session's topic and have the broker fan it out to every
// subscriber as if the server had produced it (T-05-03). Dropping the frame (returning null)
// rather than throwing is deliberate: throwing would tear down the connection over a single bad
// frame, handing an unauthenticated caller a cheap way to disconnect themselves and nobody else,
// while a silent drop keeps the smaller surface. Note 05-CONTEXT.md D-01 separately and
// deliberately defers *token* validation on CONNECT/SUBSCRIBE -- that is a distinct, still-open
// decision this interceptor does not attempt to make.
@Configuration
@EnableWebSocketMessageBroker
class WebSocketConfig : WebSocketMessageBrokerConfigurer {

    override fun configureMessageBroker(config: MessageBrokerRegistry) {
        config.enableSimpleBroker("/topic")
    }

    override fun registerStompEndpoints(registry: StompEndpointRegistry) {
        registry.addEndpoint("/ws")
    }

    override fun configureClientInboundChannel(registration: ChannelRegistration) {
        registration.interceptors(object : ChannelInterceptor {
            override fun preSend(message: Message<*>, channel: MessageChannel): Message<*>? {
                val accessor = StompHeaderAccessor.wrap(message)
                if (accessor.command == StompCommand.SEND) {
                    return null
                }
                return message
            }
        })
    }
}
