package cloud.jjoon.workout.session.repository

import cloud.jjoon.workout.jooq.Tables.EXERCISE
import cloud.jjoon.workout.jooq.Tables.WORKOUT_SESSION
import cloud.jjoon.workout.jooq.Tables.WORKOUT_SESSION_EXERCISE
import cloud.jjoon.workout.jooq.Tables.WORKOUT_SET
import cloud.jjoon.workout.session.domain.WorkoutSessionStatus
import org.jooq.DSLContext
import org.jooq.Field
import org.jooq.impl.DSL.arrayAgg
import org.jooq.impl.DSL.exists
import org.jooq.impl.DSL.max
import org.jooq.impl.DSL.field
import org.jooq.impl.DSL.row
import org.jooq.impl.DSL.select
import org.jooq.impl.DSL.selectCount
import org.jooq.impl.DSL.selectOne
import org.springframework.stereotype.Repository
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

@Repository
class WorkoutSessionQueryRepository(private val dsl: DSLContext) {

    /** The session's exercises and their sets in the order they were added, read in one query. */
    fun findExercises(sessionId: UUID): List<SessionExerciseRow> {
        val wse = WORKOUT_SESSION_EXERCISE
        val rows = dsl.select(
            wse.ID, wse.EXERCISE_ID, EXERCISE.NAME, EXERCISE.CATEGORY, EXERCISE.IMAGE_URL,
            WORKOUT_SET.ID, WORKOUT_SET.WEIGHT, WORKOUT_SET.REPETITIONS,
        )
            .from(wse)
            .join(EXERCISE).on(EXERCISE.ID.eq(wse.EXERCISE_ID))
            .leftJoin(WORKOUT_SET).on(WORKOUT_SET.WORKOUT_SESSION_EXERCISE_ID.eq(wse.ID))
            .where(wse.WORKOUT_SESSION_ID.eq(sessionId))
            .orderBy(wse.CREATED_AT, wse.ID, WORKOUT_SET.CREATED_AT, WORKOUT_SET.ID)
            .fetch()
        return rows.groupBy { it.value1() }.values.map { group ->
            val first = group.first()
            SessionExerciseRow(
                sessionExerciseId = first.value1(),
                exerciseId = first.value2(),
                name = first.value3(),
                category = first.value4(),
                imageUrl = first.value5(),
                sets = group.filter { it.value6() != null }.map { SetRow(it.value6(), it.value7(), it.value8()) },
            )
        }
    }

    /** One query for the page; exercise names and set counts are aggregated only for the sessions on it (NFR-PERF-002). */
    fun findPage(userId: UUID, page: Int, size: Int): List<WorkoutSessionListItem> {
        val ws = WORKOUT_SESSION
        val wse = WORKOUT_SESSION_EXERCISE
        val exerciseNames = field(
            select(arrayAgg(EXERCISE.NAME).orderBy(wse.CREATED_AT, wse.ID))
                .from(wse)
                .join(EXERCISE).on(EXERCISE.ID.eq(wse.EXERCISE_ID))
                .where(wse.WORKOUT_SESSION_ID.eq(ws.ID)),
        )
        val totalSets = field(
            selectCount()
                .from(WORKOUT_SET)
                .join(wse).on(wse.ID.eq(WORKOUT_SET.WORKOUT_SESSION_EXERCISE_ID))
                .where(wse.WORKOUT_SESSION_ID.eq(ws.ID)),
        )
        return dsl.select(ws.ID, ws.STATUS, ws.PERFORMED_DATE, ws.STARTED_AT, ws.ENDED_AT, exerciseNames, totalSets)
            .from(ws)
            .where(ws.USER_ID.eq(userId))
            .orderBy(ws.PERFORMED_DATE.desc(), ws.STARTED_AT.desc(), ws.ID.desc())
            .limit(size)
            .offset(page.toLong() * size)
            .fetch {
                WorkoutSessionListItem(
                    id = it.value1(),
                    status = WorkoutSessionStatus.valueOf(it.value2()),
                    performedDate = it.value3(),
                    startedAt = it.value4(),
                    endedAt = it.value5(),
                    durationSeconds = it.value5()?.let { endedAt -> Duration.between(it.value4(), endedAt).seconds },
                    exerciseNames = it.value6()?.distinct().orEmpty(), // each exercise once, in the order first added
                    totalSets = it.value7(),
                )
            }
    }

