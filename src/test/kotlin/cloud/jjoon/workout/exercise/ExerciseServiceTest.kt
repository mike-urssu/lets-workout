package cloud.jjoon.workout.exercise

import cloud.jjoon.workout.TestcontainersConfiguration
import cloud.jjoon.workout.common.error.BusinessException
import cloud.jjoon.workout.common.error.ErrorCode
import cloud.jjoon.workout.exercise.repository.ExerciseQueryRepository
import cloud.jjoon.workout.exercise.service.ExerciseService
import cloud.jjoon.workout.support.Operator
import cloud.jjoon.workout.support.SignedInUser
import cloud.jjoon.workout.support.TestClockConfiguration
import cloud.jjoon.workout.support.TestUsers
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import java.util.UUID

/** BR-032 at the service: a new order must be exactly the body part's own exercises, each once. */
@SpringBootTest
@Import(TestcontainersConfiguration::class, TestClockConfiguration::class)
class ExerciseServiceTest {

    @Autowired lateinit var exerciseService: ExerciseService
    @Autowired lateinit var queryRepository: ExerciseQueryRepository
    @Autowired lateinit var operator: Operator
    @Autowired lateinit var users: TestUsers

    lateinit var me: SignedInUser
    lateinit var chest: UUID

    @BeforeEach
    fun setUp() {
        operator.deleteAllAccounts()
        me = users.signIn("joonhee.song")
        chest = operator.categoryId("가슴")
    }

    @Test
    fun `BR-032 개수가 맞아도 겹치거나 다른 부위·다른 사용자의 종목이 섞이면 순서를 바꾸지 않는다`() {
        val before = chestIds()
        val othersBench = operator.exerciseId("벤치프레스", users.signIn("other.user").id)
        val squat = operator.exerciseId("스쿼트", me.id)

        listOf(
            before.dropLast(1) + before.first(),   // one twice, one missing
            before.dropLast(1) + squat,            // another body part's
            before.dropLast(1) + othersBench,      // someone else's
        ).forEach { order ->
            val e = assertThrows<BusinessException> { exerciseService.reorder(me.id, chest, order) }
            assertEquals(ErrorCode.EXERCISE_ORDER_OUTDATED, e.code)
        }
        val e = assertThrows<BusinessException> { exerciseService.reorder(me.id, UUID.randomUUID(), before) }
        assertEquals(ErrorCode.EXERCISE_ORDER_OUTDATED, e.code) // no such body part

        assertEquals(before, chestIds())
    }

    private fun chestIds(): List<UUID> = queryRepository.findByCategory(me.id, chest).map { it.id }
}
