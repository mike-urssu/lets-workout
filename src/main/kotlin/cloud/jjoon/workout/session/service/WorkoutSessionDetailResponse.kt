package cloud.jjoon.workout.session.service

import cloud.jjoon.workout.session.domain.WorkoutSession
import cloud.jjoon.workout.session.domain.WorkoutSessionStatus
import cloud.jjoon.workout.session.repository.SessionExerciseRow
import cloud.jjoon.workout.session.repository.SetRow
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

data class WorkoutSessionDetailResponse(
    val id: UUID,
    val status: WorkoutSessionStatus,
    val performedDate: LocalDate,
    val startedAt: Instant,
    val endedAt: Instant?,
    val memo: String?,
    val exercises: List<SessionExerciseResponse>,
    val summary: WorkoutSessionSummary,
) {
    companion object {
        /** Set numbers and totals are computed from the sets, never stored (DEC-WORKOUT-002, DEC-WORKOUT-003). */
        fun of(session: WorkoutSession, exercises: List<SessionExerciseRow>): WorkoutSessionDetailResponse {
            val sets = exercises.flatMap { it.sets }
            return WorkoutSessionDetailResponse(
                id = session.id!!,
                status = session.status,
                performedDate = session.performedDate,
                startedAt = session.startedAt,
                endedAt = session.endedAt,
                memo = session.memo,
                exercises = exercises.map(SessionExerciseResponse::of),
                summary = WorkoutSessionSummary(
                    durationSeconds = session.endedAt?.let { Duration.between(session.startedAt, it).seconds },
                    exerciseCount = exercises.count { it.sets.isNotEmpty() },
                    totalSets = sets.size,
                    totalRepetitions = sets.sumOf { it.repetitions },
                    totalWeight = sets.sumOf { it.weight * it.repetitions.toBigDecimal() }.setScale(2),
                ),
            )
        }
    }
}

data class SessionExerciseResponse(
    val sessionExerciseId: UUID,
    val exerciseId: UUID,
    val name: String,
    val category: String,
    val imageUrl: String?,
    val sets: List<WorkoutSetResponse>,
    val totalSets: Int,
    val totalRepetitions: Int,
) {
    companion object {
        fun of(row: SessionExerciseRow) = SessionExerciseResponse(
            sessionExerciseId = row.sessionExerciseId,
            exerciseId = row.exerciseId,
            name = row.name,
            category = row.category,
            imageUrl = row.imageUrl,
            sets = row.sets.mapIndexed { index, set -> WorkoutSetResponse.of(set, setNumber = index + 1) },
            totalSets = row.sets.size,
            totalRepetitions = row.sets.sumOf { it.repetitions },
        )
    }
}

data class WorkoutSetResponse(val id: UUID, val setNumber: Int, val weight: BigDecimal, val repetitions: Int) {
    companion object {
        fun of(set: SetRow, setNumber: Int) = WorkoutSetResponse(set.id, setNumber, set.weight, set.repetitions)
    }
}

data class WorkoutSessionSummary(
    val durationSeconds: Long?,
    val exerciseCount: Int,
    val totalSets: Int,
    val totalRepetitions: Int,
    val totalWeight: BigDecimal,
)
