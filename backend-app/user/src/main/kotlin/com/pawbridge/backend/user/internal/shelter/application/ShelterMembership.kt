package com.pawbridge.backend.user.internal.shelter.application

import java.time.LocalDate
import java.time.LocalDateTime

interface ShelterMembership {

    enum class Status { PENDING, APPROVED, REJECTED }

    @JvmRecord
    data class Application(
        val id: Long?,
        val userId: Long?,
        val shelterName: String?,
        val status: Status?,
        val requestedAt: LocalDateTime?,
        val reviewedAt: LocalDateTime?,
        val reviewedBy: Long?,
        val reason: String?,
        val careRegNo: String?,
    )

    @JvmRecord
    data class AdminApplication(
        val application: Application?,
        val applicantName: String?,
        val applicantEmail: String?,
        val reviewNote: String?,
    )

    @JvmRecord
    data class Member(
        val userId: Long?,
        val name: String?,
        val email: String?,
        val approvalApplicationId: Long?,
    )

    @JvmRecord
    data class Daily(
        val date: LocalDate?,
        val count: Long,
    )

    @JvmRecord
    data class Statistics(
        val startDate: LocalDate?,
        val endDate: LocalDate?,
        val daily: List<Daily>?,
        val previousDayCount: Long,
        val currentPending: Long,
        val approvedCount: Long,
        val rejectedCount: Long,
    )

    @JvmRecord
    data class Page<T>(
        val content: List<T>,
        val total: Long,
        val number: Int,
        val size: Int,
    )

    @JvmRecord
    data class Shelter(
        val id: Long?,
        val careRegNo: String?,
    )

    fun interface ShelterLookup {
        fun find(careRegNo: String?): Shelter?
    }

    class MissingShelter : RuntimeException()

    fun submit(currentUserId: Long, shelterName: String?): Application

    fun mine(currentUserId: Long, page: Int, size: Int): Page<Application>

    fun list(currentUserId: Long, status: Status?, page: Int, size: Int): Page<AdminApplication>

    fun detail(currentUserId: Long, id: Long): AdminApplication

    fun approve(currentUserId: Long, id: Long, careRegNo: String?, note: String?): AdminApplication

    fun reject(currentUserId: Long, id: Long, reason: String?): AdminApplication

    fun members(currentUserId: Long, careRegNo: String?, page: Int, size: Int): Page<Member>

    fun statistics(currentUserId: Long, startDate: LocalDate?, endDate: LocalDate?): Statistics
}
