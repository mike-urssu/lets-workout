package cloud.jjoon.workout.stats

import cloud.jjoon.workout.TestcontainersConfiguration
import cloud.jjoon.workout.support.MutableClock
import cloud.jjoon.workout.support.Operator
import cloud.jjoon.workout.support.SignedInUser
import cloud.jjoon.workout.support.TestClockConfiguration
import cloud.jjoon.workout.support.TestUsers
import org.hamcrest.Matchers.contains
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

/** Workout stats: body-part and exercise volume trends over workout days (design workout-stats). */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class, TestClockConfiguration::class)
class StatsApiTest {

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var objectMapper: ObjectMapper
    @Autowired lateinit var operator: Operator
    @Autowired lateinit var users: TestUsers
    @Autowired lateinit var clock: MutableClock

    lateinit var me: SignedInUser

    @BeforeEach
    fun setUp() {
        operator.deleteAllAccounts()
        clock.reset() // 2026-10-05T09:00Z, 18:00 in Seoul
        me = users.signIn("joonhee.song")
    }

    @Test
    fun `REQ-STATS-001 BR-006 운동한 날마다 부위 4개의 볼륨을 주고 안 한 부위는 값이 없다`() {
        workout(me, "벤치프레스", "스쿼트")                         // 10-05: 가슴 600, 하체 600
        clock.advance(Duration.ofDays(1))
        workout(me, "벤치프레스", "인클라인 벤치프레스")              // 10-06: 가슴 1200
        clock.advance(Duration.ofDays(2))
        workout(me, "랫풀다운", weight = 0)                         // 10-08: 등 0 (bodyweight still counts)
        clock.advance(Duration.ofDays(1))
        workout(me, "스쿼트", complete = false)                     // 10-09: in progress, not a workout day yet

        categoryVolumes().andExpect {
            status { isOk() }
            jsonPath("$.dates") { value(contains("2026-10-05", "2026-10-06", "2026-10-08")) }
            jsonPath("$.categories[*].name") { value(contains("가슴", "등", "어깨", "하체")) }
            jsonPath("$.categories[0].volumes") { value(contains<Any?>(600.0, 1200.0, null)) }
            jsonPath("$.categories[1].volumes") { value(contains<Any?>(null, null, 0.0)) }
            jsonPath("$.categories[2].volumes") { value(contains<Any?>(null, null, null)) }
            jsonPath("$.categories[3].volumes") { value(contains<Any?>(600.0, null, null)) }
            jsonPath("$.hasPrevious") { value(false) }
        }
    }

    @Test
    fun `REQ-STATS-002 BR-016 기준 날짜 이전으로 넘기면 운동한 날 하나씩 밀리고 처음 운동한 날에서 멈춘다`() {
        repeat(10) {                                               // 10-05 … 10-14
            workout(me, "벤치프레스")
            clock.advance(Duration.ofDays(1))
        }

        categoryVolumes().andExpect {
            jsonPath("$.dates") { value(contains(*days(8..14))) }
            jsonPath("$.hasPrevious") { value(true) }
        }
        categoryVolumes(before = "2026-10-14").andExpect {
            status { isOk() }
            jsonPath("$.dates") { value(contains(*days(7..13))) }
            jsonPath("$.hasPrevious") { value(true) }
        }
        categoryVolumes(before = "2026-10-13").andExpect { jsonPath("$.dates") { value(contains(*days(6..12))) } }
        categoryVolumes(before = "2026-10-12").andExpect {
            jsonPath("$.dates") { value(contains(*days(5..11))) }
            jsonPath("$.hasPrevious") { value(false) }
        }
    }

    @Test
    fun `BR-001 BR-002 다른 사용자 기록은 빠지고 6시간 지난 세션은 자동 완료되어 운동한 날이 된다`() {
        workout(users.signIn("other.user"), "벤치프레스")          // someone else's 10-05
        clock.advance(Duration.ofDays(1))
        workout(me, "스쿼트", complete = false)                     // 10-06, left in progress
        clock.advance(Duration.ofHours(7))

        categoryVolumes().andExpect {
            jsonPath("$.dates") { value(contains("2026-10-06")) }
            jsonPath("$.categories[3].volumes") { value(contains<Any?>(600.0)) }
        }
    }

    @Test
    fun `ERR-003 운동 기록이 없으면 오류가 아니라 빈 결과다`() {
        categoryVolumes().andExpect {
            status { isOk() }
            jsonPath("$.dates") { isEmpty() }
            jsonPath("$.categories[*].name") { value(contains("가슴", "등", "어깨", "하체")) }
            jsonPath("$.categories[0].volumes") { isEmpty() }
            jsonPath("$.hasPrevious") { value(false) }
        }
    }

