package com.pawbridge.backend.contract

import java.sql.DriverManager
import java.sql.SQLException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/** The contract database itself: the migrations, the application role and the revoke helper. */
@Tag("contract")
class ContractInfrastructureTest {
    @Test
    fun `마이그레이션을 적용했을 때__테이블을 조회하면__세 스키마의 현재 테이블만 있다`() {
        // given
        // when
        val tables = ContractPostgres.admin.queryForList(
            "SELECT schemaname || '.' || tablename FROM pg_tables WHERE schemaname IN (?, ?, ?)",
            String::class.java,
            ContractPostgres.USER_SCHEMA,
            ContractPostgres.COMMUNITY_SCHEMA,
            ContractPostgres.ANIMAL_SCHEMA,
        )

        // then
        assertThat(tables).contains(
            "pawbridge_user.users",
            "pawbridge_user.auth_sessions",
            "pawbridge_user.auth_session_token_history",
            "pawbridge_community.posts",
            "pawbridge_community.private_notes",
            "pawbridge_community.member_chat_rooms",
            "pawbridge_animal.animals",
            "pawbridge_animal.lost_gallery_documents",
            "pawbridge_animal.batch_job_instance",
        )
        // user V5/V6, community V12 and animal V9 drop them, so the files were applied in version order.
        assertThat(tables).doesNotContain(
            "pawbridge_user.refresh_tokens",
            "pawbridge_user.processed_events",
            "pawbridge_community.processed_events",
            "pawbridge_animal.processed_events",
            "pawbridge_animal.sync_history",
            "pawbridge_animal.animal_image_embeddings",
        )
    }

    @Test
    fun `앱 역할일 때__권한을 조회하면__슈퍼유저도 테이블 소유자도 아니다`() {
        // given
        // when
        val superuser = ContractPostgres.admin.queryForObject(
            "SELECT rolsuper OR rolcreaterole OR rolcreatedb OR rolbypassrls OR rolreplication " +
                "FROM pg_roles WHERE rolname = ?",
            Boolean::class.javaObjectType,
            ContractPostgres.APP_ROLE,
        )
        val owned = ContractPostgres.admin.queryForObject(
            "SELECT count(*) FROM pg_tables WHERE schemaname IN (?, ?) AND tableowner = ?",
            Long::class.javaObjectType,
            ContractPostgres.USER_SCHEMA,
            ContractPostgres.COMMUNITY_SCHEMA,
            ContractPostgres.APP_ROLE,
        )

        // then
        assertThat(superuser).isFalse()
        assertThat(owned).isZero()
    }

    @Test
    fun `열 권한을 회수했을 때__앱 역할로 조회하면__거부되고 끝나면 복구된다`() {
        // given
        assertThat(canReadEmail()).isNull()

        // when
        val denied = ContractPostgres.withRevoked("SELECT(email)", "pawbridge_user.users") { canReadEmail() }

        // then
        assertThat(denied).isEqualTo("42501")
        assertThat(canReadEmail()).isNull()
    }

    @Test
    fun `본문이 실패했을 때__회수를 감싸면__예외를 그대로 던지고 권한은 복구된다`() {
        // given
        // when
        val failure = catchThrowable {
            ContractPostgres.withRevoked("SELECT(email)", "pawbridge_user.users") {
                throw IllegalStateException("본문 실패")
            }
        }

        // then
        assertThat(failure).isInstanceOf(IllegalStateException::class.java).hasMessage("본문 실패")
        assertThat(canReadEmail()).isNull()
    }

    /** The SQLState of the failure, or null when the application role can read the column. */
    private fun canReadEmail(): String? {
        DriverManager.getConnection(ContractPostgres.jdbcUrl, ContractPostgres.APP_ROLE, ContractPostgres.appPassword)
            .use { connection ->
                try {
                    connection.createStatement().use { it.executeQuery("SELECT email FROM pawbridge_user.users").close() }
                    return null
                } catch (failure: SQLException) {
                    return failure.sqlState
                }
            }
    }
}
