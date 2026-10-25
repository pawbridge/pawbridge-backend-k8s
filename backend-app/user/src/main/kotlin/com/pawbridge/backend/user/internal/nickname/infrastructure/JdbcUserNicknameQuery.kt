package com.pawbridge.backend.user.internal.nickname.infrastructure

import com.pawbridge.backend.user.api.UserNicknameQuery
import com.pawbridge.backend.user.api.UserNicknameUnavailableException
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.JdbcOperations
import org.springframework.jdbc.core.ResultSetExtractor
import org.springframework.transaction.TransactionException
import org.springframework.transaction.support.TransactionOperations
import java.util.Optional

class JdbcUserNicknameQuery(
    private val jdbc: JdbcOperations,
    private val readOnlyTransaction: TransactionOperations,
) : UserNicknameQuery {

    override fun findNickname(userId: Long): Optional<String> {
        try {
            return readOnlyTransaction.execute { _ ->
                jdbc.query(
                    FIND_NICKNAME_SQL,
                    ResultSetExtractor { rows ->
                        if (!rows.next()) {
                            Optional.empty()
                        } else {
                            Optional.of(rows.getString("nickname"))
                        }
                    },
                    userId,
                )
            }!!
        } catch (exception: DataAccessException) {
            throw UserNicknameUnavailableException(exception)
        } catch (exception: TransactionException) {
            throw UserNicknameUnavailableException(exception)
        }
    }

    private companion object {
        private const val FIND_NICKNAME_SQL = "SELECT nickname FROM pawbridge_user.users WHERE user_id = ?"
    }
}
