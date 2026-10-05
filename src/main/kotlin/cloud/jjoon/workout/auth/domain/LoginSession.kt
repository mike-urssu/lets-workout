package cloud.jjoon.workout.auth.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.UuidGenerator
import java.time.Duration
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "login_session")
class LoginSession(
    @Column(name = "user_id", nullable = false, updatable = false)
    val userId: UUID,

    @Column(name = "token_hash", nullable = false, updatable = false)
    val tokenHash: String,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var status: LoginSessionStatus,

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant,

    @Column(name = "last_used_at", nullable = false)
    var lastUsedAt: Instant,

    @Column(name = "ended_at")
    var endedAt: Instant? = null,
) {
    @Id
    @GeneratedValue
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    var id: UUID? = null

    /** Expiry is derived from last use instead of stored (DEC-AUTH-003). */
    fun isExpiredAt(now: Instant): Boolean = !lastUsedAt.plus(TIME_TO_LIVE).isAfter(now)

    companion object {
        val TIME_TO_LIVE: Duration = Duration.ofDays(30)
    }
}

enum class LoginSessionStatus { ACTIVE, REPLACED, REVOKED, EXPIRED }
