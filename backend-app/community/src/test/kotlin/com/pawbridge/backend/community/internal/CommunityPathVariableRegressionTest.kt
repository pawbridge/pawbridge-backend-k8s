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
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.nio.charset.StandardCharsets

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

    // PostReadController

    @Test
    fun `공백 ID일 때__게시글을 조회하면__400을 응답한다`() {
        // when
        val result = postReadMvc.perform(get("/api/v1/posts/read/{postId}", BLANK_SEGMENT)).andReturn()

        // then
        assertBadRequest(result)
        verify(exactly = 0) { readPost.read(any()) }
    }

    @Test
    fun `숫자가 아닌 ID일 때__게시글을 조회하면__400을 응답한다`() {
        // when
        val result = postReadMvc.perform(get("/api/v1/posts/read/{postId}", NON_NUMERIC)).andReturn()

        // then
        assertBadRequest(result)
        verify(exactly = 0) { readPost.read(any()) }
    }

    // AdminPostReadController

    @Test
    fun `공백 ID일 때__관리자가 게시글을 조회하면__400을 응답한다`() {
        // when
        val result = adminPostReadMvc.perform(get("/api/v1/admin/posts/{postId}", BLANK_SEGMENT)).andReturn()

        // then
        assertBadRequest(result)
        verify(exactly = 0) { readPost.read(any()) }
    }

    // PostDeleteController, AdminPostDeleteController

    @Test
    fun `공백 ID일 때__게시글을 삭제하면__400을 응답한다`() {
        // when
        val result = postDeleteMvc.perform(delete("/api/v1/posts/{postId}", BLANK_SEGMENT)).andReturn()

        // then
        assertBadRequest(result)
        verify(exactly = 0) { deletePost.delete(any(), any()) }
    }

    @Test
    fun `공백 ID일 때__관리자가 게시글을 삭제하면__400을 응답한다`() {
        // when
        val result = postDeleteMvc.perform(delete("/api/v1/admin/posts/{postId}", BLANK_SEGMENT)).andReturn()

        // then
        assertBadRequest(result)
        verify(exactly = 0) { deletePost.deleteAsAdmin(any()) }
    }

    // PostUpdateController, AdminPostUpdateController

    @Test
    fun `공백 ID일 때__게시글을 수정하면__400을 응답한다`() {
        // when
        val result = postUpdateMvc.perform(multipart(HttpMethod.PUT, "/api/v1/posts/{postId}", BLANK_SEGMENT))
            .andReturn()

        // then
        assertBadRequest(result)
        verify(exactly = 0) { updatePost.update(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `공백 ID일 때__관리자가 게시글을 수정하면__400을 응답한다`() {
        // when
        val result = postUpdateMvc.perform(multipart(HttpMethod.PUT, "/api/v1/admin/posts/{postId}", BLANK_SEGMENT))
            .andReturn()

        // then
        assertBadRequest(result)
        verify(exactly = 0) { updatePost.updateAsAdmin(any(), any(), any(), any()) }
    }

    // Comment controllers

    @Test
    fun `공백 ID일 때__댓글을 조회하면__400을 응답한다`() {
        // when
        val result = commentReadMvc.perform(get("/api/v1/comments/posts/read/{postId}", BLANK_SEGMENT)).andReturn()

        // then
        assertBadRequest(result)
        verify(exactly = 0) { listComments.read(any()) }
    }

    @Test
    fun `공백 ID일 때__댓글을 작성하면__400을 응답한다`() {
        // when
        val result = commentCreateMvc.perform(
            post("/api/v1/comments/posts/{postId}", BLANK_SEGMENT)
                .contentType(MediaType.APPLICATION_JSON)
                .content(COMMENT_BODY),
        ).andReturn()

        // then
        assertBadRequest(result)
        verify(exactly = 0) { createComment.create(any(), any(), any()) }
    }

    @Test
    fun `공백 ID일 때__댓글을 수정하면__400을 응답한다`() {
        // when
        val result = commentUpdateMvc.perform(
            put("/api/v1/comments/{commentId}", BLANK_SEGMENT)
                .contentType(MediaType.APPLICATION_JSON)
                .content(COMMENT_BODY),
        ).andReturn()

        // then
        assertBadRequest(result)
        verify(exactly = 0) { updateComment.update(any(), any(), any()) }
    }

    @Test
    fun `공백 ID일 때__댓글을 삭제하면__400을 응답한다`() {
        // when
        val result = commentDeleteMvc.perform(delete("/api/v1/comments/{commentId}", BLANK_SEGMENT)).andReturn()

        // then
        assertBadRequest(result)
        verify(exactly = 0) { commentDeletion.delete(any(), any()) }
    }

    // MemberChatController

    @Test
    fun `공백 회원 ID일 때__기존 채팅방을 조회하면__400을 응답한다`() {
        // when
        val result = chatMvc.perform(get("/api/v1/chats/with/{member}", BLANK_SEGMENT)).andReturn()

        // then
        assertBadRequest(result)
        verify(exactly = 0) { chats.existingRoom(any(), any()) }
    }

    @Test
    fun `숫자가 아닌 회원 ID일 때__기존 채팅방을 조회하면__400을 응답한다`() {
        // when
        val result = chatMvc.perform(get("/api/v1/chats/with/{member}", NON_NUMERIC)).andReturn()

        // then
        assertBadRequest(result)
        verify(exactly = 0) { chats.existingRoom(any(), any()) }
    }

    companion object {
        /** Expanded into the URI template, so it reaches Spring as the blank segment `%20`. */
        private const val BLANK_SEGMENT = " "
        private const val NON_NUMERIC = "abc"
        private const val COMMENT_BODY = "{\"content\":\"x\"}"

        private const val BAD_REQUEST_BODY =
            "{\"code\":400,\"message\":\"요청 경로의 식별자 형식이 올바르지 않습니다.\",\"data\":null}"

        /** 400 in the feature's `{code, message, data}` envelope, with no use case call. */
        private fun assertBadRequest(result: MvcResult) {
            assertThat(result.response.status).isEqualTo(400)
            assertThat(result.response.getContentAsString(StandardCharsets.UTF_8)).isEqualTo(BAD_REQUEST_BODY)
        }
    }
}
