package com.pawbridge.backend

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.pawbridge.backend.boot.configuration.ReadSecurityConfiguration
import com.pawbridge.backend.community.internal.post.application.DeletePost
import com.pawbridge.backend.community.internal.post.application.PostDeletion
import com.pawbridge.backend.community.internal.post.application.PostMediaRemover
import com.pawbridge.backend.community.internal.post.application.PostMediaUploader
import com.pawbridge.backend.community.internal.post.application.PostModification
import com.pawbridge.backend.community.internal.post.application.PostReader
import com.pawbridge.backend.community.internal.post.application.PostSnapshot
import com.pawbridge.backend.community.internal.post.application.PostUpload
import com.pawbridge.backend.community.internal.post.application.UpdatePost
import com.pawbridge.backend.community.internal.post.web.AdminPostDeleteController
import com.pawbridge.backend.community.internal.post.web.AdminPostUpdateController
import com.pawbridge.backend.community.internal.post.web.PostDeleteExceptionHandler
import com.pawbridge.backend.community.internal.post.web.PostUpdateExceptionHandler
import com.pawbridge.backend.support.eqValue
import com.pawbridge.backend.user.api.UserNicknameQuery
import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.Matchers
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.ArgumentMatchers.anyList
import org.mockito.ArgumentMatchers.argThat
import org.mockito.ArgumentMatchers.nullable
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.inOrder
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.verifyNoMoreInteractions
import org.mockito.Mockito.`when` as whenever
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.http.HttpMethod
import org.springframework.mock.web.MockMultipartFile
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.LocalDateTime
import java.util.Optional

@WebMvcTest(
    controllers = [AdminPostUpdateController::class, AdminPostDeleteController::class],
    excludeAutoConfiguration = [UserDetailsServiceAutoConfiguration::class],
)
@ContextConfiguration(
    classes = [
        AdminPostUpdateController::class,
        AdminPostDeleteController::class,
        PostUpdateExceptionHandler::class,
        PostDeleteExceptionHandler::class,
        ReadSecurityConfiguration::class,
        F3TestInputs.SecurityInputs::class,
        AdminPostMutationHttpTest.UseCases::class,
    ],
)
class AdminPostMutationHttpTest {
    @Autowired
    lateinit var mvc: MockMvc

    @MockitoBean
    lateinit var reader: PostReader

    @MockitoBean
    lateinit var remover: PostMediaRemover

    @MockitoBean
    lateinit var uploader: PostMediaUploader

    @MockitoBean
    lateinit var users: UserNicknameQuery

    @MockitoBean
    lateinit var writer: PostModification

    @MockitoBean
    lateinit var deletion: PostDeletion

    private val time = LocalDateTime.of(2026, 10, 20, 12, 0)
    private lateinit var existing: PostSnapshot

    @TestConfiguration(proxyBeanMethods = false)
    class UseCases {
        @Bean
        fun update(
            reader: PostReader,
            remover: PostMediaRemover,
            uploader: PostMediaUploader,
            users: UserNicknameQuery,
            writer: PostModification,
        ): UpdatePost {
            return UpdatePost(reader, remover, uploader, users, writer)
        }

        @Bean
        fun delete(reader: PostReader, remover: PostMediaRemover, deletion: PostDeletion): DeletePost {
            return DeletePost(reader, remover, deletion)
        }
    }

    @BeforeEach
    fun givenAnotherAuthorsExistingPost() {
        existing = PostSnapshot(42, 7, "title", "content", "MISSING", listOf("https://fixture.invalid/old"), time, time)
        whenever(reader.findById(42)).thenReturn(Optional.of(existing))
        whenever(users.findNickname(7)).thenReturn(Optional.of("작성자"))
        @Suppress("UNCHECKED_CAST")
        val anyImageUrls = List::class.java as Class<List<String?>>
        whenever(
            writer.update(
                eqValue(42L),
                eqValue(7L),
                nullable(String::class.java),
                nullable(String::class.java),
                nullable(anyImageUrls),
            ),
        ).thenAnswer { call ->
            PostSnapshot(
                42,
                7,
                call.getArgument<String?>(2) ?: existing.title(),
                call.getArgument<String?>(3) ?: existing.content(),
                existing.boardType(),
                call.getArgument<List<String?>?>(4) ?: existing.imageUrls(),
                time,
                time,
            )
        }
    }

