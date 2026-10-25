package com.pawbridge.backend

import com.pawbridge.backend.boot.configuration.AdminPostReadConfiguration
import com.pawbridge.backend.boot.configuration.ReadSecurityConfiguration
import com.pawbridge.backend.community.internal.post.application.PostDetails
import com.pawbridge.backend.community.internal.post.application.PostNotFoundException
import com.pawbridge.backend.community.internal.post.application.PostPageReader
import com.pawbridge.backend.community.internal.post.application.ReadAdminPosts
import com.pawbridge.backend.community.internal.post.application.ReadPost
import com.pawbridge.backend.community.internal.post.web.AdminPostReadController
import com.pawbridge.backend.support.anyNonNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.Mockito.`when` as whenever
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.json.JsonCompareMode
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.stream.Stream

@WebMvcTest(
    controllers = [AdminPostReadController::class],
    excludeAutoConfiguration = [UserDetailsServiceAutoConfiguration::class],
)
@ContextConfiguration(
    classes = [AdminPostReadConfiguration::class, ReadSecurityConfiguration::class, F3TestInputs.SecurityInputs::class],
)
class AdminPostReadHttpTest {
    @Autowired
    lateinit var mvc: MockMvc

    @MockitoBean
    lateinit var posts: ReadAdminPosts

    @MockitoBean
    lateinit var post: ReadPost

    @MockitoBean
    lateinit var pageReader: PostPageReader

    @Test
    fun given__default_admin_page__when__reading__then__match_original_page_json_and_ignore_forged_identity_headers() {
        whenever(posts.read(anyNonNull<PostPageReader.Request>())).thenReturn(ReadAdminPosts.Page(listOf(row()), 41))
        mvc.perform(
            get(ROOT)
                .header("Authorization", admin())
                .header("X-User-Role", "ROLE_USER")
                .header("X-User-Id", "999"),
        )
            .andExpect(status().isOk())
            .andExpect(header().doesNotExist("Set-Cookie"))
            .andExpect(content().json(EXPECTED_DEFAULT, JsonCompareMode.STRICT))
        verify(posts).read(PostPageReader.Request(0, 20, listOf(PostPageReader.Order("createdAt", false, false))))
        verifyNoInteractions(post)
    }

    @ParameterizedTest
    @MethodSource("paging")
    fun given__invalid_or_large_page_parameters__when__binding__then__reuse_original_fallback_and_maximum(
        page: String,
        size: String,
        expectedPage: Int,
        expectedSize: Int,
    ) {
        whenever(posts.read(anyNonNull<PostPageReader.Request>())).thenReturn(ReadAdminPosts.Page(listOf(), 0))
        mvc.perform(get(ROOT).param("page", page).param("size", size).header("Authorization", admin()))
            .andExpect(status().isOk())
        verify(posts).read(
            PostPageReader.Request(expectedPage, expectedSize, listOf(PostPageReader.Order("createdAt", false, false))),
        )
    }

    @Test
    fun given__repeated_sort_with_ignorecase__when__binding__then__retain_original_order_list_without_added_tiebreak() {
        whenever(posts.read(anyNonNull<PostPageReader.Request>())).thenReturn(ReadAdminPosts.Page(listOf(), 0))
        mvc.perform(
            get(ROOT)
                .param("sort", "title,content,desc,ignorecase", "postId,asc")
                .header("Authorization", admin()),
        ).andExpect(status().isOk())
        verify(posts).read(
            PostPageReader.Request(
                0,
                20,
                listOf(
                    PostPageReader.Order("title", false, true),
                    PostPageReader.Order("content", false, true),
                    PostPageReader.Order("postId", true, false),
                ),
            ),
        )
    }

    @Test
    fun given__admin_detail__when__reading__then__reuse_exact_nine_fields_and_null_media_without_new_query() {
        val row = PostDetails(7, 9, null, "title", "content", "COMMUNICATION", null, null, null)
        whenever(post.read(7)).thenReturn(row)
        mvc.perform(get("$ROOT/7").header("Authorization", admin()))
            .andExpect(status().isOk())
            .andExpect(
                content().json(
                    "{\"code\":200,\"message\":\"요청이 성공적으로 처리되었습니다.\",\"data\":{\"postId\":7,\"authorId\":9,\"authorNickname\":null,\"title\":\"title\",\"content\":\"content\",\"boardType\":\"COMMUNICATION\",\"imageUrls\":null,\"createdAt\":null,\"updatedAt\":null}}",
                    JsonCompareMode.STRICT,
                ),
            )
        verify(post).read(7)
        verifyNoInteractions(posts)
    }

    @ParameterizedTest
    @ValueSource(longs = [-1, 0, 7])
    fun given__missing_or_deleted_numeric_post__when__reading__then__retain_original_404_without_positive_id_guard(id: Long) {
        whenever(post.read(id)).thenThrow(PostNotFoundException())
        mvc.perform(get("$ROOT/$id").header("Authorization", admin()))
            .andExpect(status().isNotFound())
            .andExpect(
                content().json(
                    "{\"code\":404,\"message\":\"게시글을 찾을 수 없습니다.\",\"data\":null}",
                    JsonCompareMode.STRICT,
                ),
            )
        verify(post).read(id)
        verifyNoInteractions(posts)
    }

