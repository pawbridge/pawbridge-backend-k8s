package com.pawbridge.backend

import com.pawbridge.backend.community.internal.comment.web.CommentCreateController
import com.pawbridge.backend.community.internal.comment.web.CommentCreateExceptionHandler
import com.pawbridge.backend.community.internal.comment.web.CommentDeleteController
import com.pawbridge.backend.community.internal.comment.web.CommentDeleteExceptionHandler
import com.pawbridge.backend.community.internal.comment.web.CommentReadController
import com.pawbridge.backend.community.internal.comment.web.CommentReadExceptionHandler
import com.pawbridge.backend.community.internal.comment.web.CommentUpdateController
import com.pawbridge.backend.community.internal.comment.web.CommentUpdateExceptionHandler
import com.pawbridge.backend.community.internal.note.web.NoteBlockCreateController
import com.pawbridge.backend.community.internal.note.web.NoteBlockCreateExceptionHandler
import com.pawbridge.backend.community.internal.note.web.NoteBlockListController
import com.pawbridge.backend.community.internal.note.web.NoteBlockListExceptionHandler
import com.pawbridge.backend.community.internal.note.web.NoteBlockRemoveController
import com.pawbridge.backend.community.internal.note.web.NoteBlockRemoveExceptionHandler
import com.pawbridge.backend.community.internal.note.web.NoteBlockStatusController
import com.pawbridge.backend.community.internal.note.web.NoteBlockStatusExceptionHandler
import com.pawbridge.backend.community.internal.note.web.NoteDeletionController
import com.pawbridge.backend.community.internal.note.web.NoteDeletionExceptionHandler
import com.pawbridge.backend.community.internal.note.web.NoteFavoriteController
import com.pawbridge.backend.community.internal.note.web.NoteFavoriteExceptionHandler
import com.pawbridge.backend.community.internal.note.web.NoteNotificationsController
import com.pawbridge.backend.community.internal.note.web.NoteNotificationsExceptionHandler
import com.pawbridge.backend.community.internal.note.web.NoteReadMutationController
import com.pawbridge.backend.community.internal.note.web.NoteReadMutationExceptionHandler
import com.pawbridge.backend.community.internal.note.web.NoteRecipientController
import com.pawbridge.backend.community.internal.note.web.NoteRecipientExceptionHandler
import com.pawbridge.backend.community.internal.note.web.NoteStreamController
import com.pawbridge.backend.community.internal.note.web.NoteStreamExceptionHandler
import com.pawbridge.backend.community.internal.note.web.PrivateNoteReadController
import com.pawbridge.backend.community.internal.note.web.PrivateNoteReadExceptionHandler
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
import com.pawbridge.backend.store.internal.payment.web.MockOrderPaymentController
import com.pawbridge.backend.store.internal.payment.web.MockOrderPaymentExceptionHandler
import com.pawbridge.backend.user.internal.adminupdate.web.AdminUserUpdateController
import com.pawbridge.backend.user.internal.adminupdate.web.AdminUserUpdateExceptionHandler
import com.pawbridge.backend.user.internal.animalactivity.web.UserAnimalActivitiesController
import com.pawbridge.backend.user.internal.animalactivity.web.UserAnimalActivitiesExceptionHandler
import com.pawbridge.backend.user.internal.deletion.web.AdminUserDeletionController
import com.pawbridge.backend.user.internal.deletion.web.AdminUserDeletionExceptionHandler
import com.pawbridge.backend.user.internal.favorite.web.FavoriteStatusController
import com.pawbridge.backend.user.internal.favorite.web.FavoriteStatusExceptionHandler
import com.pawbridge.backend.user.internal.profile.web.AdminUserDetailController
import com.pawbridge.backend.user.internal.profile.web.UserProfileController
import com.pawbridge.backend.user.internal.profile.web.UserProfileExceptionHandler
import com.pawbridge.backend.user.internal.statistics.web.TotalUserCountController
import com.pawbridge.backend.user.internal.statistics.web.TotalUserCountExceptionHandler
import com.fasterxml.jackson.databind.ObjectMapper
import io.mockk.clearAllMocks
import io.mockk.confirmVerified
import io.mockk.mockkClass
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import org.springframework.beans.factory.support.BeanDefinitionRegistry
import org.springframework.beans.factory.support.GenericBeanDefinition
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.ImportBeanDefinitionRegistrar
import org.springframework.core.type.AnnotationMetadata
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import java.time.Clock
import java.util.function.Supplier
import kotlin.reflect.KClass
import kotlin.reflect.full.primaryConstructor

