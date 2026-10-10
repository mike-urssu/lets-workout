package cloud.jjoon.workout.exercise.service

import cloud.jjoon.workout.common.error.BusinessException
import cloud.jjoon.workout.common.error.ErrorCode
import cloud.jjoon.workout.common.error.ErrorResponse.FieldError
import cloud.jjoon.workout.exercise.domain.Exercise
import cloud.jjoon.workout.exercise.repository.ExerciseQueryRepository
import cloud.jjoon.workout.exercise.repository.ExerciseRepository
import cloud.jjoon.workout.session.service.WorkoutSessionService
import org.hibernate.exception.ConstraintViolationException
import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.util.UUID

/** Adding, editing and deleting the user's own exercises (design workout-exercise-manage 3.2). */
@Service
class ExerciseService(
    private val exerciseRepository: ExerciseRepository,
    private val queryRepository: ExerciseQueryRepository,
    private val sessionService: WorkoutSessionService,
    private val clock: Clock,
) {

    @Transactional
    fun create(userId: UUID, request: ExerciseRequest): ExerciseResponse {
        val input = validated(request)
        requireNameFree(userId, input, except = null)
        val exercise = Exercise(
            userId = userId,
            categoryId = input.categoryId,
            name = input.name,
            nameEn = input.nameEn,
            target = null, // BR-028
            sortOrder = nextSortOrder(userId, input.categoryId),
            createdAt = clock.instant(),
        )
        return ExerciseResponse.of(flushed { exerciseRepository.saveAndFlush(exercise) })
    }

    /** Past records point at the exercise, so they show the new values without being touched (BR-024). */
    @Transactional
    fun update(userId: UUID, exerciseId: UUID, request: ExerciseRequest): ExerciseResponse {
        val input = validated(request)
        val exercise = owned(userId, exerciseRepository.findForUpdateById(exerciseId))
        requireNameFree(userId, input, except = exercise.id)
        if (exercise.categoryId != input.categoryId) {
            exercise.sortOrder = nextSortOrder(userId, input.categoryId) // BR-027
            exercise.target = null // BR-028
            exercise.categoryId = input.categoryId
        }
        exercise.name = input.name
        exercise.nameEn = input.nameEn
        exercise.updatedAt = clock.instant()
        return ExerciseResponse.of(flushed { exercise.also { exerciseRepository.flush() } })
    }

    /** REQ-EXERCISE-006: the exercise's records go with it, and completed sessions left without sets too (BR-025, BR-029). */
    @Transactional
    fun delete(userId: UUID, exerciseId: UUID) {
        sessionService.lockInProgress(userId) // session before exercise, the order set changes use (DEC-WORKOUT-027)
        val exercise = owned(userId, exerciseRepository.findForUpdateById(exerciseId))
        val completedSessions = queryRepository.findCompletedSessionIds(exerciseId)
        val setCount = queryRepository.countSets(exerciseId)
        exerciseRepository.delete(exercise)
        exerciseRepository.flush() // its session exercises and sets go by cascade
        val deletedSessions = sessionService.deleteSessionsLeftEmpty(userId, completedSessions)
        log.info(
            "event=exercise.deleted userId={} exerciseId={} deletedSetCount={} deletedSessionCount={}",
            userId, exerciseId, setCount, deletedSessions.size,
        )
    }

    /** REQ-EXERCISE-007: the body part's exercises take the given order (BR-031). */
    @Transactional
    fun reorder(userId: UUID, categoryId: UUID, exerciseIds: List<UUID>) {
        val current = exerciseRepository.findForUpdateByUserIdAndCategoryId(userId, categoryId).associateBy { it.id!! }
        // Exactly the body part's own exercises, each once; anything else means the screen's list is stale (BR-032, ERR-018).
        if (exerciseIds.size != current.size || exerciseIds.toSet() != current.keys) {
            throw BusinessException(ErrorCode.EXERCISE_ORDER_OUTDATED)
        }
        val now = clock.instant()
        exerciseIds.forEachIndexed { index, id ->
            current.getValue(id).apply {
                sortOrder = index + 1
                updatedAt = now
            }
        }
    }

    /** Step (V): trim, then check what is required and how long it may be (BR-023, ERR-016). */
    private fun validated(request: ExerciseRequest): ExerciseInput {
        val name = request.name?.trim().orEmpty()
        val nameEn = request.nameEn?.trim()?.ifEmpty { null }
        val errors = buildList {
            if (request.categoryId == null) add(FieldError("categoryId", "운동 부위를 골라야 합니다."))
            else if (!queryRepository.categoryExists(request.categoryId)) add(FieldError("categoryId", "존재하지 않는 운동 부위입니다."))
            if (name.isEmpty()) add(FieldError("name", "종목명을 입력해야 합니다."))
            else if (name.length > NAME_MAX) add(FieldError("name", "종목명은 ${NAME_MAX}자까지 입력할 수 있습니다."))
            if (nameEn != null && nameEn.length > NAME_EN_MAX) add(FieldError("nameEn", "영어명은 ${NAME_EN_MAX}자까지 입력할 수 있습니다."))
        }
        if (errors.isNotEmpty()) throw BusinessException(ErrorCode.VALIDATION_FAILED, errors = errors)
        return ExerciseInput(request.categoryId!!, name, nameEn)
    }

    /** Step (D); the unique index catches concurrent requests (BR-026). */
    private fun requireNameFree(userId: UUID, input: ExerciseInput, except: UUID?) {
        val same = exerciseRepository.findFirstByUserIdAndCategoryIdAndNameIgnoreCase(userId, input.categoryId, input.name)
        if (same != null && same.id != except) throw BusinessException(ErrorCode.EXERCISE_NAME_DUPLICATED)
    }

    private fun nextSortOrder(userId: UUID, categoryId: UUID): Int =
        (exerciseRepository.maxSortOrder(userId, categoryId) ?: 0) + 1

    private fun owned(userId: UUID, exercise: Exercise?): Exercise {
        if (exercise == null) throw BusinessException(ErrorCode.EXERCISE_NOT_FOUND)
        if (exercise.userId != userId) throw BusinessException(ErrorCode.FORBIDDEN)
        return exercise
    }

    private fun <T> flushed(write: () -> T): T = try {
        write()
    } catch (e: DataIntegrityViolationException) {
        if ((e.cause as? ConstraintViolationException)?.constraintName != NAME_CONSTRAINT) throw e
        throw BusinessException(ErrorCode.EXERCISE_NAME_DUPLICATED)
    }

    companion object {
        private val log = LoggerFactory.getLogger(ExerciseService::class.java)
        private const val NAME_MAX = 30
        private const val NAME_EN_MAX = 50
        private const val NAME_CONSTRAINT = "ux_exercise_user_category_name"
    }
}

data class ExerciseOrderRequest(val exerciseIds: List<UUID>)

/** Fields are optional here so that missing ones are reported per field (ERR-016). */
data class ExerciseRequest(val categoryId: UUID?, val name: String?, val nameEn: String?)

private data class ExerciseInput(val categoryId: UUID, val name: String, val nameEn: String?)

data class ExerciseResponse(val id: UUID, val categoryId: UUID, val name: String, val nameEn: String?, val target: String?) {
    companion object {
        fun of(e: Exercise) = ExerciseResponse(e.id!!, e.categoryId, e.name, e.nameEn, e.target)
    }
}
