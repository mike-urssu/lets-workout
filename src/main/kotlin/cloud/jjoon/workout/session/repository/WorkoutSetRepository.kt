package cloud.jjoon.workout.session.repository

import cloud.jjoon.workout.session.domain.WorkoutSet
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import java.util.UUID

interface WorkoutSetRepository : JpaRepository<WorkoutSet, UUID> {
    fun findByIdAndWorkoutSessionExerciseId(id: UUID, workoutSessionExerciseId: UUID): WorkoutSet?

    @Modifying
    @Query("delete from WorkoutSet s where s.workoutSessionExerciseId = :workoutSessionExerciseId")
    fun deleteAllOf(workoutSessionExerciseId: UUID): Int
}
