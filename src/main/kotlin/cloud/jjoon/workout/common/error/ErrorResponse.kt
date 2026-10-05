package cloud.jjoon.workout.common.error

import com.fasterxml.jackson.annotation.JsonInclude
import java.time.Instant

@JsonInclude(JsonInclude.Include.NON_NULL)
data class ErrorResponse(
    val code: String,
    val message: String,
    val timestamp: Instant,
    val errors: List<FieldError>? = null,
    val details: Map<String, Any>? = null,
) {
    data class FieldError(val field: String, val reason: String)

    companion object {
        fun of(code: ErrorCode, timestamp: Instant, details: Map<String, Any>? = null) =
            ErrorResponse(code.name, code.message, timestamp, details = details)
    }
}
