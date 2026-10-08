package cloud.jjoon.workout.session

import cloud.jjoon.workout.session.domain.WorkoutSession
import cloud.jjoon.workout.session.domain.WorkoutSessionStatus
import cloud.jjoon.workout.session.repository.SessionExerciseRow
import cloud.jjoon.workout.session.repository.SetRow
import cloud.jjoon.workout.session.service.WorkoutSessionResponse
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlin.test.assertEquals

class WorkoutSessionResponseTest {

    private val chest = UUID.randomUUID()
    private val back = UUID.randomUUID()
    private val session = WorkoutSession(
        userId = UUID.randomUUID(),
        status = WorkoutSessionStatus.COMPLETED,
        performedDate = LocalDate.parse("2026-10-05"),
        startedAt = Instant.parse("2026-10-05T09:00:00Z"),
        endedAt = Instant.parse("2026-10-05T10:05:00Z"),
    ).apply { id = UUID.randomUUID() }

    @Test
    fun `BR-007 세트 번호는 운동마다 추가한 순서로 1부터 매긴다`() {
        val response = WorkoutSessionResponse.of(session, listOf(exercise(chest, 1, set("60", 10), set("62.5", 8)), exercise(chest, 1, set("100", 5))))

        assertEquals(listOf(1, 2), response.exercises[0].sets.map { it.setNumber })
        assertEquals(listOf(1), response.exercises[1].sets.map { it.setNumber })
    }

    @Test
    fun `BR-010 요약은 모든 세트로 계산하고 세트 없는 운동은 운동 수에서 뺀다`() {
        val response = WorkoutSessionResponse.of(
            session,
            listOf(exercise(chest, 1, set("60", 10), set("62.5", 8)), exercise(chest, 1), exercise(back, 2, set("0", 12))),
        )

        with(response.summary) {
            assertEquals(3900L, durationSeconds)
            assertEquals(2, exerciseCount)
            assertEquals(3, totalSets)
            assertEquals(30, totalRepetitions)
            assertEquals(BigDecimal("1100.00"), totalVolume) // 60×10 + 62.5×8 + 0×12
        }
        assertEquals(BigDecimal("1100.00"), response.exercises[0].volume)
        assertEquals(null, response.exercises[1].firstSetAt)
    }

    @Test
    fun `BR-015 부위별 요약은 세트가 있는 운동만 부위 순서로 묶는다`() {
        val response = WorkoutSessionResponse.of(
            session,
            listOf(exercise(back, 2, set("50", 10)), exercise(chest, 1), exercise(chest, 1, set("60", 10)), exercise(back, 2, set("40", 5))),
        )

        assertEquals(listOf(chest, back), response.categories.map { it.id })
        assertEquals(listOf(response.exercises[2].sessionExerciseId), response.categories[0].sessionExerciseIds)
        assertEquals(2, response.categories[1].setCount)
        assertEquals(BigDecimal("700.00"), response.categories[1].volume)
    }

    @Test
    fun `DATA-005 진행 중 세션은 운동 시간이 없고 합계는 0이다`() {
        val inProgress = WorkoutSession(
            userId = UUID.randomUUID(),
            status = WorkoutSessionStatus.IN_PROGRESS,
            performedDate = LocalDate.parse("2026-10-05"),
            startedAt = Instant.parse("2026-10-05T09:00:00Z"),
        ).apply { id = UUID.randomUUID() }

        with(WorkoutSessionResponse.of(inProgress, emptyList()).summary) {
            assertEquals(null, durationSeconds)
            assertEquals(0, totalSets)
            assertEquals(BigDecimal("0.00"), totalVolume)
        }
    }

    private fun exercise(categoryId: UUID, categoryOrder: Int, vararg sets: SetRow) = SessionExerciseRow(
        UUID.randomUUID(), UUID.randomUUID(), "운동", "Exercise", "타겟",
        categoryId, if (categoryId == chest) "가슴" else "등", categoryOrder, sets.toList(),
    )

    private fun set(weight: String, repetitions: Int) =
        SetRow(UUID.randomUUID(), BigDecimal(weight), repetitions, Instant.parse("2026-10-05T09:00:00Z"))
}
