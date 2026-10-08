package cloud.jjoon.workout.exercise.repository

import cloud.jjoon.workout.jooq.Tables.EXERCISE
import cloud.jjoon.workout.jooq.Tables.WORKOUT_SESSION
import cloud.jjoon.workout.jooq.Tables.WORKOUT_SESSION_EXERCISE
import cloud.jjoon.workout.session.domain.WorkoutSessionStatus
import org.jooq.DSLContext
import org.jooq.impl.DSL.field
import org.jooq.impl.DSL.max
import org.jooq.impl.DSL.noCondition
import org.jooq.impl.DSL.select
import org.springframework.stereotype.Repository
import java.time.LocalDate
import java.util.UUID

@Repository
class ExerciseQueryRepository(private val dsl: DSLContext) {

    /** [keyword] matches part of the name, ignoring case; jOOQ escapes its wildcard characters. */
    fun search(userId: UUID, keyword: String?): List<ExerciseRow> {
        // Only completed sessions count as having done the exercise (DEC-WORKOUT-012).
        val lastPerformedDate = field(
            select(max(WORKOUT_SESSION.PERFORMED_DATE))
                .from(WORKOUT_SESSION_EXERCISE)
                .join(WORKOUT_SESSION).on(WORKOUT_SESSION.ID.eq(WORKOUT_SESSION_EXERCISE.WORKOUT_SESSION_ID))
                .where(WORKOUT_SESSION_EXERCISE.EXERCISE_ID.eq(EXERCISE.ID))
                .and(WORKOUT_SESSION.USER_ID.eq(userId))
                .and(WORKOUT_SESSION.STATUS.eq(WorkoutSessionStatus.COMPLETED.name)),
        )
        return dsl.select(EXERCISE.ID, EXERCISE.NAME, EXERCISE.CATEGORY, EXERCISE.IMAGE_URL, lastPerformedDate)
            .from(EXERCISE)
            .where(if (keyword == null) noCondition() else EXERCISE.NAME.containsIgnoreCase(keyword))
            .orderBy(EXERCISE.CATEGORY, EXERCISE.NAME)
            .fetch { ExerciseRow(it.value1(), it.value2(), it.value3(), it.value4(), it.value5()) }
    }
}

data class ExerciseRow(
    val id: UUID,
    val name: String,
    val category: String,
    val imageUrl: String?,
    val lastPerformedDate: LocalDate?,
)