/**
 * Path variables that cannot be converted, for every endpoint that has one. The production chain and the real
 * controllers and exception handlers are assembled once; every collaborator behind them is a relaxed mock, and a
 * rejected request must not touch any of them. Each endpoint keeps the status and body its own exception handler
 * answers with, so the differences between features stay in the table.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PathVariableHttpTest {
    private val mvc: MockMvc = SecurityChainFixture.mvcWith(Everything::class.java)

    @BeforeEach
    fun forgetEarlierCalls() {
        clearAllMocks()
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("rows")
    fun `변환할 수 없는 경로 변수가 주어졌을 때__엔드포인트를 요청하면__업무 호출 없이 그 기능의 400 본문을 준다`(row: Row) {
        // given
        val request = row.build()

        // when
        val result = mvc.perform(request).andReturn().response

        // then
        assertThat(result.status).describedAs("%s", row).isEqualTo(row.status)
        assertThat(result.contentAsString).describedAs("%s", row).isEqualTo(row.body)
        assertThat(result.getHeader("Set-Cookie")).isNull()
        assertThat(result.getHeader("Cache-Control")).describedAs("%s", row).isEqualTo(row.cacheControl)
        confirmVerified(*RelaxedBeans.mocks.toTypedArray())
    }

    @Test
    fun `숫자가 아닌 주문 ID가 주어졌을 때__모의 결제를 요청하면__업무 호출 없이 타입 오류 목록을 준다`() {
        // given
        val request = request(HttpMethod.POST, "/api/v1/orders/abc/payment")
            .header("Authorization", SecurityChainFixture.bearer(7, "ROLE_USER"))

        // when
        val result = mvc.perform(request).andReturn().response

        // then
        val json = ObjectMapper().readTree(result.contentAsByteArray)
        assertThat(result.status).isEqualTo(400)
        assertThat(json.path("message").asText()).isEqualTo("Invalid Type Value")
        assertThat(json.path("code").asText()).isEqualTo("C005")
        assertThat(json.path("errors").get(0).path("field").asText()).isEqualTo("orderId")
        assertThat(json.path("errors").get(0).path("value").asText()).isEqualTo("abc")
        assertThat(json.path("errors").get(0).path("reason").asText()).isEqualTo("typeMismatch")
        confirmVerified(*RelaxedBeans.mocks.toTypedArray())
    }

    @AfterEach
    fun noLeftoverIdentity() {
        assertThat(org.springframework.security.core.context.SecurityContextHolder.getContext().authentication).isNull()
    }

    data class Row(
        val method: String,
        val path: String,
        val role: String?,
        val status: Int,
        val body: String,
        val cacheControl: String = SecurityChainFixture.SECURITY_CACHE,
        val json: String? = null,
        val multipart: Boolean = false,
    ) {
        fun build(): MockHttpServletRequestBuilder {
            val builder = if (multipart) multipart(HttpMethod.valueOf(method), path) else request(HttpMethod.valueOf(method), path)
            if (role != null) {
                builder.header("Authorization", SecurityChainFixture.bearer(7, role))
            }
            if (json != null) {
                builder.contentType(MediaType.APPLICATION_JSON).content(json)
            }
            return builder
        }

        override fun toString(): String = "$method $path"
    }

    @Configuration(proxyBeanMethods = false)
    @Import(Registrar::class)
    class Everything

    class Registrar : ImportBeanDefinitionRegistrar {
        override fun registerBeanDefinitions(metadata: AnnotationMetadata, registry: BeanDefinitionRegistry) {
            (CONTROLLERS + HANDLERS).forEach { type ->
                val definition = GenericBeanDefinition()
                definition.setBeanClass(type.java)
                definition.instanceSupplier = Supplier { RelaxedBeans.create(type) }
                registry.registerBeanDefinition(type.java.name, definition)
            }
        }
    }

    /** Builds a class with a relaxed mock for each constructor parameter and records the mocks. */
    object RelaxedBeans {
        val mocks = mutableListOf<Any>()

        fun <T : Any> create(type: KClass<T>): T {
            val constructor = type.primaryConstructor ?: type.constructors.first()
            val arguments = constructor.parameters.map { parameter -> argument(parameter.type.classifier as KClass<*>) }
            return constructor.call(*arguments.toTypedArray())
        }

        private fun argument(type: KClass<*>): Any {
            return when (type) {
                String::class -> "synthetic"
                ObjectMapper::class -> ObjectMapper()
                Clock::class -> Clock.systemUTC()
                else -> mockkClass(type, relaxed = true).also { mocks.add(it) }
            }
        }
    }

    companion object {
        private val CONTROLLERS: List<KClass<*>> = listOf(
            AdminPostReadController::class, AdminPostUpdateController::class, AdminPostDeleteController::class,
            PostReadController::class, PostDeleteController::class, PostUpdateController::class,
            AdminUserDeletionController::class, AdminUserDetailController::class, AdminUserUpdateController::class,
            CommentCreateController::class, CommentReadController::class, CommentDeleteController::class,
            CommentUpdateController::class, FavoriteStatusController::class, UserProfileController::class,
            NoteBlockCreateController::class, NoteBlockRemoveController::class, NoteBlockStatusController::class,
            NoteBlockListController::class, NoteDeletionController::class, NoteFavoriteController::class,
            NoteReadMutationController::class, NoteStreamController::class, NoteRecipientController::class,
            NoteNotificationsController::class, PrivateNoteReadController::class, TotalUserCountController::class,
            UserAnimalActivitiesController::class, MockOrderPaymentController::class,
        )

        private val HANDLERS: List<KClass<*>> = listOf(
            AdminPostReadExceptionHandler::class, PostReadExceptionHandler::class, PostDeleteExceptionHandler::class,
            PostUpdateExceptionHandler::class, AdminUserDeletionExceptionHandler::class,
            UserProfileExceptionHandler::class, AdminUserUpdateExceptionHandler::class,
            CommentCreateExceptionHandler::class, CommentReadExceptionHandler::class,
            CommentDeleteExceptionHandler::class, CommentUpdateExceptionHandler::class,
            FavoriteStatusExceptionHandler::class, NoteBlockCreateExceptionHandler::class,
            NoteBlockRemoveExceptionHandler::class, NoteBlockStatusExceptionHandler::class,
            NoteBlockListExceptionHandler::class, NoteDeletionExceptionHandler::class,
            NoteFavoriteExceptionHandler::class, NoteReadMutationExceptionHandler::class,
            NoteStreamExceptionHandler::class, NoteRecipientExceptionHandler::class,
            NoteNotificationsExceptionHandler::class, PrivateNoteReadExceptionHandler::class,
            TotalUserCountExceptionHandler::class, UserAnimalActivitiesExceptionHandler::class,
            MockOrderPaymentExceptionHandler::class,
        )

        private const val IDENTIFIER_400 =
            "{\"code\":400,\"message\":\"요청 경로의 식별자 형식이 올바르지 않습니다.\",\"data\":null}"
        private const val NOTE_400 = "{\"message\":\"요청 형식과 로그인 정보를 확인해 주세요.\"}"
        private const val INTERNAL_500 = "{\"code\":500,\"message\":\"서버 에러!\",\"data\":null}"
        private const val NO_STORE = "no-store"
        private const val OVERFLOW = "9223372036854775808"

        @JvmStatic
        fun rows(): List<Row> = buildList {
            fun identifier(method: String, template: String, values: List<String>, role: String?, json: String? = null, multipart: Boolean = false) {
                values.forEach { value ->
                    add(Row(method, template.replace("{x}", value), role, 400, IDENTIFIER_400, json = json, multipart = multipart))
                }
            }
            fun note(method: String, template: String, values: List<String>, json: String? = null) {
                values.forEach { value ->
                    add(Row(method, template.replace("{x}", value), "ROLE_USER", 400, NOTE_400, NO_STORE, json))
                }
            }

            val longs = listOf("abc", OVERFLOW)
            // community: posts and comments
            identifier("PUT", "/api/v1/admin/posts/{x}", listOf("not-a-long"), "ROLE_ADMIN", multipart = true)
            identifier("DELETE", "/api/v1/admin/posts/{x}", listOf("not-a-long"), "ROLE_ADMIN")
            identifier("GET", "/api/v1/admin/posts/{x}", listOf("no-number", OVERFLOW, "read", "search"), "ROLE_ADMIN")
            identifier("PUT", "/api/v1/posts/{x}", longs, "ROLE_USER", multipart = true)
            identifier("DELETE", "/api/v1/posts/{x}", longs, "ROLE_USER")
            identifier("GET", "/api/v1/posts/read/{x}", listOf("abc", "search", OVERFLOW), null)
            identifier("POST", "/api/v1/comments/posts/{x}", longs, "ROLE_USER", json = "{\"content\":\"댓글\"}")
            identifier("PUT", "/api/v1/comments/{x}", longs, "ROLE_USER", json = "{\"content\":\"수정\"}")
            identifier("DELETE", "/api/v1/comments/{x}", longs, "ROLE_USER")
            identifier("GET", "/api/v1/comments/posts/read/{x}", longs, null)
            // community: notes
            note("PUT", "/api/v1/notes/blocks/{x}", listOf("not-a-long", OVERFLOW, "status"))
            note("DELETE", "/api/v1/notes/blocks/{x}", listOf("not-a-long", OVERFLOW, "status"))
            note("DELETE", "/api/v1/notes/{x}", listOf("not-a-uuid", "blocks", "notifications"))
            note("PUT", "/api/v1/notes/{x}/favorite", listOf("not-a-uuid"), json = "{\"favorite\":true}")
            note("PUT", "/api/v1/notes/{x}/read", listOf("not-a-uuid"))
            note("GET", "/api/v1/notes/recipients/{x}", longs)
            note("GET", "/api/v1/notes/{x}", listOf("not-a-uuid"))
            // user
            identifier("DELETE", "/api/v1/admin/users/{x}", listOf("not-a-number"), "ROLE_ADMIN")
            identifier("GET", "/api/v1/admin/users/{x}", listOf("not-a-number"), "ROLE_ADMIN")
            identifier("PUT", "/api/v1/admin/users/{x}", listOf("bad"), "ROLE_ADMIN", json = "{}")
            identifier("POST", "/api/v1/favorites/{x}", listOf("bad"), "ROLE_USER")
            listOf("abc", "1.5", OVERFLOW, "-9223372036854775809").forEach { value ->
                add(Row("GET", "/api/v1/favorites/$value/check", "ROLE_USER", 500, INTERNAL_500))
            }
        }
    }
}