    @ParameterizedTest
    @ValueSource(strings = ["no-number", "9223372036854775808", "read", "search"])
    fun given__malformed_or_overflow_id__when__admin_binds__then__retain_measured_original_500_without_service_call(
        id: String,
    ) {
        mvc.perform(get("$ROOT/$id").header("Authorization", admin()))
            .andExpect(status().isInternalServerError())
            .andExpect(content().json(ERROR, JsonCompareMode.STRICT))
        verifyNoInteractions(posts, post)
    }

    @ParameterizedTest
    @ValueSource(strings = ["", "/7"])
    fun given__user_token_and_admin_header__when__requesting__then__deny_before_binding_or_query(suffix: String) {
        mvc.perform(
            get(ROOT + suffix)
                .header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_USER"))
                .header("X-User-Role", "ROLE_ADMIN"),
        ).andExpect(status().isForbidden())
        verifyNoInteractions(posts, post)
    }

    @ParameterizedTest
    @ValueSource(strings = ["missing", "invalid"])
    fun given__missing_or_invalid_token__when__requesting_bad_id__then__authenticate_before_binding(mode: String) {
        val request = get("$ROOT/no-number").header("X-User-Role", "ROLE_ADMIN")
        if (mode == "invalid") {
            request.header("Authorization", "Bearer invalid")
        }
        mvc.perform(request).andExpect(status().isUnauthorized())
        verifyNoInteractions(posts, post)
    }

    @ParameterizedTest
    @ValueSource(strings = ["read", "search"])
    fun given__ported_id_mapping_literals__when__requested_without_bearer__then__authenticate_before_the_original_500_binding(
        path: String,
    ) {
        mvc.perform(get("$ROOT/$path").header("X-User-Role", "ROLE_ADMIN")).andExpect(status().isUnauthorized())
        verifyNoInteractions(posts, post)
    }

    @ParameterizedTest
    @ValueSource(strings = ["list", "detail"])
    fun given__query_or_commit_runtime_failure__when__reading__then__return_safe_original_500(mode: String) {
        val failure = org.springframework.transaction.TransactionSystemException("private synthetic details")
        if (mode == "list") {
            whenever(posts.read(anyNonNull<PostPageReader.Request>())).thenThrow(failure)
        } else {
            whenever(post.read(7)).thenThrow(failure)
        }
        mvc.perform(get(ROOT + (if (mode == "list") "" else "/7")).header("Authorization", admin()))
            .andExpect(status().isInternalServerError())
            .andExpect(content().json(ERROR, JsonCompareMode.STRICT))
    }

    @Test
    fun given__admin_and_deeper_or_unported_mutations__when__requesting__then__retain_default_deny() {
        mvc.perform(delete("$ROOT/7/extra").header("Authorization", admin())).andExpect(status().isForbidden())
        mvc.perform(put("$ROOT/7/extra").header("Authorization", admin())).andExpect(status().isForbidden())
        mvc.perform(post(ROOT).header("Authorization", admin())).andExpect(status().isForbidden())
        verifyNoInteractions(posts, post)
    }

    companion object {
        const val ROOT = "/api/v1/admin/posts"
        const val ERROR = "{\"code\":500,\"message\":\"서버 에러!\",\"data\":null}"

        @JvmStatic
        fun paging(): Stream<Arguments> {
            return Stream.of(
                Arguments.of("-1", "0", 0, 20),
                Arguments.of("garbage", "oops", 0, 20),
                Arguments.of("2147483647", "3000", Int.MAX_VALUE, 2000),
                Arguments.of("2147483648", "999999999999", 0, 20),
            )
        }

        private fun row(): PostDetails {
            return PostDetails(7, 9, "synthetic", "title", "content", "COMMUNICATION", listOf(), null, null)
        }

        private fun admin(): String = "Bearer " + F3TestInputs.token(7, "ROLE_ADMIN")

        const val EXPECTED_DEFAULT = """
{
  "code": 200,
  "message": "요청이 성공적으로 처리되었습니다.",
  "data": {
    "content": [
      {
        "postId": 7,
        "authorId": 9,
        "authorNickname": "synthetic",
        "title": "title",
        "content": "content",
        "boardType": "COMMUNICATION",
        "imageUrls": [],
        "createdAt": null,
        "updatedAt": null
      }
    ],
    "pageable": {
      "pageNumber": 0,
      "pageSize": 20,
      "sort": {
        "sorted": true,
        "unsorted": false,
        "empty": false
      },
      "offset": 0,
      "paged": true,
      "unpaged": false
    },
    "last": false,
    "totalElements": 41,
    "totalPages": 3,
    "numberOfElements": 1,
    "first": true,
    "size": 20,
    "number": 0,
    "sort": {
      "sorted": true,
      "unsorted": false,
      "empty": false
    },
    "empty": false
  }
}
    """
    }
}
