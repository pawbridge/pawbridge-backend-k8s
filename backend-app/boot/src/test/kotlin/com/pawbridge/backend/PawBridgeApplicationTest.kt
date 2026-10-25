package com.pawbridge.backend

import com.pawbridge.backend.animal.internal.shelterdirectory.infrastructure.ShelterDirectoryCollector
import com.pawbridge.backend.boot.configuration.NoteExpiryConfiguration
import com.pawbridge.backend.boot.configuration.PostWriteSettings
import com.pawbridge.backend.boot.configuration.ReadDatabaseSettings
import com.pawbridge.backend.boot.configuration.VisitReadConfiguration
import com.pawbridge.backend.community.api.ContactMemberCleanup
import com.pawbridge.backend.community.internal.comment.application.CommentDeletion
import com.pawbridge.backend.community.internal.comment.application.CommentListReader
import com.pawbridge.backend.community.internal.comment.application.CommentModification
import com.pawbridge.backend.community.internal.comment.application.CommentReader
import com.pawbridge.backend.community.internal.comment.application.CommentWriter
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
import com.pawbridge.backend.community.internal.homevideo.application.HomeVideoManagement
import com.pawbridge.backend.community.internal.homevideo.application.HomeVideoReader
import com.pawbridge.backend.community.internal.homevideo.application.ReadHomeVideos
import com.pawbridge.backend.community.internal.homevideo.infrastructure.YouTubeVideoClient
import com.pawbridge.backend.community.internal.homevideo.web.HomeVideoController
import com.pawbridge.backend.community.internal.homevideo.web.HomeVideoExceptionHandler
import com.pawbridge.backend.community.internal.note.application.ContactMemberCleanupStore
import com.pawbridge.backend.community.internal.note.application.CreateNoteBlock
import com.pawbridge.backend.community.internal.note.application.DeletePrivateNote
import com.pawbridge.backend.community.internal.note.application.ListNoteBlocks
import com.pawbridge.backend.community.internal.note.application.ListPrivateNotes
import com.pawbridge.backend.community.internal.note.application.MarkPrivateNoteRead
import com.pawbridge.backend.community.internal.note.application.NoteBlockCreation
import com.pawbridge.backend.community.internal.note.application.NoteBlockListReader
import com.pawbridge.backend.community.internal.note.application.NoteBlockReader
import com.pawbridge.backend.community.internal.note.application.NoteBlockRemoval
import com.pawbridge.backend.community.internal.note.application.NoteBlockStatusReader
import com.pawbridge.backend.community.internal.note.application.NoteChangeHints
import com.pawbridge.backend.community.internal.note.application.NoteDeletion
import com.pawbridge.backend.community.internal.note.application.NoteDeliveryHints
import com.pawbridge.backend.community.internal.note.application.NoteExpiry
import com.pawbridge.backend.community.internal.note.application.NoteFavoriteMutation
import com.pawbridge.backend.community.internal.note.application.NoteNotificationReader
import com.pawbridge.backend.community.internal.note.application.NoteReadMutation
import com.pawbridge.backend.community.internal.note.application.NoteSendStore
import com.pawbridge.backend.community.internal.note.application.PrivateNoteListReader
import com.pawbridge.backend.community.internal.note.application.PrivateNoteReader
import com.pawbridge.backend.community.internal.note.application.ReadBlockedAuthors
import com.pawbridge.backend.community.internal.note.application.ReadNoteNotifications
import com.pawbridge.backend.community.internal.note.application.ReadNoteRecipient
import com.pawbridge.backend.community.internal.note.application.ReadPrivateNote
import com.pawbridge.backend.community.internal.note.application.RemoveNoteBlock
import com.pawbridge.backend.community.internal.note.application.RequireActiveNoteStream
import com.pawbridge.backend.community.internal.note.application.SendPrivateNote
import com.pawbridge.backend.community.internal.note.application.SetPrivateNoteFavorite
import com.pawbridge.backend.community.internal.note.infrastructure.JdbcNoteBlockCreation
import com.pawbridge.backend.community.internal.note.infrastructure.JdbcNoteBlockRemoval
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
import com.pawbridge.backend.community.internal.note.web.NoteSendController
import com.pawbridge.backend.community.internal.note.web.NoteSendExceptionHandler
import com.pawbridge.backend.community.internal.note.web.NoteStreamController
import com.pawbridge.backend.community.internal.note.web.NoteStreamExceptionHandler
import com.pawbridge.backend.community.internal.note.web.PrivateNoteListController
import com.pawbridge.backend.community.internal.note.web.PrivateNoteListExceptionHandler
import com.pawbridge.backend.community.internal.note.web.PrivateNoteReadController
import com.pawbridge.backend.community.internal.note.web.PrivateNoteReadExceptionHandler
import com.pawbridge.backend.community.internal.note.web.PrivateNoteStreamRegistry
import com.pawbridge.backend.community.internal.post.application.AdminPostStatisticsQuery
import com.pawbridge.backend.community.internal.post.application.DeletePost
import com.pawbridge.backend.community.internal.post.application.ListPosts
import com.pawbridge.backend.community.internal.post.application.PostDeletion
import com.pawbridge.backend.community.internal.post.application.PostListReader
import com.pawbridge.backend.community.internal.post.application.PostMediaRemover
import com.pawbridge.backend.community.internal.post.application.PostModification
import com.pawbridge.backend.community.internal.post.application.PostPageReader
import com.pawbridge.backend.community.internal.post.application.PostReader
import com.pawbridge.backend.community.internal.post.application.PostSearchReader
import com.pawbridge.backend.community.internal.post.application.ReadAdminPosts
import com.pawbridge.backend.community.internal.post.application.SearchPosts
import com.pawbridge.backend.community.internal.post.application.UpdatePost
import com.pawbridge.backend.community.internal.post.infrastructure.JdbcPostSearchRebuild
import com.pawbridge.backend.community.internal.post.web.AdminPostDeleteController
import com.pawbridge.backend.community.internal.post.web.AdminPostReadController
import com.pawbridge.backend.community.internal.post.web.AdminPostStatisticsController
import com.pawbridge.backend.community.internal.post.web.AdminPostStatisticsExceptionHandler
import com.pawbridge.backend.community.internal.post.web.AdminPostUpdateController
import com.pawbridge.backend.community.internal.post.web.PostDeleteController
import com.pawbridge.backend.community.internal.post.web.PostReadController
import com.pawbridge.backend.community.internal.post.web.PostSearchController
import com.pawbridge.backend.community.internal.post.web.PostSearchExceptionHandler
import com.pawbridge.backend.community.internal.post.web.PostUpdateController
import com.pawbridge.backend.community.internal.report.application.AnimalReports
import com.pawbridge.backend.community.internal.report.web.AnimalReportController
import com.pawbridge.backend.community.internal.report.web.AnimalReportExceptionHandler
import com.pawbridge.backend.community.internal.visit.application.ReadTodayVisits
import com.pawbridge.backend.community.internal.visit.application.VisitCollection
import com.pawbridge.backend.community.internal.visit.application.VisitCountReader
import com.pawbridge.backend.community.internal.visit.application.VisitIdentity
import com.pawbridge.backend.community.internal.visit.web.VisitTodayController
import com.pawbridge.backend.community.internal.visit.web.VisitTodayExceptionHandler
import com.pawbridge.backend.store.internal.catalog.application.StoreCatalog
import com.pawbridge.backend.store.internal.catalog.web.StoreCatalogController
import com.pawbridge.backend.store.internal.catalog.web.StoreCatalogExceptionHandler
import com.pawbridge.backend.store.internal.directorder.infrastructure.JdbcProductSearchRebuild
import com.pawbridge.backend.store.internal.payment.web.MockOrderPaymentController
import com.pawbridge.backend.user.api.ContactMemberQuery
import com.pawbridge.backend.user.api.ContactMembersQuery
import com.pawbridge.backend.user.api.UserNicknameQuery
import com.pawbridge.backend.user.internal.adminupdate.application.AdminUserUpdate
import com.pawbridge.backend.user.internal.adminupdate.web.AdminUserUpdateController
import com.pawbridge.backend.user.internal.animalactivity.application.UserAnimalActivities
import com.pawbridge.backend.user.internal.deletion.application.UserDeletion
import com.pawbridge.backend.user.internal.deletion.web.AdminUserDeletionController
import com.pawbridge.backend.user.internal.deletion.web.AdminUserDeletionExceptionHandler
import com.pawbridge.backend.user.internal.directory.application.UserDirectoryQuery
import com.pawbridge.backend.user.internal.directory.web.AdminUserDirectoryController
import com.pawbridge.backend.user.internal.emailverification.application.EmailVerifications
import com.pawbridge.backend.user.internal.favorite.application.FavoriteStatusQuery
import com.pawbridge.backend.user.internal.favorite.web.FavoriteStatusController
import com.pawbridge.backend.user.internal.favorite.web.FavoriteStatusExceptionHandler
import com.pawbridge.backend.user.internal.nickname.application.ChangeUserNickname
import com.pawbridge.backend.user.internal.nickname.application.UserNicknameStore
import com.pawbridge.backend.user.internal.nickname.web.UserNicknameController
import com.pawbridge.backend.user.internal.nickname.web.UserNicknameExceptionHandler
import com.pawbridge.backend.user.internal.password.application.ChangeUserPassword
import com.pawbridge.backend.user.internal.passwordreset.application.PasswordReset
import com.pawbridge.backend.user.internal.profile.application.UserProfileQuery
import com.pawbridge.backend.user.internal.profile.web.AdminUserDetailController
import com.pawbridge.backend.user.internal.profile.web.UserProfileController
import com.pawbridge.backend.user.internal.profile.web.UserProfileExceptionHandler
import com.pawbridge.backend.user.internal.session.application.AuthSessions
import com.pawbridge.backend.user.internal.session.infrastructure.JwtSessionTokens
import com.pawbridge.backend.user.internal.shelter.application.ShelterMembership
import com.pawbridge.backend.user.internal.signup.application.LocalSignup
import com.pawbridge.backend.user.internal.statistics.application.SignupStatisticsQuery
import com.pawbridge.backend.user.internal.statistics.application.TotalUserCountQuery
import com.pawbridge.backend.user.internal.statistics.web.SignupStatisticsController
import com.pawbridge.backend.user.internal.statistics.web.TotalUserCountController
import com.pawbridge.backend.user.internal.statistics.web.TotalUserCountExceptionHandler
import com.pawbridge.backend.user.internal.storemypage.application.StoreMyPage
import com.zaxxer.hikari.HikariDataSource
import io.mockk.confirmVerified
import io.mockk.mockk
import jakarta.persistence.EntityManagerFactory
import jakarta.servlet.MultipartConfigElement
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.aop.support.AopUtils
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.autoconfigure.web.servlet.MultipartAutoConfiguration
import org.springframework.boot.autoconfigure.web.servlet.MultipartProperties
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer
import org.springframework.boot.test.context.runner.WebApplicationContextRunner
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.web.PageableHandlerMethodArgumentResolver
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.support.JdbcTransactionManager
import org.springframework.scheduling.TaskScheduler
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler
import org.springframework.scheduling.config.ScheduledTaskHolder
import org.springframework.test.util.ReflectionTestUtils
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.util.unit.DataSize
import java.net.http.HttpClient
import java.time.Clock
import java.time.ZoneOffset
import java.util.List
import java.util.function.Function
import javax.sql.DataSource

