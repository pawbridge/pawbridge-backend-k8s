package com.pawbridge.backend.animal.internal.apms.infrastructure.dto

import java.util.Objects

/**
 * APMS API 응답 바디
 *
 * @param T item 타입 (ApmsAnimal 등)
 */
class ApmsBody<T>() {

    /**
     * item 배열
     */
    var items: ApmsItems<T>? = null

    /**
     * 한 페이지당 결과 수
     */
    var numOfRows: String? = null

    /**
     * 페이지 번호
     */
    var pageNo: String? = null

    /**
     * 전체 결과 수
     */
    var totalCount: String? = null

    constructor(
        items: ApmsItems<T>?,
        numOfRows: String?,
        pageNo: String?,
        totalCount: String?,
    ) : this() {
        this.items = items
        this.numOfRows = numOfRows
        this.pageNo = pageNo
        this.totalCount = totalCount
    }

    // Lombok @Data의 값 기반 equals/hashCode/toString
    override fun equals(other: Any?): Boolean {
        if (this === other) {
            return true
        }
        if (other !is ApmsBody<*>) {
            return false
        }
        return items == other.items &&
            numOfRows == other.numOfRows &&
            pageNo == other.pageNo &&
            totalCount == other.totalCount
    }

    override fun hashCode(): Int = Objects.hash(items, numOfRows, pageNo, totalCount)

    override fun toString(): String {
        return "ApmsBody(items=$items, numOfRows=$numOfRows, pageNo=$pageNo, totalCount=$totalCount)"
    }

    class ApmsBodyBuilder<T> internal constructor() {
        private var items: ApmsItems<T>? = null
        private var numOfRows: String? = null
        private var pageNo: String? = null
        private var totalCount: String? = null

        fun items(items: ApmsItems<T>?): ApmsBodyBuilder<T> = apply {
            this.items = items
        }

        fun numOfRows(numOfRows: String?): ApmsBodyBuilder<T> = apply {
            this.numOfRows = numOfRows
        }

        fun pageNo(pageNo: String?): ApmsBodyBuilder<T> = apply {
            this.pageNo = pageNo
        }

        fun totalCount(totalCount: String?): ApmsBodyBuilder<T> = apply {
            this.totalCount = totalCount
        }

        fun build(): ApmsBody<T> = ApmsBody(items, numOfRows, pageNo, totalCount)
    }

    companion object {
        @JvmStatic
        fun <T> builder(): ApmsBodyBuilder<T> = ApmsBodyBuilder()
    }
}
