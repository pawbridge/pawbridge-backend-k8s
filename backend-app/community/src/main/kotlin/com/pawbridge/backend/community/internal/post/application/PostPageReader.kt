package com.pawbridge.backend.community.internal.post.application

interface PostPageReader {
    fun findPage(request: Request): Slice

    @JvmRecord
    data class Order(val property: String, val ascending: Boolean, val ignoreCase: Boolean)

    // A Java record with a compact constructor that copies `orders` (List.copyOf: immutable, rejects nulls).
    // @JvmRecord cannot transform a constructor argument, so this is a plain class that keeps the record-style
    // accessor names, equals, hashCode and toString.
    class Request(
        private val number: Int,
        private val size: Int,
        orders: List<Order>,
    ) {
        private val orders: List<Order> = java.util.List.copyOf(orders)

        fun number(): Int = number

        fun size(): Int = size

        fun orders(): List<Order> = orders

        fun offset(): Long = number.toLong() * size

        override fun equals(other: Any?): Boolean =
            other is Request && number == other.number && size == other.size && orders == other.orders

        override fun hashCode(): Int {
            var result = number
            result = 31 * result + size
            result = 31 * result + orders.hashCode()
            return result
        }

        override fun toString(): String = "Request[number=$number, size=$size, orders=$orders]"
    }

    // Same reason as Request: the Java compact constructor copies `content` with List.copyOf.
    class Slice(
        content: List<PostSnapshot>,
        private val total: Long,
    ) {
        private val content: List<PostSnapshot> = java.util.List.copyOf(content)

        fun content(): List<PostSnapshot> = content

        fun total(): Long = total

        override fun equals(other: Any?): Boolean =
            other is Slice && content == other.content && total == other.total

        override fun hashCode(): Int {
            var result = content.hashCode()
            result = 31 * result + total.hashCode()
            return result
        }

        override fun toString(): String = "Slice[content=$content, total=$total]"
    }
}
