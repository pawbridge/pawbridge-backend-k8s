package com.pawbridge.storeservice.domain.mypage.repository;

import com.pawbridge.storeservice.domain.cart.entity.Cart;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * 장바구니 Repository
 * - 마이페이지용
 */
@Repository
public interface MyCartRepository extends JpaRepository<Cart, Long> {

    /**
     * 사용자별 장바구니 조회 (CartItems, ProductSKU, Product fetch join)
     * - N+1 방지를 위해 @EntityGraph 사용
     * @param userId 사용자 ID
     * @return Optional<Cart>
     */
    @EntityGraph(attributePaths = {"cartItems", "cartItems.productSKU", "cartItems.productSKU.product"})
    Optional<Cart> findByUserId(Long userId);
}
