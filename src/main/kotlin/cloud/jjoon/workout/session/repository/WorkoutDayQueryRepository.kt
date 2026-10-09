package cloud.jjoon.workout.session.repository

import cloud.jjoon.workout.jooq.Tables.EXERCISE
import cloud.jjoon.workout.jooq.Tables.EXERCISE_CATEGORY
import cloud.jjoon.workout.jooq.Tables.WORKOUT_SESSION
import cloud.jjoon.workout.jooq.Tables.WORKOUT_SESSION_EXERCISE
import cloud.jjoon.workout.jooq.Tables.WORKOUT_SET
import cloud.jjoon.workout.session.domain.WorkoutSessionStatus
import cloud.jjoon.workout.session.service.CategoryRef
import org.jooq.DSLContext
import org.jooq.impl.DSL.max
import org.jooq.impl.DSL.selectOne
import org.springframework.stereotype.Repository
import java.time.Duration
import java.time.LocalDate
import java.time.YearMonth
import java.util.UUID

/** Reads by workout day: only completed sessions count (BR-018, TODO-024). */
@Repository
class WorkoutDayQueryRepository(private val dsl: DSLContext) {

    /** The user's latest workout day, where the history screen opens. */
    fun findLatestDate(userId: UUID): LocalDate? =
        dsl.select(max(WORKOUT_SESSION.PERFORMED_DATE))
            .from(WORKOUT_SESSION)
            .where(WORKOUT_SESSION.USER_ID.eq(userId))
            .and(WORKOUT_SESSION.STATUS.eq(WorkoutSessionStatus.COMPLETED.name))
            .fetchOne(0, LocalDate::class.java)

    /** The month's workout days with the body parts that got sets, in catalog order, in one query (API-WORKOUT-007). */
    fun findMonth(userId: UUID, month: YearMonth): List<CalendarDay> {
        val ws = WORKOUT_SESSION
        val wse = WORKOUT_SESSION_EXERCISE
        val category = EXERCISE_CATEGORY
        return dsl.selectDistinct(ws.PERFORMED_DATE, category.ID, category.NAME, category.SORT_ORDER)
            .from(ws)
            .join(wse).on(wse.WORKOUT_SESSION_ID.eq(ws.ID))
            .join(EXERCISE).on(EXERCISE.ID.eq(wse.EXERCISE_ID))
            .join(category).on(category.ID.eq(EXERCISE.EXERCISE_CATEGORY_ID))
            .where(ws.USER_ID.eq(userId))
            .and(ws.STATUS.eq(WorkoutSessionStatus.COMPLETED.name))
            .and(ws.PERFORMED_DATE.between(month.atDay(1), month.atEndOfMonth()))
            .andExists(selectOne().from(WORKOUT_SET).where(WORKOUT_SET.WORKOUT_SESSION_EXERCISE_ID.eq(wse.ID)))
            .orderBy(ws.PERFORMED_DATE, category.SORT_ORDER)
            .fetch()
            .groupBy({ it.value1() }, { CategoryRef(it.value2(), it.value3()) })
            .map { (date, categories) -> CalendarDay(date, categories) }
    }

    /**
     * The day's completed sessions merged in one query (BR-020): one row per exercise in the order it was first added,
     * its sets across sessions in the order they were added. Null when nothing was completed that day.
     */
    fun findDay(userId: UUID, date: LocalDate): DayRecord? {
        val ws = WORKOUT_SESSION
        val wse = WORKOUT_SESSION_EXERCISE
        val category = EXERCISE_CATEGORY
        val rows = dsl.select(
            ws.ID, ws.STARTED_AT, ws.ENDED_AT,
            wse.ID, wse.EXERCISE_ID, EXERCISE.NAME, EXERCISE.NAME_EN, EXERCISE.TARGET,
            category.ID, category.NAME, category.SORT_ORDER,
            WORKOUT_SET.ID, WORKOUT_SET.WEIGHT, WORKOUT_SET.REPETITIONS, WORKOUT_SET.CREATED_AT,
        )
            .from(ws)
            .join(wse).on(wse.WORKOUT_SESSION_ID.eq(ws.ID))
            .join(EXERCISE).on(EXERCISE.ID.eq(wse.EXERCISE_ID))
            .join(category).on(category.ID.eq(EXERCISE.EXERCISE_CATEGORY_ID))
            .leftJoin(WORKOUT_SET).on(WORKOUT_SET.WORKOUT_SESSION_EXERCISE_ID.eq(wse.ID))
            .where(ws.USER_ID.eq(userId))
            .and(ws.STATUS.eq(WorkoutSessionStatus.COMPLETED.name))
            .and(ws.PERFORMED_DATE.eq(date))
            .orderBy(ws.STARTED_AT, ws.ID, wse.CREATED_AT, wse.ID, WORKOUT_SET.CREATED_AT, WORKOUT_SET.ID)
            .fetch()
        if (rows.isEmpty()) return null
        val sessions = rows.distinctBy { it.value1() }
        val exercises = rows.groupBy { it.value5() }.values.map { group ->
            val first = group.first()
            SessionExerciseRow(
                sessionExerciseId = first.value4(),
                exerciseId = first.value5(),
                name = first.value6(),
                nameEn = first.value7(),
                target = first.value8(),
                categoryId = first.value9(),
                categoryName = first.value10(),
                categorySortOrder = first.value11(),
                sets = group.filter { it.value12() != null }.map { SetRow(it.value12(), it.value13(), it.value14(), it.value15()) },
            )
        }
        return DayRecord(
            sessionIds = sessions.map { it.value1() },
            durationSeconds = sessions.sumOf { Duration.between(it.value2(), it.value3()).seconds },
            exercises = exercises,
        )
    }

    /** Deletes the day's completed sessions; exercises, sets and media rows go by cascade (BR-020). Idempotent. */
    fun deleteDay(userId: UUID, date: LocalDate): List<UUID> =
        dsl.deleteFrom(WORKOUT_SESSION)
            .where(WORKOUT_SESSION.USER_ID.eq(userId))
            .and(WORKOUT_SESSION.STATUS.eq(WorkoutSessionStatus.COMPLETED.name))
            .and(WORKOUT_SESSION.PERFORMED_DATE.eq(date))
            .returning(WORKOUT_SESSION.ID)
            .fetch(WORKOUT_SESSION.ID)
}

data class DayRecord(val sessionIds: List<UUID>, val durationSeconds: Long, val exercises: List<SessionExerciseRow>)

data class CalendarDay(val date: LocalDate, val categories: List<CategoryRef>)
