package cloud.jjoon.workout.common.error

import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import org.springframework.web.servlet.resource.NoResourceFoundException
import java.time.Clock

@RestControllerAdvice
class GlobalExceptionHandler(private val clock: Clock) {

    @ExceptionHandler(BusinessException::class)
    fun handleBusiness(e: BusinessException): ResponseEntity<ErrorResponse> =
        ResponseEntity.status(e.code.status)
            .body(ErrorResponse(e.code.name, e.userMessage ?: e.code.message, clock.instant(), errors = e.errors, details = e.details))

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleInvalid(e: MethodArgumentNotValidException): ResponseEntity<ErrorResponse> {
        val code = ErrorCode.VALIDATION_FAILED
        val errors = e.bindingResult.fieldErrors.map { ErrorResponse.FieldError(it.field, it.defaultMessage ?: code.message) }
        return ResponseEntity.status(code.status)
            .body(ErrorResponse(code.name, code.message, clock.instant(), errors = errors))
    }

    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun handleUnreadable(e: HttpMessageNotReadableException): ResponseEntity<ErrorResponse> =
        respond(ErrorCode.VALIDATION_FAILED)

    /** Path variables that do not convert, such as a malformed UUID. */
    @ExceptionHandler(MethodArgumentTypeMismatchException::class)
    fun handleTypeMismatch(e: MethodArgumentTypeMismatchException): ResponseEntity<ErrorResponse> =
        respond(ErrorCode.VALIDATION_FAILED)

    @ExceptionHandler(NoResourceFoundException::class)
    fun handleNoResource(e: NoResourceFoundException): ResponseEntity<ErrorResponse> = respond(ErrorCode.NOT_FOUND)

    /** Details go to the log only; the response never exposes internals. */
    @ExceptionHandler(Exception::class)
    fun handleUnexpected(e: Exception): ResponseEntity<ErrorResponse> {
        // Spring's own request errors (unsupported media type, method, missing header) are client mistakes.
        if (e is org.springframework.web.ErrorResponse && e.statusCode.is4xxClientError) {
            return respond(ErrorCode.VALIDATION_FAILED)
        }
        log.error("event=unhandled_exception", e)
        return respond(ErrorCode.INTERNAL_ERROR)
    }

    private fun respond(code: ErrorCode): ResponseEntity<ErrorResponse> =
        ResponseEntity.status(code.status).body(ErrorResponse.of(code, clock.instant()))

    companion object {
        private val log = LoggerFactory.getLogger(GlobalExceptionHandler::class.java)
    }
}