class PawBridgeApplicationTest {
    @Test
    fun `설정이 없을 때__시작을 검증하면__기본 데이터베이스를 고르지 않는다`() {
        // given
        val environment = emptyMap<String, String>()

        // when
        val thrown = catchThrowable { ReadDatabaseSettings.fromEnvironment(Function { key -> environment[key] }) }

        // then
        assertThat(thrown)
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("PAWBRIDGE_DB_URL")
    }

    @Test
    fun `명시적 입력이 주어졌을 때__시작을 검증하면__값을 보존하고 풀 크기를 제한한다`() {
        // given
        val inputs: Map<String, String> = inputs()

        // when
        val settings = ReadDatabaseSettings.fromEnvironment(Function { key -> inputs[key] })

        // then
        assertThat(settings.jdbcUrl()).isEqualTo(inputs["PAWBRIDGE_DB_URL"])
        assertThat(settings.username()).isEqualTo(inputs["PAWBRIDGE_DB_USER"])
        assertThat(settings.maximumPoolSize()).isEqualTo(10)
        assertThat(settings.toString()).doesNotContain(inputs["PAWBRIDGE_DB_PASSWORD"])
    }

    @Test
    fun `잘못된 엔드포인트나 풀이 주어졌을 때__시작을 검증하면__입력을 되풀이하지 않고 거부한다`() {
        // given
        val urls = arrayOf(
            "jdbc:h2:mem:ignored",
            "jdbc:postgresql://localhost/pawbridge_f2_ci?password=private-value",
            "jdbc:postgresql://private-value@localhost/pawbridge_f2_ci",
            "jdbc:postgresql://localhost:0/pawbridge_f2_ci",
        )

        // when
        val rejectedUrls = urls.map { url ->
            val values = inputs()
            values["PAWBRIDGE_DB_URL"] = url
            catchThrowable { ReadDatabaseSettings.fromEnvironment(Function { key -> values[key] }) }
        }
        val rejectedSizes = arrayOf("0", "51", "invalid").map { size ->
            val values = inputs()
            values["PAWBRIDGE_DB_POOL_MAX_SIZE"] = size
            catchThrowable { ReadDatabaseSettings.fromEnvironment(Function { key -> values[key] }) }
        }

        // then
        assertThat(rejectedUrls).hasSize(urls.size).allSatisfy { thrown ->
            assertThat(thrown).hasMessage("Missing or invalid explicit setting: PAWBRIDGE_DB_URL")
        }
        assertThat(rejectedSizes).hasSize(3).allSatisfy { thrown ->
            assertThat(thrown).hasMessage("Missing or invalid explicit setting: PAWBRIDGE_DB_POOL_MAX_SIZE")
        }
    }

