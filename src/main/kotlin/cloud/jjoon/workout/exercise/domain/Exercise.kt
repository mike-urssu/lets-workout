package cloud.jjoon.workout.exercise.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.util.UUID

/** Read-only: rows come from schema scripts (BR-009). */
@Entity
@Table(name = "exercise")
class Exercise(
    @Id
    val id: UUID,

    @Column(nullable = false)
    val name: String,

    @Column(nullable = false)
    val category: String,

    @Column(name = "image_url")
    val imageUrl: String?,
)
