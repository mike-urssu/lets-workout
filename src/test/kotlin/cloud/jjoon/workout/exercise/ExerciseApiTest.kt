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
        clock.reset()
        me = users.signIn("joonhee.song")
    }

    @Test
    fun `IF-EXERCISE-003 부위 목록은 화면 순서이고 부위마다 이미지와 종목 수가 있다`() {
        mockMvc.get("/api/v1/exercise-categories") { header("Authorization", "Bearer ${me.token}") }.andExpect {
            status { isOk() }
            jsonPath("$[*].name") { value(contains("가슴", "등", "어깨", "하체")) }
            jsonPath("$[*].exerciseCount") { value(contains(5, 6, 5, 8)) }
            jsonPath("$[0].imageUrl") { value("/images/exercise-categories/chest.jpg") }
            jsonPath("$[0].id") { isString() }
        }
    }

    @Test
    fun `DEC-WORKOUT-008 부위 이미지는 로그인 없이 받을 수 있다`() {
        listOf("chest", "back", "shoulders", "legs").forEach { name ->
            mockMvc.get("/images/exercise-categories/$name.jpg").andExpect {
                status { isOk() }
                content { contentType(MediaType.IMAGE_JPEG) }
            }
        }
    }

    @Test
    fun `REQ-EXERCISE-001 부위의 운동 목록은 초기 목록 순서이고 해본 적 없는 운동의 최근 수행일은 없다`() {
        exercises(operator.categoryId("가슴")).andExpect {
            status { isOk() }
            jsonPath("$[*].name") {
                value(contains("벤치프레스", "스미스 벤치프레스", "인클라인 벤치프레스", "스미스 인클라인 벤치프레스", "펙덱 플라이"))
            }
            jsonPath("$[0].nameEn") { value("Bench Press") }
            jsonPath("$[0].target") { value("가슴 중부 타겟") }
            jsonPath("$[0].lastPerformedDate") { value(null) }
        }
        exercises(operator.categoryId("하체")).andExpect {
            jsonPath("$[*].name") {
                value(contains("스쿼트", "레그 프레스", "레그 익스텐션", "레그 컬", "런지", "힙 어덕션", "힙 어브덕션", "힙 쓰러스트"))
            }
        }
    }

    @Test
    fun `ERR-010 부위를 주지 않거나 형식이 틀리면 입력값 오류다`() {
        mockMvc.get("/api/v1/exercises") { header("Authorization", "Bearer ${me.token}") }.andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("VALIDATION_FAILED") }
        }
        mockMvc.get("/api/v1/exercises?categoryId=chest") { header("Authorization", "Bearer ${me.token}") }.andExpect {
            status { isBadRequest() }
        }
    }

    @Test
    fun `ERR-009 없는 부위는 찾을 수 없다`() {
        exercises(UUID.randomUUID()).andExpect {
            status { isNotFound() }
            jsonPath("$.code") { value("EXERCISE_CATEGORY_NOT_FOUND") }
        }
    }

    @Test
    fun `DEC-WORKOUT-012 최근 수행일은 내가 완료한 세션 중 가장 늦은 수행 날짜다`() {
        workout(me, "벤치프레스", complete = true)
        clock.advance(Duration.ofDays(1))
        workout(me, "벤치프레스", complete = true)
        workout(users.signIn("other.user"), "인클라인 벤치프레스", complete = true)
        workout(me, "펙덱 플라이", complete = false)

        exercises(operator.categoryId("가슴")).andExpect {
            jsonPath("$[?(@.name == '벤치프레스')].lastPerformedDate") { value(contains("2026-10-06")) }
            jsonPath("$[?(@.name == '인클라인 벤치프레스')].lastPerformedDate") { value(contains(nullValue())) }
            jsonPath("$[?(@.name == '펙덱 플라이')].lastPerformedDate") { value(contains(nullValue())) }
        }
    }

    @Test
    fun `DEC-WORKOUT-012 세트 없이 추가만 하고 완료한 종목은 최근 수행일이 없다`() {
        workout(me, "벤치프레스", complete = true, addedWithoutSets = listOf("스미스 벤치프레스"))

        exercises(operator.categoryId("가슴")).andExpect {
            jsonPath("$[?(@.name == '벤치프레스')].lastPerformedDate") { value(contains("2026-10-05")) }
            jsonPath("$[?(@.name == '스미스 벤치프레스')].lastPerformedDate") { value(contains(nullValue())) }
        }
    }

    @Test
    fun `BR-013 6시간이 지나 자동 완료된 세션도 최근 수행일에 반영된다`() {
        workout(me, "벤치프레스", complete = false)
        clock.advance(Duration.ofHours(7))

        exercises(operator.categoryId("가슴")).andExpect { jsonPath("$[0].lastPerformedDate") { value("2026-10-05") } }
    }

    private fun workout(user: SignedInUser, exercise: String, complete: Boolean, addedWithoutSets: List<String> = emptyList()) {
        val auth = "Bearer ${user.token}"
        val session = idOf(mockMvc.post("/api/v1/workout-sessions") {
            header("Authorization", auth)
            header("X-Time-Zone", "Asia/Seoul")
        })
        val sessionExercise = idOf(mockMvc.post("/api/v1/workout-sessions/$session/exercises") {
            header("Authorization", auth)
            contentType = MediaType.APPLICATION_JSON
            content = """{"exerciseId": "${operator.exerciseId(exercise, user.id)}"}"""
        }, "sessionExerciseId")
        mockMvc.post("/api/v1/workout-sessions/$session/exercises/$sessionExercise/sets") {
            header("Authorization", auth)
            contentType = MediaType.APPLICATION_JSON
            content = """{"weight": 60, "repetitions": 10}"""
        }.andExpect { status { isCreated() } }
        addedWithoutSets.forEach { other ->
            mockMvc.post("/api/v1/workout-sessions/$session/exercises") {
                header("Authorization", auth)
                contentType = MediaType.APPLICATION_JSON
                content = """{"exerciseId": "${operator.exerciseId(other, user.id)}"}"""
            }.andExpect { status { isCreated() } }
        }
        if (complete) {
            mockMvc.post("/api/v1/workout-sessions/$session/complete") { header("Authorization", auth) }
                .andExpect { status { isOk() } }
        }
    }

    private fun idOf(result: ResultActionsDsl, field: String = "id"): String =
        objectMapper.readTree(result.andReturn().response.contentAsString).get(field).asString()

    private fun exercises(categoryId: UUID, user: SignedInUser = me): ResultActionsDsl =
        mockMvc.get("/api/v1/exercises") {
            header("Authorization", "Bearer ${user.token}")
            param("categoryId", categoryId.toString())
        }
}
