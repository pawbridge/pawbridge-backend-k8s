package com.pawbridge.backend.animal.internal.discovery.web

import com.pawbridge.backend.animal.internal.discovery.application.AnimalDiscovery.AnimalStatus
import com.pawbridge.backend.animal.internal.discovery.application.AnimalDiscovery.Gender
import com.pawbridge.backend.animal.internal.discovery.application.AnimalDiscovery.NeuterStatus
import com.pawbridge.backend.animal.internal.discovery.application.AnimalDiscovery.Search
import com.pawbridge.backend.animal.internal.discovery.application.AnimalDiscovery.Species
import org.springframework.format.annotation.DateTimeFormat
import java.time.LocalDate

@JvmRecord
data class AnimalSearchParameters(
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
    @param:DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    @field:DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    @get:DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    val intakeFrom: LocalDate?,
    @param:DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    @field:DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    @get:DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    val intakeTo: LocalDate?,
) {
    internal fun search(): Search {
        return Search(
            species,
            breed,
            gender,
            neuterStatus,
            status,
            minAge,
            maxAge,
            region,
            city,
            keyword,
            noticeNo,
            shelterId,
            intakeFrom,
            intakeTo,
        )
    }
}
