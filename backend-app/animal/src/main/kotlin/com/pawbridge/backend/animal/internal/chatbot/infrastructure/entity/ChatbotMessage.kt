package com.pawbridge.backend.animal.internal.chatbot.infrastructure.entity

import com.pawbridge.backend.animal.internal.command.infrastructure.entity.BaseTimeEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table

@Entity
@Table(name = "chatbot_messages")
class ChatbotMessage protected constructor() : BaseTimeEntity() {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
        protected set

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "session_id", nullable = false)
    var session: ChatbotSession? = null
        protected set

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var role: ChatbotMessageRole? = null
        protected set

    @Column(nullable = false, length = 1000)
    var content: String? = null
        protected set

    @Column(length = 50)
    var provider: String? = null
        protected set

    @Column(length = 500)
    var safetyNotice: String? = null
        protected set

    private constructor(
        session: ChatbotSession?,
        role: ChatbotMessageRole?,
        content: String?,
        provider: String?,
        safetyNotice: String?,
    ) : this() {
        this.session = session
        this.role = role
        this.content = content
        this.provider = provider
        this.safetyNotice = safetyNotice
    }

    companion object {
        @JvmStatic
        fun user(session: ChatbotSession?, content: String?): ChatbotMessage {
            return ChatbotMessage(session, ChatbotMessageRole.USER, content, null, null)
        }

        @JvmStatic
        fun assistant(
            session: ChatbotSession?,
            content: String?,
            provider: String?,
            safetyNotice: String?,
        ): ChatbotMessage {
            return ChatbotMessage(session, ChatbotMessageRole.ASSISTANT, content, provider, safetyNotice)
        }
    }
}
