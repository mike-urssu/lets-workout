package cloud.jjoon.workout.stats.repository

import cloud.jjoon.workout.jooq.Tables.EXERCISE
import cloud.jjoon.workout.jooq.Tables.EXERCISE_CATEGORY
import cloud.jjoon.workout.jooq.Tables.WORKOUT_SESSION
import cloud.jjoon.workout.jooq.Tables.WORKOUT_SESSION_EXERCISE
import cloud.jjoon.workout.jooq.Tables.WORKOUT_SET
import cloud.jjoon.workout.session.domain.WorkoutSessionStatus
import cloud.jjoon.workout.session.service.CategoryRef
import org.jooq.DSLContext
import org.jooq.impl.DSL.noCondition
import org.jooq.impl.DSL.selectOne
import org.jooq.impl.DSL.sum
import org.springframework.stereotype.Repository
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

/** Reads for the stats screen: only completed sessions count (workout-stats BR-002). */
@Repository
class StatsQueryRepository(private val dsl: DSLContext) {

    /**
     * The user's workout days before [before] (all when null), latest first, at most [limit]
     * (one more than shown tells whether earlier ones exist).
     */
    fun findWorkoutDays(userId: UUID, before: LocalDate?, limit: Int): List<LocalDate> =
        dsl.selectDistinct(WORKOUT_SESSION.PERFORMED_DATE)
            .from(WORKOUT_SESSION)
            .where(WORKOUT_SESSION.USER_ID.eq(userId))
            .and(WORKOUT_SESSION.STATUS.eq(WorkoutSessionStatus.COMPLETED.name))
            .and(before?.let(WORKOUT_SESSION.PERFORMED_DATE::lt) ?: noCondition())
            .orderBy(WORKOUT_SESSION.PERFORMED_DATE.desc())
            .limit(limit)
            .fetch(WORKOUT_SESSION.PERFORMED_DATE)

    fun findCategories(): List<CategoryRef> =
        dsl.select(EXERCISE_CATEGORY.ID, EXERCISE_CATEGORY.NAME)
            .from(EXERCISE_CATEGORY)
            .orderBy(EXERCISE_CATEGORY.SORT_ORDER)
            .fetch { CategoryRef(it.value1(), it.value2()) }

    /** The body part's exercises the user has sets for in a completed session, in catalog order (BR-011, BR-017). */
    fun findRecordedExercises(userId: UUID, categoryId: UUID): List<StatsExercise> {
        val ws = WORKOUT_SESSION
        val wse = WORKOUT_SESSION_EXERCISE
        return dsl.select(EXERCISE.ID, EXERCISE.NAME)
            .from(EXERCISE)
            .where(EXERCISE.EXERCISE_CATEGORY_ID.eq(categoryId))
            .andExists(
                selectOne().from(wse)
                    .join(ws).on(ws.ID.eq(wse.WORKOUT_SESSION_ID))
                    .where(wse.EXERCISE_ID.eq(EXERCISE.ID))
                    .and(ws.USER_ID.eq(userId))
                    .and(ws.STATUS.eq(WorkoutSessionStatus.COMPLETED.name))
                    .andExists(selectOne().from(WORKOUT_SET).where(WORKOUT_SET.WORKOUT_SESSION_EXERCISE_ID.eq(wse.ID))),
            )
            .orderBy(EXERCISE.SORT_ORDER)
            .fetch { StatsExercise(it.value1(), it.value2()) }
    }

    /** Σ(weight × repetitions) per (day, body part) over the given days (BR-005). */
    fun sumVolumeByCategory(userId: UUID, dates: Collection<LocalDate>): Map<Pair<LocalDate, UUID>, BigDecimal> {
        val ws = WORKOUT_SESSION
        val wse = WORKOUT_SESSION_EXERCISE
        val volume = sum(WORKOUT_SET.WEIGHT.mul(WORKOUT_SET.REPETITIONS))
        return dsl.select(ws.PERFORMED_DATE, EXERCISE.EXERCISE_CATEGORY_ID, volume)
            .from(ws)
            .join(wse).on(wse.WORKOUT_SESSION_ID.eq(ws.ID))
            .join(EXERCISE).on(EXERCISE.ID.eq(wse.EXERCISE_ID))
            .join(WORKOUT_SET).on(WORKOUT_SET.WORKOUT_SESSION_EXERCISE_ID.eq(wse.ID))
            .where(ws.USER_ID.eq(userId))
            .and(ws.STATUS.eq(WorkoutSessionStatus.COMPLETED.name))
            .and(ws.PERFORMED_DATE.`in`(dates))
            .groupBy(ws.PERFORMED_DATE, EXERCISE.EXERCISE_CATEGORY_ID)
            .fetch()
            .associate { (it.value1() to it.value2()) to it.value3() }
    }

    fun findExercises(ids: Collection<UUID>): Map<UUID, String> =
        dsl.select(EXERCISE.ID, EXERCISE.NAME).from(EXERCISE).where(EXERCISE.ID.`in`(ids))
            .fetch().associate { it.value1() to it.value2() }

    /** Σ(weight × repetitions) per (day, exercise) over the given days and exercises (BR-009). */
    fun sumVolumeByExercise(
        userId: UUID,
        exerciseIds: Collection<UUID>,
        dates: Collection<LocalDate>,
    ): Map<Pair<LocalDate, UUID>, BigDecimal> {
        val ws = WORKOUT_SESSION
        val wse = WORKOUT_SESSION_EXERCISE
        val volume = sum(WORKOUT_SET.WEIGHT.mul(WORKOUT_SET.REPETITIONS))
        return dsl.select(ws.PERFORMED_DATE, wse.EXERCISE_ID, volume)
            .from(ws)
            .join(wse).on(wse.WORKOUT_SESSION_ID.eq(ws.ID))
            .join(WORKOUT_SET).on(WORKOUT_SET.WORKOUT_SESSION_EXERCISE_ID.eq(wse.ID))
            .where(ws.USER_ID.eq(userId))
            .and(ws.STATUS.eq(WorkoutSessionStatus.COMPLETED.name))
            .and(ws.PERFORMED_DATE.`in`(dates))
            .and(wse.EXERCISE_ID.`in`(exerciseIds))
            .groupBy(ws.PERFORMED_DATE, wse.EXERCISE_ID)
            .fetch()
            .associate { (it.value1() to it.value2()) to it.value3() }
    }
}

data class StatsExercise(val id: UUID, val name: String)
