package com.pawbridge.backend.animal.internal.apms.infrastructure.dto

/**
 * APMS API 최상위 응답 (response 필드 Wrapper)
 *
 * @param T item 타입 (ApmsAnimal 등)
 */
class ApmsRootResponse<T>() {

    /**
     * 실제 응답 데이터
     */
    var response: ApmsResponse<T>? = null

    constructor(response: ApmsResponse<T>?) : this() {
        this.response = response
    }

    // Lombok @Data의 값 기반 equals/hashCode/toString
    override fun equals(other: Any?): Boolean {
        if (this === other) {
            return true
        }
        if (other !is ApmsRootResponse<*>) {
            return false
        }
        return response == other.response
    }

    override fun hashCode(): Int = response.hashCode()

    override fun toString(): String = "ApmsRootResponse(response=$response)"

    class ApmsRootResponseBuilder<T> internal constructor() {
        private var response: ApmsResponse<T>? = null

        fun response(response: ApmsResponse<T>?): ApmsRootResponseBuilder<T> = apply {
            this.response = response
        }

        fun build(): ApmsRootResponse<T> = ApmsRootResponse(response)
    }

    companion object {
        @JvmStatic
        fun <T> builder(): ApmsRootResponseBuilder<T> = ApmsRootResponseBuilder()
    }
}
