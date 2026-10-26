package com.pawbridge.backend.community.internal.visit.application

import java.time.LocalDate

@JvmRecord
data class TodayVisits(val date: LocalDate, val count: Long)
