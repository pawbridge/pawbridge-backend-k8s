package com.pawbridge.backend.animal.internal.travel.web

import com.pawbridge.backend.animal.internal.travel.application.PetTravel
import com.pawbridge.backend.animal.internal.travel.application.PetTravelException
import com.pawbridge.backend.animal.internal.travel.application.PetTravelResponse
import org.springframework.dao.DataAccessException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.transaction.CannotCreateTransactionException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException

@RestController
@RequestMapping("/api/v1/places")
class PetTravelController(
    private val service: PetTravel,
) {

    @GetMapping("/regions")
    fun regions(): PetTravelResponse.Regions {
        return service.regions()
    }

    @GetMapping
    fun places(
        @RequestParam(value = "areaCode", required = false) areaCode: String?,
        @RequestParam(value = "page", defaultValue = "0") page: Int,
    ): PetTravelResponse.Places {
        return service.places(areaCode, page)
    }

    @GetMapping("/{contentId}")
    fun detail(@PathVariable("contentId") contentId: String): PetTravelResponse.Detail {
        return service.detail(contentId)
    }

    @ExceptionHandler(PetTravelException::class)
    fun failure(exception: PetTravelException): ResponseEntity<Map<String, String>> {
        val status = when (exception.code) {
            PetTravelException.Code.INVALID_REQUEST -> HttpStatus.BAD_REQUEST
            PetTravelException.Code.NOT_FOUND -> HttpStatus.NOT_FOUND
            PetTravelException.Code.UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE
        }
        return ResponseEntity.status(status)
            .header("Cache-Control", "no-store")
            .body(mapOf("code" to "PET_TRAVEL_" + exception.code.name))
    }

    @ExceptionHandler(DataAccessException::class, CannotCreateTransactionException::class)
    fun databaseFailure(): ResponseEntity<Map<String, String>> {
        return failure(PetTravelException.unavailable())
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException::class)
    fun invalidParameter(): ResponseEntity<Map<String, String>> {
        return failure(PetTravelException(PetTravelException.Code.INVALID_REQUEST))
    }
}
