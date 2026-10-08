package cloud.jjoon.workout.session.service

import cloud.jjoon.workout.common.error.BusinessException
import cloud.jjoon.workout.common.error.ErrorCode
import cloud.jjoon.workout.common.web.PageResponse
import cloud.jjoon.workout.exercise.repository.ExerciseRepository
import cloud.jjoon.workout.session.domain.WorkoutSession
import cloud.jjoon.workout.session.domain.WorkoutSessionExercise
import cloud.jjoon.workout.session.domain.WorkoutSet
import cloud.jjoon.workout.session.domain.WorkoutSessionStatus
import cloud.jjoon.workout.session.repository.SessionExerciseRow
import cloud.jjoon.workout.session.repository.WorkoutSessionExerciseRepository
import cloud.jjoon.workout.session.repository.WorkoutSessionListItem
import cloud.jjoon.workout.session.repository.WorkoutSessionQueryRepository
import cloud.jjoon.workout.session.repository.WorkoutSessionRepository
import cloud.jjoon.workout.session.repository.WorkoutSetRepository
import org.hibernate.exception.ConstraintViolationException
import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.Clock
import java.time.ZoneId
import java.util.UUID

@Service
class WorkoutSessionService(
    private val sessionRepository: WorkoutSessionRepository,
    private val sessionExerciseRepository: WorkoutSessionExerciseRepository,
    private val setRepository: WorkoutSetRepository,
    private val exerciseRepository: ExerciseRepository,
    private val queryRepository: WorkoutSessionQueryRepository,
    private val clock: Clock,
) {

    @Transactional
    fun start(userId: UUID, zone: ZoneId): WorkoutSessionDetailResponse {
        if (sessionRepository.findByUserIdAndStatus(userId, WorkoutSessionStatus.IN_PROGRESS) != null) {
            throw BusinessException(ErrorCode.WORKOUT_SESSION_ALREADY_IN_PROGRESS)
        }
        val now = clock.instant()
        val session = try {
            sessionRepository.saveAndFlush(
                WorkoutSession(
                    userId = userId,
                    status = WorkoutSessionStatus.IN_PROGRESS,
                    performedDate = now.atZone(zone).toLocalDate(), // BR-004
                    startedAt = now,
                ),
            )
        } catch (e: DataIntegrityViolationException) {
            // A concurrent start passed the check above; the partial unique index lets only one through (DEC-WORKOUT-006).
            if ((e.cause as? ConstraintViolationException)?.constraintName != IN_PROGRESS_CONSTRAINT) throw e
            throw BusinessException(ErrorCode.WORKOUT_SESSION_ALREADY_IN_PROGRESS)
        }
        log.info("event=workout_session.started userId={} sessionId={}", userId, session.id)
        return WorkoutSessionDetailResponse.of(session, emptyList())
    }

    @Transactional(readOnly = true)
    fun getInProgress(userId: UUID): WorkoutSessionDetailResponse? =
        sessionRepository.findByUserIdAndStatus(userId, WorkoutSessionStatus.IN_PROGRESS)?.let(::detail)

    @Transactional
    fun complete(userId: UUID, sessionId: UUID, memo: String?): WorkoutSessionDetailResponse {
        val session = editable(userId, sessionId, ErrorCode.WORKOUT_SESSION_ALREADY_COMPLETED)
        if (!queryRepository.hasSets(sessionId)) throw BusinessException(ErrorCode.WORKOUT_SESSION_HAS_NO_SETS) // BR-012
        val now = clock.instant()
        session.status = WorkoutSessionStatus.COMPLETED
        session.endedAt = now
        session.memo = memo
        session.updatedAt = now
        log.info("event=workout_session.completed userId={} sessionId={}", userId, sessionId)
        return detail(session)
    }

    @Transactional(readOnly = true)
    fun list(userId: UUID, page: Int, size: Int): PageResponse<WorkoutSessionListItem> =
        PageResponse.of(queryRepository.findPage(userId, page, size), page, size, queryRepository.count(userId))

    /** Deletes regardless of status; exercises and sets go by cascade (NFR-INTEG-002). */
    @Transactional
    fun delete(userId: UUID, sessionId: UUID) {
        sessionRepository.delete(owned(userId, sessionRepository.findForUpdateById(sessionId)))
        log.info("event=workout_session.deleted userId={} sessionId={}", userId, sessionId)
    }

    @Transactional(readOnly = true)
    fun get(userId: UUID, sessionId: UUID): WorkoutSessionDetailResponse =
        detail(owned(userId, sessionRepository.findByIdOrNull(sessionId)))

    @Transactional
    fun addExercise(userId: UUID, sessionId: UUID, exerciseId: UUID): SessionExerciseResponse {
        val session = editable(userId, sessionId)
        val exercise = exerciseRepository.findByIdOrNull(exerciseId)
            ?: throw BusinessException(ErrorCode.EXERCISE_NOT_FOUND)
        val added = sessionExerciseRepository.save(WorkoutSessionExercise(session.id!!, exercise.id, clock.instant()))
        return SessionExerciseResponse.of(
            SessionExerciseRow(added.id!!, exercise.id, exercise.name, exercise.category, exercise.imageUrl, emptyList()),
        )
    }

    @Transactional
    fun removeExercise(userId: UUID, sessionId: UUID, sessionExerciseId: UUID) {
        val session = editable(userId, sessionId)
        sessionExerciseRepository.delete(sessionExerciseOf(session, sessionExerciseId)) // sets go by cascade
    }

    @Transactional
    fun addSet(userId: UUID, sessionId: UUID, sessionExerciseId: UUID, weight: BigDecimal, repetitions: Int): WorkoutSetResponse {
        val sessionExercise = sessionExerciseOf(editable(userId, sessionId), sessionExerciseId)
        val set = setRepository.saveAndFlush(
            WorkoutSet(sessionExercise.id!!, weight.setScale(WEIGHT_SCALE), repetitions, clock.instant()),
        )
        return setResponse(set)
    }

    @Transactional
    fun updateSet(
        userId: UUID, sessionId: UUID, sessionExerciseId: UUID, setId: UUID, weight: BigDecimal, repetitions: Int,
    ): WorkoutSetResponse {
        val set = setOf(sessionExerciseOf(editable(userId, sessionId), sessionExerciseId), setId)
        set.weight = weight.setScale(WEIGHT_SCALE)
        set.repetitions = repetitions
        set.updatedAt = clock.instant()
        return setResponse(set)
    }

    /** Numbers are not stored, so nothing is renumbered (BR-007). */
    @Transactional
    fun deleteSet(userId: UUID, sessionId: UUID, sessionExerciseId: UUID, setId: UUID) {
        setRepository.delete(setOf(sessionExerciseOf(editable(userId, sessionId), sessionExerciseId), setId))
    }

    private fun sessionExerciseOf(session: WorkoutSession, sessionExerciseId: UUID): WorkoutSessionExercise =
        sessionExerciseRepository.findByIdAndWorkoutSessionId(sessionExerciseId, session.id!!)
            ?: throw BusinessException(ErrorCode.SESSION_EXERCISE_NOT_FOUND)

    private fun setOf(sessionExercise: WorkoutSessionExercise, setId: UUID): WorkoutSet =
        setRepository.findByIdAndWorkoutSessionExerciseId(setId, sessionExercise.id!!)
            ?: throw BusinessException(ErrorCode.WORKOUT_SET_NOT_FOUND)

    private fun setResponse(set: WorkoutSet) = WorkoutSetResponse(
        id = set.id!!,
        setNumber = queryRepository.setNumber(set.id!!),
        weight = set.weight,
        repetitions = set.repetitions,
    )

    /** Step (E) of design 3.2: lock the session, then check owner and status. */
    private fun editable(
        userId: UUID,
        sessionId: UUID,
        completedCode: ErrorCode = ErrorCode.WORKOUT_SESSION_NOT_EDITABLE,
    ): WorkoutSession {
        val session = owned(userId, sessionRepository.findForUpdateById(sessionId))
        if (session.status == WorkoutSessionStatus.COMPLETED) throw BusinessException(completedCode) // BR-002
        return session
    }

    private fun owned(userId: UUID, session: WorkoutSession?): WorkoutSession {
        if (session == null) throw BusinessException(ErrorCode.WORKOUT_SESSION_NOT_FOUND)
        if (session.userId != userId) throw BusinessException(ErrorCode.FORBIDDEN) // BR-001
        return session
    }

    private fun detail(session: WorkoutSession): WorkoutSessionDetailResponse =
        WorkoutSessionDetailResponse.of(session, queryRepository.findExercises(session.id!!))

    companion object {
        private val log = LoggerFactory.getLogger(WorkoutSessionService::class.java)
        private const val WEIGHT_SCALE = 2
        private const val IN_PROGRESS_CONSTRAINT = "ux_workout_session_user_in_progress"
    }
}
