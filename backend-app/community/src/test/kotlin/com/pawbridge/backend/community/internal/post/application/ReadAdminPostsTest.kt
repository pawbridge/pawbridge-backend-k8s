package com.pawbridge.backend.community.internal.post.application

import com.pawbridge.backend.community.support.postPageReader
import com.pawbridge.backend.community.support.postReader
import com.pawbridge.backend.community.support.userNicknameQuery
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.util.Optional

class ReadAdminPostsTest {
    @Test
    fun `정렬된 페이지가 있을 때__닉네임을 채우면__상세 조회의 닉네임 규칙을 재사용하고 전체 개수를 보존하며 게시글을 추가 조회하지 않는다`() {
        // given
        val order = PostPageReader.Order("createdAt", false, false)
        val request = PostPageReader.Request(1, 2, listOf(order))
        val rows = listOf(post(1, 7), post(2, 8))
        val calls = ArrayList<Long>()
        val details = ReadPost(
            postReader { throw AssertionError("No per-row post query") },
            userNicknameQuery { id ->
                calls.add(id)
                if (id == 7L) Optional.of("") else Optional.empty()
            },
        )
        val query = ReadAdminPosts(
            postPageReader { r ->
                assertThat(r).isSameAs(request)
                PostPageReader.Slice(rows, 41)
            },
            details,
        )

        // when
        val page = query.read(request)

        // then
        assertThat(page.total()).isEqualTo(41)
        assertThat(page.content()).extracting<Long> { it.postId }.containsExactly(1L, 2L)
        assertThat(page.content()).extracting<String?> { it.authorNickname }.containsExactly(null, "사용자8")
        assertThat(page.content().first().imageUrls).containsExactly("same", null, "same")
        assertThat(calls).containsExactly(7L, 8L)
    }

    @Test
    fun `페이지가 비었거나 실패할 때__조회하면__닉네임을 요청하지 않고 실패를 빈 성공으로 바꾸지 않는다`() {
        // given
        val request = PostPageReader.Request(4, 20, listOf())
        val details = ReadPost(
            postReader { throw AssertionError() },
            userNicknameQuery { throw AssertionError() },
        )

        val failure = IllegalStateException("synthetic")

        // when
        val emptyPage = ReadAdminPosts(postPageReader { PostPageReader.Slice(listOf(), 41) }, details).read(request)
        val thrown = assertThatThrownBy {
            ReadAdminPosts(postPageReader { throw failure }, details).read(request)
        }

        // then
        assertThat(emptyPage).isEqualTo(ReadAdminPosts.Page(listOf(), 41))
        thrown.isSameAs(failure)
    }

    private fun post(id: Long, author: Long): PostSnapshot =
        PostSnapshot(id, author, "title", "content", "REPORT", listOf("same", null, "same"), null, null)
}
