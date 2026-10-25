package com.pawbridge.backend.animal.internal.chatbot.infrastructure.entity

import com.pawbridge.backend.animal.internal.command.infrastructure.entity.Animal
import com.pawbridge.backend.animal.internal.command.infrastructure.entity.BaseTimeEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table

@Entity
@Table(name = "chatbot_block_logs")
class ChatbotBlockLog protected constructor() : BaseTimeEntity() {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
        protected set

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "animal_id", nullable = false)
    var animal: Animal? = null
        protected set

    @Column(nullable = false, length = 36)
    var anonymousSessionId: String? = null
        protected set

    @Column(nullable = false, length = 64)
    var ipHash: String? = null
        protected set

    @Column(nullable = false, length = 50)
    var category: String? = null
        protected set

    @Column(nullable = false, length = 100)
    var reason: String? = null
        protected set

    @Column(nullable = false)
    var questionLength: Int? = null
        protected set

    @Column(length = 200)
    var questionPreview: String? = null
        protected set

    constructor(
        animal: Animal?,
        anonymousSessionId: String?,
        ipHash: String?,
        category: String?,
        reason: String?,
        questionLength: Int,
        questionPreview: String?,
    ) : this() {
        this.animal = animal
        this.anonymousSessionId = anonymousSessionId
        this.ipHash = ipHash
        this.category = category
        this.reason = reason
        this.questionLength = questionLength
        this.questionPreview = questionPreview
    }
}
