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
     * API-STATS-001: up to 7 days the body part was trained before [before] (the latest ones when null), oldest first,
     * with its volume per day. Passing the newest shown day moves the window back by one such day (BR-016, BR-020).
     */
    @Transactional(readOnly = true)
    fun categoryVolumes(userId: UUID, categoryId: UUID, before: LocalDate?): CategoryVolumeTrendResponse {
        if (!exerciseQueryRepository.categoryExists(categoryId)) throw invalid("categoryId", "존재하지 않는 부위입니다.") // ERR-009
        // One extra day tells whether earlier days exist (DEC-STATS-001).
        val days = queryRepository.findWorkoutDays(userId, categoryId, before, DAYS + 1)
        val dates = days.take(DAYS).sorted()
        val volumes = queryRepository.sumVolumeByDay(userId, categoryId, dates)
        // Every day picked has the body part's sets, so each has a volume (BR-020).
        return CategoryVolumeTrendResponse(dates, dates.map(volumes::getValue), hasPrevious = days.size > DAYS)
    }

    /** API-STATS-002: exercises of the body part the user has recorded, to pick for the exercise trend. */
    @Transactional(readOnly = true)
    fun exercises(userId: UUID, categoryId: UUID): List<StatsExercise> {
        // A wrong body part is a wrong query condition, so an input error rather than 404 (ERR-004, DEC-STATS-005).
        if (!exerciseQueryRepository.categoryExists(categoryId)) throw invalid("categoryId", "존재하지 않는 부위입니다.")
        return queryRepository.findRecordedExercises(userId, categoryId)
    }

    /** API-STATS-003: each exercise's volume on the given days, the same axis as the body-part trend (BR-015). */
    @Transactional(readOnly = true)
    fun exerciseVolumes(userId: UUID, exerciseIds: List<UUID>, dates: List<LocalDate>): ExerciseVolumeTrendResponse {
        // No count limit: distinct existing exercises are bounded by the catalog (DEC-STATS-009).
        if (exerciseIds.isEmpty() || exerciseIds.toSet().size != exerciseIds.size) {
            throw invalid("exerciseIds", "1개 이상 겹치지 않게 골라야 합니다.")
        }
        if (dates.size !in 1..DAYS || dates.toSet().size != dates.size) {
            throw invalid("dates", "1개 이상 ${DAYS}개 이하의 서로 다른 날짜여야 합니다.") // BR-004
        }
        val names = queryRepository.findExercises(userId, exerciseIds)
        if (names.size != exerciseIds.size) throw invalid("exerciseIds", "존재하지 않는 운동입니다.") // ERR-005
        val sorted = dates.sorted()
        val volumes = queryRepository.sumVolumeByExercise(userId, exerciseIds, sorted)
        return ExerciseVolumeTrendResponse(
            dates = sorted,
            exercises = exerciseIds.map { id -> ExerciseVolumes(id, names.getValue(id), sorted.map { volumes[it to id] }) },
        )
    }

    private fun invalid(field: String, reason: String) =
        BusinessException(ErrorCode.VALIDATION_FAILED, errors = listOf(ErrorResponse.FieldError(field, reason)))

    companion object {
        const val DAYS = 7 // BR-004
    }
}

data class CategoryVolumeTrendResponse(val dates: List<LocalDate>, val volumes: List<BigDecimal>, val hasPrevious: Boolean)

data class ExerciseVolumeTrendResponse(val dates: List<LocalDate>, val exercises: List<ExerciseVolumes>)

data class ExerciseVolumes(val id: UUID, val name: String, val volumes: List<BigDecimal?>)
