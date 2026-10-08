package cloud.jjoon.workout.exercise.controller

import cloud.jjoon.workout.common.error.BusinessException
import cloud.jjoon.workout.common.error.ErrorCode
import cloud.jjoon.workout.exercise.repository.ExerciseQueryRepository
import cloud.jjoon.workout.exercise.repository.ExerciseRow
import cloud.jjoon.workout.session.service.ExpiredSessionCleaner
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api/v1/exercises")
class ExerciseController(
    private val exerciseQueryRepository: ExerciseQueryRepository,
    private val expiredSessionCleaner: ExpiredSessionCleaner,
) {

    @GetMapping
    fun search(@AuthenticationPrincipal userId: UUID, @RequestParam keyword: String?): List<ExerciseRow> {
        expiredSessionCleaner.cleanUp(userId) // so the last performed dates include auto-completed sessions
        val trimmed = keyword?.trim()?.ifEmpty { null }
        if (trimmed != null && trimmed.length > MAX_KEYWORD_LENGTH) throw BusinessException(ErrorCode.VALIDATION_FAILED)
        return exerciseQueryRepository.search(userId, trimmed)
    }

    companion object {
        private const val MAX_KEYWORD_LENGTH = 50
    }
}
