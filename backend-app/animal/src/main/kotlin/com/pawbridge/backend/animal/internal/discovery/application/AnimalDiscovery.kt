package com.pawbridge.backend.animal.internal.discovery.application

import java.time.LocalDate
import java.time.LocalDateTime

/** Public animal discovery only; collection, projection and writes keep their existing owner. */
interface AnimalDiscovery {
    fun search(request: Search, page: PageRequest): Page

    fun findById(id: Long): Details

    fun findByApmsDesertionNo(number: String?): Details

    fun findExpiringSoonAnimals(page: PageRequest): Page

    enum class Species { DOG, CAT, ETC }

    enum class Gender { MALE, FEMALE, UNKNOWN }

    enum class NeuterStatus { YES, NO, UNKNOWN }

    enum class AnimalStatus {
        NOTICE,
        PROTECT,
        ADOPTION_PENDING,
        ADOPTED,
        EUTHANIZED,
        NATURAL_DEATH,
        RETURNED,
        DONATED,
        RELEASED,
        ESCAPED,
        UNKNOWN,
    }

    enum class ApiSource { APMS_ANIMAL, GYEONGGI, MANUAL, UNKNOWN }

    @JvmRecord
    data class Search(
        val species: Species?,
        val breed: String?,
        val gender: Gender?,
        val neuterStatus: NeuterStatus?,
        val status: AnimalStatus?,
        val minAge: Int?,
        val maxAge: Int?,
        val region: String?,
        val city: String?,
        val keyword: String?,
        val noticeNo: String?,
        val shelterId: Long?,
        val intakeFrom: LocalDate?,
        val intakeTo: LocalDate?,
    ) {
        companion object {
            @JvmStatic
            fun empty(): Search = Search(null, null, null, null, null, null, null, null, null, null, null, null, null, null)
        }
    }

    @JvmRecord
    data class Order(
        val property: String?,
        val ascending: Boolean,
    )

    // A Java record with a compact constructor that copies `orders` (List.copyOf: immutable, rejects nulls).
    // @JvmRecord cannot transform a constructor argument, so this is a plain class that keeps the record-style
    // accessor names, equals, hashCode and toString.
    // Not Jackson-serializable: this class has no getters (serializing yields `{}`), so do not return it
    // directly from a controller; map it to a response type first.
    class PageRequest(
        private val number: Int,
        private val size: Int,
        orders: List<Order>,
        private val paged: Boolean,
    ) {
        private val orders: List<Order> = java.util.List.copyOf(orders)

        fun number(): Int = number

        fun size(): Int = size

        fun orders(): List<Order> = orders

        fun paged(): Boolean = paged

        fun offset(): Long = number.toLong() * size

        override fun equals(other: Any?): Boolean =
            other is PageRequest &&
                number == other.number &&
                size == other.size &&
                orders == other.orders &&
                paged == other.paged

        override fun hashCode(): Int {
            var result = number
            result = 31 * result + size
            result = 31 * result + orders.hashCode()
            result = 31 * result + paged.hashCode()
            return result
        }

        override fun toString(): String = "PageRequest[number=$number, size=$size, orders=$orders, paged=$paged]"
    }

    // Same reason as PageRequest: the Java compact constructor copies `content` with List.copyOf.
    // Not Jackson-serializable: this class has no getters (serializing yields `{}`), so do not return it
    // directly from a controller; map it to a response type first.
    class Page(
        content: List<Summary>,
        private val number: Int,
        private val size: Int,
        private val totalElements: Long,
        private val totalPages: Int,
        private val first: Boolean,
        private val last: Boolean,
    ) {
        private val content: List<Summary> = java.util.List.copyOf(content)

        fun content(): List<Summary> = content

        fun number(): Int = number

        fun size(): Int = size

        fun totalElements(): Long = totalElements

        fun totalPages(): Int = totalPages

        fun first(): Boolean = first

        fun last(): Boolean = last

        override fun equals(other: Any?): Boolean =
            other is Page &&
                content == other.content &&
                number == other.number &&
                size == other.size &&
                totalElements == other.totalElements &&
                totalPages == other.totalPages &&
                first == other.first &&
                last == other.last

        override fun hashCode(): Int {
            var result = content.hashCode()
            result = 31 * result + number
            result = 31 * result + size
            result = 31 * result + totalElements.hashCode()
            result = 31 * result + totalPages
            result = 31 * result + first.hashCode()
            result = 31 * result + last.hashCode()
            return result
        }

        override fun toString(): String =
            "Page[content=$content, number=$number, size=$size, totalElements=$totalElements, " +
                "totalPages=$totalPages, first=$first, last=$last]"

        companion object {
            @JvmStatic
            fun from(content: List<Summary>, request: PageRequest, total: Long): Page {
                // Same last-page total adaptation as the source PageImpl.
                val adjusted = if (content.isNotEmpty() && request.offset() + request.size() > total) {
                    request.offset() + content.size
                } else {
                    total
                }
                val pages = Math.ceil(adjusted.toDouble() / request.size()).toInt()
                return Page(
                    content,
                    request.number(),
                    request.size(),
                    adjusted,
                    pages,
                    request.number() == 0,
                    request.number() >= pages - 1,
                )
            }
        }
    }

    @JvmRecord
    data class Summary(
        val id: Long?,
        val apmsNoticeNo: String?,
        val species: Species?,
        val breed: String?,
        val gender: Gender?,
        val birthYear: Int?,
        val age: Int?,
        val specialMark: String?,
        val status: AnimalStatus?,
        val noticeEndDate: LocalDate?,
        val imageUrl: String?,
        val favoriteCount: Int?,
        val shelterId: Long?,
        val shelterName: String?,
        val createdAt: LocalDateTime?,
    )

    @JvmRecord
    data class Details(
        val id: Long?,
        val apmsNoticeNo: String?,
        val species: Species?,
        val breed: String?,
        val gender: Gender?,
        val neuterStatus: NeuterStatus?,
        val birthYear: Int?,
        val age: Int?,
        val weight: String?,
        val color: String?,
        val specialMark: String?,
        val status: AnimalStatus?,
        val noticeStartDate: LocalDate?,
        val noticeEndDate: LocalDate?,
        val happenPlace: String?,
        val happenDate: LocalDate?,
        val imageUrl: String?,
        val imageUrl2: String?,
        val description: String?,
        val favoriteCount: Int?,
        val shelterId: Long?,
        val shelterName: String?,
        val apmsDesertionNo: String?,
        val apmsProcessState: String?,
        val apmsUpdatedAt: LocalDateTime?,
        val apiSource: ApiSource?,
        val createdAt: LocalDateTime?,
        val updatedAt: LocalDateTime?,
    )
}
