package com.pawbridge.storeservice.domain.mypage.repository;

import com.pawbridge.storeservice.domain.product.entity.Wishlist;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * 위시리스트 Repository
 * - 마이페이지용
 */
@Repository
public interface MyWishlistRepository extends JpaRepository<Wishlist, Long> {

    /**
     * 사용자별 찜 목록 조회 (Product fetch join)
     * - N+1 방지를 위해 @EntityGraph 사용
     * @param userId 사용자 ID
     * @param pageable 페이징 정보
     * @return Page<Wishlist>
     */
    @EntityGraph(attributePaths = {"product", "product.category"})
    Page<Wishlist> findByUserId(Long userId, Pageable pageable);

    /**
     * 사용자별 찜 개수
     * @param userId 사용자 ID
     * @return 찜 개수
     */
    long countByUserId(Long userId);
}
