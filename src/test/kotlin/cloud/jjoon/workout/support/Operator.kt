package cloud.jjoon.workout.support

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component

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
}