    @Test
    fun `리포트가 명시적으로 켜졌을 때__컨텍스트가 시작되면__기존 JDBC와 트랜잭션과 시계를 재사용한다`() {
        // given
        val values = F3TestInputs.values()
        val settings = ReadDatabaseSettings.fromEnvironment(values::get)
        val writeSettings = PostWriteSettings.fromEnvironment(values::get)

        // when
        WebApplicationContextRunner().withUserConfiguration(PawBridgeApplication::class.java)
            .withInitializer { context ->
                context.beanFactory.registerSingleton("readDatabaseSettings", settings)
                context.beanFactory.registerSingleton("postWriteSettings", writeSettings)
            }
            .withPropertyValues("spring.sql.init.mode=never", "spring.jmx.enabled=false", "pawbridge.animal-reports.enabled=true")
            .run { context ->
                // then
                assertThat(context).hasNotFailed()
                val reports = context.getBean(AnimalReports::class.java)
                assertThat(ReflectionTestUtils.getField(reports, "reader")).isSameAs(context.getBean("readJdbc"))
                assertThat(ReflectionTestUtils.getField(reports, "writer")).isSameAs(context.getBean("writeJdbc"))
                assertThat(ReflectionTestUtils.getField(reports, "readTransaction")).isSameAs(context.getBean("readOnlyTransaction"))
                assertThat(ReflectionTestUtils.getField(reports, "writeTransaction")).isSameAs(context.getBean("writeTransaction"))
                assertThat(ReflectionTestUtils.getField(reports, "clock")).isSameAs(context.getBean("readClock"))
                assertThat(context.getBeansOfType(AnimalReportController::class.java)).hasSize(1)
                assertThat(context.getBeansOfType(AnimalReportExceptionHandler::class.java)).hasSize(1)
                assertThat(context.getBeansOfType(DataSource::class.java)).hasSize(1)
                assertThat(context.getBeansOfType(PlatformTransactionManager::class.java)).hasSize(2)
            }
    }

