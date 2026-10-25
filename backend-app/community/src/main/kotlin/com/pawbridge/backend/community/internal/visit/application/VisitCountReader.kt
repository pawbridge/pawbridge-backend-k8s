package com.pawbridge.backend.community.internal.visit.application

import java.time.LocalDate

interface VisitCountReader {

    fun count(day: LocalDate): Long
}
