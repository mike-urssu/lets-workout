package cloud.jjoon.workout.common.error

class BusinessException(
    val code: ErrorCode,
    val details: Map<String, Any>? = null,
    /** Replaces the code's default message when the case needs a more specific one. */
    val userMessage: String? = null,
) : RuntimeException(code.name)
