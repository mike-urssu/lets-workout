package cloud.jjoon.workout.session.controller

import cloud.jjoon.workout.common.error.BusinessException
import cloud.jjoon.workout.common.error.ErrorCode
import cloud.jjoon.workout.session.service.ExpiredSessionCleaner
import cloud.jjoon.workout.session.service.WorkoutCalendarResponse
import cloud.jjoon.workout.session.service.WorkoutDayResponse
import cloud.jjoon.workout.session.service.WorkoutDayService
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeParseException
import java.util.UUID

@RestController
@RequestMapping("/api/v1/workout-days")
class WorkoutDayController(
    private val dayService: WorkoutDayService,
    private val expiredSessionCleaner: ExpiredSessionCleaner,
) {

    /** API-WORKOUT-007 */
    @GetMapping
    fun calendar(@AuthenticationPrincipal userId: UUID, @RequestParam month: String?): WorkoutCalendarResponse {
        expiredSessionCleaner.cleanUp(userId)
        return dayService.calendar(userId, month?.let { parse(it, YearMonth::parse) })
    }

    /** API-WORKOUT-008: 204 for a day without completed workouts. */
    @GetMapping("/{date}")
    fun day(@AuthenticationPrincipal userId: UUID, @PathVariable date: String): ResponseEntity<WorkoutDayResponse> {
        expiredSessionCleaner.cleanUp(userId)
        return dayService.day(userId, parse(date, LocalDate::parse))?.let { ResponseEntity.ok(it) }
            ?: ResponseEntity.noContent().build()
    }

    /** API-WORKOUT-009 */
    @DeleteMapping("/{date}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(@AuthenticationPrincipal userId: UUID, @PathVariable date: String) {
        expiredSessionCleaner.cleanUp(userId)
        dayService.delete(userId, parse(date, LocalDate::parse))
    }

    /** ERR-014, ERR-015: a malformed month or date is an input error. */
    private fun <T> parse(text: String, parser: (String) -> T): T = try {
        parser(text)
    } catch (e: DateTimeParseException) {
        throw BusinessException(ErrorCode.VALIDATION_FAILED)
    }
}
