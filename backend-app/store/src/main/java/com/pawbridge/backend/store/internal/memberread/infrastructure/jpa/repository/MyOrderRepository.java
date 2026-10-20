package com.pawbridge.backend.store.internal.memberread.infrastructure.jpa.repository;

import com.pawbridge.backend.store.internal.memberread.infrastructure.jpa.model.Order;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.Collection;
import java.util.List;

/**
 * 주문 Repository
 * - 마이페이지용
 */
@Repository
public interface MyOrderRepository extends JpaRepository<Order, Long> {

    interface IdOnly { Long getId(); }

    Page<IdOnly> findIdsByUserId(Long userId, Pageable pageable);

    /** 선택한 사용자·주문 ID의 전체 원문 graph 조회. 부모 페이지에는 collection graph를 적용하지 않는다. */
    @EntityGraph(attributePaths = {"orderItems", "orderItems.productSKU"})
    List<Order> findByUserIdAndIdIn(Long userId, Collection<Long> ids);

    /**
     * 사용자별 주문 개수
     * @param userId 사용자 ID
     * @return 주문 개수
     */
    long countByUserId(Long userId);
}
