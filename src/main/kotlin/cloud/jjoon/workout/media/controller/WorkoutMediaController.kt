package cloud.jjoon.workout.media.controller

import cloud.jjoon.workout.media.service.WorkoutMediaResponse
import cloud.jjoon.workout.media.service.WorkoutMediaService
import cloud.jjoon.workout.session.service.ExpiredSessionCleaner
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestPart
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile
import java.util.UUID

@RestController
class WorkoutMediaController(
    private val mediaService: WorkoutMediaService,
    private val expiredSessionCleaner: ExpiredSessionCleaner,
) {

    /** API-MEDIA-001: one file per request, staged until the session completes. */
    @PostMapping("/api/v1/workout-sessions/{sessionId}/media", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    @ResponseStatus(HttpStatus.CREATED)
    fun upload(
        @AuthenticationPrincipal userId: UUID,
        @PathVariable sessionId: UUID,
        @RequestPart("file") file: MultipartFile,
    ): WorkoutMediaResponse {
        expiredSessionCleaner.cleanUp(userId)
        return mediaService.upload(userId, sessionId, file)
    }
}
