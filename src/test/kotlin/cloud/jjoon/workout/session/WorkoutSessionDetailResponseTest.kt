package cloud.jjoon.workout.session

import cloud.jjoon.workout.session.domain.WorkoutSession
import cloud.jjoon.workout.session.domain.WorkoutSessionStatus
import cloud.jjoon.workout.session.repository.SessionExerciseRow
import cloud.jjoon.workout.session.repository.SetRow
import cloud.jjoon.workout.session.service.WorkoutSessionDetailResponse
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlin.test.assertEquals

class WorkoutSessionDetailResponseTest {

    private val session = WorkoutSession(
        userId = UUID.randomUUID(),
        status = WorkoutSessionStatus.COMPLETED,
        performedDate = LocalDate.parse("2026-10-05"),
        startedAt = Instant.parse("2026-10-05T09:00:00Z"),
        endedAt = Instant.parse("2026-10-05T10:05:00Z"),
    ).apply { id = UUID.randomUUID() }

    @Test
    fun `BR-007 세트 번호는 운동마다 추가한 순서로 1부터 매긴다`() {
        val detail = WorkoutSessionDetailResponse.of(session, listOf(exercise(set("60", 10), set("62.5", 8)), exercise(set("100", 5))))

        assertEquals(listOf(1, 2), detail.exercises[0].sets.map { it.setNumber })
        assertEquals(listOf(1), detail.exercises[1].sets.map { it.setNumber })
    }

    @Test
    fun `BR-010 요약은 모든 세트로 계산하고 세트 없는 운동은 운동 수에서 뺀다`() {
        val detail = WorkoutSessionDetailResponse.of(
            session,
            listOf(exercise(set("60", 10), set("62.5", 8)), exercise(), exercise(set("0", 12))),
        )

        with(detail.summary) {
            assertEquals(3900L, durationSeconds)
            assertEquals(2, exerciseCount)
            assertEquals(3, totalSets)
            assertEquals(30, totalRepetitions)
            assertEquals(BigDecimal("1100.00"), totalWeight) // 60×10 + 62.5×8 + 0×12
        }
        assertEquals(2, detail.exercises[0].totalSets)
        assertEquals(18, detail.exercises[0].totalRepetitions)
    }

    @Test
    fun `DATA-005 진행 중 세션은 운동 시간이 없고 합계는 0이다`() {
        val inProgress = WorkoutSession(
            userId = UUID.randomUUID(),
            status = WorkoutSessionStatus.IN_PROGRESS,
            performedDate = LocalDate.parse("2026-10-05"),
            startedAt = Instant.parse("2026-10-05T09:00:00Z"),
        ).apply { id = UUID.randomUUID() }

        with(WorkoutSessionDetailResponse.of(inProgress, emptyList()).summary) {
            assertEquals(null, durationSeconds)
            assertEquals(0, totalSets)
            assertEquals(BigDecimal("0.00"), totalWeight)
        }
    }

    private fun exercise(vararg sets: SetRow) =
        SessionExerciseRow(UUID.randomUUID(), UUID.randomUUID(), "벤치프레스", "가슴", null, sets.toList())

    private fun set(weight: String, repetitions: Int) = SetRow(UUID.randomUUID(), BigDecimal(weight), repetitions)
}
