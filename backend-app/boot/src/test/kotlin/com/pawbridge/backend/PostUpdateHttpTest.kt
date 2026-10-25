package com.pawbridge.backend

import com.pawbridge.backend.boot.configuration.ReadSecurityConfiguration
import com.pawbridge.backend.community.internal.post.application.PostMediaRemover
import com.pawbridge.backend.community.internal.post.application.PostMediaUploader
import com.pawbridge.backend.community.internal.post.application.PostModification
import com.pawbridge.backend.community.internal.post.application.PostReader
import com.pawbridge.backend.community.internal.post.application.PostSnapshot
import com.pawbridge.backend.community.internal.post.application.PostUpload
import com.pawbridge.backend.community.internal.post.application.UpdatePost
import com.pawbridge.backend.community.internal.post.web.PostUpdateController
import com.pawbridge.backend.community.internal.post.web.PostUpdateExceptionHandler
import com.pawbridge.backend.support.argThatValue
import com.pawbridge.backend.support.eqValue
import com.pawbridge.backend.user.api.UserNicknameQuery
import io.jsonwebtoken.Jwts
import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.Matchers
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.ArgumentMatchers.anyList
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.ArgumentMatchers.nullable
import org.mockito.Mockito.`when` as whenever
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.LocalDateTime
import java.util.Date
import java.util.Optional

@WebMvcTest(
    controllers = [PostUpdateController::class],
    excludeAutoConfiguration = [UserDetailsServiceAutoConfiguration::class],
)
@ContextConfiguration(
    classes = [
        PostUpdateController::class,
        PostUpdateExceptionHandler::class,
        ReadSecurityConfiguration::class,
        F3TestInputs.SecurityInputs::class,
        PostUpdateHttpTest.UseCase::class,
    ],
)
class PostUpdateHttpTest {
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

    private lateinit var existing: PostSnapshot
    private val time = LocalDateTime.of(2026, 1, 1, 0, 0)

    @TestConfiguration(proxyBeanMethods = false)
    class UseCase {
        @Bean
        fun updatePost(
            reader: PostReader,
            remover: PostMediaRemover,
            uploader: PostMediaUploader,
            users: UserNicknameQuery,
            writer: PostModification,
        ): UpdatePost {
            return UpdatePost(reader, remover, uploader, users, writer)
        }
    }

