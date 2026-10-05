package cloud.jjoon.workout.auth.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/** Accounts are created only by operator SQL (BR-001); the application never inserts users. */
@Entity
@Table(name = "users")
class User(
    @Id
    val id: UUID,

    @Column(name = "login_id", nullable = false, updatable = false)
    val loginId: String,

    @Column(nullable = false, updatable = false)
    val pin: String,

    @Column(name = "failed_pin_count", nullable = false)
    var failedPinCount: Int,

    @Column(name = "locked_until")
    var lockedUntil: Instant?,

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant,
)
