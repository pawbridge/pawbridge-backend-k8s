package com.pawbridge.backend.animal.internal.shelter.web

import com.pawbridge.backend.animal.internal.shelter.application.ShelterReads
import com.pawbridge.backend.animal.internal.shelter.application.ShelterReads.Discovery
import com.pawbridge.backend.animal.internal.shelter.application.ShelterReads.Observation
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.PageRequest
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import java.time.LocalDate
import java.time.ZoneId

/** Parent registers this PostgreSQL-only controller with the selected Animal read backend. */
@RestController
class ShelterDiscoveryReadController(
    private val shelters: ShelterReads,
) {

    @ExceptionHandler(MethodArgumentTypeMismatchException::class)
    fun invalidParameter(): ResponseEntity<Void> = ResponseEntity.badRequest().build()

    @GetMapping("/api/v1/shelters/discovery")
    fun discover(
        @RequestParam(name = "keyword", defaultValue = "") keyword: String,
        @RequestParam(name = "address", defaultValue = "") address: String,
        @RequestParam(name = "intakeFrom", required = false)
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
        from: LocalDate?,
        @RequestParam(name = "intakeTo", required = false)
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
        to: LocalDate?,
        @RequestParam(name = "page", defaultValue = "0") page: Int,
        @RequestParam(name = "size", defaultValue = "12") size: Int,
    ): Page<Discovery> {
        var intakeFrom = from
        var intakeTo = to
        if (intakeFrom == null && intakeTo == null) {
            intakeTo = LocalDate.now(ZoneId.of("Asia/Seoul"))
            intakeFrom = intakeTo.minusDays(29)
        }
        val result = shelters.discover(keyword, address, intakeFrom, intakeTo, page, size)
        return PageImpl(result.content(), PageRequest.of(page, size), result.totalElements())
    }

    @GetMapping("/api/v1/shelters/{id}/observations")
    fun observations(
        @PathVariable("id") id: Long,
        @RequestParam(name = "from") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) from: LocalDate,
        @RequestParam(name = "to") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) to: LocalDate,
    ): List<Observation> {
        return shelters.observations(id, from, to)
    }
}
