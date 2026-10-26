package com.pawbridge.backend.user.internal.directory.web

import com.pawbridge.backend.user.internal.directory.application.UserDirectoryQuery
import com.pawbridge.backend.user.internal.profile.application.UserProfile
import com.pawbridge.backend.user.internal.profile.web.UserProfileController
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Sort
import org.springframework.data.web.PageableDefault
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
class AdminUserDirectoryController(private val users: UserDirectoryQuery) {

    enum class Role { ROLE_USER, ROLE_ADMIN, ROLE_SHELTER }

    @GetMapping("/api/v1/admin/users")
    fun list(
        @PageableDefault(size = 20, sort = ["createdAt"], direction = Sort.Direction.DESC) pageable: Pageable,
    ): UserProfileController.Response<Page<UserProfile>> = response(null, null, pageable)

    @GetMapping("/api/v1/admin/users/search")
    fun search(
        @RequestParam(name = "keyword", required = false) keyword: String?,
        @RequestParam(name = "role", required = false) role: Role?,
        @PageableDefault(size = 20, sort = ["createdAt"], direction = Sort.Direction.DESC) pageable: Pageable,
    ): UserProfileController.Response<Page<UserProfile>> = response(keyword, role?.name, pageable)

    private fun response(
        keyword: String?,
        role: String?,
        pageable: Pageable,
    ): UserProfileController.Response<Page<UserProfile>> {
        val request = UserDirectoryQuery.Request(
            pageable.pageNumber,
            pageable.pageSize,
            pageable.sort.stream()
                .map { order -> UserDirectoryQuery.Order(order.property, order.isAscending, order.isIgnoreCase) }
                .toList(),
        )
        val result = users.page(keyword, role, request)
        return UserProfileController.Response(
            200,
            "요청이 성공적으로 처리되었습니다.",
            PageImpl(result.content(), pageable, result.total()),
        )
    }
}
