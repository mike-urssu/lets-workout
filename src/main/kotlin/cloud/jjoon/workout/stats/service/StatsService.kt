package cloud.jjoon.workout.stats.service

import cloud.jjoon.workout.common.error.BusinessException
import cloud.jjoon.workout.common.error.ErrorCode
import cloud.jjoon.workout.common.error.ErrorResponse
import cloud.jjoon.workout.exercise.repository.ExerciseQueryRepository
import cloud.jjoon.workout.stats.repository.StatsExercise
import cloud.jjoon.workout.stats.repository.StatsQueryRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

/** Volume trends over workout days for the stats screen (design workout-stats 3.2). */
@Service
class StatsService(
    private val queryRepository: StatsQueryRepository,
    private val exerciseQueryRepository: ExerciseQueryRepository,
) {

    /**
     * API-STATS-001: up to 12 days the body part was trained before [before] (the latest ones when null), oldest first,
     * with its volume per day. Passing the newest shown day moves the window back by one such day (BR-016, BR-020).
     */
    @Transactional(readOnly = true)
    fun categoryVolumes(userId: UUID, categoryId: UUID, before: LocalDate?): CategoryVolumeTrendResponse {
        if (!exerciseQueryRepository.categoryExists(categoryId)) throw invalid("categoryId", "존재하지 않는 부위입니다.") // ERR-009
        val (dates, hasPrevious) = window(queryRepository.findWorkoutDays(userId, categoryId, before, DAYS + 1))
        val volumes = queryRepository.sumVolumeByDay(userId, categoryId, dates)
        // Every day picked has the body part's sets, so each has a volume (BR-020).
        return CategoryVolumeTrendResponse(dates, dates.map(volumes::getValue), hasPrevious)
    }

    /** API-STATS-002: exercises of the body part the user has recorded, to pick for the exercise trend. */
    @Transactional(readOnly = true)
    fun exercises(userId: UUID, categoryId: UUID): List<StatsExercise> {
        // A wrong body part is a wrong query condition, so an input error rather than 404 (ERR-004, DEC-STATS-005).
        if (!exerciseQueryRepository.categoryExists(categoryId)) throw invalid("categoryId", "존재하지 않는 부위입니다.")
        return queryRepository.findRecordedExercises(userId, categoryId)
    }

    /**
     * API-STATS-003: up to 12 days any of the exercises was done before [before] (the latest ones when null), oldest first,
     * with each exercise's volume per day. Paging works as in API-STATS-001 (BR-015, BR-016).
     */
    @Transactional(readOnly = true)
    fun exerciseVolumes(userId: UUID, exerciseIds: List<UUID>, before: LocalDate?): ExerciseVolumeTrendResponse {
        // No count limit: distinct existing exercises are bounded by the catalog (DEC-STATS-009).
        if (exerciseIds.isEmpty() || exerciseIds.toSet().size != exerciseIds.size) {
            throw invalid("exerciseIds", "1개 이상 겹치지 않게 골라야 합니다.")
        }
        val names = queryRepository.findExercises(userId, exerciseIds)
        if (names.size != exerciseIds.size) throw invalid("exerciseIds", "존재하지 않는 운동입니다.") // ERR-005
        val (dates, hasPrevious) = window(queryRepository.findExerciseDays(userId, exerciseIds, before, DAYS + 1))
        val volumes = queryRepository.sumVolumeByExercise(userId, exerciseIds, dates)
        return ExerciseVolumeTrendResponse(
            dates = dates,
            exercises = exerciseIds.map { id -> ExerciseVolumes(id, names.getValue(id), dates.map { volumes[it to id] }) },
            hasPrevious = hasPrevious,
        )
    }

    /**
     * The shown days, oldest first, out of latest-first [days] fetched one past [DAYS]: at most [DAYS] of them, none
     * before the same date [MONTHS] months back from the newest (the month's last day if it has no such date).
     * Any day left out means earlier days exist (BR-004, BR-007, BR-021, DEC-STATS-001, DEC-STATS-014).
     */
    private fun window(days: List<LocalDate>): Pair<List<LocalDate>, Boolean> {
        val from = days.firstOrNull()?.minusMonths(MONTHS) ?: return emptyList<LocalDate>() to false
        val shown = days.take(DAYS).takeWhile { !it.isBefore(from) }
        return shown.sorted() to (days.size > shown.size)
    }

    private fun invalid(field: String, reason: String) =
        BusinessException(ErrorCode.VALIDATION_FAILED, errors = listOf(ErrorResponse.FieldError(field, reason)))

    companion object {
        const val DAYS = 12 // BR-004
        const val MONTHS = 3L // BR-021
    }
}

data class CategoryVolumeTrendResponse(val dates: List<LocalDate>, val volumes: List<BigDecimal>, val hasPrevious: Boolean)

data class ExerciseVolumeTrendResponse(val dates: List<LocalDate>, val exercises: List<ExerciseVolumes>, val hasPrevious: Boolean)

data class ExerciseVolumes(val id: UUID, val name: String, val volumes: List<BigDecimal?>)
