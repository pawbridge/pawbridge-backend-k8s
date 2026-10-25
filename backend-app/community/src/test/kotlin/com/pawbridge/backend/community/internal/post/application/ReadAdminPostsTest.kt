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
    fun given__ordered_page__when__enriching__then__reuse_detail_nickname_null_fallback_and_preserve_page_total_without_extra_post_queries() {
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

        val page = query.read(request)

        assertThat(page.total()).isEqualTo(41)
        assertThat(page.content()).extracting<Long> { it.postId }.containsExactly(1L, 2L)
        assertThat(page.content()).extracting<String?> { it.authorNickname }.containsExactly(null, "사용자8")
        assertThat(page.content().first().imageUrls).containsExactly("same", null, "same")
        assertThat(calls).containsExactly(7L, 8L)
    }

    @Test
    fun given__empty_or_failed_page__when__reading__then__do_not_request_nicknames_or_convert_failure_into_empty_success() {
        val request = PostPageReader.Request(4, 20, listOf())
        val details = ReadPost(
            postReader { throw AssertionError() },
            userNicknameQuery { throw AssertionError() },
        )

        assertThat(ReadAdminPosts(postPageReader { PostPageReader.Slice(listOf(), 41) }, details).read(request))
            .isEqualTo(ReadAdminPosts.Page(listOf(), 41))
        val failure = IllegalStateException("synthetic")
        assertThatThrownBy { ReadAdminPosts(postPageReader { throw failure }, details).read(request) }
            .isSameAs(failure)
    }

    private fun post(id: Long, author: Long): PostSnapshot =
        PostSnapshot(id, author, "title", "content", "REPORT", listOf("same", null, "same"), null, null)
}
