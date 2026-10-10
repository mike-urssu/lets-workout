package cloud.jjoon.workout.exercise.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.UuidGenerator
import java.time.Instant
import java.util.UUID

/** One of a user's own exercises (BR-022). New accounts get theirs from the DB trigger; lists are read with jOOQ. */
@Entity
@Table(name = "exercise")
class Exercise(
    @Column(name = "user_id", nullable = false, updatable = false)
    val userId: UUID,

    @Column(name = "exercise_category_id", nullable = false)
    var categoryId: UUID,

    @Column(nullable = false)
    var name: String,

    @Column(name = "name_en")
    var nameEn: String?,

    /** Only exercises copied from the default list have one (BR-028). */
    @Column
    var target: String?,

    @Column(name = "sort_order", nullable = false)
    var sortOrder: Int,

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant,

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = createdAt,
) {
    @Id
    @GeneratedValue
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    var id: UUID? = null
}
