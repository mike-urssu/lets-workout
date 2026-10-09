package cloud.jjoon.workout.media.repository

import cloud.jjoon.workout.media.domain.WorkoutMedia
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface WorkoutMediaRepository : JpaRepository<WorkoutMedia, UUID> {
    fun countByWorkoutSessionId(workoutSessionId: UUID): Long
    fun findByWorkoutSessionId(workoutSessionId: UUID): List<WorkoutMedia>
    fun deleteByWorkoutSessionIdIn(workoutSessionIds: Collection<UUID>)
    fun findByWorkoutSessionIdIn(workoutSessionIds: Collection<UUID>): List<WorkoutMedia>
}
