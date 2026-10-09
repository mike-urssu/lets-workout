package cloud.jjoon.workout.common.error

class BusinessException(
    val code: ErrorCode,
    val details: Map<String, Any>? = null,
    /** Replaces the code's default message when the case needs a more specific one. */
    val userMessage: String? = null,
    /** Which inputs were wrong, for VALIDATION_FAILED found by a service rather than request validation. */
    val errors: List<ErrorResponse.FieldError>? = null,
) : RuntimeException(code.name)
