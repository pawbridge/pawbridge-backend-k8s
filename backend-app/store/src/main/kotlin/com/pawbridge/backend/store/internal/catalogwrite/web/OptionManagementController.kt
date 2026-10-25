@file:Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")

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

// Path ids use java.lang.Long deliberately: kotlin.Long? makes the variable optional (a blank segment reaches the
// service as null) and kotlin.Long is a primitive (a blank segment becomes a type mismatch). A non-null
// java.lang.Long keeps the Java behaviour: required and boxed, so a blank segment fails before the handler runs.

/** GET remains owned by StoreCatalogController; only original option write handlers are added. */
@RestController
@RequestMapping("/api/v1/option-groups")
class OptionManagementController(private val options: JpaCatalogOptions) {

    @PostMapping
    fun createOptionGroup(@RequestBody request: OptionGroupRequest): ResponseEntity<OptionGroupResponse> =
        ResponseEntity.status(201).body(options.createOptionGroup(request))

    @PutMapping("/{groupId}")
    fun updateOptionGroup(
        @PathVariable("groupId") id: java.lang.Long,
        @RequestBody request: OptionGroupRequest,
    ): ResponseEntity<OptionGroupResponse> = ResponseEntity.ok(options.updateOptionGroup(id.toLong(), request))

    @DeleteMapping("/{groupId}")
    fun deleteOptionGroup(@PathVariable("groupId") id: java.lang.Long): ResponseEntity<Void> {
        options.deleteOptionGroup(id.toLong())
        return ResponseEntity.noContent().build()
    }

    @PostMapping("/{groupId}/values")
    fun addOptionValue(
        @PathVariable("groupId") id: java.lang.Long,
        @RequestBody request: OptionValueRequest,
    ): ResponseEntity<OptionValueResponse> =
        ResponseEntity.status(201).body(options.addOptionValue(id.toLong(), request))

    @PutMapping("/values/{valueId}")
    fun updateOptionValue(
        @PathVariable("valueId") id: java.lang.Long,
        @RequestBody request: OptionValueRequest,
    ): ResponseEntity<OptionValueResponse> = ResponseEntity.ok(options.updateOptionValue(id.toLong(), request))

    @DeleteMapping("/values/{valueId}")
    fun deleteOptionValue(@PathVariable("valueId") id: java.lang.Long): ResponseEntity<Void> {
        options.deleteOptionValue(id.toLong())
        return ResponseEntity.noContent().build()
    }
}
