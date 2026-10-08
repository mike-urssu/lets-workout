package cloud.jjoon.workout.support

import cloud.jjoon.workout.auth.service.LoginToken
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.sql.Timestamp
import java.time.Clock
import java.util.UUID

/** Creates a user and a login directly in the DB and hands back the token (docs/design/architecture.md 7.6). */
@Component
class TestUsers(private val jdbc: JdbcTemplate, private val clock: Clock) {

    fun signIn(loginId: String): SignedInUser {
        val userId = jdbc.queryForObject(
            "INSERT INTO users (login_id, pin) VALUES (?, '123456') RETURNING id", UUID::class.java, loginId,
        )!!
        val token = LoginToken.generate()
        val now = Timestamp.from(clock.instant())
        jdbc.update(
            "INSERT INTO login_session (id, user_id, token_hash, status, created_at, last_used_at) " +
                "VALUES (uuidv7(), ?, ?, 'ACTIVE', ?, ?)",
            userId, LoginToken.hash(token), now, now,
        )
        return SignedInUser(userId, token)
    }
}

data class SignedInUser(val id: UUID, val token: String)
