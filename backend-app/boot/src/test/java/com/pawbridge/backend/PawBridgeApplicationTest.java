package com.pawbridge.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import com.pawbridge.backend.boot.configuration.ReadDatabaseSettings;
import jakarta.servlet.MultipartConfigElement;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.web.servlet.MultipartAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.MultipartProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.util.unit.DataSize;

class PawBridgeApplicationTest {
    @Test
    void given__missing_settings__when__validating_startup__then__no_default_database_is_selected() {
        assertThatThrownBy(() -> ReadDatabaseSettings.fromEnvironment(Map.<String, String>of()::get))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("F2_JDBC_URL");
    }

    @Test
    void given__explicit_inputs__when__validating_startup__then__preserve_values_and_bound_pool() {
        Map<String, String> inputs = inputs();
        var settings = ReadDatabaseSettings.fromEnvironment(inputs::get);
        assertThat(settings.jdbcUrl()).isEqualTo(inputs.get("F2_JDBC_URL"));
        assertThat(settings.username()).isEqualTo(inputs.get("F2_DB_USER"));
        assertThat(settings.maximumPoolSize()).isEqualTo(2);
        assertThat(settings.toString()).doesNotContain(inputs.get("F2_DB_PASSWORD"));
    }

    @Test
    void given__invalid_endpoint_or_pool__when__validating_startup__then__reject_without_echoing_input() {
        for (String url : new String[]{"jdbc:h2:mem:ignored", "jdbc:postgresql://localhost/pawbridge_f2_ci?password=private-value",
                "jdbc:postgresql://private-value@localhost/pawbridge_f2_ci", "jdbc:postgresql://localhost:0/pawbridge_f2_ci"}) {
            Map<String, String> values = inputs(); values.put("F2_JDBC_URL", url);
            assertThatThrownBy(() -> ReadDatabaseSettings.fromEnvironment(values::get))
                    .hasMessage("Missing or invalid explicit setting: F2_JDBC_URL");
        }
        for (String size : new String[]{"0", "11", "invalid"}) {
            Map<String, String> values = inputs(); values.put("F2_POOL_MAX_SIZE", size);
            assertThatThrownBy(() -> ReadDatabaseSettings.fromEnvironment(values::get))
                    .hasMessage("Missing or invalid explicit setting: F2_POOL_MAX_SIZE");
        }
    }