    @Test
    fun `명시적 후보 조립이 주어졌을 때__컨텍스트가 시작되면__명시한 읽기와 쓰기 의존성만 조립한다`() {
        // given
        val values = F3TestInputs.values()
        val settings = ReadDatabaseSettings.fromEnvironment(values::get)
        val writeSettings = PostWriteSettings.fromEnvironment(values::get)

        // when
        WebApplicationContextRunner()
            .withUserConfiguration(PawBridgeApplication::class.java)
            .withInitializer { context ->
                context.beanFactory.registerSingleton("readDatabaseSettings", settings)
                context.beanFactory.registerSingleton("postWriteSettings", writeSettings)
            }
            .withPropertyValues("spring.sql.init.mode=never", "spring.jmx.enabled=false", "pawbridge.visits.enabled=true")
            .run { context ->
                // then
                assertThat(context).hasNotFailed()
                assertThat(context.getBeansOfType(JdbcPostSearchRebuild::class.java)).isEmpty()
                assertThat(context.getBeansOfType(JdbcProductSearchRebuild::class.java)).isEmpty()
                assertThat(context.getBeansOfType(MockOrderPaymentController::class.java)).isEmpty()
                assertThat(context.getBeansOfType(ShelterDirectoryCollector::class.java)).isEmpty()
                assertThat(context.getBeansOfType(ChangeUserPassword::class.java)).isEmpty()
                assertThat(context.getBeansOfType(EmailVerifications::class.java)).isEmpty()
                assertThat(context.getBeansOfType(LocalSignup::class.java)).isEmpty()
                assertThat(context.getBeansOfType(PasswordReset::class.java)).isEmpty()
                assertThat(context.getBeansOfType(StoreMyPage::class.java)).isEmpty()
                assertThat(context.getBeansOfType(EntityManagerFactory::class.java)).isEmpty()
                assertThat(context.getBeansOfType(JpaRepository::class.java)).isEmpty()
                assertThat(context.getBeansOfType(StoreCatalog::class.java)).isEmpty()
                assertThat(context.getBeansOfType(StoreCatalogController::class.java)).isEmpty()
                assertThat(context.getBeansOfType(StoreCatalogExceptionHandler::class.java)).isEmpty()
                assertThat(context.getBeansOfType(AnimalReports::class.java)).isEmpty()
                assertThat(context.getBeansOfType(AnimalReportController::class.java)).isEmpty()
                assertThat(context.getBeansOfType(AnimalReportExceptionHandler::class.java)).isEmpty()
                assertThat(context.getBeansOfType(AdminUserDetailController::class.java)).hasSize(1)
                assertThat(context.getBeansOfType(AdminUserDirectoryController::class.java)).hasSize(1)
                val directory = context.getBean(UserDirectoryQuery::class.java)
                assertThat(ReflectionTestUtils.getField(directory, "jdbc")).isSameAs(context.getBean("readJdbc"))
                assertThat(ReflectionTestUtils.getField(directory, "readOnlyTransaction")).isSameAs(context.getBean("readOnlyTransaction"))
                assertThat(context.getBeansOfType(SignupStatisticsQuery::class.java)).hasSize(1)
                assertThat(context.getBeansOfType(SignupStatisticsController::class.java)).hasSize(1)
                val signupStatistics = context.getBean(SignupStatisticsQuery::class.java)
                assertThat(ReflectionTestUtils.getField(signupStatistics, "jdbc")).isSameAs(context.getBean("readJdbc"))
                assertThat(ReflectionTestUtils.getField(signupStatistics, "readOnlyTransaction")).isSameAs(context.getBean("readOnlyTransaction"))
                assertThat(ReflectionTestUtils.getField(signupStatistics, "clock")).isSameAs(context.getBean("readClock"))
                assertThat(context.getBeansOfType(AdminUserUpdate::class.java)).hasSize(1)
                assertThat(context.getBeansOfType(AdminUserUpdateController::class.java)).hasSize(1)
                val adminUpdate = context.getBean(AdminUserUpdate::class.java)
                assertThat(ReflectionTestUtils.getField(adminUpdate, "jdbc")).isSameAs(context.getBean("writeJdbc"))
                val adminUpdateTx = ReflectionTestUtils.getField(adminUpdate, "transaction") as TransactionTemplate
                assertThat(adminUpdateTx.transactionManager).isSameAs(context.getBean("writeTransactionManager"))
                assertThat(adminUpdateTx.timeout).isEqualTo(15)
                assertThat(context.getBeansOfType(UserAnimalActivities::class.java)).isEmpty()
                assertThat(context.getBeansOfType(ShelterMembership::class.java)).isEmpty()
                assertThat(context.getBeansOfType(AuthSessions::class.java)).isEmpty()
                assertThat(context.getBeansOfType(JwtSessionTokens::class.java)).isEmpty()
                assertThat(context.getBeansOfType(AdminPostReadController::class.java)).hasSize(1)
                assertThat(context.getBeansOfType(ReadAdminPosts::class.java)).hasSize(1)
                assertThat(context.getBean(PostPageReader::class.java)).isSameAs(context.getBean("postReader"))
                assertThat(context.getBeansOfType(PageableHandlerMethodArgumentResolver::class.java)).hasSize(1)
                val postStatistics = context.getBean(AdminPostStatisticsQuery::class.java)
                assertThat(ReflectionTestUtils.getField(postStatistics, "jdbc")).isSameAs(context.getBean("readJdbc"))
                assertThat(ReflectionTestUtils.getField(postStatistics, "todayTransaction")).isSameAs(context.getBean("readOnlyTransaction"))
                assertThat(ReflectionTestUtils.getField(postStatistics, "clock")).isSameAs(context.getBean("readClock"))
                val periodTransaction = context.getBean("adminPostStatisticsTransaction", TransactionTemplate::class.java)
                assertThat(ReflectionTestUtils.getField(postStatistics, "periodTransaction")).isSameAs(periodTransaction)
                assertThat(periodTransaction.transactionManager).isSameAs(context.getBean("transactionManager"))
                assertThat(periodTransaction.isReadOnly).isTrue()
                assertThat(periodTransaction.timeout).isEqualTo(3)
                assertThat(periodTransaction.isolationLevel).isEqualTo(TransactionDefinition.ISOLATION_REPEATABLE_READ)
                assertThat(context.getBean("readOnlyTransaction", TransactionTemplate::class.java).isolationLevel).isEqualTo(TransactionDefinition.ISOLATION_DEFAULT)
                assertThat(context.getBeansOfType(AdminPostStatisticsController::class.java)).hasSize(1)
                assertThat(context.getBeansOfType(AdminPostStatisticsExceptionHandler::class.java)).hasSize(1)
                assertThat(context.getBeansOfType(DataSource::class.java)).hasSize(1)
                assertThat(context.getBeansOfType(PlatformTransactionManager::class.java)).hasSize(2)
                assertThat(context.getBeansOfType(HomeVideoManagement::class.java)).isEmpty()
                assertThat(context.getBeansOfType(YouTubeVideoClient::class.java)).isEmpty()
                assertThat(context.getBeansOfType(HttpClient::class.java)).isEmpty()
                assertThat(context.getBeansOfType(TaskScheduler::class.java)).isEmpty()
                assertThat(context.getBeansOfType(ScheduledTaskHolder::class.java)).isEmpty()
                assertThat(context.getBeansOfType(VisitCollection::class.java)).isEmpty()
                assertThat(context.getBeansOfType(VisitIdentity::class.java)).isEmpty()
                assertThat(context.getBeansOfType(VisitCountReader::class.java)).hasSize(1)
                assertThat(context.getBeansOfType(ReadTodayVisits::class.java)).hasSize(1)
                assertThat(context.getBeansOfType(VisitTodayController::class.java)).hasSize(1)
                assertThat(context.getBeansOfType(VisitTodayExceptionHandler::class.java)).hasSize(1)
                assertThat(context.getBeansOfType(Clock::class.java)).containsOnlyKeys("readClock")
                assertThat(context.getBean("readClock", Clock::class.java).zone).isEqualTo(ZoneOffset.UTC)
                assertThat(context.getBeansOfType(HomeVideoReader::class.java)).hasSize(1)
                assertThat(context.getBeansOfType(ReadHomeVideos::class.java)).hasSize(1)
                assertThat(context.getBeansOfType(HomeVideoController::class.java)).hasSize(1)
                assertThat(context.getBeansOfType(HomeVideoExceptionHandler::class.java)).hasSize(1)
                assertThat(context.getBeansOfType(UserNicknameQuery::class.java)).hasSize(1)
                assertThat(context.getBeansOfType(ContactMemberQuery::class.java)).hasSize(1)
                assertThat(context.getBeansOfType(NoteBlockReader::class.java)).hasSize(1)
                assertThat(context.getBeansOfType(ReadNoteRecipient::class.java)).hasSize(1)
                assertThat(AopUtils.isAopProxy(context.getBean(ReadNoteRecipient::class.java))).isFalse()
                assertThat(context.getBeansOfType(NoteRecipientController::class.java)).hasSize(1)
                assertThat(context.getBeansOfType(NoteRecipientExceptionHandler::class.java)).hasSize(1)
                assertThat(context.getBeansOfType(NoteBlockStatusReader::class.java)).hasSize(1)
                assertThat(context.getBeansOfType(ReadBlockedAuthors::class.java)).hasSize(1)
                assertThat(AopUtils.isAopProxy(context.getBean(ReadBlockedAuthors::class.java))).isFalse()
                assertThat(context.getBeansOfType(NoteBlockStatusController::class.java)).hasSize(1)
                assertThat(context.getBeansOfType(NoteBlockStatusExceptionHandler::class.java)).hasSize(1)
                assertThat(context.getBeansOfType(ContactMembersQuery::class.java)).hasSize(1)
                assertThat(context.getBeansOfType(NoteBlockListReader::class.java)).hasSize(1)
                assertThat(context.getBeansOfType(ListNoteBlocks::class.java)).hasSize(1)
                assertThat(AopUtils.isAopProxy(context.getBean(ListNoteBlocks::class.java))).isFalse()
                assertThat(context.getBeansOfType(NoteBlockListController::class.java)).hasSize(1)
                assertThat(context.getBeansOfType(NoteBlockListExceptionHandler::class.java)).hasSize(1)
                assertThat(context.getBeansOfType(NoteNotificationReader::class.java)).hasSize(1)
                assertThat(context.getBeansOfType(ReadNoteNotifications::class.java)).hasSize(1)
                assertThat(AopUtils.isAopProxy(context.getBean(ReadNoteNotifications::class.java))).isFalse()
                assertThat(context.getBeansOfType(NoteNotificationsController::class.java)).hasSize(1)
                assertThat(context.getBeansOfType(NoteNotificationsExceptionHandler::class.java)).hasSize(1)
                assertThat(context.getBeansOfType(PrivateNoteReader::class.java)).hasSize(1)
                assertThat(context.getBeansOfType(ReadPrivateNote::class.java)).hasSize(1)
                assertThat(AopUtils.isAopProxy(context.getBean(ReadPrivateNote::class.java))).isFalse()
                assertThat(context.getBeansOfType(PrivateNoteReadController::class.java)).hasSize(1)
                assertThat(context.getBeansOfType(PrivateNoteReadExceptionHandler::class.java)).hasSize(1)
                assertThat(context.getBeansOfType(PrivateNoteListReader::class.java)).hasSize(1)
                assertThat(context.getBeansOfType(ListPrivateNotes::class.java)).hasSize(1)
                assertThat(AopUtils.isAopProxy(context.getBean(ListPrivateNotes::class.java))).isFalse()
                assertThat(context.getBeansOfType(PrivateNoteListController::class.java)).hasSize(1)
                assertThat(context.getBeansOfType(PrivateNoteListExceptionHandler::class.java)).hasSize(1)
                assertThat(context.getBeansOfType(NoteBlockRemoval::class.java)).hasSize(1)
                assertThat(context.getBeansOfType(RemoveNoteBlock::class.java)).hasSize(1)
                assertThat(AopUtils.isAopProxy(context.getBean(RemoveNoteBlock::class.java))).isFalse()
                assertThat(context.getBeansOfType(NoteBlockRemoveController::class.java)).hasSize(1)
                assertThat(context.getBeansOfType(NoteBlockRemoveExceptionHandler::class.java)).hasSize(1)
                assertThat(context.getBeansOfType(NoteBlockCreation::class.java)).hasSize(1)
                assertThat(context.getBeansOfType(CreateNoteBlock::class.java)).hasSize(1)
                assertThat(AopUtils.isAopProxy(context.getBean(CreateNoteBlock::class.java))).isFalse()
                assertThat(context.getBeansOfType(NoteBlockCreateController::class.java)).hasSize(1)
                assertThat(context.getBeansOfType(NoteBlockCreateExceptionHandler::class.java)).hasSize(1)
                val createNoteBlock = context.getBean(CreateNoteBlock::class.java)
                assertThat(ReflectionTestUtils.getField(createNoteBlock, "clock")).isSameAs(context.getBean("readClock"))
                assertThat(ReflectionTestUtils.getField(createNoteBlock, "members")).isSameAs(context.getBean(ContactMemberQuery::class.java))
                assertThat(context.getBeansOfType(TotalUserCountQuery::class.java)).hasSize(1)
                assertThat(context.getBeansOfType(TotalUserCountController::class.java)).hasSize(1)
                assertThat(context.getBeansOfType(TotalUserCountExceptionHandler::class.java)).hasSize(1)
                assertThat(context.getBeansOfType(UserProfileQuery::class.java)).hasSize(1)
                assertThat(context.getBeansOfType(UserProfileController::class.java)).hasSize(1)
                assertThat(context.getBeansOfType(UserProfileExceptionHandler::class.java)).hasSize(1)
                assertThat(context.getBeansOfType(UserNicknameStore::class.java)).hasSize(1)
                assertThat(context.getBeansOfType(ChangeUserNickname::class.java)).hasSize(1)
                assertThat(AopUtils.isAopProxy(context.getBean(ChangeUserNickname::class.java))).isTrue()
                assertThat(context.getBeansOfType(UserNicknameController::class.java)).hasSize(1)
                assertThat(context.getBeansOfType(UserNicknameExceptionHandler::class.java)).hasSize(1)
                assertThat(context.getBeansOfType(FavoriteStatusQuery::class.java)).hasSize(1)
                assertThat(context.getBeansOfType(FavoriteStatusController::class.java)).hasSize(1)
                assertThat(context.getBeansOfType(FavoriteStatusExceptionHandler::class.java)).hasSize(1)
                assertThat(context.getBeansOfType(CommentListReader::class.java)).hasSize(1)
                assertThat(context.getBeansOfType(ListComments::class.java)).hasSize(1)
                assertThat(context.getBeansOfType(CommentReadController::class.java)).hasSize(1)
                assertThat(context.getBeansOfType(CommentReadExceptionHandler::class.java)).hasSize(1)
                assertThat(context.getBeansOfType(CommentWriter::class.java)).hasSize(1)
                assertThat(context.getBeansOfType(CreateComment::class.java)).hasSize(1)
                assertThat(context.getBeansOfType(CommentCreateController::class.java)).hasSize(1)
                assertThat(context.getBeansOfType(CommentCreateExceptionHandler::class.java)).hasSize(1)
                assertThat(context.getBeansOfType(CommentDeletion::class.java)).hasSize(1)
                assertThat(context.getBeansOfType(CommentDeleteController::class.java)).hasSize(1)
                assertThat(context.getBeansOfType(CommentDeleteExceptionHandler::class.java)).hasSize(1)
                assertThat(context.getBeansOfType(UpdateComment::class.java)).hasSize(1)
                assertThat(context.getBeansOfType(CommentUpdateController::class.java)).hasSize(1)
                assertThat(context.getBeansOfType(CommentUpdateExceptionHandler::class.java)).hasSize(1)
                assertThat(context.getBean(CommentReader::class.java))
                        .isSameAs(context.getBean(CommentListReader::class.java))
                assertThat(context.getBean(CommentModification::class.java))
                        .isSameAs(context.getBean(CommentWriter::class.java))
                assertThat(context.getBeansOfType(ListPosts::class.java)).hasSize(1)
                assertThat(context.getBeansOfType(SearchPosts::class.java)).hasSize(1)
                assertThat(context.getBeansOfType(PostSearchReader::class.java)).hasSize(1)
                assertThat(context.getBeansOfType(PostSearchController::class.java)).hasSize(1)
                assertThat(context.getBeansOfType(PostSearchExceptionHandler::class.java)).hasSize(1)
                assertThat(context.getBeansOfType(PostReader::class.java)).hasSize(1)
                assertThat(context.getBeansOfType(PostListReader::class.java)).hasSize(1)
                assertThat(context.getBean(PostReader::class.java))
                        .isSameAs(context.getBean(PostListReader::class.java))
                assertThat(context.getBeansOfType(PostReadController::class.java)).hasSize(1)
                assertThat(context.getBeansOfType(DeletePost::class.java)).hasSize(1)
                assertThat(context.getBeansOfType(PostMediaRemover::class.java)).hasSize(1)
                assertThat(context.getBeansOfType(PostDeletion::class.java)).hasSize(1)
                assertThat(context.getBeansOfType(PostDeleteController::class.java)).hasSize(1)
                assertThat(context.getBeansOfType(UpdatePost::class.java)).hasSize(1)
                assertThat(context.getBeansOfType(PostModification::class.java)).hasSize(1)
                assertThat(context.getBeansOfType(PostUpdateController::class.java)).hasSize(1)
                assertThat(context.getBeansOfType(AdminPostUpdateController::class.java)).hasSize(1)
                assertThat(context.getBeansOfType(AdminPostDeleteController::class.java)).hasSize(1)
                val pools = context.getBeansOfType(HikariDataSource::class.java)
                assertThat(pools).containsOnlyKeys("dataSource")
                val pool = pools["dataSource"]!!
                assertThat(context.getBean("writeDataSource")).isSameAs(pool)
                assertThat(context.getBean("storeWishlistWriteDataSource")).isSameAs(pool)
                assertThat(pool.poolName).isEqualTo("pawbridge-db")
                assertThat(pool.isReadOnly).isFalse()
                assertThat(pool.maximumPoolSize).isEqualTo(10)
                assertThat(pool.minimumIdle).isEqualTo(2)
                assertThat(pool.connectionTimeout).isEqualTo(5000)
                assertThat(pool.validationTimeout).isEqualTo(1000)
                assertThat(pool.initializationFailTimeout).isEqualTo(-1)
                val readJdbc = context.getBean("readJdbc", JdbcTemplate::class.java)
                val searchJdbc = context.getBean("searchJdbc", JdbcTemplate::class.java)
                assertThat(readJdbc.queryTimeout).isEqualTo(3)
                assertThat(searchJdbc.queryTimeout).isEqualTo(5)
                assertThat(searchJdbc.dataSource).isSameAs(pools["dataSource"])
                assertThat(readJdbc.dataSource).isSameAs(searchJdbc.dataSource)
                val manager = context.getBean("transactionManager", JdbcTransactionManager::class.java)
                assertThat(manager.isEnforceReadOnly).isTrue()
                val readTransaction = context.getBean("readOnlyTransaction", TransactionTemplate::class.java)
                val searchTransaction = context.getBean("searchReadOnlyTransaction", TransactionTemplate::class.java)
                assertThat(readTransaction.timeout).isEqualTo(3)
                assertThat(searchTransaction.timeout).isEqualTo(5)
                assertThat(readTransaction.isReadOnly).isTrue()
                assertThat(searchTransaction.isReadOnly).isTrue()
                assertThat(searchTransaction.transactionManager).isSameAs(manager)
                assertThat(readTransaction.transactionManager).isSameAs(manager)
                val writeJdbc = context.getBean("writeJdbc", JdbcTemplate::class.java)
                val writeManager = context.getBean("writeTransactionManager", JdbcTransactionManager::class.java)
                val sharedWriteTransaction = context.getBean("writeTransaction", TransactionTemplate::class.java)
                assertThat(writeJdbc.dataSource).isSameAs(pool)
                assertThat(writeJdbc.queryTimeout).isEqualTo(5)
                assertThat(writeManager.dataSource).isSameAs(pool)
                assertThat(sharedWriteTransaction.transactionManager).isSameAs(writeManager)
                assertThat(sharedWriteTransaction.timeout).isEqualTo(10)
                val blockRemoval = context.getBean(NoteBlockRemoval::class.java)
                assertThat(blockRemoval).isInstanceOf(JdbcNoteBlockRemoval::class.java)
                assertThat(ReflectionTestUtils.getField(blockRemoval, "jdbc")).isSameAs(writeJdbc)
                val removalTransaction = ReflectionTestUtils.getField(blockRemoval, "transaction") as TransactionTemplate
                assertThat(removalTransaction).isNotNull().isNotSameAs(sharedWriteTransaction)
                assertThat(removalTransaction.transactionManager).isSameAs(writeManager)
                assertThat(removalTransaction.timeout).isEqualTo(15)
                assertThat(removalTransaction.isReadOnly).isFalse()
                val blockCreation = context.getBean(NoteBlockCreation::class.java)
                assertThat(blockCreation).isInstanceOf(JdbcNoteBlockCreation::class.java)
                assertThat(ReflectionTestUtils.getField(blockCreation, "jdbc")).isSameAs(writeJdbc)
                assertThat(ReflectionTestUtils.getField(createNoteBlock, "creation")).isSameAs(blockCreation)
                val creationTransaction = ReflectionTestUtils.getField(blockCreation, "transaction") as TransactionTemplate
                assertThat(creationTransaction).isNotNull().isNotSameAs(sharedWriteTransaction)
                assertThat(creationTransaction.transactionManager).isSameAs(writeManager)
                assertThat(creationTransaction.timeout).isEqualTo(15)
                assertThat(creationTransaction.isReadOnly).isFalse()
                val streams = context.getBean(PrivateNoteStreamRegistry::class.java)
                assertThat(context.getBeansOfType(NoteChangeHints::class.java)).hasSize(1).containsValue(streams)
                assertThat(streams.isRunning()).isTrue()
                assertThat(streams.phase).isEqualTo(Int.MAX_VALUE)
                assertThat(ReflectionTestUtils.getField(streams, "clock")).isSameAs(context.getBean("readClock"))
                assertThat(context.getBeansOfType(RequireActiveNoteStream::class.java)).hasSize(1)
                assertThat(context.getBeansOfType(NoteStreamController::class.java)).hasSize(1)
                assertThat(context.getBeansOfType(NoteStreamExceptionHandler::class.java)).hasSize(1)
                assertThat(context.getBeansOfType(MarkPrivateNoteRead::class.java)).hasSize(1)
                assertThat(context.getBeansOfType(NoteReadMutationController::class.java)).hasSize(1)
                assertThat(context.getBeansOfType(NoteReadMutationExceptionHandler::class.java)).hasSize(1)
                val markRead = context.getBean(MarkPrivateNoteRead::class.java)
                assertThat(AopUtils.isAopProxy(markRead)).isFalse()
                assertThat(ReflectionTestUtils.getField(markRead, "clock")).isSameAs(context.getBean("readClock"))
                val readMutation = context.getBean(NoteReadMutation::class.java)
                assertThat(ReflectionTestUtils.getField(markRead, "mutation")).isSameAs(readMutation)
                assertThat(ReflectionTestUtils.getField(readMutation, "jdbc")).isSameAs(writeJdbc)
                assertThat(ReflectionTestUtils.getField(readMutation, "hints")).isSameAs(streams)
                val mutationTransaction = ReflectionTestUtils.getField(readMutation, "transaction") as TransactionTemplate
                assertThat(mutationTransaction).isNotSameAs(sharedWriteTransaction)
                assertThat(mutationTransaction.transactionManager).isSameAs(writeManager)
                assertThat(mutationTransaction.timeout).isEqualTo(15)
                assertThat(mutationTransaction.isReadOnly).isFalse()
                assertThat(context.getBeansOfType(NoteFavoriteMutation::class.java)).hasSize(1)
                assertThat(context.getBeansOfType(SetPrivateNoteFavorite::class.java)).hasSize(1)
                assertThat(context.getBeansOfType(NoteFavoriteController::class.java)).hasSize(1)
                assertThat(context.getBeansOfType(NoteFavoriteExceptionHandler::class.java)).hasSize(1)
                val setFavorite = context.getBean(SetPrivateNoteFavorite::class.java)
                assertThat(AopUtils.isAopProxy(setFavorite)).isFalse()
                assertThat(ReflectionTestUtils.getField(setFavorite, "members")).isSameAs(context.getBean(ContactMemberQuery::class.java))
                assertThat(ReflectionTestUtils.getField(setFavorite, "clock")).isSameAs(context.getBean("readClock"))
                val favoriteMutation = context.getBean(NoteFavoriteMutation::class.java)
                assertThat(ReflectionTestUtils.getField(setFavorite, "mutation")).isSameAs(favoriteMutation)
                assertThat(ReflectionTestUtils.getField(favoriteMutation, "jdbc")).isSameAs(writeJdbc)
                assertThat(ReflectionTestUtils.getField(favoriteMutation, "hints")).isSameAs(streams)
                val favoriteTransaction = ReflectionTestUtils.getField(favoriteMutation, "transaction") as TransactionTemplate
                assertThat(favoriteTransaction).isNotSameAs(sharedWriteTransaction).isNotSameAs(mutationTransaction)
                assertThat(favoriteTransaction.transactionManager).isSameAs(writeManager)
                assertThat(favoriteTransaction.timeout).isEqualTo(15)
                assertThat(favoriteTransaction.isReadOnly).isFalse()
                assertThat(context.getBeansOfType(NoteDeletion::class.java)).hasSize(1)
                assertThat(context.getBeansOfType(DeletePrivateNote::class.java)).hasSize(1)
                assertThat(context.getBeansOfType(NoteDeletionController::class.java)).hasSize(1)
                assertThat(context.getBeansOfType(NoteDeletionExceptionHandler::class.java)).hasSize(1)
                val deleteNote = context.getBean(DeletePrivateNote::class.java)
                assertThat(AopUtils.isAopProxy(deleteNote)).isFalse()
                assertThat(ReflectionTestUtils.getField(deleteNote, "members")).isSameAs(context.getBean(ContactMemberQuery::class.java))
                assertThat(ReflectionTestUtils.getField(deleteNote, "clock")).isSameAs(context.getBean("readClock"))
                val deletion = context.getBean(NoteDeletion::class.java)
                assertThat(ReflectionTestUtils.getField(deleteNote, "mutation")).isSameAs(deletion)
                assertThat(ReflectionTestUtils.getField(deletion, "jdbc")).isSameAs(writeJdbc)
                assertThat(ReflectionTestUtils.getField(deletion, "hints")).isSameAs(streams)
                val deletionTransaction = ReflectionTestUtils.getField(deletion, "transaction") as TransactionTemplate
                assertThat(deletionTransaction).isNotSameAs(sharedWriteTransaction).isNotSameAs(mutationTransaction).isNotSameAs(favoriteTransaction)
                assertThat(deletionTransaction.transactionManager).isSameAs(writeManager)
                assertThat(deletionTransaction.timeout).isEqualTo(15)
                assertThat(deletionTransaction.isReadOnly).isFalse()
                assertThat(context.getBeansOfType(NoteSendStore::class.java)).hasSize(1)
                assertThat(context.getBeansOfType(SendPrivateNote::class.java)).hasSize(1)
                assertThat(context.getBeansOfType(NoteSendController::class.java)).hasSize(1)
                assertThat(context.getBeansOfType(NoteSendExceptionHandler::class.java)).hasSize(1)
                assertThat(context.getBeansOfType(NoteDeliveryHints::class.java)).hasSize(1).containsValue(streams)
                val sending = context.getBean(SendPrivateNote::class.java)
                assertThat(AopUtils.isAopProxy(sending)).isFalse()
                assertThat(ReflectionTestUtils.getField(sending, "members")).isSameAs(context.getBean(ContactMemberQuery::class.java))
                assertThat(ReflectionTestUtils.getField(sending, "clock")).isSameAs(context.getBean("readClock"))
                val sendStore = context.getBean(NoteSendStore::class.java)
                assertThat(ReflectionTestUtils.getField(sending, "store")).isSameAs(sendStore)
                assertThat(ReflectionTestUtils.getField(sendStore, "jdbc")).isSameAs(writeJdbc)
                assertThat(ReflectionTestUtils.getField(sendStore, "hints")).isSameAs(streams)
                val sendTransaction = ReflectionTestUtils.getField(sendStore, "transaction") as TransactionTemplate
                assertThat(sendTransaction).isNotSameAs(sharedWriteTransaction).isNotSameAs(mutationTransaction).isNotSameAs(favoriteTransaction).isNotSameAs(deletionTransaction)
                assertThat(sendTransaction.transactionManager).isSameAs(writeManager)
                assertThat(sendTransaction.timeout).isEqualTo(15)
                assertThat(sendTransaction.isReadOnly).isFalse()
                assertThat(context.getBeansOfType(ContactMemberCleanup::class.java)).hasSize(1)
                assertThat(context.getBeansOfType(ContactMemberCleanupStore::class.java)).hasSize(1)
                assertThat(context.getBeansOfType(UserDeletion::class.java)).hasSize(1)
                assertThat(context.getBeansOfType(AdminUserDeletionController::class.java)).hasSize(1)
                assertThat(context.getBeansOfType(AdminUserDeletionExceptionHandler::class.java)).hasSize(1)
                val cleanup = context.getBean(ContactMemberCleanup::class.java)
                val cleanupStore = context.getBean(ContactMemberCleanupStore::class.java)
                val userDeletion = context.getBean(UserDeletion::class.java)
                assertThat(ReflectionTestUtils.getField(cleanup, "members")).isSameAs(context.getBean(ContactMemberQuery::class.java))
                assertThat(ReflectionTestUtils.getField(cleanup, "store")).isSameAs(cleanupStore)
                assertThat(ReflectionTestUtils.getField(cleanupStore, "jdbc")).isSameAs(writeJdbc)
                assertThat(ReflectionTestUtils.getField(userDeletion, "jdbc")).isSameAs(writeJdbc)
                assertThat(AopUtils.isAopProxy(cleanup)).isFalse()
                assertThat(AopUtils.isAopProxy(userDeletion)).isFalse()
                val cleanupTransaction = ReflectionTestUtils.getField(cleanupStore, "transaction") as TransactionTemplate
                val userDeletionTransaction = ReflectionTestUtils.getField(userDeletion, "transactions") as TransactionTemplate
                assertThat(cleanupTransaction).isNotSameAs(sharedWriteTransaction).isNotSameAs(userDeletionTransaction)
                assertThat(userDeletionTransaction).isNotSameAs(sharedWriteTransaction)
                for (operationTransaction in listOf(cleanupTransaction, userDeletionTransaction)) {
                    assertThat(operationTransaction.transactionManager).isSameAs(writeManager)
                    assertThat(operationTransaction.timeout).isEqualTo(15)
                    assertThat(operationTransaction.isReadOnly).isFalse()
                }
                assertThat(context.getBeansOfType(NoteExpiry::class.java)).isEmpty()
                assertThat(context.getBeansOfType(NoteExpiryConfiguration.NoteExpiryJob::class.java)).isEmpty()
                assertThat(context.getBeansOfType(ThreadPoolTaskScheduler::class.java)).isEmpty()
                assertThat(context.getBeansOfType(ScheduledAnnotationBeanPostProcessor::class.java)).isEmpty()
                assertThat(pool.hikariPoolMXBean.activeConnections).isZero()
            }
    }

