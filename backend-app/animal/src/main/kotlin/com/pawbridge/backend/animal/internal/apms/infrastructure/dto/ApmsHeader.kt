package com.pawbridge.backend.animal.internal.apms.infrastructure.dto

import java.util.Objects

/**
 * APMS API 응답 헤더
 */
class ApmsHeader() {

    /**
     * 요청번호
     */
    var reqNo: String? = null

    /**
     * 결과코드
     * - 00: 정상
     * - 기타: 오류
     */
    var resultCode: String? = null

    /**
     * 결과메시지
     */
    var resultMsg: String? = null

    /**
     * 에러메시지
     */
    var errorMsg: String? = null

    constructor(
        reqNo: String?,
        resultCode: String?,
        resultMsg: String?,
        errorMsg: String?,
    ) : this() {
        this.reqNo = reqNo
        this.resultCode = resultCode
        this.resultMsg = resultMsg
        this.errorMsg = errorMsg
    }

    // Lombok @Data의 값 기반 equals/hashCode/toString
    override fun equals(other: Any?): Boolean {
        if (this === other) {
            return true
        }
        if (other !is ApmsHeader) {
            return false
        }
        return reqNo == other.reqNo &&
            resultCode == other.resultCode &&
            resultMsg == other.resultMsg &&
            errorMsg == other.errorMsg
    }

    override fun hashCode(): Int = Objects.hash(reqNo, resultCode, resultMsg, errorMsg)

    override fun toString(): String {
        return "ApmsHeader(reqNo=$reqNo, resultCode=$resultCode, resultMsg=$resultMsg, errorMsg=$errorMsg)"
    }

    class ApmsHeaderBuilder internal constructor() {
        private var reqNo: String? = null
        private var resultCode: String? = null
        private var resultMsg: String? = null
        private var errorMsg: String? = null

        fun reqNo(reqNo: String?): ApmsHeaderBuilder = apply {
            this.reqNo = reqNo
        }

        fun resultCode(resultCode: String?): ApmsHeaderBuilder = apply {
            this.resultCode = resultCode
        }

        fun resultMsg(resultMsg: String?): ApmsHeaderBuilder = apply {
            this.resultMsg = resultMsg
        }

        fun errorMsg(errorMsg: String?): ApmsHeaderBuilder = apply {
            this.errorMsg = errorMsg
        }

        fun build(): ApmsHeader = ApmsHeader(reqNo, resultCode, resultMsg, errorMsg)
    }

    companion object {
        @JvmStatic
        fun builder(): ApmsHeaderBuilder = ApmsHeaderBuilder()
    }
}
