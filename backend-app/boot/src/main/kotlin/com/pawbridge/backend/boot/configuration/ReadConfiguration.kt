package com.pawbridge.backend.boot.configuration

import com.fasterxml.jackson.databind.ObjectMapper
import com.pawbridge.backend.community.internal.comment.application.CommentListReader
import com.pawbridge.backend.community.internal.comment.application.ListComments
import com.pawbridge.backend.community.internal.comment.infrastructure.JdbcCommentReader
import com.pawbridge.backend.community.internal.comment.web.CommentReadController
import com.pawbridge.backend.community.internal.comment.web.CommentReadExceptionHandler
import com.pawbridge.backend.community.internal.post.application.ListPosts
import com.pawbridge.backend.community.internal.post.application.PostListReader
import com.pawbridge.backend.community.internal.post.application.PostReader
import com.pawbridge.backend.community.internal.post.application.PostSearchReader
import com.pawbridge.backend.community.internal.post.application.ReadPost
import com.pawbridge.backend.community.internal.post.application.SearchPosts
import com.pawbridge.backend.community.internal.post.infrastructure.JdbcPostReader
import com.pawbridge.backend.community.internal.post.infrastructure.JdbcPostSearchReader
import com.pawbridge.backend.community.internal.post.web.PostReadController
import com.pawbridge.backend.community.internal.post.web.PostReadExceptionHandler
import com.pawbridge.backend.community.internal.post.web.PostSearchController
import com.pawbridge.backend.community.internal.post.web.PostSearchExceptionHandler
import com.pawbridge.backend.user.api.UserNicknameQuery
import com.pawbridge.backend.user.internal.nickname.infrastructure.JdbcUserNicknameQuery
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import javax.sql.DataSource
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.support.JdbcTransactionManager
import org.springframework.transaction.support.TransactionTemplate

@Configuration(proxyBeanMethods = false)
@Import(ReadSecurityConfiguration::class)
class ReadConfiguration {
    /**
     * The monolith's only connection pool. The historical reader/writer bean names stay as aliases so
     * every JdbcTemplate, transaction manager and EntityManagerFactory resolves this same instance.
     * Read-only protection is applied per transaction (readOnly templates + enforceReadOnly), not per pool.
     */
    @Bean(name = ["dataSource", "writeDataSource", "storeWishlistWriteDataSource"], destroyMethod = "close")
    fun dataSource(settings: ReadDatabaseSettings): HikariDataSource {
        val config = HikariConfig()
        config.jdbcUrl = settings.jdbcUrl()
        config.username = settings.username()
        config.password = settings.password()
        config.poolName = "pawbridge-db"
        config.maximumPoolSize = settings.maximumPoolSize()
        config.minimumIdle = Math.min(2, settings.maximumPoolSize())
        config.connectionTimeout = 5000
        config.validationTimeout = 1000
        config.initializationFailTimeout = -1
        config.isReadOnly = false
        return HikariDataSource(config)
    }

    @Bean
    fun readJdbc(@Qualifier("dataSource") dataSource: DataSource): JdbcTemplate {
        val jdbc = JdbcTemplate(dataSource)
        jdbc.queryTimeout = 3
        return jdbc
    }

    @Bean
    fun transactionManager(@Qualifier("dataSource") dataSource: DataSource): JdbcTransactionManager {
        val manager = JdbcTransactionManager(dataSource)
        manager.isEnforceReadOnly = true
        return manager
    }

    @Bean
    fun readOnlyTransaction(@Qualifier("transactionManager") manager: JdbcTransactionManager): TransactionTemplate {
        val transaction = TransactionTemplate(manager)
        transaction.isReadOnly = true
        transaction.timeout = 3
        return transaction
    }

    @Bean
    fun searchJdbc(@Qualifier("dataSource") dataSource: DataSource): JdbcTemplate {
        val jdbc = JdbcTemplate(dataSource)
        jdbc.queryTimeout = 5
        return jdbc
    }

    @Bean
    fun searchReadOnlyTransaction(
        @Qualifier("transactionManager") manager: JdbcTransactionManager,
    ): TransactionTemplate {
        val transaction = TransactionTemplate(manager)
        transaction.isReadOnly = true
        transaction.timeout = 5
        return transaction
    }

    @Bean
    fun userNicknameQuery(
        @Qualifier("readJdbc") readJdbc: JdbcTemplate,
        @Qualifier("readOnlyTransaction") readOnlyTransaction: TransactionTemplate,
    ): UserNicknameQuery {
        return JdbcUserNicknameQuery(readJdbc, readOnlyTransaction)
    }

    @Bean
    fun postReader(
        @Qualifier("readJdbc") readJdbc: JdbcTemplate,
        @Qualifier("readOnlyTransaction") readOnlyTransaction: TransactionTemplate,
        mapper: ObjectMapper,
    ): JdbcPostReader {
        return JdbcPostReader(readJdbc, readOnlyTransaction, mapper)
    }

    @Bean
    fun readPost(posts: PostReader, users: UserNicknameQuery): ReadPost {
        return ReadPost(posts, users)
    }

    @Bean
    fun listPosts(posts: PostListReader, users: UserNicknameQuery): ListPosts {
        return ListPosts(posts, users)
    }

    @Bean
    fun postReadController(readPost: ReadPost, listPosts: ListPosts): PostReadController {
        return PostReadController(readPost, listPosts)
    }

    @Bean
    fun postReadExceptionHandler(): PostReadExceptionHandler {
        return PostReadExceptionHandler()
    }

    @Bean
    fun commentReader(
        @Qualifier("readJdbc") readJdbc: JdbcTemplate,
        @Qualifier("readOnlyTransaction") readOnlyTransaction: TransactionTemplate,
    ): JdbcCommentReader {
        return JdbcCommentReader(readJdbc, readOnlyTransaction)
    }

    @Bean
    fun listComments(comments: CommentListReader, users: UserNicknameQuery): ListComments {
        return ListComments(comments, users)
    }

    @Bean
    fun commentReadController(listComments: ListComments): CommentReadController {
        return CommentReadController(listComments)
    }

    @Bean
    fun commentReadExceptionHandler(): CommentReadExceptionHandler {
        return CommentReadExceptionHandler()
    }

    @Bean(destroyMethod = "close")
    fun postSearchReader(
        @Qualifier("searchJdbc") searchJdbc: JdbcTemplate,
        @Qualifier("searchReadOnlyTransaction") searchReadOnlyTransaction: TransactionTemplate,
        mapper: ObjectMapper,
    ): JdbcPostSearchReader {
        return JdbcPostSearchReader(searchJdbc, searchReadOnlyTransaction, mapper)
    }

    @Bean
    fun searchPosts(posts: PostSearchReader, users: UserNicknameQuery): SearchPosts {
        return SearchPosts(posts, users)
    }

    @Bean
    fun postSearchController(searchPosts: SearchPosts): PostSearchController {
        return PostSearchController(searchPosts)
    }

    @Bean
    fun postSearchExceptionHandler(): PostSearchExceptionHandler {
        return PostSearchExceptionHandler()
    }
}
