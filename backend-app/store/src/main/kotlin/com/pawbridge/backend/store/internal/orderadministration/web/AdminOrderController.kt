package com.pawbridge.backend.store.internal.orderadministration.web

import com.pawbridge.backend.store.internal.directorder.application.DirectOrders
import com.pawbridge.backend.store.internal.memberread.infrastructure.jpa.model.DeliveryStatus
import com.pawbridge.backend.store.internal.memberread.infrastructure.jpa.model.OrderStatus
import com.pawbridge.backend.store.internal.orderadministration.application.AdminOrders
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Sort
import org.springframework.data.web.PageableDefault
import org.springframework.http.ResponseEntity
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/admin/orders")
class AdminOrderController(private val orders: AdminOrders) {

    @GetMapping
    fun list(
        @AuthenticationPrincipal user: Long?,
        @RequestParam(name = "status", required = false) status: OrderStatus?,
        @RequestParam(name = "deliveryStatus", required = false) delivery: DeliveryStatus?,
        @RequestParam(name = "userId", required = false) member: Long?,
        @RequestParam(name = "keyword", required = false) keyword: String?,
        @RequestParam(name = "sortBy", defaultValue = "createdAt") sortBy: String,
        @RequestParam(name = "sortOrder", defaultValue = "desc") sortOrder: String,
        @PageableDefault(size = 20) pageable: Pageable,
    ): ResponseEntity<Page<DirectOrders.OrderDetails>> {
        val page = orders.list(
            current(user),
            AdminOrders.Query(
                status?.name,
                delivery?.name,
                member,
                keyword,
                sortBy,
                sortOrder,
                pageable.pageNumber,
                pageable.pageSize,
            ),
        )
        return ResponseEntity.ok(
            PageImpl(
                // PageImpl rejects null content with this exact message.
                page.content ?: throw IllegalArgumentException("Content must not be null"),
                PageRequest.of(
                    page.number,
                    page.size,
                    Sort.by(if (page.ascending) Sort.Direction.ASC else Sort.Direction.DESC, page.sortBy),
                ),
                page.totalElements,
            ),
        )
    }

    @GetMapping("/{orderId}")
    fun get(
        @AuthenticationPrincipal user: Long?,
        @PathVariable("orderId") id: Long,
    ): ResponseEntity<DirectOrders.OrderDetails> =
        ResponseEntity.ok(orders.get(current(user), id))

    @PatchMapping("/{orderId}/delivery-status")
    fun update(
        @AuthenticationPrincipal user: Long?,
        @PathVariable("orderId") id: Long,
        @RequestBody request: DeliveryStatusUpdateRequest,
    ): ResponseEntity<DirectOrders.OrderDetails> =
        ResponseEntity.ok(orders.updateDelivery(current(user), id, request.deliveryStatus?.name))

    @PatchMapping("/{orderId}/status")
    fun updateStatus(
        @AuthenticationPrincipal user: Long?,
        @PathVariable("orderId") id: Long,
        @RequestBody request: OrderStatusUpdateRequest,
    ): ResponseEntity<DirectOrders.OrderDetails> =
        ResponseEntity.ok(orders.updateStatus(current(user), id, request.status?.name))

    private companion object {
        private fun current(user: Long?): Long {
            if (user == null) {
                throw AuthenticationCredentialsNotFoundException("An authenticated member is required")
            }
            return user
        }
    }
}