    @Test
    void given__explicit_candidate_assembly__when__context_starts__then__only_explicit_read_and_write_dependencies_are_assembled() {
        var values = F3TestInputs.values();
        var settings = ReadDatabaseSettings.fromEnvironment(values::get);
        var writeSettings = com.pawbridge.backend.boot.configuration.PostWriteSettings.fromEnvironment(values::get,settings);
        new org.springframework.boot.test.context.runner.WebApplicationContextRunner()
                .withUserConfiguration(PawBridgeApplication.class)
                .withInitializer(context -> {
                    context.getBeanFactory().registerSingleton("readDatabaseSettings", settings);
                    context.getBeanFactory().registerSingleton("postWriteSettings", writeSettings);
                })
                .withPropertyValues("spring.sql.init.mode=never", "spring.jmx.enabled=false", "pawbridge.visits.enabled=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBeansOfType(javax.sql.DataSource.class)).hasSize(2);
                    assertThat(context.getBeansOfType(org.springframework.transaction.PlatformTransactionManager.class)).hasSize(2);
                    assertThat(context.getBeansOfType(org.springframework.scheduling.TaskScheduler.class)).isEmpty();
                    assertThat(context.getBeansOfType(org.springframework.scheduling.config.ScheduledTaskHolder.class)).isEmpty();
                    assertThat(context.getBeansOfType(com.pawbridge.backend.community.internal.visit.application.VisitCountReader.class)).hasSize(1);
                    assertThat(context.getBeansOfType(com.pawbridge.backend.community.internal.visit.application.ReadTodayVisits.class)).hasSize(1);
                    assertThat(context.getBeansOfType(com.pawbridge.backend.community.internal.visit.web.VisitTodayController.class)).hasSize(1);
                    assertThat(context.getBeansOfType(com.pawbridge.backend.community.internal.visit.web.VisitTodayExceptionHandler.class)).hasSize(1);
                    assertThat(context.getBeansOfType(java.time.Clock.class)).containsOnlyKeys("readClock");
                    assertThat(context.getBean("readClock", java.time.Clock.class).getZone()).isEqualTo(java.time.ZoneOffset.UTC);
                    assertThat(context.getBeansOfType(com.pawbridge.backend.community.internal.homevideo.application.HomeVideoReader.class)).hasSize(1);
                    assertThat(context.getBeansOfType(com.pawbridge.backend.community.internal.homevideo.application.ReadHomeVideos.class)).hasSize(1);
                    assertThat(context.getBeansOfType(com.pawbridge.backend.community.internal.homevideo.web.HomeVideoController.class)).hasSize(1);
                    assertThat(context.getBeansOfType(com.pawbridge.backend.community.internal.homevideo.web.HomeVideoExceptionHandler.class)).hasSize(1);
                    assertThat(context.getBeansOfType(com.pawbridge.backend.user.api.UserNicknameQuery.class)).hasSize(1);
                    assertThat(context.getBeansOfType(com.pawbridge.backend.user.api.ContactMemberQuery.class)).hasSize(1);
                    assertThat(context.getBeansOfType(com.pawbridge.backend.community.internal.note.application.NoteBlockReader.class)).hasSize(1);
                    assertThat(context.getBeansOfType(com.pawbridge.backend.community.internal.note.application.ReadNoteRecipient.class)).hasSize(1);
                    assertThat(org.springframework.aop.support.AopUtils.isAopProxy(context.getBean(com.pawbridge.backend.community.internal.note.application.ReadNoteRecipient.class))).isFalse();
                    assertThat(context.getBeansOfType(com.pawbridge.backend.community.internal.note.web.NoteRecipientController.class)).hasSize(1);
                    assertThat(context.getBeansOfType(com.pawbridge.backend.community.internal.note.web.NoteRecipientExceptionHandler.class)).hasSize(1);
                    assertThat(context.getBeansOfType(com.pawbridge.backend.community.internal.note.application.NoteBlockStatusReader.class)).hasSize(1);
                    assertThat(context.getBeansOfType(com.pawbridge.backend.community.internal.note.application.ReadBlockedAuthors.class)).hasSize(1);
                    assertThat(org.springframework.aop.support.AopUtils.isAopProxy(context.getBean(com.pawbridge.backend.community.internal.note.application.ReadBlockedAuthors.class))).isFalse();
                    assertThat(context.getBeansOfType(com.pawbridge.backend.community.internal.note.web.NoteBlockStatusController.class)).hasSize(1);
                    assertThat(context.getBeansOfType(com.pawbridge.backend.community.internal.note.web.NoteBlockStatusExceptionHandler.class)).hasSize(1);
                    assertThat(context.getBeansOfType(com.pawbridge.backend.user.api.ContactMembersQuery.class)).hasSize(1);
                    assertThat(context.getBeansOfType(com.pawbridge.backend.community.internal.note.application.NoteBlockListReader.class)).hasSize(1);
                    assertThat(context.getBeansOfType(com.pawbridge.backend.community.internal.note.application.ListNoteBlocks.class)).hasSize(1);
                    assertThat(org.springframework.aop.support.AopUtils.isAopProxy(context.getBean(com.pawbridge.backend.community.internal.note.application.ListNoteBlocks.class))).isFalse();
                    assertThat(context.getBeansOfType(com.pawbridge.backend.community.internal.note.web.NoteBlockListController.class)).hasSize(1);
                    assertThat(context.getBeansOfType(com.pawbridge.backend.community.internal.note.web.NoteBlockListExceptionHandler.class)).hasSize(1);
                    assertThat(context.getBeansOfType(com.pawbridge.backend.community.internal.note.application.NoteNotificationReader.class)).hasSize(1);
                    assertThat(context.getBeansOfType(com.pawbridge.backend.community.internal.note.application.ReadNoteNotifications.class)).hasSize(1);
                    assertThat(org.springframework.aop.support.AopUtils.isAopProxy(context.getBean(com.pawbridge.backend.community.internal.note.application.ReadNoteNotifications.class))).isFalse();
                    assertThat(context.getBeansOfType(com.pawbridge.backend.community.internal.note.web.NoteNotificationsController.class)).hasSize(1);
                    assertThat(context.getBeansOfType(com.pawbridge.backend.community.internal.note.web.NoteNotificationsExceptionHandler.class)).hasSize(1);
                    assertThat(context.getBeansOfType(com.pawbridge.backend.community.internal.note.application.PrivateNoteReader.class)).hasSize(1);
                    assertThat(context.getBeansOfType(com.pawbridge.backend.community.internal.note.application.ReadPrivateNote.class)).hasSize(1);
                    assertThat(org.springframework.aop.support.AopUtils.isAopProxy(context.getBean(com.pawbridge.backend.community.internal.note.application.ReadPrivateNote.class))).isFalse();
                    assertThat(context.getBeansOfType(com.pawbridge.backend.community.internal.note.web.PrivateNoteReadController.class)).hasSize(1);
                    assertThat(context.getBeansOfType(com.pawbridge.backend.community.internal.note.web.PrivateNoteReadExceptionHandler.class)).hasSize(1);
                    assertThat(context.getBeansOfType(com.pawbridge.backend.community.internal.note.application.PrivateNoteListReader.class)).hasSize(1);
                    assertThat(context.getBeansOfType(com.pawbridge.backend.community.internal.note.application.ListPrivateNotes.class)).hasSize(1);
                    assertThat(org.springframework.aop.support.AopUtils.isAopProxy(context.getBean(com.pawbridge.backend.community.internal.note.application.ListPrivateNotes.class))).isFalse();
                    assertThat(context.getBeansOfType(com.pawbridge.backend.community.internal.note.web.PrivateNoteListController.class)).hasSize(1);
                    assertThat(context.getBeansOfType(com.pawbridge.backend.community.internal.note.web.PrivateNoteListExceptionHandler.class)).hasSize(1);
                    assertThat(context.getBeansOfType(com.pawbridge.backend.community.internal.note.application.NoteBlockRemoval.class)).hasSize(1);
                    assertThat(context.getBeansOfType(com.pawbridge.backend.community.internal.note.application.RemoveNoteBlock.class)).hasSize(1);
                    assertThat(org.springframework.aop.support.AopUtils.isAopProxy(context.getBean(com.pawbridge.backend.community.internal.note.application.RemoveNoteBlock.class))).isFalse();
                    assertThat(context.getBeansOfType(com.pawbridge.backend.community.internal.note.web.NoteBlockRemoveController.class)).hasSize(1);
                    assertThat(context.getBeansOfType(com.pawbridge.backend.community.internal.note.web.NoteBlockRemoveExceptionHandler.class)).hasSize(1);
                    assertThat(context.getBeansOfType(com.pawbridge.backend.community.internal.note.application.NoteBlockCreation.class)).hasSize(1);
                    assertThat(context.getBeansOfType(com.pawbridge.backend.community.internal.note.application.CreateNoteBlock.class)).hasSize(1);
                    assertThat(org.springframework.aop.support.AopUtils.isAopProxy(context.getBean(com.pawbridge.backend.community.internal.note.application.CreateNoteBlock.class))).isFalse();
                    assertThat(context.getBeansOfType(com.pawbridge.backend.community.internal.note.web.NoteBlockCreateController.class)).hasSize(1);
                    assertThat(context.getBeansOfType(com.pawbridge.backend.community.internal.note.web.NoteBlockCreateExceptionHandler.class)).hasSize(1);
                    var createNoteBlock = context.getBean(com.pawbridge.backend.community.internal.note.application.CreateNoteBlock.class);
                    assertThat(org.springframework.test.util.ReflectionTestUtils.getField(createNoteBlock, "clock")).isSameAs(context.getBean("readClock"));
                    assertThat(org.springframework.test.util.ReflectionTestUtils.getField(createNoteBlock, "members")).isSameAs(context.getBean(com.pawbridge.backend.user.api.ContactMemberQuery.class));
                    assertThat(context.getBeansOfType(com.pawbridge.backend.user.internal.statistics.application.TotalUserCountQuery.class)).hasSize(1);
                    assertThat(context.getBeansOfType(com.pawbridge.backend.user.internal.statistics.web.TotalUserCountController.class)).hasSize(1);
                    assertThat(context.getBeansOfType(com.pawbridge.backend.user.internal.statistics.web.TotalUserCountExceptionHandler.class)).hasSize(1);
                    assertThat(context.getBeansOfType(com.pawbridge.backend.user.internal.profile.application.UserProfileQuery.class)).hasSize(1);
                    assertThat(context.getBeansOfType(com.pawbridge.backend.user.internal.profile.web.UserProfileController.class)).hasSize(1);
                    assertThat(context.getBeansOfType(com.pawbridge.backend.user.internal.profile.web.UserProfileExceptionHandler.class)).hasSize(1);
                    assertThat(context.getBeansOfType(com.pawbridge.backend.user.internal.nickname.application.UserNicknameStore.class)).hasSize(1);
                    assertThat(context.getBeansOfType(com.pawbridge.backend.user.internal.nickname.application.ChangeUserNickname.class)).hasSize(1);
                    assertThat(org.springframework.aop.support.AopUtils.isAopProxy(context.getBean(com.pawbridge.backend.user.internal.nickname.application.ChangeUserNickname.class))).isTrue();
                    assertThat(context.getBeansOfType(com.pawbridge.backend.user.internal.nickname.web.UserNicknameController.class)).hasSize(1);
                    assertThat(context.getBeansOfType(com.pawbridge.backend.user.internal.nickname.web.UserNicknameExceptionHandler.class)).hasSize(1);
                    assertThat(context.getBeansOfType(com.pawbridge.backend.user.internal.favorite.application.FavoriteStatusQuery.class)).hasSize(1);
                    assertThat(context.getBeansOfType(com.pawbridge.backend.user.internal.favorite.web.FavoriteStatusController.class)).hasSize(1);
                    assertThat(context.getBeansOfType(com.pawbridge.backend.user.internal.favorite.web.FavoriteStatusExceptionHandler.class)).hasSize(1);
                    assertThat(context.getBeansOfType(com.pawbridge.backend.community.internal.comment.application.CommentListReader.class)).hasSize(1);
                    assertThat(context.getBeansOfType(com.pawbridge.backend.community.internal.comment.application.ListComments.class)).hasSize(1);
                    assertThat(context.getBeansOfType(com.pawbridge.backend.community.internal.comment.web.CommentReadController.class)).hasSize(1);
                    assertThat(context.getBeansOfType(com.pawbridge.backend.community.internal.comment.web.CommentReadExceptionHandler.class)).hasSize(1);
                    assertThat(context.getBeansOfType(com.pawbridge.backend.community.internal.comment.application.CommentWriter.class)).hasSize(1);
                    assertThat(context.getBeansOfType(com.pawbridge.backend.community.internal.comment.application.CreateComment.class)).hasSize(1);
                    assertThat(context.getBeansOfType(com.pawbridge.backend.community.internal.comment.web.CommentCreateController.class)).hasSize(1);
                    assertThat(context.getBeansOfType(com.pawbridge.backend.community.internal.comment.web.CommentCreateExceptionHandler.class)).hasSize(1);
                    assertThat(context.getBeansOfType(com.pawbridge.backend.community.internal.comment.application.CommentDeletion.class)).hasSize(1);
                    assertThat(context.getBeansOfType(com.pawbridge.backend.community.internal.comment.web.CommentDeleteController.class)).hasSize(1);
                    assertThat(context.getBeansOfType(com.pawbridge.backend.community.internal.comment.web.CommentDeleteExceptionHandler.class)).hasSize(1);
                    assertThat(context.getBeansOfType(com.pawbridge.backend.community.internal.comment.application.UpdateComment.class)).hasSize(1);
                    assertThat(context.getBeansOfType(com.pawbridge.backend.community.internal.comment.web.CommentUpdateController.class)).hasSize(1);
                    assertThat(context.getBeansOfType(com.pawbridge.backend.community.internal.comment.web.CommentUpdateExceptionHandler.class)).hasSize(1);
                    assertThat(context.getBean(com.pawbridge.backend.community.internal.comment.application.CommentReader.class))
                            .isSameAs(context.getBean(com.pawbridge.backend.community.internal.comment.application.CommentListReader.class));
                    assertThat(context.getBean(com.pawbridge.backend.community.internal.comment.application.CommentModification.class))
                            .isSameAs(context.getBean(com.pawbridge.backend.community.internal.comment.application.CommentWriter.class));
                    assertThat(context.getBeansOfType(com.pawbridge.backend.community.internal.post.application.ListPosts.class)).hasSize(1);
                    assertThat(context.getBeansOfType(com.pawbridge.backend.community.internal.post.application.SearchPosts.class)).hasSize(1);
                    assertThat(context.getBeansOfType(com.pawbridge.backend.community.internal.post.application.PostSearchReader.class)).hasSize(1);
                    assertThat(context.getBeansOfType(com.pawbridge.backend.community.internal.post.web.PostSearchController.class)).hasSize(1);
                    assertThat(context.getBeansOfType(com.pawbridge.backend.community.internal.post.web.PostSearchExceptionHandler.class)).hasSize(1);
                    assertThat(context.getBeansOfType(com.pawbridge.backend.community.internal.post.application.PostReader.class)).hasSize(1);
                    assertThat(context.getBeansOfType(com.pawbridge.backend.community.internal.post.application.PostListReader.class)).hasSize(1);
                    assertThat(context.getBean(com.pawbridge.backend.community.internal.post.application.PostReader.class))
                            .isSameAs(context.getBean(com.pawbridge.backend.community.internal.post.application.PostListReader.class));
                    assertThat(context.getBeansOfType(com.pawbridge.backend.community.internal.post.web.PostReadController.class)).hasSize(1);
                    assertThat(context.getBeansOfType(com.pawbridge.backend.community.internal.post.application.DeletePost.class)).hasSize(1);
                    assertThat(context.getBeansOfType(com.pawbridge.backend.community.internal.post.application.PostMediaRemover.class)).hasSize(1);
                    assertThat(context.getBeansOfType(com.pawbridge.backend.community.internal.post.application.PostDeletion.class)).hasSize(1);
                    assertThat(context.getBeansOfType(com.pawbridge.backend.community.internal.post.web.PostDeleteController.class)).hasSize(1);
                    assertThat(context.getBeansOfType(com.pawbridge.backend.community.internal.post.application.UpdatePost.class)).hasSize(1);
                    assertThat(context.getBeansOfType(com.pawbridge.backend.community.internal.post.application.PostModification.class)).hasSize(1);
                    assertThat(context.getBeansOfType(com.pawbridge.backend.community.internal.post.web.PostUpdateController.class)).hasSize(1);
                    var pools = context.getBeansOfType(com.zaxxer.hikari.HikariDataSource.class);
                    assertThat(pools.get("dataSource").isReadOnly()).isTrue();
                    assertThat(pools.get("dataSource").getMaximumPoolSize()).isEqualTo(2);
                    assertThat(pools.get("writeDataSource").isReadOnly()).isFalse();
                    assertThat(pools.get("writeDataSource").getMaximumPoolSize()).isEqualTo(1);
                    var readJdbc = context.getBean("readJdbc", org.springframework.jdbc.core.JdbcTemplate.class);
                    var searchJdbc = context.getBean("searchJdbc", org.springframework.jdbc.core.JdbcTemplate.class);
                    assertThat(readJdbc.getQueryTimeout()).isEqualTo(3);
                    assertThat(searchJdbc.getQueryTimeout()).isEqualTo(5);
                    assertThat(searchJdbc.getDataSource()).isSameAs(pools.get("dataSource"));
                    assertThat(readJdbc.getDataSource()).isSameAs(searchJdbc.getDataSource());
                    var manager = context.getBean("transactionManager", org.springframework.jdbc.support.JdbcTransactionManager.class);
                    assertThat(manager.isEnforceReadOnly()).isTrue();
                    var readTransaction = context.getBean("readOnlyTransaction", org.springframework.transaction.support.TransactionTemplate.class);
                    var searchTransaction = context.getBean("searchReadOnlyTransaction", org.springframework.transaction.support.TransactionTemplate.class);
                    assertThat(readTransaction.getTimeout()).isEqualTo(3);
                    assertThat(searchTransaction.getTimeout()).isEqualTo(5);
                    assertThat(readTransaction.isReadOnly()).isTrue();
                    assertThat(searchTransaction.isReadOnly()).isTrue();
                    assertThat(searchTransaction.getTransactionManager()).isSameAs(manager);
                    assertThat(readTransaction.getTransactionManager()).isSameAs(manager);
                    var writeJdbc = context.getBean("writeJdbc", org.springframework.jdbc.core.JdbcTemplate.class);
                    var writeManager = context.getBean("writeTransactionManager", org.springframework.jdbc.support.JdbcTransactionManager.class);
                    var sharedWriteTransaction = context.getBean("writeTransaction", org.springframework.transaction.support.TransactionTemplate.class);
                    assertThat(writeJdbc.getDataSource()).isSameAs(pools.get("writeDataSource"));
                    assertThat(writeJdbc.getQueryTimeout()).isEqualTo(5);
                    assertThat(writeManager.getDataSource()).isSameAs(pools.get("writeDataSource"));
                    assertThat(sharedWriteTransaction.getTransactionManager()).isSameAs(writeManager);
                    assertThat(sharedWriteTransaction.getTimeout()).isEqualTo(10);
                    var blockRemoval = context.getBean(com.pawbridge.backend.community.internal.note.application.NoteBlockRemoval.class);
                    assertThat(blockRemoval).isInstanceOf(com.pawbridge.backend.community.internal.note.infrastructure.JdbcNoteBlockRemoval.class);
                    assertThat(org.springframework.test.util.ReflectionTestUtils.getField(blockRemoval, "jdbc")).isSameAs(writeJdbc);
                    var removalTransaction = (org.springframework.transaction.support.TransactionTemplate)
                            org.springframework.test.util.ReflectionTestUtils.getField(blockRemoval, "transaction");
                    assertThat(removalTransaction).isNotNull().isNotSameAs(sharedWriteTransaction);
                    assertThat(removalTransaction.getTransactionManager()).isSameAs(writeManager);
                    assertThat(removalTransaction.getTimeout()).isEqualTo(15);
                    assertThat(removalTransaction.isReadOnly()).isFalse();
                    var blockCreation = context.getBean(com.pawbridge.backend.community.internal.note.application.NoteBlockCreation.class);
                    assertThat(blockCreation).isInstanceOf(com.pawbridge.backend.community.internal.note.infrastructure.JdbcNoteBlockCreation.class);
                    assertThat(org.springframework.test.util.ReflectionTestUtils.getField(blockCreation, "jdbc")).isSameAs(writeJdbc);
                    assertThat(org.springframework.test.util.ReflectionTestUtils.getField(createNoteBlock, "creation")).isSameAs(blockCreation);
                    var creationTransaction = (org.springframework.transaction.support.TransactionTemplate)
                            org.springframework.test.util.ReflectionTestUtils.getField(blockCreation, "transaction");
                    assertThat(creationTransaction).isNotNull().isNotSameAs(sharedWriteTransaction);
                    assertThat(creationTransaction.getTransactionManager()).isSameAs(writeManager);
                    assertThat(creationTransaction.getTimeout()).isEqualTo(15);
                    assertThat(creationTransaction.isReadOnly()).isFalse();
                    var streams = context.getBean(com.pawbridge.backend.community.internal.note.web.PrivateNoteStreamRegistry.class);
                    assertThat(context.getBeansOfType(com.pawbridge.backend.community.internal.note.application.NoteChangeHints.class)).hasSize(1).containsValue(streams);
                    assertThat(streams.isRunning()).isTrue();
                    assertThat(streams.getPhase()).isEqualTo(Integer.MAX_VALUE);
                    assertThat(org.springframework.test.util.ReflectionTestUtils.getField(streams, "clock")).isSameAs(context.getBean("readClock"));
                    assertThat(context.getBeansOfType(com.pawbridge.backend.community.internal.note.application.RequireActiveNoteStream.class)).hasSize(1);
                    assertThat(context.getBeansOfType(com.pawbridge.backend.community.internal.note.web.NoteStreamController.class)).hasSize(1);
                    assertThat(context.getBeansOfType(com.pawbridge.backend.community.internal.note.web.NoteStreamExceptionHandler.class)).hasSize(1);
                    assertThat(context.getBeansOfType(com.pawbridge.backend.community.internal.note.application.MarkPrivateNoteRead.class)).hasSize(1);
                    assertThat(context.getBeansOfType(com.pawbridge.backend.community.internal.note.web.NoteReadMutationController.class)).hasSize(1);
                    assertThat(context.getBeansOfType(com.pawbridge.backend.community.internal.note.web.NoteReadMutationExceptionHandler.class)).hasSize(1);
                    var markRead = context.getBean(com.pawbridge.backend.community.internal.note.application.MarkPrivateNoteRead.class);
                    assertThat(org.springframework.aop.support.AopUtils.isAopProxy(markRead)).isFalse();
                    assertThat(org.springframework.test.util.ReflectionTestUtils.getField(markRead, "clock")).isSameAs(context.getBean("readClock"));
                    var readMutation = context.getBean(com.pawbridge.backend.community.internal.note.application.NoteReadMutation.class);
                    assertThat(org.springframework.test.util.ReflectionTestUtils.getField(markRead, "mutation")).isSameAs(readMutation);
                    assertThat(org.springframework.test.util.ReflectionTestUtils.getField(readMutation, "jdbc")).isSameAs(writeJdbc);
                    assertThat(org.springframework.test.util.ReflectionTestUtils.getField(readMutation, "hints")).isSameAs(streams);
                    var mutationTransaction = (org.springframework.transaction.support.TransactionTemplate)
                            org.springframework.test.util.ReflectionTestUtils.getField(readMutation, "transaction");
                    assertThat(mutationTransaction).isNotSameAs(sharedWriteTransaction);
                    assertThat(mutationTransaction.getTransactionManager()).isSameAs(writeManager);
                    assertThat(mutationTransaction.getTimeout()).isEqualTo(15);
                    assertThat(mutationTransaction.isReadOnly()).isFalse();
                    assertThat(context.getBeansOfType(com.pawbridge.backend.community.internal.note.application.NoteFavoriteMutation.class)).hasSize(1);
                    assertThat(context.getBeansOfType(com.pawbridge.backend.community.internal.note.application.SetPrivateNoteFavorite.class)).hasSize(1);
                    assertThat(context.getBeansOfType(com.pawbridge.backend.community.internal.note.web.NoteFavoriteController.class)).hasSize(1);
                    assertThat(context.getBeansOfType(com.pawbridge.backend.community.internal.note.web.NoteFavoriteExceptionHandler.class)).hasSize(1);
                    var setFavorite = context.getBean(com.pawbridge.backend.community.internal.note.application.SetPrivateNoteFavorite.class);
                    assertThat(org.springframework.aop.support.AopUtils.isAopProxy(setFavorite)).isFalse();
                    assertThat(org.springframework.test.util.ReflectionTestUtils.getField(setFavorite, "members")).isSameAs(context.getBean(com.pawbridge.backend.user.api.ContactMemberQuery.class));
                    assertThat(org.springframework.test.util.ReflectionTestUtils.getField(setFavorite, "clock")).isSameAs(context.getBean("readClock"));
                    var favoriteMutation = context.getBean(com.pawbridge.backend.community.internal.note.application.NoteFavoriteMutation.class);
                    assertThat(org.springframework.test.util.ReflectionTestUtils.getField(setFavorite, "mutation")).isSameAs(favoriteMutation);
                    assertThat(org.springframework.test.util.ReflectionTestUtils.getField(favoriteMutation, "jdbc")).isSameAs(writeJdbc);
                    assertThat(org.springframework.test.util.ReflectionTestUtils.getField(favoriteMutation, "hints")).isSameAs(streams);
                    var favoriteTransaction = (org.springframework.transaction.support.TransactionTemplate)
                            org.springframework.test.util.ReflectionTestUtils.getField(favoriteMutation, "transaction");
                    assertThat(favoriteTransaction).isNotSameAs(sharedWriteTransaction).isNotSameAs(mutationTransaction);
                    assertThat(favoriteTransaction.getTransactionManager()).isSameAs(writeManager);
                    assertThat(favoriteTransaction.getTimeout()).isEqualTo(15);
                    assertThat(favoriteTransaction.isReadOnly()).isFalse();
                    assertThat(context.getBeansOfType(com.pawbridge.backend.community.internal.note.application.NoteDeletion.class)).hasSize(1);
                    assertThat(context.getBeansOfType(com.pawbridge.backend.community.internal.note.application.DeletePrivateNote.class)).hasSize(1);
                    assertThat(context.getBeansOfType(com.pawbridge.backend.community.internal.note.web.NoteDeletionController.class)).hasSize(1);
                    assertThat(context.getBeansOfType(com.pawbridge.backend.community.internal.note.web.NoteDeletionExceptionHandler.class)).hasSize(1);
                    var deleteNote = context.getBean(com.pawbridge.backend.community.internal.note.application.DeletePrivateNote.class);
                    assertThat(org.springframework.aop.support.AopUtils.isAopProxy(deleteNote)).isFalse();
                    assertThat(org.springframework.test.util.ReflectionTestUtils.getField(deleteNote, "members")).isSameAs(context.getBean(com.pawbridge.backend.user.api.ContactMemberQuery.class));
                    assertThat(org.springframework.test.util.ReflectionTestUtils.getField(deleteNote, "clock")).isSameAs(context.getBean("readClock"));
                    var deletion = context.getBean(com.pawbridge.backend.community.internal.note.application.NoteDeletion.class);
                    assertThat(org.springframework.test.util.ReflectionTestUtils.getField(deleteNote, "mutation")).isSameAs(deletion);
                    assertThat(org.springframework.test.util.ReflectionTestUtils.getField(deletion, "jdbc")).isSameAs(writeJdbc);
                    assertThat(org.springframework.test.util.ReflectionTestUtils.getField(deletion, "hints")).isSameAs(streams);
                    var deletionTransaction = (org.springframework.transaction.support.TransactionTemplate)
                            org.springframework.test.util.ReflectionTestUtils.getField(deletion, "transaction");
                    assertThat(deletionTransaction).isNotSameAs(sharedWriteTransaction).isNotSameAs(mutationTransaction).isNotSameAs(favoriteTransaction);
                    assertThat(deletionTransaction.getTransactionManager()).isSameAs(writeManager);
                    assertThat(deletionTransaction.getTimeout()).isEqualTo(15);
                    assertThat(deletionTransaction.isReadOnly()).isFalse();
                    pools.values().forEach(pool -> {
                        assertThat(pool.getHikariPoolMXBean().getActiveConnections()).isZero();
                        assertThat(pool.getHikariPoolMXBean().getTotalConnections()).isZero();
                    });
                });
    }

    @Test
    void given__candidate_multipart_configuration__when__binding_servlet_configuration__then__preserve_file_and_request_limits() {
        new WebApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withConfiguration(AutoConfigurations.of(MultipartAutoConfiguration.class))
                .withPropertyValues("spring.config.location=classpath:/application.properties")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    var properties = context.getBean(MultipartProperties.class);
                    assertThat(properties.getMaxFileSize()).isEqualTo(DataSize.ofMegabytes(100));
                    assertThat(properties.getMaxRequestSize()).isEqualTo(DataSize.ofMegabytes(200));
                    var servletConfig = context.getBean(MultipartConfigElement.class);
                    assertThat(servletConfig.getMaxFileSize()).isEqualTo(DataSize.ofMegabytes(100).toBytes());
                    assertThat(servletConfig.getMaxRequestSize()).isEqualTo(DataSize.ofMegabytes(200).toBytes());
                });
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"false", "absent"})
    void given__false_or_absent_visit_flag__when__assembling_actual_configuration__then__no_visit_beans_or_jdbc_calls(String flag) {
        var jdbc = org.mockito.Mockito.mock(org.springframework.jdbc.core.JdbcTemplate.class);
        var transaction = org.mockito.Mockito.mock(org.springframework.transaction.support.TransactionTemplate.class);
        var runner = new WebApplicationContextRunner()
                .withUserConfiguration(com.pawbridge.backend.boot.configuration.VisitReadConfiguration.class)
                .withInitializer(context -> {
                    context.getBeanFactory().registerSingleton("readJdbc", jdbc);
                    context.getBeanFactory().registerSingleton("readOnlyTransaction", transaction);
                    context.getBeanFactory().registerSingleton("readClock", java.time.Clock.systemUTC());
                });
        if (!flag.equals("absent")) runner = runner.withPropertyValues("pawbridge.visits.enabled=" + flag);

        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBeansOfType(com.pawbridge.backend.community.internal.visit.application.VisitCountReader.class)).isEmpty();
            assertThat(context.getBeansOfType(com.pawbridge.backend.community.internal.visit.application.ReadTodayVisits.class)).isEmpty();
            assertThat(context.getBeansOfType(com.pawbridge.backend.community.internal.visit.web.VisitTodayController.class)).isEmpty();
            assertThat(context.getBeansOfType(com.pawbridge.backend.community.internal.visit.web.VisitTodayExceptionHandler.class)).isEmpty();
            org.mockito.Mockito.verifyNoInteractions(jdbc, transaction);
        });
    }

    private static Map<String, String> inputs() {
        return new HashMap<>(Map.of("F2_JDBC_URL", "jdbc:postgresql://127.0.0.1:5432/pawbridge_f2_ci",
                "F2_DB_USER", "fixture-reader", "F2_DB_PASSWORD", "synthetic-only"));
    }
}
