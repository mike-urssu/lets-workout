package cloud.jjoon.workout.support

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.util.UUID

/** 운영자가 DB에 직접 실행하는 계정 관리 SQL (docs/design/architecture.md 10.7). */
@Component
class Operator(private val jdbc: JdbcTemplate) {

    fun issueAccount(loginId: String, pin: String) {
        jdbc.update("INSERT INTO users (login_id, pin) VALUES (?, ?)", loginId, pin)
    }

    fun reissuePin(loginId: String, pin: String) {
        jdbc.update(
            "UPDATE users SET pin = ?, failed_pin_count = 0, locked_until = NULL, updated_at = now() WHERE login_id = ?",
            pin, loginId,
        )
    }

    fun unlock(loginId: String) {
        jdbc.update(
            "UPDATE users SET failed_pin_count = 0, locked_until = NULL, updated_at = now() WHERE login_id = ?",
            loginId,
        )
    }

    fun deleteAccount(loginId: String) {
        jdbc.update("DELETE FROM users WHERE login_id = ?", loginId)
    }

    fun deleteAllAccounts() {
        jdbc.update("DELETE FROM users")
    }

    /** Every account gets its own copy of the default list (BR-022); tests look exercises up by owner and name. */
    fun exerciseId(name: String, userId: UUID): UUID =
        jdbc.queryForObject("SELECT id FROM exercise WHERE user_id = ? AND name = ?", UUID::class.java, userId, name)!!

    fun categoryId(name: String): UUID =
        jdbc.queryForObject("SELECT id FROM exercise_category WHERE name = ?", UUID::class.java, name)!!
}
