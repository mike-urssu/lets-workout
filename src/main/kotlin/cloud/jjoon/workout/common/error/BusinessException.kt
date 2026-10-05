package cloud.jjoon.workout.common.error

class BusinessException(
    val code: ErrorCode,
    val details: Map<String, Any>? = null,
) : RuntimeException(code.name)