    fun count(userId: UUID): Long = dsl.fetchCount(WORKOUT_SESSION, WORKOUT_SESSION.USER_ID.eq(userId)).toLong()

    /** Completes stale sessions that have sets, ending them at their last set (BR-013). Idempotent. */
    fun completeExpired(userId: UUID, cutoff: Instant, now: Instant): List<UUID> {
        val lastSetAt = select(max(WORKOUT_SET.CREATED_AT)).from(setsOf(WORKOUT_SESSION.ID))
        return dsl.update(WORKOUT_SESSION)
            .set(WORKOUT_SESSION.STATUS, WorkoutSessionStatus.COMPLETED.name)
            .set(WORKOUT_SESSION.ENDED_AT, field(lastSetAt))
            .set(WORKOUT_SESSION.UPDATED_AT, now)
            .where(expired(userId, cutoff))
            .and(exists(selectOne().from(setsOf(WORKOUT_SESSION.ID))))
            .returning(WORKOUT_SESSION.ID)
            .fetch(WORKOUT_SESSION.ID)
    }

    /** Stale sessions without sets cannot be completed (BR-012), so they are deleted (BR-013). Idempotent. */
    fun deleteExpiredWithoutSets(userId: UUID, cutoff: Instant): List<UUID> =
        dsl.deleteFrom(WORKOUT_SESSION)
            .where(expired(userId, cutoff))
            .andNotExists(selectOne().from(setsOf(WORKOUT_SESSION.ID)))
            .returning(WORKOUT_SESSION.ID)
            .fetch(WORKOUT_SESSION.ID)

    private fun expired(userId: UUID, cutoff: Instant) =
        WORKOUT_SESSION.USER_ID.eq(userId)
            .and(WORKOUT_SESSION.STATUS.eq(WorkoutSessionStatus.IN_PROGRESS.name))
            .and(WORKOUT_SESSION.STARTED_AT.lt(cutoff))

    /** Sets of the session identified by [sessionId], usable as a correlated subquery source. */
    private fun setsOf(sessionId: Field<UUID>) =
        WORKOUT_SET.join(WORKOUT_SESSION_EXERCISE)
            .on(WORKOUT_SESSION_EXERCISE.ID.eq(WORKOUT_SET.WORKOUT_SESSION_EXERCISE_ID))
            .and(WORKOUT_SESSION_EXERCISE.WORKOUT_SESSION_ID.eq(sessionId))

    fun hasSets(sessionId: UUID): Boolean =
        dsl.fetchExists(
            WORKOUT_SET.join(WORKOUT_SESSION_EXERCISE)
                .on(WORKOUT_SESSION_EXERCISE.ID.eq(WORKOUT_SET.WORKOUT_SESSION_EXERCISE_ID)),
            WORKOUT_SESSION_EXERCISE.WORKOUT_SESSION_ID.eq(sessionId),
        )

    /** Position of the set within its exercise, by the order sets were added (BR-007). */
    fun setNumber(setId: UUID): Int {
        val target = WORKOUT_SET.`as`("target")
        return dsl.selectCount()
            .from(WORKOUT_SET)
            .join(target).on(target.WORKOUT_SESSION_EXERCISE_ID.eq(WORKOUT_SET.WORKOUT_SESSION_EXERCISE_ID))
            .where(target.ID.eq(setId))
            .and(row(WORKOUT_SET.CREATED_AT, WORKOUT_SET.ID).le(target.CREATED_AT, target.ID))
            .fetchOne(0, Int::class.java)!!
    }
}

data class WorkoutSessionListItem(
    val id: UUID,
    val status: WorkoutSessionStatus,
    val performedDate: LocalDate,
    val startedAt: Instant,
    val endedAt: Instant?,
    val durationSeconds: Long?,
    val exerciseNames: List<String>,
    val totalSets: Int,
)

data class SessionExerciseRow(
    val sessionExerciseId: UUID,
    val exerciseId: UUID,
    val name: String,
    val category: String,
    val imageUrl: String?,
    val sets: List<SetRow>,
)

data class SetRow(val id: UUID, val weight: BigDecimal, val repetitions: Int)
