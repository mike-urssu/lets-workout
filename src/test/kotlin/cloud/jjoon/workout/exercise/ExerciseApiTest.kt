package cloud.jjoon.workout.exercise

import cloud.jjoon.workout.TestcontainersConfiguration
import cloud.jjoon.workout.support.MutableClock
import cloud.jjoon.workout.support.Operator
import cloud.jjoon.workout.support.SignedInUser
import cloud.jjoon.workout.support.TestClockConfiguration
import cloud.jjoon.workout.support.TestUsers
import org.hamcrest.Matchers.contains
import org.hamcrest.Matchers.nullValue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import tools.jackson.databind.ObjectMapper
import java.time.Duration
import java.util.UUID

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class, TestClockConfiguration::class)
class ExerciseApiTest {

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var operator: Operator
    @Autowired lateinit var users: TestUsers
    @Autowired lateinit var clock: MutableClock
    @Autowired lateinit var objectMapper: ObjectMapper

    lateinit var me: SignedInUser

    @BeforeEach
    fun setUp() {
        operator.deleteAllAccounts()
        operator.deleteAllExercises()
        clock.reset()
        me = users.signIn("joonhee.song")
    }

    @Test
    fun `REQ-EXERCISE-001 운동 목록은 카테고리, 운동명 순이고 해본 적 없는 운동의 최근 수행일은 없다`() {
        operator.addExercise("스쿼트", "하체")
        operator.addExercise("벤치프레스", "가슴", "/images/exercises/bench-press.png")
        operator.addExercise("덤벨프레스", "가슴")

        exercises().andExpect {
            status { isOk() }
            jsonPath("$[*].name") { value(contains("덤벨프레스", "벤치프레스", "스쿼트")) }
            jsonPath("$[1].category") { value("가슴") }
            jsonPath("$[1].imageUrl") { value("/images/exercises/bench-press.png") }
            jsonPath("$[0].imageUrl") { value(null) }
            jsonPath("$[0].lastPerformedDate") { value(null) }
            jsonPath("$[0].id") { isString() }
        }
    }

    @Test
    fun `REQ-EXERCISE-001 운동명 일부로 대소문자 구분 없이 검색한다`() {
        operator.addExercise("Bench Press", "가슴")
        operator.addExercise("벤치 딥스", "가슴")
        operator.addExercise("Squat", "하체")

        exercises("  bench ").andExpect {
            status { isOk() }
            jsonPath("$[*].name") { value(contains("Bench Press")) }
        }
    }

    @Test
    fun `REQ-EXERCISE-001 검색어의 와일드카드 문자는 일반 문자로 찾는다`() {
        operator.addExercise("100% 스쿼트", "하체")
        operator.addExercise("스쿼트", "하체")

        exercises("%").andExpect {
            jsonPath("$[*].name") { value(contains("100% 스쿼트")) }
        }
    }

    @Test
    fun `ERR-010 검색어가 50자를 넘으면 입력값 오류다`() {
        exercises("가".repeat(50)).andExpect { status { isOk() } }
        exercises("가".repeat(51)).andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("VALIDATION_FAILED") }
        }
    }

    @Test
    fun `DEC-WORKOUT-012 최근 수행일은 내가 완료한 세션 중 가장 늦은 수행 날짜다`() {
        val bench = operator.addExercise("벤치프레스", "가슴")
        val squat = operator.addExercise("스쿼트", "하체")
        val deadlift = operator.addExercise("데드리프트", "등")
        workout(me, bench, complete = true)
        clock.advance(Duration.ofDays(1))
        workout(me, bench, complete = true)
        workout(users.signIn("other.user"), deadlift, complete = true)
        workout(me, squat, complete = false)

        exercises().andExpect {
            jsonPath("$[?(@.name == '벤치프레스')].lastPerformedDate") { value(contains("2026-10-06")) }
            jsonPath("$[?(@.name == '스쿼트')].lastPerformedDate") { value(contains(nullValue())) }
            jsonPath("$[?(@.name == '데드리프트')].lastPerformedDate") { value(contains(nullValue())) }
        }
    }

    @Test
    fun `BR-013 6시간이 지나 자동 완료된 세션도 최근 수행일에 반영된다`() {
        val bench = operator.addExercise("벤치프레스", "가슴")
        workout(me, bench, complete = false)
        clock.advance(Duration.ofHours(7))

        exercises().andExpect { jsonPath("$[0].lastPerformedDate") { value("2026-10-05") } }
    }

    private fun workout(user: SignedInUser, exerciseId: UUID, complete: Boolean) {
        val auth = "Bearer ${user.token}"
        val session = idOf(mockMvc.post("/api/v1/workout-sessions") {
            header("Authorization", auth)
            header("X-Time-Zone", "Asia/Seoul")
        })
        val sessionExercise = idOf(mockMvc.post("/api/v1/workout-sessions/$session/exercises") {
            header("Authorization", auth)
            contentType = MediaType.APPLICATION_JSON
            content = """{"exerciseId": "$exerciseId"}"""
        }, "sessionExerciseId")
        mockMvc.post("/api/v1/workout-sessions/$session/exercises/$sessionExercise/sets") {
            header("Authorization", auth)
            contentType = MediaType.APPLICATION_JSON
            content = """{"weight": 60, "repetitions": 10}"""
        }.andExpect { status { isCreated() } }
        if (complete) {
            mockMvc.post("/api/v1/workout-sessions/$session/complete") { header("Authorization", auth) }
                .andExpect { status { isOk() } }
        }
    }

    private fun idOf(result: ResultActionsDsl, field: String = "id"): String =
        objectMapper.readTree(result.andReturn().response.contentAsString).get(field).asString()

    private fun exercises(keyword: String? = null, user: SignedInUser = me): ResultActionsDsl =
        mockMvc.get("/api/v1/exercises") {
            header("Authorization", "Bearer ${user.token}")
            if (keyword != null) param("keyword", keyword)
        }
}
