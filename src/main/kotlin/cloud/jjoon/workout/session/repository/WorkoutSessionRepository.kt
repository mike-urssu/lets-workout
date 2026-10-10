package cloud.jjoon.workout.session.repository

import cloud.jjoon.workout.session.domain.WorkoutSession
import cloud.jjoon.workout.session.domain.WorkoutSessionStatus
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import java.util.UUID

interface WorkoutSessionRepository : JpaRepository<WorkoutSession, UUID> {
    fun findByUserIdAndStatus(userId: UUID, status: WorkoutSessionStatus): WorkoutSession?

    /** Changes to one session run one at a time, so completion and set changes cannot interleave (DEC-WORKOUT-005). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from WorkoutSession s where s.id = :id")
    fun findForUpdateById(id: UUID): WorkoutSession?

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from WorkoutSession s where s.userId = :userId and s.status = :status")
    fun findForUpdateByUserIdAndStatus(userId: UUID, status: WorkoutSessionStatus): WorkoutSession?
}
