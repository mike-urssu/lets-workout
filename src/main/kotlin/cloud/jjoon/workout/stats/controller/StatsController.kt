package cloud.jjoon.workout.stats.controller

import cloud.jjoon.workout.common.error.BusinessException
import cloud.jjoon.workout.common.error.ErrorCode
import cloud.jjoon.workout.session.service.ExpiredSessionCleaner
import cloud.jjoon.workout.stats.repository.StatsExercise
import cloud.jjoon.workout.stats.service.CategoryVolumeTrendResponse
import cloud.jjoon.workout.stats.service.ExerciseVolumeTrendResponse
import cloud.jjoon.workout.stats.service.StatsService
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.util.UUID

@RestController
@RequestMapping("/api/v1/stats")
class StatsController(
    private val statsService: StatsService,
    private val expiredSessionCleaner: ExpiredSessionCleaner,
) {

    /** API-STATS-001 */
    @GetMapping("/category-volumes")
    fun categoryVolumes(
        @AuthenticationPrincipal userId: UUID,
        @RequestParam categoryId: UUID,
        @RequestParam before: String?,
    ): CategoryVolumeTrendResponse {
        expiredSessionCleaner.cleanUp(userId) // sessions past 6 hours count once auto-completed (DEC-STATS-007)
        return statsService.categoryVolumes(userId, categoryId, before?.let(::date))
    }

    /** API-STATS-002 */
    @GetMapping("/exercises")
    fun exercises(@AuthenticationPrincipal userId: UUID, @RequestParam categoryId: UUID): List<StatsExercise> {
        expiredSessionCleaner.cleanUp(userId)
        return statsService.exercises(userId, categoryId)
    }

    /** API-STATS-003 */
    @GetMapping("/exercise-volumes")
    fun exerciseVolumes(
        @AuthenticationPrincipal userId: UUID,
        // Optional here so that none at all gets the same field error as too many (checked in the service).
        @RequestParam(required = false) exerciseIds: List<UUID>?,
        @RequestParam before: String?,
    ): ExerciseVolumeTrendResponse {
        expiredSessionCleaner.cleanUp(userId)
        return statsService.exerciseVolumes(userId, exerciseIds.orEmpty(), before?.let(::date))
    }

    /** ERR-002: a malformed date is an input error. */
    private fun date(text: String): LocalDate = try {
        LocalDate.parse(text)
    } catch (e: DateTimeParseException) {
        throw BusinessException(ErrorCode.VALIDATION_FAILED)
    }
}
