package cloud.jjoon.workout.session.service

import cloud.jjoon.workout.media.service.WorkoutMediaService
import cloud.jjoon.workout.session.repository.WorkoutSessionQueryRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Duration
import java.util.UUID

/**
 * Ends the user's sessions left in progress for over 6 hours (REQ-WORKOUT-006, BR-013).
 * Runs before each workout API instead of on a schedule, and commits on its own so a failing request keeps the
 * cleanup (DEC-WORKOUT-004). Time comes from the injected clock like everywhere else (architecture 10.1).
 */
@Service
class ExpiredSessionCleaner(
    private val queryRepository: WorkoutSessionQueryRepository,
    private val mediaService: WorkoutMediaService,
    private val clock: Clock,
) {

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun cleanUp(userId: UUID) {
        val now = clock.instant()
        val cutoff = now.minus(MAX_DURATION)
        val completed = queryRepository.completeExpired(userId, cutoff, now)
        completed.forEach { log.info("event=workout_session.auto_completed userId={} sessionId={}", userId, it) }
        mediaService.discardStaged(userId, completed) // nobody pressed save, so no upload is attached
        val deleted = queryRepository.deleteExpiredWithoutSets(userId, cutoff)
        deleted.forEach { log.info("event=workout_session.auto_deleted userId={} sessionId={}", userId, it) }
        mediaService.deleteFilesOfSessions(userId, deleted)
    }

    companion object {
        private val log = LoggerFactory.getLogger(ExpiredSessionCleaner::class.java)
        private val MAX_DURATION: Duration = Duration.ofHours(6)
    }
}
