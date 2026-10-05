package cloud.jjoon.workout.common.security

object BearerToken {
    private const val PREFIX = "Bearer "

    fun from(authorizationHeader: String?): String? {
        if (authorizationHeader == null || !authorizationHeader.startsWith(PREFIX)) return null
        return authorizationHeader.removePrefix(PREFIX).takeIf { it.isNotBlank() }
    }
}