    @BeforeEach
    fun givenOwnerAndReturningWriter() {
        existing = snapshot(7, "COMMUNICATION")
        whenever(reader.findById(42)).thenReturn(Optional.of(existing))
        whenever(users.findNickname(anyLong())).thenReturn(Optional.of("작성자"))
        @Suppress("UNCHECKED_CAST")
        val anyImageUrls = List::class.java as Class<List<String?>>
        whenever(
            writer.update(
                anyLong(),
                anyLong(),
                nullable(String::class.java),
                nullable(String::class.java),
                nullable(anyImageUrls),
            ),
        ).thenAnswer { call ->
            val old = existing
            val title = call.getArgument<String?>(2)
            val content = call.getArgument<String?>(3)
            val images = call.getArgument<List<String?>?>(4)
            PostSnapshot(
                old.postId(),
                old.authorId(),
                title ?: old.title(),
                content ?: old.content(),
                old.boardType(),
                images ?: old.imageUrls(),
                time,
                time,
            )
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["ROLE_USER", "ROLE_ADMIN", "ROLE_SHELTER"])
    fun given__verified_owner_with_forged_headers__when__multipart_put__then__return_nine_original_fields_and_verified_author(
        role: String,
    ) {
        mvc.perform(
            multipart(HttpMethod.PUT, "/api/v1/posts/42")
                .param("title", "수정 제목")
                .param("content", "")
                .param("authorId", "999")
                .header("X-User-Id", "999")
                .header("X-User-Role", "ROLE_ADMIN")
                .header("Authorization", "Bearer " + F3TestInputs.token(7, role)),
        )
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.message").value("게시글이 수정되었습니다."))
            .andExpect(jsonPath("$.data.length()").value(9))
            .andExpect(jsonPath("$.data.authorId").value(7))
            .andExpect(jsonPath("$.data.authorNickname").value("작성자"))
            .andExpect(jsonPath("$.data.title").value("수정 제목"))
            .andExpect(jsonPath("$.data.content").value(""))
            .andExpect(header().doesNotExist("Set-Cookie"))
        verify(writer).update(42, 7, "수정 제목", "", null)
        verifyNoInteractions(remover, uploader)
        assertThat(SecurityContextHolder.getContext().authentication).isNull()
    }

    @ParameterizedTest
    @ValueSource(strings = ["MISSING", "PROTECTION", "REPORT", "ADOPTION", "COMMUNICATION"])
    fun given__existing_board__when__put_without_fields__then__do_not_apply_general_post_creation_restrictions(
        board: String,
    ) {
        existing = snapshot(7, board)
        whenever(reader.findById(42)).thenReturn(Optional.of(existing))
        mvc.perform(
            multipart(HttpMethod.PUT, "/api/v1/posts/42")
                .header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_USER")),
        )
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.boardType").value(board))
            .andExpect(jsonPath("$.data.title").value("제목"))
        verify(writer).update(42, 7, null, null, null)
        verifyNoInteractions(remover, uploader)
    }

    @Test
    fun given__one_empty_multipart_file__when__put__then__request_media_replacement_with_an_empty_result() {
        whenever(uploader.upload(anyList())).thenReturn(listOf())
        mvc.perform(
            multipart(HttpMethod.PUT, "/api/v1/posts/42")
                .file(MockMultipartFile("files", "empty.png", "image/png", ByteArray(0)))
                .header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_USER")),
        )
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.imageUrls").isEmpty())
        verify(remover).remove(snapshot(7, "COMMUNICATION").imageUrls())
        verify(uploader).upload(argThatValue<List<PostUpload>> { files -> files.size == 1 && files.first().size == 0L })
        verify(writer).update(42, 7, null, null, listOf())
    }

    @Test
    fun given__long_title_without_legacy_web_validation__when__put__then__pass_input_to_writer_instead_of_adding_400() {
        val title = "x".repeat(201)
        mvc.perform(
            multipart(HttpMethod.PUT, "/api/v1/posts/42")
                .param("title", title)
                .header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_USER")),
        )
            .andExpect(status().isOk())
        verify(writer).update(42, 7, title, null, null)
    }

    @Test
    fun given__foreign_administrator__when__put__then__403_before_media_nickname_or_writer() {
        mvc.perform(
            multipart(HttpMethod.PUT, "/api/v1/posts/42")
                .header("Authorization", "Bearer " + F3TestInputs.token(8, "ROLE_ADMIN")),
        )
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.message").value("게시글에 접근할 권한이 없습니다."))
        verifyNoInteractions(remover, uploader, users, writer)
    }

    @Test
    fun given__missing_post__when__put__then__404_before_media_nickname_or_writer() {
        whenever(reader.findById(42)).thenReturn(Optional.empty())
        mvc.perform(
            multipart(HttpMethod.PUT, "/api/v1/posts/42")
                .header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_USER")),
        )
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.message").value("게시글을 찾을 수 없습니다."))
        verifyNoInteractions(remover, uploader, users, writer)
    }

    @ParameterizedTest
    @ValueSource(strings = ["missing", "invalid", "refresh", "expired"])
    fun given__unverified_token__when__put__then__401_before_all_business_ports(token: String) {
        val request = multipart(HttpMethod.PUT, "/api/v1/posts/42").header("X-User-Id", "7")
        val value: String? = when (token) {
            "invalid" -> "invalid"
            "refresh" -> Jwts.builder()
                .subject("7")
                .expiration(Date(System.currentTimeMillis() + 60_000))
                .signWith(F3TestInputs.KEY)
                .compact()
            "expired" -> Jwts.builder()
                .claim("userId", 7)
                .claim("role", "ROLE_USER")
                .expiration(Date(System.currentTimeMillis() - 60_000))
                .signWith(F3TestInputs.KEY)
                .compact()
            else -> null
        }
        if (value != null) {
            request.header("Authorization", "Bearer $value")
        }
        mvc.perform(request).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value(401))
        verifyNoInteractions(reader, remover, uploader, users, writer)
    }

    @ParameterizedTest
    @ValueSource(strings = ["abc", "9223372036854775808"])
    fun given__malformed_post_id__when__put__then__preserve_legacy_500(id: String) {
        mvc.perform(
            multipart(HttpMethod.PUT, "/api/v1/posts/$id")
                .header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_USER")),
        )
            .andExpect(status().isInternalServerError())
            .andExpect(jsonPath("$.message").value("서버 에러!"))
        verifyNoInteractions(reader, remover, uploader, users, writer)
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "PUT /api/v1/posts", "PUT /api/v1/posts/42/extra", "PUT /api/v1/posts/read/42",
            "PUT /api/v1/admin/posts/42/extra", "PATCH /api/v1/posts/42", "POST /api/v1/posts/42",
            "GET /api/v1/posts/42",
        ],
    )
    fun given__unexposed_path_or_method__when__requesting__then__deny_before_business(request: String) {
        val parts = request.split(" ", limit = 2)
        mvc.perform(
            request(HttpMethod.valueOf(parts[0]), parts[1])
                .header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_USER")),
        )
            .andExpect(status().isForbidden())
        verifyNoInteractions(reader, remover, uploader, users, writer)
    }

    @Test
    fun given__writer_failure_after_authentication__when__put__then__safe_500_and_clear_principal() {
        whenever(writer.update(42, 7, null, null, null)).thenThrow(IllegalStateException("synthetic-private-db-detail"))
        mvc.perform(
            multipart(HttpMethod.PUT, "/api/v1/posts/42")
                .header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_USER")),
        )
            .andExpect(status().isInternalServerError())
            .andExpect(content().string(Matchers.not(Matchers.containsString("synthetic-private-db-detail"))))
        assertThat(SecurityContextHolder.getContext().authentication).isNull()
    }

    private fun snapshot(author: Long, board: String): PostSnapshot {
        return PostSnapshot(42, author, "제목", "본문", board, listOf("https://fixture.invalid/old"), time, time)
    }
}
