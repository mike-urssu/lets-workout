package cloud.jjoon.workout.session.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.UuidGenerator
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "workout_session_exercise")
class WorkoutSessionExercise(
    @Column(name = "workout_session_id", nullable = false, updatable = false)
    val workoutSessionId: UUID,

    @Column(name = "exercise_id", nullable = false, updatable = false)
    val exerciseId: UUID,

    /** Orders exercises within the session (DEC-WORKOUT-002). */
    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant,
) {
    @Id
    @GeneratedValue
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    var id: UUID? = null
}