    @Test
    fun `ERR-002 기준 날짜 형식이 틀리면 입력값 오류다`() {
        listOf("2026-10-32", "2026/10/05", "yesterday").forEach {
            categoryVolumes(before = it).andExpect {
                status { isBadRequest() }
                jsonPath("$.code") { value("VALIDATION_FAILED") }
            }
        }
    }

    @Test
    fun `REQ-STATS-003 BR-011 부위의 종목 목록에는 완료한 세션에서 세트를 기록한 종목만 목록 순서로 나온다`() {
        val session = start(me)
        addExercise(session, "펙덱 플라이", me)                       // added but no sets
        addSet(session, addExercise(session, "인클라인 벤치프레스", me), 60, me)
        addSet(session, addExercise(session, "벤치프레스", me), 60, me)
        addSet(session, addExercise(session, "랫풀다운", me), 60, me)  // another body part
        complete(session, me)
        clock.advance(Duration.ofDays(1))
        workout(me, "스미스 벤치프레스", complete = false)            // only in progress
        workout(users.signIn("other.user"), "스미스 인클라인 벤치프레스")

        exercises(operator.categoryId("가슴").toString()).andExpect {
            status { isOk() }
            jsonPath("$[*].name") { value(contains("벤치프레스", "인클라인 벤치프레스")) }
            jsonPath("$[0].id") { value(operator.exerciseId("벤치프레스").toString()) }
        }
        exercises(operator.categoryId("어깨").toString()).andExpect {
            status { isOk() }
            jsonPath("$") { isEmpty() }
        }
    }

