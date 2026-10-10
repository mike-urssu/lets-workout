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

/** One shape for start, in-progress status and completion (design 5.2, DEC-WORKOUT-015). */
data class WorkoutSessionResponse(
    val id: UUID,
    val status: WorkoutSessionStatus,
    val performedDate: LocalDate,
    val startedAt: Instant,
    val endedAt: Instant?,
    val exercises: List<SessionExerciseResponse>,
    val categories: List<CategorySummary>,
    val summary: WorkoutSessionSummary,
) {
    companion object {
        /** Set numbers and every total are computed from the sets, never stored (DEC-WORKOUT-002, DEC-WORKOUT-003). */
        fun of(session: WorkoutSession, rows: List<SessionExerciseRow>): WorkoutSessionResponse {
            val exercises = rows.map(SessionExerciseResponse::of)
            val sets = rows.flatMap { it.sets }
            // BR-015: only exercises with sets, grouped by body part in the catalog's order
            val categories = rows.zip(exercises)
                .filter { (row, _) -> row.sets.isNotEmpty() }
                .groupBy { (row, _) -> row.categorySortOrder }
                .toSortedMap()
                .values.map { group ->
                    val row = group.first().first
                    CategorySummary(
                        id = row.categoryId,
                        name = row.categoryName,
                        setCount = group.sumOf { it.second.setCount },
                        volume = group.sumOf { it.second.volume },
                        sessionExerciseIds = group.map { it.second.sessionExerciseId },
                    )
                }
            return WorkoutSessionResponse(
                id = session.id!!,
                status = session.status,
                performedDate = session.performedDate,
                startedAt = session.startedAt,
                endedAt = session.endedAt,
                exercises = exercises,
                categories = categories,
                summary = WorkoutSessionSummary(
                    durationSeconds = session.endedAt?.let { Duration.between(session.startedAt, it).seconds },
                    exerciseCount = rows.count { it.sets.isNotEmpty() },
                    totalSets = sets.size,
                    totalRepetitions = sets.sumOf { it.repetitions },
                    totalVolume = volumeOf(sets),
                ),
            )
        }

        /** Σ(weight × repetitions) (BR-010, BR-015). */
        fun volumeOf(sets: List<SetRow>): BigDecimal = sets.sumOf { it.weight * it.repetitions.toBigDecimal() }.setScale(2)
    }
}

data class SessionExerciseResponse(
    val sessionExerciseId: UUID,
    val exerciseId: UUID,
    val name: String,
    val nameEn: String?,
    val target: String?,
    val category: CategoryRef,
    val sets: List<WorkoutSetResponse>,
    val setCount: Int,
    val totalRepetitions: Int,
    val volume: BigDecimal,
    val firstSetAt: Instant?,
) {
    companion object {
        fun of(row: SessionExerciseRow) = SessionExerciseResponse(
            sessionExerciseId = row.sessionExerciseId,
            exerciseId = row.exerciseId,
            name = row.name,
            nameEn = row.nameEn,
            target = row.target,
            category = CategoryRef(row.categoryId, row.categoryName),
            sets = row.sets.mapIndexed { index, set -> WorkoutSetResponse.of(set, setNumber = index + 1) },
            setCount = row.sets.size,
            totalRepetitions = row.sets.sumOf { it.repetitions },
            volume = WorkoutSessionResponse.volumeOf(row.sets),
            firstSetAt = row.sets.firstOrNull()?.createdAt,
        )
    }
}

data class CategoryRef(val id: UUID, val name: String)

data class CategorySummary(
    val id: UUID,
    val name: String,
    val setCount: Int,
    val volume: BigDecimal,
    val sessionExerciseIds: List<UUID>,
)

data class WorkoutSetResponse(
    val id: UUID,
    val setNumber: Int,
    val weight: BigDecimal,
    val repetitions: Int,
    val createdAt: Instant,
) {
    companion object {
        fun of(set: SetRow, setNumber: Int) = WorkoutSetResponse(set.id, setNumber, set.weight, set.repetitions, set.createdAt)
    }
}

data class WorkoutSessionSummary(
    val durationSeconds: Long?,
    val exerciseCount: Int,
    val totalSets: Int,
    val totalRepetitions: Int,
    val totalVolume: BigDecimal,
)
