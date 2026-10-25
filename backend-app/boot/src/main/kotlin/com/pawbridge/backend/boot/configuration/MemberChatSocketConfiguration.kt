package com.pawbridge.backend.boot.configuration

import com.pawbridge.backend.boot.javaSplit
import com.pawbridge.backend.boot.javaStrip
import com.pawbridge.backend.community.internal.chat.application.MemberChat
import com.pawbridge.backend.community.internal.chat.infrastructure.MemberChatSignals
import com.pawbridge.backend.community.internal.chat.infrastructure.MemberChatTickets
import com.pawbridge.backend.community.internal.chat.web.MemberChatProtocolErrors
import com.pawbridge.backend.community.internal.chat.web.MemberChatSessions
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.redis.connection.RedisConnectionFactory
import org.springframework.data.redis.listener.ChannelTopic
import org.springframework.data.redis.listener.RedisMessageListenerContainer
import org.springframework.messaging.Message
import org.springframework.messaging.MessageChannel
import org.springframework.messaging.simp.config.ChannelRegistration
import org.springframework.messaging.simp.config.MessageBrokerRegistry
import org.springframework.messaging.simp.stomp.StompCommand
import org.springframework.messaging.simp.stomp.StompHeaderAccessor
import org.springframework.messaging.support.ChannelInterceptor
import org.springframework.messaging.support.MessageHeaderAccessor
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler
import org.springframework.web.socket.CloseStatus
import org.springframework.web.socket.WebSocketSession
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker
import org.springframework.web.socket.config.annotation.StompEndpointRegistry
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer
import org.springframework.web.socket.config.annotation.WebSocketTransportRegistration
import org.springframework.web.socket.handler.WebSocketHandlerDecorator
import org.springframework.web.socket.handler.WebSocketHandlerDecoratorFactory

