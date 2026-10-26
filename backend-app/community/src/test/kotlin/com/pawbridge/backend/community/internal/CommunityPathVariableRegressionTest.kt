package com.pawbridge.backend.community.internal

import com.pawbridge.backend.community.internal.chat.application.MemberChat
import com.pawbridge.backend.community.internal.chat.infrastructure.MemberChatTickets
import com.pawbridge.backend.community.internal.chat.web.MemberChatController
import com.pawbridge.backend.community.internal.chat.web.MemberChatExceptionHandler
import com.pawbridge.backend.community.internal.comment.application.CommentDeletion
import com.pawbridge.backend.community.internal.comment.application.CreateComment
import com.pawbridge.backend.community.internal.comment.application.ListComments
import com.pawbridge.backend.community.internal.comment.application.UpdateComment
import com.pawbridge.backend.community.internal.comment.web.CommentCreateController
import com.pawbridge.backend.community.internal.comment.web.CommentCreateExceptionHandler
import com.pawbridge.backend.community.internal.comment.web.CommentDeleteController
import com.pawbridge.backend.community.internal.comment.web.CommentDeleteExceptionHandler
import com.pawbridge.backend.community.internal.comment.web.CommentReadController
import com.pawbridge.backend.community.internal.comment.web.CommentReadExceptionHandler
import com.pawbridge.backend.community.internal.comment.web.CommentUpdateController
import com.pawbridge.backend.community.internal.comment.web.CommentUpdateExceptionHandler
import com.pawbridge.backend.community.internal.post.application.DeletePost
import com.pawbridge.backend.community.internal.post.application.ListPosts
import com.pawbridge.backend.community.internal.post.application.ReadAdminPosts
import com.pawbridge.backend.community.internal.post.application.ReadPost
import com.pawbridge.backend.community.internal.post.application.UpdatePost
import com.pawbridge.backend.community.internal.post.web.AdminPostDeleteController
import com.pawbridge.backend.community.internal.post.web.AdminPostReadController
import com.pawbridge.backend.community.internal.post.web.AdminPostReadExceptionHandler
import com.pawbridge.backend.community.internal.post.web.AdminPostUpdateController
import com.pawbridge.backend.community.internal.post.web.PostDeleteController
import com.pawbridge.backend.community.internal.post.web.PostDeleteExceptionHandler
import com.pawbridge.backend.community.internal.post.web.PostReadController
import com.pawbridge.backend.community.internal.post.web.PostReadExceptionHandler
import com.pawbridge.backend.community.internal.post.web.PostUpdateController
import com.pawbridge.backend.community.internal.post.web.PostUpdateExceptionHandler
import io.mockk.confirmVerified
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.RequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.nio.charset.StandardCharsets
import java.util.stream.Stream

/**
 * Locks in the 400 response for a blank (`%20`) or non-numeric path variable in the community post, comment and
 * chat controllers.
 *
 * The path variables are Kotlin primitives, so the value fails with MethodArgumentTypeMismatchException before the
 * handler runs. The feature advice catch-alls used to turn that into a 500; each advice now answers 400 in the
 * feature's own `{code, message, data}` envelope and the use case is never reached.
 *
 * Standalone MockMvc with the module exception handlers and mocked use cases only. It does not cover the
 * servlet container, the security filter chain or error dispatch.
 */
class CommunityPathVariableRegressionTest {
    private val readPost: ReadPost = mockk<ReadPost>()
    private val listPosts: ListPosts = mockk<ListPosts>()
    private val readAdminPosts: ReadAdminPosts = mockk<ReadAdminPosts>()
    private val deletePost: DeletePost = mockk<DeletePost>()
    private val updatePost: UpdatePost = mockk<UpdatePost>()
    private val createComment: CreateComment = mockk<CreateComment>()
    private val updateComment: UpdateComment = mockk<UpdateComment>()
    private val commentDeletion: CommentDeletion = mockk<CommentDeletion>()
    private val listComments: ListComments = mockk<ListComments>()
    private val chats: MemberChat = mockk<MemberChat>()
    private val tickets: MemberChatTickets = mockk<MemberChatTickets>()

    private lateinit var postReadMvc: MockMvc
    private lateinit var adminPostReadMvc: MockMvc
    private lateinit var postDeleteMvc: MockMvc
    private lateinit var postUpdateMvc: MockMvc
    private lateinit var commentCreateMvc: MockMvc
    private lateinit var commentUpdateMvc: MockMvc
    private lateinit var commentDeleteMvc: MockMvc
    private lateinit var commentReadMvc: MockMvc
    private lateinit var chatMvc: MockMvc

