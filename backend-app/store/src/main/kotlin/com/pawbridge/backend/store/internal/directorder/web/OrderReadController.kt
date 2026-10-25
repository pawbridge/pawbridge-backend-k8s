package com.pawbridge.backend.store.internal.directorder.web

import com.pawbridge.backend.store.api.StoreMemberReads
import com.pawbridge.backend.store.internal.directorder.application.DirectOrders
import com.pawbridge.backend.store.internal.memberread.infrastructure.jpa.model.OrderStatus
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
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
class OrderReadController(private val orders: DirectOrders) {

    @GetMapping("/api/v1/orders")
    fun list(
        @AuthenticationPrincipal current: Long?,
        @RequestParam(name = "status", required = false) status: OrderStatus?,
        @PageableDefault(size = 10) pageable: Pageable,
    ): ResponseEntity<Page<DirectOrders.OrderDetails>> {
        val request = StoreMemberReads.Request(
            pageable.pageNumber,
            pageable.pageSize,
            pageable.sort.stream()
                .map { order -> StoreMemberReads.Order(order.property, order.isAscending, order.isIgnoreCase) }
                .toList(),
        )
        val page = orders.list(current(current), status?.name, request)
        val sort = Sort.by(
            page.sort().map { order ->
                Sort.Order(
                    if (order.ascending()) Sort.Direction.ASC else Sort.Direction.DESC,
                    order.property(),
                    order.ignoreCase(),
                    Sort.NullHandling.NATIVE,
                )
            },
        )
        return ResponseEntity.ok(
            PageImpl(page.content(), PageRequest.of(page.number(), page.size(), sort), page.totalElements()),
        )
    }

    @GetMapping("/api/v1/orders/{orderId}")
    fun get(
        @AuthenticationPrincipal current: Long?,
        @PathVariable("orderId") orderId: Long,
    ): ResponseEntity<DirectOrders.OrderDetails> =
        ResponseEntity.ok(orders.get(current(current), orderId))

    private companion object {
        private fun current(member: Long?): Long {
            if (member == null) {
                throw AuthenticationCredentialsNotFoundException("An authenticated member is required")
            }
            return member
        }
    }
}
