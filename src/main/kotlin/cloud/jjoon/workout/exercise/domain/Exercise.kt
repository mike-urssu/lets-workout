package cloud.jjoon.workout.exercise.domain

import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.util.UUID

/** Read-only: rows come from schema scripts (BR-009). Only existence is checked here; lists are read with jOOQ. */
@Entity
@Table(name = "exercise")
class Exercise(
    @Id
    val id: UUID,
)
