package com.pawbridge.backend.store.internal.catalogwrite.web

import com.pawbridge.backend.store.internal.catalogwrite.infrastructure.JpaCatalogOptions
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/** GET remains owned by StoreCatalogController; only original option write handlers are added. */
@RestController
@RequestMapping("/api/v1/option-groups")
class OptionManagementController(private val options: JpaCatalogOptions) {

    @PostMapping
    fun createOptionGroup(@RequestBody request: OptionGroupRequest): ResponseEntity<OptionGroupResponse> =
        ResponseEntity.status(201).body(options.createOptionGroup(request))

    @PutMapping("/{groupId}")
    fun updateOptionGroup(
        @PathVariable("groupId") id: Long?,
        @RequestBody request: OptionGroupRequest,
    ): ResponseEntity<OptionGroupResponse> = ResponseEntity.ok(options.updateOptionGroup(id, request))

    @DeleteMapping("/{groupId}")
    fun deleteOptionGroup(@PathVariable("groupId") id: Long?): ResponseEntity<Void> {
        options.deleteOptionGroup(id)
        return ResponseEntity.noContent().build()
    }

    @PostMapping("/{groupId}/values")
    fun addOptionValue(
        @PathVariable("groupId") id: Long?,
        @RequestBody request: OptionValueRequest,
    ): ResponseEntity<OptionValueResponse> = ResponseEntity.status(201).body(options.addOptionValue(id, request))

    @PutMapping("/values/{valueId}")
    fun updateOptionValue(
        @PathVariable("valueId") id: Long?,
        @RequestBody request: OptionValueRequest,
    ): ResponseEntity<OptionValueResponse> = ResponseEntity.ok(options.updateOptionValue(id, request))

    @DeleteMapping("/values/{valueId}")
    fun deleteOptionValue(@PathVariable("valueId") id: Long?): ResponseEntity<Void> {
        options.deleteOptionValue(id)
        return ResponseEntity.noContent().build()
    }
}
