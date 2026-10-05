package cloud.jjoon.workout.auth.repository

import cloud.jjoon.workout.auth.domain.LoginSession
import cloud.jjoon.workout.auth.domain.LoginSessionStatus
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import java.time.Instant
import java.util.UUID

interface LoginSessionRepository : JpaRepository<LoginSession, UUID> {
    fun findByTokenHash(tokenHash: String): LoginSession?

    fun findByUserIdAndStatus(userId: UUID, status: LoginSessionStatus): LoginSession?

    @Modifying
    @Query("delete from LoginSession s where s.userId = :userId and s.endedAt < :cutoff")
    fun deleteEndedBefore(userId: UUID, cutoff: Instant): Int

    /** Only an ACTIVE session is extended, so a concurrent replace/revoke is never overwritten. */
    @Modifying(clearAutomatically = true)
    @Query(
        "update LoginSession s set s.lastUsedAt = :now " +
            "where s.id = :id and s.status = cloud.jjoon.workout.auth.domain.LoginSessionStatus.ACTIVE",
    )
    fun extendIfActive(id: UUID, now: Instant): Int
}