    @Test
    fun `ERR-004 부위가 없거나 형식이 틀리거나 없는 부위면 입력값 오류다`() {
        listOf(null, "chest").forEach {
            exercises(it).andExpect {
                status { isBadRequest() }
                jsonPath("$.code") { value("VALIDATION_FAILED") }
            }
        }
        exercises(UUID.randomUUID().toString()).andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("VALIDATION_FAILED") }
            jsonPath("$.errors[0].field") { value("categoryId") }
        }
    }

    @Test
    fun `REQ-STATS-004 BR-009 고른 종목마다 받은 날짜의 볼륨을 요청 순서로 주고 안 한 날은 값이 없다`() {
        workout(me, "벤치프레스", "인클라인 벤치프레스")              // 10-05: 600, 600
        workout(users.signIn("other.user"), "벤치프레스")          // someone else's, same day
        clock.advance(Duration.ofDays(1))
        workout(me, "벤치프레스", weight = 70)                      // 10-06: 700
        clock.advance(Duration.ofDays(1))
        workout(me, "스쿼트")                                       // 10-07

        exerciseVolumes(listOf("인클라인 벤치프레스", "벤치프레스"), listOf("2026-10-07", "2026-10-05", "2026-10-06")).andExpect {
            status { isOk() }
            jsonPath("$.dates") { value(contains("2026-10-05", "2026-10-06", "2026-10-07")) }
            jsonPath("$.exercises[*].name") { value(contains("인클라인 벤치프레스", "벤치프레스")) }
            jsonPath("$.exercises[0].volumes") { value(contains<Any?>(600.0, null, null)) }
            jsonPath("$.exercises[1].volumes") { value(contains<Any?>(600.0, 700.0, null)) }
        }
    }

    @Test
    fun `ERR-005 종목은 1개 이상, 날짜는 1~7개이고 중복이나 없는 종목은 입력값 오류다`() {
        val four = listOf("벤치프레스", "스미스 벤치프레스", "인클라인 벤치프레스", "펙덱 플라이")
        val legs = listOf("스쿼트", "레그 프레스", "레그 익스텐션", "레그 컬", "런지", "힙 어덕션", "힙 어브덕션", "힙 쓰러스트")
        val sevenDays = days(1..7).toList()
        exerciseVolumes(four, sevenDays).andExpect { status { isOk() } }
        exerciseVolumes(legs, sevenDays).andExpect { status { isOk() } } // BR-017: a whole body part, no count limit

        fun rejects(result: ResultActionsDsl, field: String) = result.andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("VALIDATION_FAILED") }
            jsonPath("$.errors[0].field") { value(field) }
        }
        rejects(exerciseVolumes(emptyList(), sevenDays), "exerciseIds")
        rejects(exerciseVolumes(listOf("벤치프레스", "벤치프레스"), sevenDays), "exerciseIds")
        rejects(exerciseVolumesById(listOf(UUID.randomUUID().toString()), sevenDays), "exerciseIds") // ERR-005
        rejects(exerciseVolumes(four, days(1..8).toList()), "dates")
        rejects(exerciseVolumes(four, emptyList()), "dates")
        rejects(exerciseVolumes(four, listOf("2026-10-01", "2026-10-01")), "dates")
        exerciseVolumes(four, listOf("2026-10-32")).andExpect { status { isBadRequest() } }
    }

    @Test
    fun `NFR-INTEG-001 같은 날 통계의 부위 볼륨은 날짜별 운동 기록의 부위 볼륨과 같다`() {
        val session = start(me)
        val bench = addExercise(session, "벤치프레스", me)
        addSet(session, bench, 62, me)
        addSet(session, bench, 0, me)
        addSet(session, addExercise(session, "펙덱 플라이", me), 35, me)
        addSet(session, addExercise(session, "스쿼트", me), 100, me)
        complete(session, me)
        workout(me, "인클라인 벤치프레스", weight = 40)               // a second session the same day

        val day = objectMapper.readTree(
            mockMvc.get("/api/v1/workout-days/2026-10-05") { header("Authorization", "Bearer ${me.token}") }
                .andReturn().response.contentAsString,
        )
        val byName = day.get("categories").associate { it.get("name").asString() to it.get("volume").asDouble() }
        categoryVolumes().andExpect {
            jsonPath("$.categories[0].volumes") { value(contains<Any?>(byName.getValue("가슴"))) } // 620 + 0 + 350 + 400
            jsonPath("$.categories[3].volumes") { value(contains<Any?>(byName.getValue("하체"))) }
        }
    }

    private fun exerciseVolumes(exercises: List<String>, dates: List<String>): ResultActionsDsl =
        exerciseVolumesById(exercises.map { operator.exerciseId(it).toString() }, dates)

    private fun exerciseVolumesById(exerciseIds: List<String>, dates: List<String>): ResultActionsDsl =
        mockMvc.get("/api/v1/stats/exercise-volumes") {
            header("Authorization", "Bearer ${me.token}")
            exerciseIds.forEach { param("exerciseIds", it) }
            dates.forEach { param("dates", it) }
        }

    private fun exercises(categoryId: String?): ResultActionsDsl =
        mockMvc.get("/api/v1/stats/exercises") {
            header("Authorization", "Bearer ${me.token}")
            categoryId?.let { param("categoryId", it) }
        }

    private fun days(range: IntRange): Array<String> = range.map { "2026-10-%02d".format(it) }.toTypedArray()

    private fun categoryVolumes(before: String? = null, user: SignedInUser = me): ResultActionsDsl =
        mockMvc.get("/api/v1/stats/category-volumes") {
            header("Authorization", "Bearer ${user.token}")
            before?.let { param("before", it) }
        }

    /** One session with one 10-rep set per exercise, so each exercise's volume is weight × 10. */
    private fun workout(user: SignedInUser, vararg exercises: String, weight: Int = 60, complete: Boolean = true): String {
        val session = start(user)
        exercises.forEach { addSet(session, addExercise(session, it, user), weight, user) }
        if (complete) complete(session, user)
        return session
    }

    private fun start(user: SignedInUser): String = idOf(mockMvc.post("/api/v1/workout-sessions") {
        header("Authorization", "Bearer ${user.token}")
        header("X-Time-Zone", "Asia/Seoul")
    })

    private fun addExercise(session: String, name: String, user: SignedInUser): String =
        idOf(mockMvc.post("/api/v1/workout-sessions/$session/exercises") {
            header("Authorization", "Bearer ${user.token}")
            contentType = MediaType.APPLICATION_JSON
            content = """{"exerciseId": "${operator.exerciseId(name)}"}"""
        }, "sessionExerciseId")

    private fun addSet(session: String, sessionExercise: String, weight: Int, user: SignedInUser) {
        mockMvc.post("/api/v1/workout-sessions/$session/exercises/$sessionExercise/sets") {
            header("Authorization", "Bearer ${user.token}")
            contentType = MediaType.APPLICATION_JSON
            content = """{"weight": $weight, "repetitions": 10}"""
        }.andExpect { status { isCreated() } }
    }

    private fun complete(session: String, user: SignedInUser) {
        mockMvc.post("/api/v1/workout-sessions/$session/complete") { header("Authorization", "Bearer ${user.token}") }
            .andExpect { status { isOk() } }
    }

    private fun idOf(result: ResultActionsDsl, field: String = "id"): String =
        objectMapper.readTree(result.andReturn().response.contentAsString).get(field).asString()
}
