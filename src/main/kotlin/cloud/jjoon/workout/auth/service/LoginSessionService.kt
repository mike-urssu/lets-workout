package cloud.jjoon.workout.auth.service

import cloud.jjoon.workout.auth.domain.LoginSession
import cloud.jjoon.workout.auth.domain.LoginSessionStatus
import cloud.jjoon.workout.auth.repository.LoginSessionRepository
import cloud.jjoon.workout.common.error.ErrorCode
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.util.UUID

@Service
class LoginSessionService(
    private val loginSessionRepository: LoginSessionRepository,
    private val clock: Clock,
) {

    /** Commits independently so a later failure in the request does not undo the extension. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun authenticate(token: String): AuthenticationResult {
        val tokenHash = LoginToken.hash(token)
        val session = loginSessionRepository.findByTokenHash(tokenHash)
            ?: return AuthenticationResult.Rejected(ErrorCode.UNAUTHORIZED)
        val now = clock.instant()
        rejection(session, now)?.let { return AuthenticationResult.Rejected(it) }

        if (loginSessionRepository.extendIfActive(session.id!!, now) == 0) {
            // Ended between read and update (another device logged in, or the PIN was reissued).
            val current = loginSessionRepository.findByTokenHash(tokenHash)
                ?: return AuthenticationResult.Rejected(ErrorCode.UNAUTHORIZED)
            return AuthenticationResult.Rejected(rejection(current, now) ?: ErrorCode.UNAUTHORIZED)
        }
        return AuthenticationResult.Authenticated(session.userId)
    }

    private fun rejection(session: LoginSession, now: Instant): ErrorCode? = when {
        session.status == LoginSessionStatus.REPLACED -> ErrorCode.AUTH_SESSION_REPLACED
        session.status == LoginSessionStatus.REVOKED -> ErrorCode.AUTH_SESSION_REVOKED
        session.status == LoginSessionStatus.EXPIRED || session.isExpiredAt(now) -> ErrorCode.AUTH_SESSION_EXPIRED
        else -> null
    }
}

sealed interface AuthenticationResult {
    data class Authenticated(val userId: UUID) : AuthenticationResult
    data class Rejected(val code: ErrorCode) : AuthenticationResult
}
