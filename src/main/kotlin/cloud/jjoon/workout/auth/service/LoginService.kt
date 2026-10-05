package cloud.jjoon.workout.auth.service

import cloud.jjoon.workout.auth.domain.LoginSession
import cloud.jjoon.workout.auth.domain.LoginSessionStatus
import cloud.jjoon.workout.auth.domain.User
import cloud.jjoon.workout.auth.repository.LoginSessionRepository
import cloud.jjoon.workout.auth.repository.UserRepository
import cloud.jjoon.workout.common.error.ErrorCode
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.time.Instant

@Service
class LoginService(
    private val userRepository: UserRepository,
    private val loginSessionRepository: LoginSessionRepository,
    private val clock: Clock,
) {

    /** Failures are returned, not thrown, so the failure count commits before the error response (DEC-AUTH-005). */
    @Transactional
    fun login(loginId: String, pin: String): LoginResult {
        val user = userRepository.findForUpdateByLoginId(loginId)
        if (user == null) {
            log.info("event=auth.login_failed reason=unknown_login_id")
            return LoginResult.Failed(ErrorCode.AUTH_INVALID_CREDENTIALS)
        }
        val now = clock.instant()

        user.lockedUntil?.let { lockedUntil ->
            if (lockedUntil.isAfter(now)) {
                return LoginResult.Failed(ErrorCode.AUTH_ACCOUNT_LOCKED, mapOf("retryAt" to lockedUntil))
            }
            user.lockedUntil = null
        }

        if (!pinMatches(user.pin, pin)) {
            recordFailure(user, now)
            log.info("event=auth.login_failed userId={}", user.id)
            return LoginResult.Failed(ErrorCode.AUTH_INVALID_CREDENTIALS)
        }

        user.failedPinCount = 0
        user.updatedAt = now
        // Ended rows are kept only long enough to report why they ended (DEC-AUTH-002).
        loginSessionRepository.deleteEndedBefore(user.id, now.minus(LoginSession.TIME_TO_LIVE))
        endActiveSession(user, now)
        val token = LoginToken.generate()
        val session = loginSessionRepository.save(
            LoginSession(
                userId = user.id,
                tokenHash = LoginToken.hash(token),
                status = LoginSessionStatus.ACTIVE,
                createdAt = now,
                lastUsedAt = now,
            ),
        )
        log.info("event=auth.login_succeeded userId={} loginSessionId={}", user.id, session.id)
        return LoginResult.Succeeded(token)
    }

    /** Always succeeds, even for unknown or already-ended tokens (REQ-AUTH-002). */
    @Transactional
    fun logout(token: String?) {
        if (token == null) return
        val session = loginSessionRepository.findByTokenHash(LoginToken.hash(token)) ?: return
        loginSessionRepository.delete(session)
        log.info("event=auth.logged_out userId={} loginSessionId={}", session.userId, session.id)
    }

    // Flushed immediately: Hibernate runs inserts before updates, which would violate ux_login_session_user_active.
    private fun endActiveSession(user: User, now: Instant) {
        val active = loginSessionRepository.findByUserIdAndStatus(user.id, LoginSessionStatus.ACTIVE) ?: return
        active.status = if (active.isExpiredAt(now)) LoginSessionStatus.EXPIRED else LoginSessionStatus.REPLACED
        active.endedAt = now
        loginSessionRepository.saveAndFlush(active)
        if (active.status == LoginSessionStatus.REPLACED) {
            log.info("event=auth.session_replaced userId={} loginSessionId={}", user.id, active.id)
        }
    }

    // Reset to 0 when locking so counting restarts after the lock expires (BR-008, DEC-AUTH-006).
    private fun recordFailure(user: User, now: Instant) {
        user.failedPinCount += 1
        if (user.failedPinCount >= MAX_FAILED_PIN_COUNT) {
            user.failedPinCount = 0
            user.lockedUntil = now.plus(LOCK_DURATION)
            log.info("event=auth.account_locked userId={} lockedUntil={}", user.id, user.lockedUntil)
        }
        user.updatedAt = now
    }

    private fun pinMatches(stored: String, given: String): Boolean =
        MessageDigest.isEqual(stored.toByteArray(), given.toByteArray())

    companion object {
        private val log = LoggerFactory.getLogger(LoginService::class.java)
        private const val MAX_FAILED_PIN_COUNT = 5
        private val LOCK_DURATION: Duration = Duration.ofMinutes(5)
    }
}

sealed interface LoginResult {
    data class Succeeded(val token: String) : LoginResult
    data class Failed(val code: ErrorCode, val details: Map<String, Any>? = null) : LoginResult
}
