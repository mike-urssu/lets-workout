package cloud.jjoon.workout.exercise.repository

import cloud.jjoon.workout.exercise.domain.Exercise
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface ExerciseRepository : JpaRepository<Exercise, UUID>
