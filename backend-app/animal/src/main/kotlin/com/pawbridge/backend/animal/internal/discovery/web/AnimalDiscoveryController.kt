@file:Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")

package com.pawbridge.backend.animal.internal.discovery.web

import com.pawbridge.backend.animal.internal.discovery.application.AnimalDiscovery
import com.pawbridge.backend.animal.internal.discovery.application.AnimalDiscovery.Order
import com.pawbridge.backend.animal.internal.discovery.application.AnimalDiscovery.PageRequest
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Sort
import org.springframework.data.web.PageableDefault
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.ModelAttribute
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RestController

@RestController
class AnimalDiscoveryController(
    private val animals: AnimalDiscovery,
) {

    @GetMapping("/api/v1/animals")
    fun search(
        @ModelAttribute parameters: AnimalSearchParameters,
        @PageableDefault(size = 20, sort = ["createdAt"], direction = Sort.Direction.DESC) pageable: Pageable,
    ): AnimalPage {
        val result = animals.search(parameters.search(), pageRequest(pageable))
        return animalPage(result)
    }

    // The path variable is java.lang.Long deliberately: kotlin.Long? makes it optional (a blank segment reaches the
    // service as null) and kotlin.Long is a primitive (a blank segment becomes a type mismatch). A non-null
    // java.lang.Long keeps the Java behaviour: required and boxed, so a blank segment fails before this method runs.
    @GetMapping("/api/v1/animals/{id}")
    fun detail(@PathVariable("id") id: java.lang.Long): AnimalDetails {
        return AnimalDetails.from(animals.findById(id.toLong()))
    }

    @GetMapping("/api/v1/animals/apms/{apmsDesertionNo}")
    fun getAnimalByApmsNo(@PathVariable("apmsDesertionNo") number: String): AnimalDetails {
        return AnimalDetails.from(animals.findByApmsDesertionNo(number))
    }

    @GetMapping("/api/v1/animals/expiring-soon")
    fun listExpiringSoon(
        @PageableDefault(size = 20, sort = ["noticeEndDate"], direction = Sort.Direction.ASC) pageable: Pageable,
    ): AnimalPage {
        val result = animals.findExpiringSoonAnimals(pageRequest(pageable))
        return animalPage(result)
    }

    private fun pageRequest(pageable: Pageable): PageRequest {
        return PageRequest(
            pageable.pageNumber,
            pageable.pageSize,
            pageable.sort.stream().map { order -> Order(order.property, order.isAscending) }.toList(),
            pageable.isPaged,
        )
    }

    private fun animalPage(result: AnimalDiscovery.Page): AnimalPage {
        return AnimalPage(
            result.content().stream().map { summary -> AnimalSummary.from(summary) }.toList(),
            result.number(),
            result.size(),
            result.totalElements(),
            result.totalPages(),
            result.first(),
            result.last(),
        )
    }

    @JvmRecord
    data class AnimalPage(
        val content: List<AnimalSummary>?,
        val number: Int,
        val size: Int,
        val totalElements: Long,
        val totalPages: Int,
        val first: Boolean,
        val last: Boolean,
    )
}
