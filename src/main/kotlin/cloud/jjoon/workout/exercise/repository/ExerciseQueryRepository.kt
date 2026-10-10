package cloud.jjoon.workout.exercise.repository

import cloud.jjoon.workout.jooq.Tables.EXERCISE
import cloud.jjoon.workout.jooq.Tables.EXERCISE_CATEGORY
import cloud.jjoon.workout.jooq.Tables.WORKOUT_SESSION
import cloud.jjoon.workout.jooq.Tables.WORKOUT_SESSION_EXERCISE
import cloud.jjoon.workout.jooq.Tables.WORKOUT_SET
import cloud.jjoon.workout.session.domain.WorkoutSessionStatus
import org.jooq.DSLContext
import org.jooq.impl.DSL.count
import org.jooq.impl.DSL.field
import org.jooq.impl.DSL.max
import org.jooq.impl.DSL.select
import org.jooq.impl.DSL.selectOne
import org.springframework.stereotype.Repository
import java.time.LocalDate
import java.util.UUID

@Repository
class ExerciseQueryRepository(private val dsl: DSLContext) {

    /** Body parts in display order with how many of the user's exercises each has (API-EXERCISE-004, BR-022). */
    fun findCategories(userId: UUID): List<ExerciseCategoryRow> =
        dsl.select(EXERCISE_CATEGORY.ID, EXERCISE_CATEGORY.NAME, EXERCISE_CATEGORY.IMAGE_URL, count(EXERCISE.ID))
            .from(EXERCISE_CATEGORY)
            .leftJoin(EXERCISE).on(EXERCISE.EXERCISE_CATEGORY_ID.eq(EXERCISE_CATEGORY.ID)).and(EXERCISE.USER_ID.eq(userId))
            .groupBy(EXERCISE_CATEGORY.ID)
            .orderBy(EXERCISE_CATEGORY.SORT_ORDER)
            .fetch { ExerciseCategoryRow(it.value1(), it.value2(), it.value3(), it.value4()) }

    /** Completed sessions that hold the exercise, checked for emptiness after it is deleted (BR-029). */
    fun findCompletedSessionIds(exerciseId: UUID): List<UUID> =
        dsl.select(WORKOUT_SESSION.ID)
            .from(WORKOUT_SESSION_EXERCISE)
            .join(WORKOUT_SESSION).on(WORKOUT_SESSION.ID.eq(WORKOUT_SESSION_EXERCISE.WORKOUT_SESSION_ID))
            .where(WORKOUT_SESSION_EXERCISE.EXERCISE_ID.eq(exerciseId))
            .and(WORKOUT_SESSION.STATUS.eq(WorkoutSessionStatus.COMPLETED.name))
            .fetch(WORKOUT_SESSION.ID)

    fun countSets(exerciseId: UUID): Int =
        dsl.fetchCount(
            WORKOUT_SET.join(WORKOUT_SESSION_EXERCISE).on(WORKOUT_SESSION_EXERCISE.ID.eq(WORKOUT_SET.WORKOUT_SESSION_EXERCISE_ID)),
            WORKOUT_SESSION_EXERCISE.EXERCISE_ID.eq(exerciseId),
        )

    fun categoryExists(categoryId: UUID): Boolean =
        dsl.fetchExists(EXERCISE_CATEGORY, EXERCISE_CATEGORY.ID.eq(categoryId))

    /** The body part's exercises of the user in list order with the last performed date (API-EXERCISE-001, BR-022). */
    fun findByCategory(userId: UUID, categoryId: UUID): List<ExerciseRow> {
        // Only completed sessions with at least one set count as having done the exercise (DEC-WORKOUT-012).
        val lastPerformedDate = field(
            select(max(WORKOUT_SESSION.PERFORMED_DATE))
                .from(WORKOUT_SESSION_EXERCISE)
                .join(WORKOUT_SESSION).on(WORKOUT_SESSION.ID.eq(WORKOUT_SESSION_EXERCISE.WORKOUT_SESSION_ID))
                .where(WORKOUT_SESSION_EXERCISE.EXERCISE_ID.eq(EXERCISE.ID))
                .and(WORKOUT_SESSION.USER_ID.eq(userId))
                .and(WORKOUT_SESSION.STATUS.eq(WorkoutSessionStatus.COMPLETED.name))
                .andExists(selectOne().from(WORKOUT_SET).where(WORKOUT_SET.WORKOUT_SESSION_EXERCISE_ID.eq(WORKOUT_SESSION_EXERCISE.ID))),
        )
        return dsl.select(EXERCISE.ID, EXERCISE.NAME, EXERCISE.NAME_EN, EXERCISE.TARGET, lastPerformedDate)
            .from(EXERCISE)
            .where(EXERCISE.EXERCISE_CATEGORY_ID.eq(categoryId))
            .and(EXERCISE.USER_ID.eq(userId))
            .orderBy(LIST_ORDER)
            .fetch { ExerciseRow(it.value1(), it.value2(), it.value3(), it.value4(), it.value5()) }
    }
}

/** Added or moved exercises share the last sort order when they arrive together; time then id breaks the tie (DEC-WORKOUT-025). */
val LIST_ORDER = listOf(EXERCISE.SORT_ORDER, EXERCISE.CREATED_AT, EXERCISE.ID)

data class ExerciseCategoryRow(val id: UUID, val name: String, val imageUrl: String, val exerciseCount: Int)

data class ExerciseRow(
    val id: UUID,
    val name: String,
    val nameEn: String?,
    val target: String?,
    val lastPerformedDate: LocalDate?,
)
