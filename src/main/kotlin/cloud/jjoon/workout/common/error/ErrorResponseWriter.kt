package cloud.jjoon.workout.common.error

import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import java.time.Clock

/** Writes the common error body from places outside Spring MVC (security filters). */
@Component
class ErrorResponseWriter(private val objectMapper: ObjectMapper, private val clock: Clock) {

    fun write(response: HttpServletResponse, code: ErrorCode) {
        response.status = code.status.value()
        response.contentType = MediaType.APPLICATION_JSON_VALUE
        response.characterEncoding = Charsets.UTF_8.name()
        objectMapper.writeValue(response.outputStream, ErrorResponse.of(code, clock.instant()))
    }
}