    @Test
    fun given__verified_admin_and_foreign_author__when__partial_put__then__keep_original_author_and_nine_fields_while_ignoring_forged_identity() {
        mvc.perform(
            multipart(HttpMethod.PUT, "$ROOT/42")
                .param("title", "")
                .param("authorId", "99")
                .header("X-User-Id", "99")
                .header("X-User-Role", "ROLE_USER")
                .header("Authorization", admin()),
        )
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.message").value("게시글이 수정되었습니다."))
            .andExpect(jsonPath("$.data.length()").value(9))
            .andExpect(jsonPath("$.data.authorId").value(7))
            .andExpect(jsonPath("$.data.authorNickname").value("작성자"))
            .andExpect(jsonPath("$.data.title").value(""))
            .andExpect(jsonPath("$.data.content").value("content"))
            .andExpect(jsonPath("$.data.boardType").value("MISSING"))
            .andExpect(header().doesNotExist("Set-Cookie"))
        verify(writer).update(42, 7, "", null, null)
        verify(users).findNickname(7)
        verifyNoInteractions(remover, uploader, deletion)
        assertThat(SecurityContextHolder.getContext().authentication).isNull()
    }

    @Test
    fun given__one_empty_file__when__admin_puts__then__replace_media_with_empty_list_using_existing_order() {
        whenever(uploader.upload(anyList())).thenReturn(listOf())
        mvc.perform(
            multipart(HttpMethod.PUT, "$ROOT/42")
                .file(MockMultipartFile("files", "empty.png", "image/png", ByteArray(0)))
                .header("Authorization", admin()),
        )
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.imageUrls").isEmpty())
        val order = inOrder(reader, remover, uploader, users, writer)
        order.verify(reader).findById(42)
        order.verify(remover).remove(existing.imageUrls())
        order.verify(uploader).upload(argThat<List<PostUpload>> { files -> files.size == 1 && files.first().size == 0L })
        order.verify(users).findNickname(7)
        order.verify(writer).update(42, 7, null, null, listOf())
    }

    @Test
    fun given__upload_failure_after_old_media_attempt__when__admin_puts__then__return_safe_500_without_db_or_compensation() {
        whenever(uploader.upload(anyList())).thenThrow(IllegalStateException(PRIVATE))
        mvc.perform(
            multipart(HttpMethod.PUT, "$ROOT/42")
                .file(MockMultipartFile("files", "a.png", "image/png", byteArrayOf(1)))
                .header("Authorization", admin()),
        )
            .andExpect(status().isInternalServerError())
            .andExpect(jsonPath("$.message").value("서버 에러!"))
            .andExpect(content().string(Matchers.not(Matchers.containsString(PRIVATE))))
        verify(remover).remove(existing.imageUrls())
        verifyNoMoreInteractions(remover)
        verifyNoInteractions(users, writer, deletion)
    }

    @Test
    fun given__foreign_post_then_already_deleted__when__admin_deletes_twice__then__first_returns_empty_data_and_second_404_without_repeated_effects() {
        mvc.perform(delete("$ROOT/42").header("Authorization", admin()))
            .andExpect(status().isOk())
            .andExpect(content().json("{\"code\":200,\"message\":\"게시글이 삭제되었습니다.\",\"data\":null}"))
            .andExpect(header().doesNotExist("Set-Cookie"))
        whenever(reader.findById(42)).thenReturn(Optional.empty())
        mvc.perform(delete("$ROOT/42").header("Authorization", admin())).andExpect(status().isNotFound())
        val order = inOrder(remover, deletion)
        order.verify(remover).remove(existing.imageUrls())
        order.verify(deletion).delete(42, 7)
        order.verifyNoMoreInteractions()
        verifyNoInteractions(uploader, users, writer)
    }