// Not proxyBeanMethods = false: configureMessageBroker() calls chatHeartbeatScheduler() and relies on the
// configuration class proxy to get the singleton bean (kotlin-spring keeps this class and its members open).
@Configuration
@ConditionalOnProperty(name = ["pawbridge.chat.enabled"], havingValue = "true")
@EnableWebSocketMessageBroker
class MemberChatSocketConfiguration(
    private val tickets: MemberChatTickets,
    private val sessions: ObjectProvider<MemberChatSessions>,
    private val service: ObjectProvider<MemberChat>,
    @Value("\${pawbridge.chat.allowed-origins}") origins: String,
) : WebSocketMessageBrokerConfigurer {
    private val origins: Array<String> = javaSplit(origins, ',').map { javaStrip(it) }.toTypedArray()

    init {
        if (this.origins.isEmpty() || this.origins.any { origin ->
                !Regex("https?://[a-zA-Z0-9.:-]+").matches(origin) || origin.contains("*")
            }
        ) {
            throw IllegalArgumentException("Explicit chat origins required")
        }
    }

    override fun registerStompEndpoints(registry: StompEndpointRegistry) {
        registry.setErrorHandler(MemberChatProtocolErrors())
        registry.setPreserveReceiveOrder(true)
        registry.addEndpoint("/api/v1/chats/socket").setAllowedOrigins(*origins)
    }

    override fun configureMessageBroker(registry: MessageBrokerRegistry) {
        registry.setApplicationDestinationPrefixes("/app")
        registry.setUserDestinationPrefix("/user")
        registry.setPreservePublishOrder(true)
        registry.enableSimpleBroker("/queue")
            .setTaskScheduler(chatHeartbeatScheduler())
            .setHeartbeatValue(longArrayOf(15000, 15000))
    }

    @Bean
    fun chatHeartbeatScheduler(): ThreadPoolTaskScheduler {
        val scheduler = ThreadPoolTaskScheduler()
        scheduler.poolSize = 1
        scheduler.setThreadNamePrefix("chat-heartbeat-")
        scheduler.setRemoveOnCancelPolicy(true)
        return scheduler
    }

    override fun configureWebSocketTransport(registry: WebSocketTransportRegistration) {
        registry.setMessageSizeLimit(16 * 1024).setSendBufferSizeLimit(64 * 1024).setSendTimeLimit(10_000)
        registry.addDecoratorFactory(
            WebSocketHandlerDecoratorFactory { handler ->
                object : WebSocketHandlerDecorator(handler) {
                    @Throws(Exception::class)
                    override fun afterConnectionEstablished(session: WebSocketSession) {
                        if (!sessions.getObject().open(session)) {
                            session.close(CloseStatus.POLICY_VIOLATION)
                            return
                        }
                        try {
                            super.afterConnectionEstablished(session)
                        } catch (failure: Exception) {
                            sessions.getObject().removed(session.id)
                            throw failure
                        }
                    }

                    @Throws(Exception::class)
                    override fun afterConnectionClosed(session: WebSocketSession, closeStatus: CloseStatus) {
                        try {
                            super.afterConnectionClosed(session, closeStatus)
                        } finally {
                            sessions.getObject().removed(session.id)
                        }
                    }
                }
            },
        )
    }

    override fun configureClientInboundChannel(registration: ChannelRegistration) {
        registration.taskExecutor().corePoolSize(2).maxPoolSize(4).queueCapacity(100)
        registration.interceptors(
            object : ChannelInterceptor {
                override fun preSend(message: Message<*>, channel: MessageChannel): Message<*>? {
                    val headers = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor::class.java)
                    try {
                        return authorized(message, headers)
                    } catch (denied: RuntimeException) {
                        // Ordered receive catches thrown errors and can log the entire private frame.
                        // Close explicitly and drop the frame; do not pass the exception or payload onward.
                        val sessionId = headers?.sessionId
                        if (headers != null && sessionId != null) {
                            try {
                                sessions.getObject().close(sessionId)
                            } catch (unavailable: RuntimeException) {
                                // Lease expiry is the bounded fallback.
                            }
                        }
                        return null
                    }
                }

                private fun authorized(message: Message<*>, headers: StompHeaderAccessor?): Message<*> {
                    if (headers == null) {
                        throw IllegalArgumentException("STOMP required")
                    }
                    val command = headers.command
                    if (command == StompCommand.DISCONNECT) {
                        return message
                    }
                    if (command == StompCommand.CONNECT) {
                        val identity = tickets.consume(headers.getFirstNativeHeader("ticket"))
                        service.getObject().active(identity.memberId)
                        sessions.getObject().authenticate(headers.sessionId!!, identity)
                        headers.user = identity
                        return message
                    }
                    sessions.getObject().requireCurrent(headers.sessionId!!)
                    if (command == StompCommand.SUBSCRIBE && "/user/queue/chat" != headers.destination) {
                        throw IllegalArgumentException("Only own chat queue is permitted")
                    }
                    if (command == StompCommand.SUBSCRIBE) {
                        if (headers.subscriptionId == null || headers.subscriptionId!!.length > 64) {
                            throw IllegalArgumentException("Invalid subscription")
                        }
                        synchronized(headers.sessionAttributes!!) {
                            if (headers.sessionAttributes!!.putIfAbsent("chatSubscription", headers.subscriptionId!!) != null) {
                                throw IllegalArgumentException("Only one chat subscription per connection is permitted")
                            }
                        }
                    }
                    if (command == StompCommand.UNSUBSCRIBE) {
                        synchronized(headers.sessionAttributes!!) {
                            headers.sessionAttributes!!.remove("chatSubscription", headers.subscriptionId)
                        }
                    }
                    if (command == StompCommand.SEND && "/app/chat/send" != headers.destination) {
                        throw IllegalArgumentException("Chat destination not permitted")
                    }
                    if (command != null && command != StompCommand.SEND && command != StompCommand.SUBSCRIBE &&
                        command != StompCommand.UNSUBSCRIBE
                    ) {
                        throw IllegalArgumentException("Command not permitted")
                    }
                    return message
                }
            },
        )
    }

    override fun configureClientOutboundChannel(registration: ChannelRegistration) {
        registration.taskExecutor().corePoolSize(2).maxPoolSize(4).queueCapacity(100)
        registration.interceptors(
            object : ChannelInterceptor {
                override fun preSend(message: Message<*>, channel: MessageChannel): Message<*>? {
                    val headers = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor::class.java)
                    if (headers != null && headers.command == StompCommand.MESSAGE) {
                        try {
                            sessions.getObject().requireCurrent(headers.sessionId!!)
                        } catch (expired: RuntimeException) {
                            return null
                        }
                    }
                    return message
                }
            },
        )
    }

    @Bean
    fun chatRedisListener(
        factory: RedisConnectionFactory,
        signals: MemberChatSignals,
        chatSignalExecutor: ThreadPoolTaskExecutor,
    ): RedisMessageListenerContainer {
        val listener = RedisMessageListenerContainer()
        listener.setConnectionFactory(factory)
        listener.setTaskExecutor(chatSignalExecutor)
        listener.addMessageListener(signals, ChannelTopic(signals.channel()))
        return listener
    }

    @Bean
    fun chatSignalExecutor(): ThreadPoolTaskExecutor {
        val executor = ThreadPoolTaskExecutor()
        executor.corePoolSize = 2
        executor.maxPoolSize = 2
        executor.setQueueCapacity(100)
        executor.setThreadNamePrefix("chat-signal-")
        return executor
    }
}
