package com.pawbridge.backend.user.internal.storemypage.web;

import com.pawbridge.backend.user.internal.profile.web.UserProfileController.Response;
import com.pawbridge.backend.user.internal.storemypage.application.StoreMyPage;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public final class StoreMyPageController {
    private final StoreMyPage pages;
    public StoreMyPageController(StoreMyPage pages) { this.pages = pages; }

    @GetMapping("/api/v1/users/me/wishlists")
    public Response<Page<StoreMyPage.Wishlist>> wishlists(@AuthenticationPrincipal Long current,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        return new Response<>(200, "요청이 성공적으로 처리되었습니다.", wire(pages.wishlists(current, request(pageable))));
    }

    @GetMapping("/api/v1/users/me/orders")
    public Response<Page<StoreMyPage.Purchase>> orders(@AuthenticationPrincipal Long current,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        return new Response<>(200, "요청이 성공적으로 처리되었습니다.", wire(pages.orders(current, request(pageable))));
    }

    @GetMapping("/api/v1/users/me/cart")
    public ResponseEntity<Response<StoreMyPage.Cart>> cart(@AuthenticationPrincipal Long current) {
        StoreMyPage.Cart cart = pages.cart(current);
        return cart == null ? ResponseEntity.noContent().build()
                : ResponseEntity.ok(new Response<>(200, "요청이 성공적으로 처리되었습니다.", cart));
    }

    private static StoreMyPage.Request request(Pageable pageable) {
        String sort = pageable.getSort().stream().map(order -> order.getProperty() + ","
                + order.getDirection().name().toLowerCase(java.util.Locale.ROOT))
                .reduce((left, right) -> left + "," + right).orElse("createdAt,desc");
        return new StoreMyPage.Request(pageable.getPageNumber(), pageable.getPageSize(), sort);
    }

    private static <T> Page<T> wire(StoreMyPage.Page<T> page) {
        if (page.size() == 0) return new PageImpl<>(page.content());
        Sort sort = Sort.by(page.sort().stream().map(order -> new Sort.Order(
                order.ascending() ? Sort.Direction.ASC : Sort.Direction.DESC, order.property(), order.ignoreCase(),
                Sort.NullHandling.valueOf(order.nullHandling()))).toList());
        return new PageImpl<>(page.content(), PageRequest.of(page.number(), page.size(), sort), page.totalElements());
    }
}
