package cloud.jjoon.workout.session.repository

import cloud.jjoon.workout.session.domain.WorkoutSessionExercise
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface WorkoutSessionExerciseRepository : JpaRepository<WorkoutSessionExercise, UUID> {
    fun findByIdAndWorkoutSessionId(id: UUID, workoutSessionId: UUID): WorkoutSessionExercise?
}
