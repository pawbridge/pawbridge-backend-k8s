package com.pawbridge.backend.user.internal.storemypage.web

import com.pawbridge.backend.user.internal.profile.web.UserProfileController.Response
import com.pawbridge.backend.user.internal.storemypage.application.StoreMyPage
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Sort
import org.springframework.data.web.PageableDefault
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import java.util.Locale

@RestController
class StoreMyPageController(private val pages: StoreMyPage) {

    // The principal stays boxed like the Java signature; a missing principal fails with NullPointerException
    // exactly as the Java unboxing did.
    @GetMapping("/api/v1/users/me/wishlists")
    fun wishlists(
        @AuthenticationPrincipal current: Long?,
        @PageableDefault(size = 20, sort = ["createdAt"], direction = Sort.Direction.DESC) pageable: Pageable,
    ): Response<Page<StoreMyPage.Wishlist>> =
        Response(200, "요청이 성공적으로 처리되었습니다.", wire(pages.wishlists(current!!, request(pageable))))

    @GetMapping("/api/v1/users/me/orders")
    fun orders(
        @AuthenticationPrincipal current: Long?,
        @PageableDefault(size = 20, sort = ["createdAt"], direction = Sort.Direction.DESC) pageable: Pageable,
    ): Response<Page<StoreMyPage.Purchase>> =
        Response(200, "요청이 성공적으로 처리되었습니다.", wire(pages.orders(current!!, request(pageable))))

    @GetMapping("/api/v1/users/me/cart")
    fun cart(@AuthenticationPrincipal current: Long?): ResponseEntity<Response<StoreMyPage.Cart>> {
        val cart = pages.cart(current!!)
        return if (cart == null) {
            ResponseEntity.noContent().build()
        } else {
            ResponseEntity.ok(Response(200, "요청이 성공적으로 처리되었습니다.", cart))
        }
    }

    private companion object {
        private fun request(pageable: Pageable): StoreMyPage.Request {
            val sort = pageable.sort.stream()
                .map { order -> order.property + "," + order.direction.name.lowercase(Locale.ROOT) }
                .reduce { left, right -> "$left,$right" }
                .orElse("createdAt,desc")
            return StoreMyPage.Request(pageable.pageNumber, pageable.pageSize, sort)
        }

        private fun <T> wire(page: StoreMyPage.Page<T>): Page<T> {
            // PageImpl rejects a null content list, as the Java code did.
            val content = requireNotNull(page.content) { "Content must not be null" }
            if (page.size == 0) {
                return PageImpl(content)
            }
            val sort = Sort.by(
                page.sort!!.stream().map { order ->
                    Sort.Order(
                        if (order.ascending) Sort.Direction.ASC else Sort.Direction.DESC,
                        order.property,
                        order.ignoreCase,
                        Sort.NullHandling.valueOf(order.nullHandling!!),
                    )
                }.toList(),
            )
            return PageImpl(content, PageRequest.of(page.number, page.size, sort), page.totalElements)
        }
    }
}
