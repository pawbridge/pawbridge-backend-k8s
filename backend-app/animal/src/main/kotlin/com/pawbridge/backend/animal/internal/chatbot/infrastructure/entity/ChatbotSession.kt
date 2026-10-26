package com.pawbridge.backend.animal.internal.chatbot.infrastructure.entity

import com.pawbridge.backend.animal.internal.command.infrastructure.entity.Animal
import com.pawbridge.backend.animal.internal.command.infrastructure.entity.BaseTimeEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.PrePersist
import jakarta.persistence.Table
import java.util.UUID

@Entity
@Table(name = "chatbot_sessions")
class ChatbotSession protected constructor() : BaseTimeEntity() {

    @Id
    @Column(length = 36)
    var id: String? = null
        protected set

    @Column(nullable = false, length = 36)
    var anonymousSessionId: String? = null
        protected set

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "animal_id", nullable = false)
    var animal: Animal? = null
        protected set

    constructor(anonymousSessionId: String?, animal: Animal?) : this() {
        this.anonymousSessionId = anonymousSessionId
        this.animal = animal
    }

    @PrePersist
    protected fun generateId() {
        if (id == null) {
            id = UUID.randomUUID().toString()
        }
    }
}