    @Test
    fun `후보 멀티파트 설정이 주어졌을 때__서블릿 설정에 바인딩하면__파일과 요청 한도를 보존한다`() {
        // given
        // when
        WebApplicationContextRunner()
            .withInitializer(ConfigDataApplicationContextInitializer())
            .withConfiguration(AutoConfigurations.of(MultipartAutoConfiguration::class.java))
            .withPropertyValues("spring.config.location=classpath:/application.properties")
            .run { context ->
                // then
                assertThat(context).hasNotFailed()
                val properties = context.getBean(MultipartProperties::class.java)
                assertThat(properties.maxFileSize).isEqualTo(DataSize.ofMegabytes(100))
                assertThat(properties.maxRequestSize).isEqualTo(DataSize.ofMegabytes(200))
                val servletConfig = context.getBean(MultipartConfigElement::class.java)
                assertThat(servletConfig.maxFileSize).isEqualTo(DataSize.ofMegabytes(100).toBytes())
                assertThat(servletConfig.maxRequestSize).isEqualTo(DataSize.ofMegabytes(200).toBytes())
            }
    }

    @ParameterizedTest
    @ValueSource(strings = ["false", "absent"])
    fun `방문 플래그가 false이거나 없을 때__실제 설정을 조립하면__방문 빈과 JDBC 호출이 없다`(
        flag: String,
    ) {
        // given
        val jdbc = mockk<JdbcTemplate>()
        val transaction = mockk<TransactionTemplate>()
        var runner = WebApplicationContextRunner()
            .withUserConfiguration(VisitReadConfiguration::class.java)
            .withInitializer { context ->
                context.beanFactory.registerSingleton("readJdbc", jdbc)
                context.beanFactory.registerSingleton("readOnlyTransaction", transaction)
                context.beanFactory.registerSingleton("readClock", Clock.systemUTC())
            }
        if (flag != "absent") {
            runner = runner.withPropertyValues("pawbridge.visits.enabled=$flag")
        }

        // when
        runner.run { context ->
            // then
            assertThat(context).hasNotFailed()
            assertThat(context.getBeansOfType(VisitCountReader::class.java)).isEmpty()
            assertThat(context.getBeansOfType(ReadTodayVisits::class.java)).isEmpty()
            assertThat(context.getBeansOfType(VisitTodayController::class.java)).isEmpty()
            assertThat(context.getBeansOfType(VisitTodayExceptionHandler::class.java)).isEmpty()
            confirmVerified(jdbc, transaction)
        }
    }

    private fun inputs(): MutableMap<String, String> {
        return HashMap(
            mapOf(
                "PAWBRIDGE_DB_URL" to "jdbc:postgresql://127.0.0.1:5432/pawbridge_f2_ci",
                "PAWBRIDGE_DB_USER" to "fixture-reader",
                "PAWBRIDGE_DB_PASSWORD" to "synthetic-only",
            ),
        )
    }
}
