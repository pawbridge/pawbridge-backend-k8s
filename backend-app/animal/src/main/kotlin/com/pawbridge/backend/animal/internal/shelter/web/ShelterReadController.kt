package com.pawbridge.backend.animal.internal.shelter.web

import com.pawbridge.backend.animal.internal.shelter.application.ShelterReads
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Sort
import org.springframework.data.web.PageableDefault
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
class ShelterReadController(
    private val shelters: ShelterReads,
) {

    @GetMapping("/api/v1/shelters")
    fun list(
        @RequestParam(name = "name", required = false) name: String?,
        @RequestParam(name = "address", required = false) address: String?,
        @RequestParam(name = "organizationName", required = false) organizationName: String?,
        @RequestParam(name = "keyword", required = false) keyword: String?,
        @PageableDefault(size = 20, sort = ["name"], direction = Sort.Direction.ASC) pageable: Pageable,
    ): Page<ShelterResponses.Summary> {
        val result = shelters.search(
            ShelterReads.Search(name, address, organizationName, keyword),
            ShelterReads.PageRequest(
                pageable.pageNumber,
                pageable.pageSize,
                pageable.sort.stream()
                    .map { order -> ShelterReads.Order(order.property, order.isAscending, order.isIgnoreCase) }
                    .toList(),
            ),
        )
        return PageImpl(
            result.content().map { summary -> ShelterResponses.Summary.from(summary) },
            pageable,
            result.totalElements(),
        )
    }

    @GetMapping("/api/v1/shelters/{id}")
    fun detail(@PathVariable("id") id: Long?): ShelterResponses.Details {
        return ShelterResponses.Details.from(shelters.findById(id!!))
    }

    @GetMapping("/api/v1/shelters/by-care-reg-no/{careRegNo}")
    fun byCareRegNo(@PathVariable("careRegNo") careRegNo: String): ShelterResponses.Details {
        return ShelterResponses.Details.from(shelters.findByCareRegNo(careRegNo)!!)
    }

    @GetMapping("/api/v1/shelters/count")
    fun count(): Long = shelters.count()
}
