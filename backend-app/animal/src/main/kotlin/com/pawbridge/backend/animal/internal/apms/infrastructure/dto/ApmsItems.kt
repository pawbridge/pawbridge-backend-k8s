package com.pawbridge.backend.animal.internal.apms.infrastructure.dto

/**
 * APMS API item 배열 래퍼
 *
 * @param T item 타입 (ApmsAnimal 등)
 */
class ApmsItems<T>() {

    /**
     * item 목록
     */
    // Java의 List<T>와 같은 시그니처를 유지하도록 와일드카드(? extends T)를 만들지 않는다.
    var item: List<@JvmSuppressWildcards T>? = null

    constructor(item: List<@JvmSuppressWildcards T>?) : this() {
        this.item = item
    }

    // Lombok @Data의 값 기반 equals/hashCode/toString
    override fun equals(other: Any?): Boolean {
        if (this === other) {
            return true
        }
        if (other !is ApmsItems<*>) {
            return false
        }
        return item == other.item
    }

    override fun hashCode(): Int = item.hashCode()

    override fun toString(): String = "ApmsItems(item=$item)"

    class ApmsItemsBuilder<T> internal constructor() {
        private var item: List<@JvmSuppressWildcards T>? = null

        fun item(item: List<@JvmSuppressWildcards T>?): ApmsItemsBuilder<T> = apply {
            this.item = item
        }

        fun build(): ApmsItems<T> = ApmsItems(item)
    }

    companion object {
        @JvmStatic
        fun <T> builder(): ApmsItemsBuilder<T> = ApmsItemsBuilder()
    }
}
