package cloud.jjoon.workout.session.controller

import cloud.jjoon.workout.common.error.BusinessException
import cloud.jjoon.workout.common.error.ErrorCode
import cloud.jjoon.workout.common.web.PageResponse
import cloud.jjoon.workout.session.repository.WorkoutSessionListItem
import cloud.jjoon.workout.session.service.ExpiredSessionCleaner
import cloud.jjoon.workout.session.service.SessionExerciseResponse
import cloud.jjoon.workout.session.service.WorkoutSessionDetailResponse
import cloud.jjoon.workout.session.service.WorkoutSessionService
import cloud.jjoon.workout.session.service.WorkoutSetResponse
import jakarta.validation.Valid
import jakarta.validation.constraints.DecimalMax
import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.Digits
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.math.BigDecimal
import java.net.URI
import java.time.DateTimeException
import java.time.ZoneId
import java.util.UUID

@RestController
@RequestMapping("/api/v1/workout-sessions")
class WorkoutSessionController(
    private val sessionService: WorkoutSessionService,
    private val expiredSessionCleaner: ExpiredSessionCleaner,
) {

    // Every workout API first cleans up the user's abandoned sessions (design 3.2 step B, DEC-WORKOUT-004).

    @PostMapping
    fun start(
        @AuthenticationPrincipal userId: UUID,
        @RequestHeader("X-Time-Zone") timeZone: String,
    ): ResponseEntity<WorkoutSessionDetailResponse> {
        expiredSessionCleaner.cleanUp(userId)
        val zone = try {
            ZoneId.of(timeZone)
        } catch (e: DateTimeException) {
            throw BusinessException(ErrorCode.VALIDATION_FAILED)
        }
        val session = sessionService.start(userId, zone)
        return ResponseEntity.created(URI.create("/api/v1/workout-sessions/${session.id}")).body(session)
    }

    @GetMapping("/in-progress")
    fun inProgress(@AuthenticationPrincipal userId: UUID): ResponseEntity<WorkoutSessionDetailResponse> {
        expiredSessionCleaner.cleanUp(userId)
        return sessionService.getInProgress(userId)?.let { ResponseEntity.ok(it) } ?: ResponseEntity.noContent().build()
    }

    @GetMapping
    fun list(
        @AuthenticationPrincipal userId: UUID,
        @RequestParam(defaultValue = "0") @Min(0) page: Int,
        @RequestParam(defaultValue = "20") @Min(1) @Max(100) size: Int,
    ): PageResponse<WorkoutSessionListItem> {
        expiredSessionCleaner.cleanUp(userId)
        return sessionService.list(userId, page, size)
    }

    @GetMapping("/{sessionId}")
    fun get(@AuthenticationPrincipal userId: UUID, @PathVariable sessionId: UUID): WorkoutSessionDetailResponse {
        expiredSessionCleaner.cleanUp(userId)
        return sessionService.get(userId, sessionId)
    }

    @DeleteMapping("/{sessionId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(@AuthenticationPrincipal userId: UUID, @PathVariable sessionId: UUID) {
        expiredSessionCleaner.cleanUp(userId)
        sessionService.delete(userId, sessionId)
    }

    @PostMapping("/{sessionId}/complete")
    fun complete(
        @AuthenticationPrincipal userId: UUID,
        @PathVariable sessionId: UUID,
        @Valid @RequestBody(required = false) request: CompleteWorkoutSessionRequest?,
    ): WorkoutSessionDetailResponse {
        expiredSessionCleaner.cleanUp(userId)
        return sessionService.complete(userId, sessionId, request?.memo)
    }

    @PostMapping("/{sessionId}/exercises")
    @ResponseStatus(HttpStatus.CREATED)
    fun addExercise(
        @AuthenticationPrincipal userId: UUID,
        @PathVariable sessionId: UUID,
        @Valid @RequestBody request: AddExerciseRequest,
    ): SessionExerciseResponse {
        expiredSessionCleaner.cleanUp(userId)
        return sessionService.addExercise(userId, sessionId, request.exerciseId!!)
    }

    @DeleteMapping("/{sessionId}/exercises/{sessionExerciseId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun removeExercise(
        @AuthenticationPrincipal userId: UUID,
        @PathVariable sessionId: UUID,
        @PathVariable sessionExerciseId: UUID,
    ) {
        expiredSessionCleaner.cleanUp(userId)
        sessionService.removeExercise(userId, sessionId, sessionExerciseId)
    }

    @PostMapping("/{sessionId}/exercises/{sessionExerciseId}/sets")
    @ResponseStatus(HttpStatus.CREATED)
    fun addSet(
        @AuthenticationPrincipal userId: UUID,
        @PathVariable sessionId: UUID,
        @PathVariable sessionExerciseId: UUID,
        @Valid @RequestBody request: WorkoutSetRequest,
    ): WorkoutSetResponse {
        expiredSessionCleaner.cleanUp(userId)
        return sessionService.addSet(userId, sessionId, sessionExerciseId, request.weight!!, request.repetitions!!)
    }

    @PutMapping("/{sessionId}/exercises/{sessionExerciseId}/sets/{setId}")
    fun updateSet(
        @AuthenticationPrincipal userId: UUID,
        @PathVariable sessionId: UUID,
        @PathVariable sessionExerciseId: UUID,
        @PathVariable setId: UUID,
        @Valid @RequestBody request: WorkoutSetRequest,
    ): WorkoutSetResponse {
        expiredSessionCleaner.cleanUp(userId)
        return sessionService.updateSet(
            userId, sessionId, sessionExerciseId, setId, request.weight!!, request.repetitions!!,
        )
    }

    @DeleteMapping("/{sessionId}/exercises/{sessionExerciseId}/sets/{setId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun deleteSet(
        @AuthenticationPrincipal userId: UUID,
        @PathVariable sessionId: UUID,
        @PathVariable sessionExerciseId: UUID,
        @PathVariable setId: UUID,
    ) {
        expiredSessionCleaner.cleanUp(userId)
        sessionService.deleteSet(userId, sessionId, sessionExerciseId, setId)
    }
}

data class CompleteWorkoutSessionRequest(@field:Size(max = 500) val memo: String?)

data class AddExerciseRequest(@field:NotNull val exerciseId: UUID?)

/** Used by both adding and changing a set (API-SET-001, API-SET-002). */
data class WorkoutSetRequest(
    // BR-005
    @field:NotNull
    @field:DecimalMin("0") @field:DecimalMax("1000")
    @field:Digits(integer = 4, fraction = 2, message = "소수 둘째 자리까지 입력할 수 있습니다.")
    val weight: BigDecimal?,

    // BR-003
    @field:NotNull @field:Min(1) @field:Max(1000)
    val repetitions: Int?,
)
