package com.pawbridge.backend.animal.internal.shelter.application

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime

/** Six read-only ShelterController/ShelterDiscoveryController contracts; no directory collector. */
interface ShelterReads {
    fun search(search: Search, page: PageRequest): Page<Summary>

    fun findById(id: Long): Details

    // Nullable: a test double (and the module adapter's existing null check) can return null.
    fun findByCareRegNo(careRegNo: String?): Details?

    fun count(): Long

    fun discover(
        keyword: String?,
        address: String?,
        from: LocalDate?,
        to: LocalDate?,
        page: Int,
        size: Int,
    ): Page<Discovery>

    fun observations(shelterId: Long, from: LocalDate?, to: LocalDate?): List<Observation>

    @JvmRecord
    data class Search(
        val name: String?,
        val address: String?,
        val organizationName: String?,
        val keyword: String?,
    )

    @JvmRecord
    data class Order(
        val property: String?,
        val ascending: Boolean,
        val ignoreCase: Boolean,
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
    ) {
        private val orders: List<Order> = java.util.List.copyOf(orders)

        fun number(): Int = number

        fun size(): Int = size

        fun orders(): List<Order> = orders

        fun offset(): Long = number.toLong() * size

        override fun equals(other: Any?): Boolean =
            other is PageRequest && number == other.number && size == other.size && orders == other.orders

        override fun hashCode(): Int {
            var result = number
            result = 31 * result + size
            result = 31 * result + orders.hashCode()
            return result
        }

        override fun toString(): String = "PageRequest[number=$number, size=$size, orders=$orders]"
    }

    // Same reason as PageRequest: the Java compact constructor copies `content` with List.copyOf.
    // Not Jackson-serializable: this class has no getters (serializing yields `{}`), so do not return it
    // directly from a controller; map it to a response type first.
    class Page<T>(
        content: List<@JvmSuppressWildcards T>,
        private val totalElements: Long,
    ) {
        private val content: List<T> = java.util.List.copyOf(content)

        fun content(): List<T> = content

        fun totalElements(): Long = totalElements

        override fun equals(other: Any?): Boolean =
            other is Page<*> && content == other.content && totalElements == other.totalElements

        override fun hashCode(): Int {
            var result = content.hashCode()
            result = 31 * result + totalElements.hashCode()
            return result
        }

        override fun toString(): String = "Page[content=$content, totalElements=$totalElements]"
    }

    @JvmRecord
    data class Summary(
        val id: Long?,
        val careRegNo: String?,
        val name: String?,
        val phone: String?,
        val address: String?,
        val organizationName: String?,
        val createdAt: LocalDateTime?,
    )

    @JvmRecord
    data class Details(
        val id: Long?,
        val careRegNo: String?,
        val name: String?,
        val phone: String?,
        val address: String?,
        val ownerName: String?,
        val organizationName: String?,
        val email: String?,
        val introduction: String?,
        val adoptionProcedure: String?,
        val operatingHours: String?,
        val createdAt: LocalDateTime?,
        val updatedAt: LocalDateTime?,
        val publicInformation: PublicInformation?,
    )

    @JvmRecord
    data class PublicInformation(
        val source: String?,
        val phone: String?,
        val latitude: Double?,
        val longitude: Double?,
        val weekdayOpen: String?,
        val weekdayClose: String?,
        val weekendOpen: String?,
        val weekendClose: String?,
        val closedDays: String?,
        val sourceUpdatedDate: String?,
        val collectedAt: LocalDateTime?,
    )

    @JvmRecord
    data class Discovery(
        val id: Long,
        val careRegNo: String?,
        val name: String?,
        val address: String?,
        val phone: String?,
        val protectedCount: Long,
        val animals: List<Preview>?,
    )

    @JvmRecord
    data class Preview(
        val id: Long,
        val breed: String?,
        val species: String?,
        val gender: String?,
        val birthYear: Int?,
        val imageUrl: String?,
        val happenDate: LocalDate?,
    )

    @JvmRecord
    data class Observation(
        val date: LocalDate?,
        val observedAt: OffsetDateTime?,
        val protectedCount: Long,
    )
}
