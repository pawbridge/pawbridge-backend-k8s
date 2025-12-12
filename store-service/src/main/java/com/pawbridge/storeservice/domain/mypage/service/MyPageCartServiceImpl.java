package com.pawbridge.storeservice.domain.mypage.service;

import com.pawbridge.storeservice.domain.cart.entity.Cart;
import com.pawbridge.storeservice.domain.mypage.dto.CartResponse;
import com.pawbridge.storeservice.domain.mypage.repository.MyCartRepository;
import com.pawbridge.storeservice.domain.mypage.repository.MyProductSKURepository;
import com.pawbridge.storeservice.domain.product.entity.ProductSKU;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 마이페이지용 장바구니 서비스 구현
 * - FeignClient 전용 단순 조회
 * - MySQL 사용
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MyPageCartServiceImpl implements MyPageCartService {

    private final MyCartRepository cartRepository;
    private final MyProductSKURepository productSKURepository;

    /**
     * 사용자별 장바구니 조회 (MySQL)
     */
    @Override
    @Transactional(readOnly = true)
    public Optional<CartResponse> findByUserId(Long userId) {
        log.debug("[MyPage] 사용자별 장바구니 조회: userId={}", userId);

        Optional<Cart> cartOpt = cartRepository.findByUserId(userId);

        return cartOpt.map(cart -> {
            // CartItem들의 productSkuId 목록 추출
            List<Long> skuIds = cart.getItems().stream()
                    .map(item -> item.getProductSkuId())
                    .collect(Collectors.toList());

            // ProductSKU 정보 일괄 조회 (Product fetch join)
            Map<Long, ProductSKU> skuMap = productSKURepository.findAllByIdWithProduct(skuIds).stream()
                    .collect(Collectors.toMap(ProductSKU::getId, sku -> sku));

            // CartResponse 생성
            return CartResponse.from(cart, skuMap);
        });
    }
}
