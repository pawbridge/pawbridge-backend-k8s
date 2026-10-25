package com.pawbridge.backend.community.internal.visit.application

class VisitCollectionRejectedException(private val reason: Reason) : RuntimeException(
    if (reason == Reason.COOKIE) "방문 쿠키 초기화가 필요합니다." else "오늘까지 최대 366일을 조회할 수 있습니다.",
) {

    enum class Reason { COOKIE, RANGE }

    fun reason(): Reason = reason
}