    @BeforeEach
    fun setUp() {
        postReadMvc = MockMvcBuilders
            .standaloneSetup(PostReadController(readPost, listPosts))
            .setControllerAdvice(PostReadExceptionHandler())
            .build()
        adminPostReadMvc = MockMvcBuilders
            .standaloneSetup(AdminPostReadController(readAdminPosts, readPost))
            .setControllerAdvice(AdminPostReadExceptionHandler())
            .build()
        postDeleteMvc = MockMvcBuilders
            .standaloneSetup(PostDeleteController(deletePost), AdminPostDeleteController(deletePost))
            .setControllerAdvice(PostDeleteExceptionHandler())
            .build()
        postUpdateMvc = MockMvcBuilders
            .standaloneSetup(PostUpdateController(updatePost), AdminPostUpdateController(updatePost))
            .setControllerAdvice(PostUpdateExceptionHandler())
            .build()
        commentCreateMvc = MockMvcBuilders
            .standaloneSetup(CommentCreateController(createComment))
            .setControllerAdvice(CommentCreateExceptionHandler())
            .build()
        commentUpdateMvc = MockMvcBuilders
            .standaloneSetup(CommentUpdateController(updateComment))
            .setControllerAdvice(CommentUpdateExceptionHandler())
            .build()
        commentDeleteMvc = MockMvcBuilders
            .standaloneSetup(CommentDeleteController(commentDeletion))
            .setControllerAdvice(CommentDeleteExceptionHandler())
            .build()
        commentReadMvc = MockMvcBuilders
            .standaloneSetup(CommentReadController(listComments))
            .setControllerAdvice(CommentReadExceptionHandler())
            .build()
        chatMvc = MockMvcBuilders
            .standaloneSetup(MemberChatController(chats, tickets))
            .setControllerAdvice(MemberChatExceptionHandler())
            .build()
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("routes")
    fun `공백이나 숫자가 아닌 ID일 때__경로를 바인딩하면__유스케이스를 호출하지 않고 400을 응답한다`(
        route: Route,
        segment: String,
    ) {
        // when
        val result = mvc(route).perform(route.request(segment)).andReturn()

        // then
        assertBadRequest(result)
        confirmVerified(
            readPost, listPosts, readAdminPosts, deletePost, updatePost,
            createComment, updateComment, commentDeletion, listComments, chats, tickets,
        )
    }

    private fun mvc(route: Route): MockMvc = when (route.controller) {
        "postRead" -> postReadMvc
        "adminPostRead" -> adminPostReadMvc
        "postDelete" -> postDeleteMvc
        "postUpdate" -> postUpdateMvc
        "commentCreate" -> commentCreateMvc
        "commentUpdate" -> commentUpdateMvc
        "commentDelete" -> commentDeleteMvc
        "commentRead" -> commentReadMvc
        else -> chatMvc
    }

    class Route(val controller: String, val name: String, val request: (String) -> RequestBuilder) {
        override fun toString(): String = name
    }

    companion object {
        /** Expanded into the URI template, so it reaches Spring as the blank segment `%20`. */
        private const val BLANK_SEGMENT = " "
        private const val NON_NUMERIC = "abc"
        private const val COMMENT_BODY = "{\"content\":\"x\"}"

        private const val BAD_REQUEST_BODY =
            "{\"code\":400,\"message\":\"요청 경로의 식별자 형식이 올바르지 않습니다.\",\"data\":null}"

        private val ROUTES = listOf(
            Route("postRead", "게시글 조회") { get("/api/v1/posts/read/{postId}", it) },
            Route("adminPostRead", "관리자 게시글 조회") { get("/api/v1/admin/posts/{postId}", it) },
            Route("postDelete", "게시글 삭제") { delete("/api/v1/posts/{postId}", it) },
            Route("postDelete", "관리자 게시글 삭제") { delete("/api/v1/admin/posts/{postId}", it) },
            Route("postUpdate", "게시글 수정") { multipart(HttpMethod.PUT, "/api/v1/posts/{postId}", it) },
            Route("postUpdate", "관리자 게시글 수정") { multipart(HttpMethod.PUT, "/api/v1/admin/posts/{postId}", it) },
            Route("commentRead", "댓글 조회") { get("/api/v1/comments/posts/read/{postId}", it) },
            Route("commentCreate", "댓글 작성") {
                post("/api/v1/comments/posts/{postId}", it).contentType(MediaType.APPLICATION_JSON).content(COMMENT_BODY)
            },
            Route("commentUpdate", "댓글 수정") {
                put("/api/v1/comments/{commentId}", it).contentType(MediaType.APPLICATION_JSON).content(COMMENT_BODY)
            },
            Route("commentDelete", "댓글 삭제") { delete("/api/v1/comments/{commentId}", it) },
            Route("chat", "기존 채팅방 조회") { get("/api/v1/chats/with/{member}", it) },
        )

        @JvmStatic
        fun routes(): Stream<Arguments> = ROUTES.stream().flatMap { route ->
            Stream.of(BLANK_SEGMENT, NON_NUMERIC).map { Arguments.of(route, it) }
        }

        /** 400 in the feature's `{code, message, data}` envelope, with no use case call. */
        private fun assertBadRequest(result: MvcResult) {
            assertThat(result.response.status).isEqualTo(400)
            assertThat(result.response.getContentAsString(StandardCharsets.UTF_8)).isEqualTo(BAD_REQUEST_BODY)
        }
    }
}
