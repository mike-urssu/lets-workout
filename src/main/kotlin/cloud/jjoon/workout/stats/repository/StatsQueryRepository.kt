package cloud.jjoon.workout.stats.repository

import cloud.jjoon.workout.exercise.repository.LIST_ORDER
import cloud.jjoon.workout.jooq.Tables.EXERCISE
import cloud.jjoon.workout.jooq.Tables.WORKOUT_SESSION
import cloud.jjoon.workout.jooq.Tables.WORKOUT_SESSION_EXERCISE
import cloud.jjoon.workout.jooq.Tables.WORKOUT_SET
import cloud.jjoon.workout.session.domain.WorkoutSessionStatus
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
     * Days the user recorded sets of the body part in a completed session (BR-020), before [before] (all when null),
     * latest first, at most [limit] (one more than shown tells whether earlier ones exist).
     */
    fun findWorkoutDays(userId: UUID, categoryId: UUID, before: LocalDate?, limit: Int): List<LocalDate> {
        val ws = WORKOUT_SESSION
        val wse = WORKOUT_SESSION_EXERCISE
        return dsl.selectDistinct(ws.PERFORMED_DATE)
            .from(ws)
            .where(ws.USER_ID.eq(userId))
            .and(ws.STATUS.eq(WorkoutSessionStatus.COMPLETED.name))
            .and(before?.let(ws.PERFORMED_DATE::lt) ?: noCondition())
            .andExists(
                selectOne().from(wse)
                    .join(EXERCISE).on(EXERCISE.ID.eq(wse.EXERCISE_ID))
                    .join(WORKOUT_SET).on(WORKOUT_SET.WORKOUT_SESSION_EXERCISE_ID.eq(wse.ID))
                    .where(wse.WORKOUT_SESSION_ID.eq(ws.ID))
                    .and(EXERCISE.EXERCISE_CATEGORY_ID.eq(categoryId)),
            )
            .orderBy(ws.PERFORMED_DATE.desc())
            .limit(limit)
            .fetch(ws.PERFORMED_DATE)
    }

    /** Days the user recorded sets of any of the exercises in a completed session (BR-015), like [findWorkoutDays]. */
    fun findExerciseDays(userId: UUID, exerciseIds: Collection<UUID>, before: LocalDate?, limit: Int): List<LocalDate> {
        val ws = WORKOUT_SESSION
        val wse = WORKOUT_SESSION_EXERCISE
        return dsl.selectDistinct(ws.PERFORMED_DATE)
            .from(ws)
            .where(ws.USER_ID.eq(userId))
            .and(ws.STATUS.eq(WorkoutSessionStatus.COMPLETED.name))
            .and(before?.let(ws.PERFORMED_DATE::lt) ?: noCondition())
            .andExists(
                selectOne().from(wse)
                    .join(WORKOUT_SET).on(WORKOUT_SET.WORKOUT_SESSION_EXERCISE_ID.eq(wse.ID))
                    .where(wse.WORKOUT_SESSION_ID.eq(ws.ID))
                    .and(wse.EXERCISE_ID.`in`(exerciseIds)),
            )
            .orderBy(ws.PERFORMED_DATE.desc())
            .limit(limit)
            .fetch(ws.PERFORMED_DATE)
    }

    /** The body part's exercises the user has sets for in a completed session, in list order (BR-011, BR-017). */
    fun findRecordedExercises(userId: UUID, categoryId: UUID): List<StatsExercise> {
        val ws = WORKOUT_SESSION
        val wse = WORKOUT_SESSION_EXERCISE
        return dsl.select(EXERCISE.ID, EXERCISE.NAME)
            .from(EXERCISE)
            .where(EXERCISE.EXERCISE_CATEGORY_ID.eq(categoryId))
            .and(EXERCISE.USER_ID.eq(userId))
            .andExists(
                selectOne().from(wse)
                    .join(ws).on(ws.ID.eq(wse.WORKOUT_SESSION_ID))
                    .where(wse.EXERCISE_ID.eq(EXERCISE.ID))
                    .and(ws.USER_ID.eq(userId))
                    .and(ws.STATUS.eq(WorkoutSessionStatus.COMPLETED.name))
                    .andExists(selectOne().from(WORKOUT_SET).where(WORKOUT_SET.WORKOUT_SESSION_EXERCISE_ID.eq(wse.ID))),
            )
            .orderBy(LIST_ORDER)
            .fetch { StatsExercise(it.value1(), it.value2()) }
    }

    /** Σ(weight × repetitions) of the body part per day over the given days (BR-005). */
    fun sumVolumeByDay(userId: UUID, categoryId: UUID, dates: Collection<LocalDate>): Map<LocalDate, BigDecimal> {
        val ws = WORKOUT_SESSION
        val wse = WORKOUT_SESSION_EXERCISE
        val volume = sum(WORKOUT_SET.WEIGHT.mul(WORKOUT_SET.REPETITIONS))
        return dsl.select(ws.PERFORMED_DATE, volume)
            .from(ws)
            .join(wse).on(wse.WORKOUT_SESSION_ID.eq(ws.ID))
            .join(EXERCISE).on(EXERCISE.ID.eq(wse.EXERCISE_ID))
            .join(WORKOUT_SET).on(WORKOUT_SET.WORKOUT_SESSION_EXERCISE_ID.eq(wse.ID))
            .where(ws.USER_ID.eq(userId))
            .and(ws.STATUS.eq(WorkoutSessionStatus.COMPLETED.name))
            .and(ws.PERFORMED_DATE.`in`(dates))
            .and(EXERCISE.EXERCISE_CATEGORY_ID.eq(categoryId))
            .groupBy(ws.PERFORMED_DATE)
            .fetch()
            .associate { it.value1() to it.value2() }
    }

    /** Someone else's exercise is as unknown as a missing one (workout-exercise-manage 7.2). */
    fun findExercises(userId: UUID, ids: Collection<UUID>): Map<UUID, String> =
        dsl.select(EXERCISE.ID, EXERCISE.NAME).from(EXERCISE).where(EXERCISE.ID.`in`(ids)).and(EXERCISE.USER_ID.eq(userId))
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
