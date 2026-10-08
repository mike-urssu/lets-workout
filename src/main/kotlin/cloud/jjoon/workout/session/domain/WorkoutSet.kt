package cloud.jjoon.workout.session.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.UuidGenerator
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "workout_set")
class WorkoutSet(
    @Column(name = "workout_session_exercise_id", nullable = false, updatable = false)
    val workoutSessionExerciseId: UUID,

    @Column(nullable = false)
    var weight: BigDecimal,

    @Column(nullable = false)
    var repetitions: Int,

    /** Orders sets and numbers them (BR-007); the latest one ends an abandoned session (BR-013). */
    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant,

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = createdAt,
) {
    @Id
    @GeneratedValue
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    var id: UUID? = null
}
