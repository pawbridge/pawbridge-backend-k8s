package com.pawbridge.backend

import com.ninjasquad.springmockk.MockkBean
import com.pawbridge.backend.boot.configuration.AdminPostReadConfiguration
import com.pawbridge.backend.boot.configuration.ReadSecurityConfiguration
import com.pawbridge.backend.community.internal.post.application.PostDetails
import com.pawbridge.backend.community.internal.post.application.PostNotFoundException
import com.pawbridge.backend.community.internal.post.application.PostPageReader
import com.pawbridge.backend.community.internal.post.application.ReadAdminPosts
import com.pawbridge.backend.community.internal.post.application.ReadPost
import com.pawbridge.backend.community.internal.post.web.AdminPostReadController
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.json.JsonCompareMode
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
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

    @MockkBean
    lateinit var posts: ReadAdminPosts

    @MockkBean
    lateinit var post: ReadPost

    @MockkBean
    lateinit var pageReader: PostPageReader

    @Test
    fun `기본 관리자 페이지__조회하면__원래 페이지 JSON과 같고 위조 신원 헤더를 무시한다`() {
        // given
        every { posts.read(any()) } returns ReadAdminPosts.Page(listOf(row()), 41)

        // when
        val result = mvc.perform(
            get(ROOT)
                .header("Authorization", admin())
                .header("X-User-Role", "ROLE_USER")
                .header("X-User-Id", "999"),
        )

        // then
        result
            .andExpect(status().isOk())
            .andExpect(header().doesNotExist("Set-Cookie"))
            .andExpect(content().json(EXPECTED_DEFAULT, JsonCompareMode.STRICT))
        verify(exactly = 1) {
            posts.read(PostPageReader.Request(0, 20, listOf(PostPageReader.Order("createdAt", false, false))))
        }
        confirmVerified(posts, post)
    }

    @ParameterizedTest
    @MethodSource("paging")
    fun `잘못되었거나 큰 페이지 매개변수__바인딩하면__원래 대체값과 최댓값을 재사용한다`(
        page: String,
        size: String,
        expectedPage: Int,
        expectedSize: Int,
    ) {
        // given
        every { posts.read(any()) } returns ReadAdminPosts.Page(listOf(), 0)

        // when
        val result = mvc.perform(get(ROOT).param("page", page).param("size", size).header("Authorization", admin()))

        // then
        result.andExpect(status().isOk())
        verify(exactly = 1) {
            posts.read(
                PostPageReader.Request(expectedPage, expectedSize, listOf(PostPageReader.Order("createdAt", false, false))),
            )
        }
    }

    @Test
    fun `대소문자 무시가 있는 반복 정렬__바인딩하면__보조 정렬 추가 없이 원래 순서 목록을 유지한다`() {
        // given
        every { posts.read(any()) } returns ReadAdminPosts.Page(listOf(), 0)

        // when
        val result = mvc.perform(
            get(ROOT)
                .param("sort", "title,content,desc,ignorecase", "postId,asc")
                .header("Authorization", admin()),
        )

        // then
        result.andExpect(status().isOk())
        verify(exactly = 1) {
            posts.read(
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
    }

    @Test
    fun `관리자 상세__조회하면__정확히 아홉 필드와 null 미디어를 새 조회 없이 재사용한다`() {
        // given
        val row = PostDetails(7, 9, null, "title", "content", "COMMUNICATION", null, null, null)

        every { post.read(any()) } returns row

        // when
        val result = mvc.perform(get("$ROOT/7").header("Authorization", admin()))

        // then
        result
            .andExpect(status().isOk())
            .andExpect(
                content().json(
                    "{\"code\":200,\"message\":\"요청이 성공적으로 처리되었습니다.\",\"data\":{\"postId\":7,\"authorId\":9,\"authorNickname\":null,\"title\":\"title\",\"content\":\"content\",\"boardType\":\"COMMUNICATION\",\"imageUrls\":null,\"createdAt\":null,\"updatedAt\":null}}",
                    JsonCompareMode.STRICT,
                ),
            )
        verify(exactly = 1) { post.read(7) }
        confirmVerified(posts, post)
    }

    @ParameterizedTest
    @ValueSource(longs = [-1, 0, 7])
    fun `없거나 삭제된 숫자 글__조회하면__양수 ID 검사 없이 원래 404를 유지한다`(id: Long) {
        // given
        every { post.read(any()) } throws PostNotFoundException()

        // when
        val result = mvc.perform(get("$ROOT/$id").header("Authorization", admin()))

        // then
        result
            .andExpect(status().isNotFound())
            .andExpect(
                content().json(
                    "{\"code\":404,\"message\":\"게시글을 찾을 수 없습니다.\",\"data\":null}",
                    JsonCompareMode.STRICT,
                ),
            )
        verify(exactly = 1) { post.read(id) }
        confirmVerified(posts, post)
    }

    @ParameterizedTest
    @ValueSource(strings = ["no-number", "9223372036854775808", "read", "search"])
    fun `숫자가 아니거나 넘치는 ID__관리자가 바인딩하면__서비스 호출 없이 400을 응답한다`(
        id: String,
    ) {
        // given
        val request = get("$ROOT/$id").header("Authorization", admin())

        // when
        val result = mvc.perform(request)

        // then
        result
            .andExpect(status().isBadRequest())
            .andExpect(content().json("{\"code\":400,\"message\":\"요청 경로의 식별자 형식이 올바르지 않습니다.\",\"data\":null}", JsonCompareMode.STRICT))
        confirmVerified(posts, post)
    }

    @ParameterizedTest
    @ValueSource(strings = ["list", "detail"])
    fun `조회 또는 커밋 런타임 실패__조회하면__안전한 원래 500을 돌려준다`(mode: String) {
        // given
        val failure = org.springframework.transaction.TransactionSystemException("private synthetic details")
        if (mode == "list") {
            every { posts.read(any()) } throws failure
        } else {
            every { post.read(any()) } throws failure
        }

        // when
        val result = mvc.perform(get(ROOT + (if (mode == "list") "" else "/7")).header("Authorization", admin()))

        // then
        result
            .andExpect(status().isInternalServerError())
            .andExpect(content().json(ERROR, JsonCompareMode.STRICT))
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
