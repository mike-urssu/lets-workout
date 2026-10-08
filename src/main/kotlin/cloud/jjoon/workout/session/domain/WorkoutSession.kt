package cloud.jjoon.workout.session.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.UuidGenerator
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

@Entity
@Table(name = "workout_session")
class WorkoutSession(
    @Column(name = "user_id", nullable = false, updatable = false)
    val userId: UUID,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var status: WorkoutSessionStatus,

    @Column(name = "performed_date", nullable = false, updatable = false)
    val performedDate: LocalDate,

    @Column(name = "started_at", nullable = false, updatable = false)
    val startedAt: Instant,

    @Column(name = "ended_at")
    var endedAt: Instant? = null,

    @Column
    var memo: String? = null,

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = startedAt,

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = startedAt,
) {
    @Id
    @GeneratedValue
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    var id: UUID? = null
}

enum class WorkoutSessionStatus { IN_PROGRESS, COMPLETED }
