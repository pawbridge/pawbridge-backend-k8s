package com.pawbridge.backend.store.internal.directorder.infrastructure.jpa.repository

import com.pawbridge.backend.store.internal.memberread.infrastructure.jpa.model.Order
import com.pawbridge.backend.store.internal.memberread.infrastructure.jpa.model.OrderStatus
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.JpaSpecificationExecutor
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.util.Optional

// Nullable parameters: Spring Data rejects null for non-null Kotlin parameters, which the Java interface accepted.
interface OrderRepository : JpaRepository<Order, Long>, JpaSpecificationExecutor<Order> {

    fun findByUserId(userId: Long?): List<Order>

    fun findByOrderUuid(orderUuid: String?): Optional<Order>

    // 페이징 조회
    fun findByUserIdOrderByCreatedAtDesc(userId: Long?, pageable: Pageable?): Page<Order>

    fun findByUserIdAndStatusOrderByCreatedAtDesc(
        userId: Long?,
        status: OrderStatus?,
        pageable: Pageable?,
    ): Page<Order>

    @Query("select distinct o from Order o left join fetch o.orderItems where o.userId = :userId and o.id in :ids")
    fun findWithItemsByUserIdAndIdIn(
        @Param("userId") userId: Long?,
        @Param("ids") ids: List<Long?>?,
    ): List<Order>
}
