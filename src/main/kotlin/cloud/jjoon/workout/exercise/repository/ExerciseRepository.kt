package cloud.jjoon.workout.exercise.repository

import cloud.jjoon.workout.exercise.domain.Exercise
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import java.util.UUID

interface ExerciseRepository : JpaRepository<Exercise, UUID> {

    /** Editing and deleting the same exercise run one at a time (design workout-exercise-manage 3.2). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from Exercise e where e.id = :id")
    fun findForUpdateById(id: UUID): Exercise?

    /** Reordering a body part holds all of its exercises, so overlapping reorders run one at a time (DEC-WORKOUT-031). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from Exercise e where e.userId = :userId and e.categoryId = :categoryId")
    fun findForUpdateByUserIdAndCategoryId(userId: UUID, categoryId: UUID): List<Exercise>

    /** Same name in the body part, ignoring case (BR-026). */
    fun findFirstByUserIdAndCategoryIdAndNameIgnoreCase(userId: UUID, categoryId: UUID, name: String): Exercise?

    @Query("select max(e.sortOrder) from Exercise e where e.userId = :userId and e.categoryId = :categoryId")
    fun maxSortOrder(userId: UUID, categoryId: UUID): Int?
}
