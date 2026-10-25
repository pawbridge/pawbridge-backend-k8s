package com.pawbridge.backend.animal.internal.apms.infrastructure.dto

import java.util.Objects

/**
 * APMS API 응답 (response 필드 내부)
 *
 * @param T item 타입 (ApmsAnimal 등)
 */
class ApmsResponse<T>() {

    /**
     * 헤더
     */
    var header: ApmsHeader? = null

    /**
     * 바디
     */
    var body: ApmsBody<T>? = null

    constructor(header: ApmsHeader?, body: ApmsBody<T>?) : this() {
        this.header = header
        this.body = body
    }

    // Lombok @Data의 값 기반 equals/hashCode/toString
    override fun equals(other: Any?): Boolean {
        if (this === other) {
            return true
        }
        if (other !is ApmsResponse<*>) {
            return false
        }
        return header == other.header && body == other.body
    }

    override fun hashCode(): Int = Objects.hash(header, body)

    override fun toString(): String = "ApmsResponse(header=$header, body=$body)"

    class ApmsResponseBuilder<T> internal constructor() {
        private var header: ApmsHeader? = null
        private var body: ApmsBody<T>? = null

        fun header(header: ApmsHeader?): ApmsResponseBuilder<T> = apply {
            this.header = header
        }

        fun body(body: ApmsBody<T>?): ApmsResponseBuilder<T> = apply {
            this.body = body
        }

        fun build(): ApmsResponse<T> = ApmsResponse(header, body)
    }

    companion object {
        @JvmStatic
        fun <T> builder(): ApmsResponseBuilder<T> = ApmsResponseBuilder()
    }
}
