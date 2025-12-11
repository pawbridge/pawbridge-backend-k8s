package com.pawbridge.storeservice.domain.mypage.repository;

import com.pawbridge.storeservice.domain.order.entity.Order;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * 주문 Repository
 * - 마이페이지용
 */
@Repository
public interface MyOrderRepository extends JpaRepository<Order, Long> {

    /**
     * 사용자별 주문 목록 조회 (OrderItems fetch join)
     * - N+1 방지를 위해 @EntityGraph 사용
     * @param userId 사용자 ID
     * @param pageable 페이징 정보
     * @return Page<Order>
     */
    @EntityGraph(attributePaths = {"orderItems", "orderItems.productSKU"})
    Page<Order> findByUserId(Long userId, Pageable pageable);

    /**
     * 사용자별 주문 개수
     * @param userId 사용자 ID
     * @return 주문 개수
     */
    long countByUserId(Long userId);
}