    @ParameterizedTest
    @CsvSource("PUT,missing,401", "DELETE,missing,401", "PUT,ROLE_USER,403", "DELETE,ROLE_USER,403", "PUT,ROLE_SHELTER,403", "DELETE,ROLE_SHELTER,403")
    fun given__missing_or_non_admin_identity_and_forged_admin_header__when__requesting_bad_id__then__deny_before_binding_or_effects(
        method: String,
        role: String,
        status: Int,
    ) {
        val request = mutation(method, "bad-id").header("X-User-Role", "ROLE_ADMIN").header("X-User-Id", "99")
        if (role != "missing") {
            request.header("Authorization", "Bearer " + F3TestInputs.token(99, role))
        }
        mvc.perform(request).andExpect(status().`is`(status))
        verifyNoInteractions(reader, remover, uploader, users, writer, deletion)
    }

    @ParameterizedTest
    @CsvSource("PUT,missing,404", "DELETE,missing,404", "PUT,db,500", "DELETE,db,500")
    fun given__missing_or_late_writer_failure__when__admin_mutates__then__reuse_scoped_response_and_safe_metadata_only_log(
        method: String,
        mode: String,
        expected: Int,
    ) {
        if (mode == "missing") {
            whenever(reader.findById(42)).thenReturn(Optional.empty())
        } else if (method == "PUT") {
            whenever(writer.update(42, 7, null, null, null)).thenThrow(IllegalStateException(PRIVATE))
        } else {
            doThrow(IllegalStateException(PRIVATE)).`when`(deletion).delete(42, 7)
        }
        val logger = LoggerFactory.getLogger(
            if (method == "PUT") PostUpdateExceptionHandler::class.java else PostDeleteExceptionHandler::class.java,
        ) as Logger
        val events = ListAppender<ILoggingEvent>()
        events.start()
        logger.addAppender(events)
        try {
            mvc.perform(mutation(method, "42").header("Authorization", admin()))
                .andExpect(status().`is`(expected))
                .andExpect(jsonPath("$.message").value(if (expected == 404) "게시글을 찾을 수 없습니다." else "서버 에러!"))
                .andExpect(content().string(Matchers.not(Matchers.containsString(PRIVATE))))
            assertThat(events.list).allSatisfy { event ->
                assertThat(event.formattedMessage).doesNotContain(PRIVATE)
                assertThat(event.throwableProxy).isNull()
            }
            if (expected == 500) {
                assertThat(events.list).anySatisfy { event ->
                    assertThat(event.formattedMessage).contains("java.lang.IllegalStateException")
                }
            } else {
                verifyNoInteractions(remover, uploader, users, writer, deletion)
            }
        } finally {
            logger.detachAppender(events)
            events.stop()
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["PUT", "DELETE"])
    fun given__admin_and_invalid_numeric_path__when__binding__then__preserve_original_500_before_business(method: String) {
        mvc.perform(mutation(method, "not-a-long").header("Authorization", admin()))
            .andExpect(status().isInternalServerError())
            .andExpect(jsonPath("$.message").value("서버 에러!"))
        verifyNoInteractions(reader, remover, uploader, users, writer, deletion)
    }

    @ParameterizedTest
    @ValueSource(strings = ["PUT", "DELETE"])
    fun given__admin_on_deeper_unported_path__when__requesting__then__retain_default_deny(method: String) {
        mvc.perform(mutation(method, "42/extra").header("Authorization", admin())).andExpect(status().isForbidden())
        verifyNoInteractions(reader, remover, uploader, users, writer, deletion)
    }

    companion object {
        private const val ROOT = "/api/v1/admin/posts"
        private const val PRIVATE = "synthetic-private-upload-or-db-value"

        private fun mutation(method: String, id: String): MockHttpServletRequestBuilder {
            return if (method == "PUT") multipart(HttpMethod.PUT, "$ROOT/$id") else delete("$ROOT/$id")
        }

        private fun admin(): String = "Bearer " + F3TestInputs.token(99, "ROLE_ADMIN")
    }
}
