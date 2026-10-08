package cloud.jjoon.workout.exercise.controller

import cloud.jjoon.workout.common.error.BusinessException
import cloud.jjoon.workout.common.error.ErrorCode
import cloud.jjoon.workout.exercise.repository.ExerciseCategoryRow
import cloud.jjoon.workout.exercise.repository.ExerciseQueryRepository
import cloud.jjoon.workout.exercise.repository.ExerciseRepository
import cloud.jjoon.workout.exercise.repository.ExerciseRow
import cloud.jjoon.workout.session.repository.LastRecord
import cloud.jjoon.workout.session.repository.WorkoutSessionQueryRepository
import cloud.jjoon.workout.session.service.ExpiredSessionCleaner
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
class ExerciseController(
    private val exerciseRepository: ExerciseRepository,
    private val exerciseQueryRepository: ExerciseQueryRepository,
    private val sessionQueryRepository: WorkoutSessionQueryRepository,
    private val expiredSessionCleaner: ExpiredSessionCleaner,
) {

    /** API-EXERCISE-004: body parts for the home cards. */
    @GetMapping("/api/v1/exercise-categories")
    fun categories(): List<ExerciseCategoryRow> = exerciseQueryRepository.findCategories()

    /** API-EXERCISE-001: one body part's exercises. */
    @GetMapping("/api/v1/exercises")
    fun exercises(@AuthenticationPrincipal userId: UUID, @RequestParam categoryId: UUID): List<ExerciseRow> {
        expiredSessionCleaner.cleanUp(userId) // so the last performed dates include auto-completed sessions
        if (!exerciseQueryRepository.categoryExists(categoryId)) throw BusinessException(ErrorCode.EXERCISE_CATEGORY_NOT_FOUND)
        return exerciseQueryRepository.findByCategory(userId, categoryId)
    }

    /** API-SET-004: the sets to load into the recording screen; 204 when there is none (ERR-013, DEC-WORKOUT-021). */
    @GetMapping("/api/v1/exercises/{exerciseId}/last-record")
    fun lastRecord(@AuthenticationPrincipal userId: UUID, @PathVariable exerciseId: UUID): ResponseEntity<LastRecord> {
        expiredSessionCleaner.cleanUp(userId)
        if (!exerciseRepository.existsById(exerciseId)) throw BusinessException(ErrorCode.EXERCISE_NOT_FOUND)
        return sessionQueryRepository.findLastRecord(userId, exerciseId)
            ?.let { ResponseEntity.ok(it) } ?: ResponseEntity.noContent().build()
    }
}
