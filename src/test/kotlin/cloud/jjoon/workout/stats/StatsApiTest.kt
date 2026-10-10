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
        me = users.signIn("demo.user")
    }

    @Test
    fun `REQ-STATS-001 BR-020 고른 부위를 한 날만 그 부위의 볼륨과 함께 준다`() {
        workout(me, "벤치프레스", "스쿼트")                         // 10-05: 가슴 600, 하체 600
        clock.advance(Duration.ofDays(1))
        workout(me, "벤치프레스", "인클라인 벤치프레스")              // 10-06: 가슴 1200
        clock.advance(Duration.ofDays(2))
        val session = start(me)                                    // 10-08: 등 only, chest added without sets
        addSet(session, addExercise(session, "랫풀다운", me), 60, me)
        addExercise(session, "벤치프레스", me)
        complete(session, me)
        clock.advance(Duration.ofDays(1))
        workout(me, "펙덱 플라이", weight = 0)                       // 10-09: 가슴 0 (bodyweight still counts)
        clock.advance(Duration.ofDays(1))
        workout(me, "벤치프레스", complete = false)                  // 10-10: in progress, not a workout day yet

        categoryVolumes("가슴").andExpect {
            status { isOk() }
            jsonPath("$.dates") { value(contains("2026-10-05", "2026-10-06", "2026-10-09")) }
            jsonPath("$.volumes") { value(contains(600.0, 1200.0, 0.0)) }
            jsonPath("$.hasPrevious") { value(false) }
        }
        categoryVolumes("하체").andExpect {
            jsonPath("$.dates") { value(contains("2026-10-05")) }
            jsonPath("$.volumes") { value(contains(600.0)) }
        }
    }

    @Test
    fun `REQ-STATS-002 BR-016 BR-020 넘기면 고른 부위를 한 날 하나씩 밀리고 그 부위를 처음 한 날에서 멈춘다`() {
        workout(me, "스쿼트")                                       // 10-05: legs only
        clock.advance(Duration.ofDays(1))
        repeat(15) {                                               // 10-06 … 10-20: chest
            workout(me, "벤치프레스")
            clock.advance(Duration.ofDays(1))
        }

        categoryVolumes().andExpect {
            jsonPath("$.dates") { value(contains(*days(9..20))) }
            jsonPath("$.hasPrevious") { value(true) }
        }
        categoryVolumes(before = "2026-10-20").andExpect {
            status { isOk() }
            jsonPath("$.dates") { value(contains(*days(8..19))) }
            jsonPath("$.hasPrevious") { value(true) }
        }
        categoryVolumes(before = "2026-10-19").andExpect { jsonPath("$.dates") { value(contains(*days(7..18))) } }
        categoryVolumes(before = "2026-10-18").andExpect {
            jsonPath("$.dates") { value(contains(*days(6..17))) }
            jsonPath("$.hasPrevious") { value(false) }               // 10-05 is a workout day, but not for chest
        }
    }

    @Test
    fun `BR-021 가장 최근 날로부터 3개월 전 같은 날짜까지만 보여주고 넘기면 그 경계도 옮겨진다`() {
        workout(me, "벤치프레스")                                   // 10-05
        clock.advance(Duration.ofDays(1))
        workout(me, "벤치프레스")                                   // 10-06: exactly 3 months before 01-06
        repeat(4) {                                                // 92 days, in steps the login outlives (30 days idle)
            clock.advance(Duration.ofDays(23))
            categoryVolumes()
        }
        workout(me, "벤치프레스")                                   // 2027-01-06

        categoryVolumes().andExpect {
            jsonPath("$.dates") { value(contains("2026-10-06", "2027-01-06")) }
            jsonPath("$.hasPrevious") { value(true) }               // 10-05 is left out though fewer than 12
        }
        exerciseVolumes(listOf("벤치프레스")).andExpect {
            jsonPath("$.dates") { value(contains("2026-10-06", "2027-01-06")) }
            jsonPath("$.hasPrevious") { value(true) }
        }
        categoryVolumes(before = "2027-01-06").andExpect {
            jsonPath("$.dates") { value(contains("2026-10-05", "2026-10-06")) }
            jsonPath("$.hasPrevious") { value(false) }
        }
    }

    @Test
    fun `BR-001 BR-002 다른 사용자 기록은 빠지고 6시간 지난 세션은 자동 완료되어 운동한 날이 된다`() {
        workout(users.signIn("other.user"), "벤치프레스")          // someone else's 10-05
        clock.advance(Duration.ofDays(1))
        workout(me, "스쿼트", complete = false)                     // 10-06, left in progress
        clock.advance(Duration.ofHours(7))

        categoryVolumes("하체").andExpect {
            jsonPath("$.dates") { value(contains("2026-10-06")) }
            jsonPath("$.volumes") { value(contains(600.0)) }
        }
        categoryVolumes("가슴").andExpect { jsonPath("$.dates") { isEmpty() } }
    }

    @Test
    fun `ERR-003 고른 부위를 한 날이 없으면 오류가 아니라 빈 결과다`() {
        categoryVolumes().andExpect {
            status { isOk() }
            jsonPath("$.dates") { isEmpty() }
            jsonPath("$.volumes") { isEmpty() }
            jsonPath("$.hasPrevious") { value(false) }
        }
    }

    @Test
    fun `ERR-009 부위별 추이에 부위가 없거나 형식이 틀리거나 없는 부위면 입력값 오류다`() {
        listOf(null, "chest").forEach {
            categoryVolumesById(it).andExpect {
                status { isBadRequest() }
                jsonPath("$.code") { value("VALIDATION_FAILED") }
            }
        }
        categoryVolumesById(UUID.randomUUID().toString()).andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("VALIDATION_FAILED") }
            jsonPath("$.errors[0].field") { value("categoryId") }
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
            jsonPath("$[0].id") { value(operator.exerciseId("벤치프레스", me.id).toString()) }
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
    fun `REQ-STATS-004 BR-009 BR-015 고른 종목 중 하나라도 한 날마다 종목별 볼륨을 요청 순서로 주고 안 한 날은 값이 없다`() {
        workout(me, "벤치프레스", "인클라인 벤치프레스")              // 10-05: 600, 600
        workout(users.signIn("other.user"), "벤치프레스")          // someone else's, same day
        clock.advance(Duration.ofDays(1))
        workout(me, "벤치프레스", weight = 70)                      // 10-06: 700
        clock.advance(Duration.ofDays(1))
        workout(me, "펙덱 플라이")                                  // 10-07: chest, but not picked
        clock.advance(Duration.ofDays(1))
        workout(me, "인클라인 벤치프레스", complete = false)          // 10-08: in progress, not a workout day yet

        exerciseVolumes(listOf("인클라인 벤치프레스", "벤치프레스")).andExpect {
            status { isOk() }
            jsonPath("$.dates") { value(contains("2026-10-05", "2026-10-06")) }
            jsonPath("$.exercises[*].name") { value(contains("인클라인 벤치프레스", "벤치프레스")) }
            jsonPath("$.exercises[0].volumes") { value(contains<Any?>(600.0, null)) }
            jsonPath("$.exercises[1].volumes") { value(contains<Any?>(600.0, 700.0)) }
            jsonPath("$.hasPrevious") { value(false) }
        }
        exerciseVolumes(listOf("인클라인 벤치프레스")).andExpect { jsonPath("$.dates") { value(contains("2026-10-05")) } }
        exerciseVolumes(listOf("스쿼트")).andExpect {                // ERR-006: never done
            status { isOk() }
            jsonPath("$.dates") { isEmpty() }
            jsonPath("$.exercises[0].volumes") { isEmpty() }
            jsonPath("$.hasPrevious") { value(false) }
        }
    }

    @Test
    fun `REQ-STATS-004 BR-016 종목별 추이를 넘기면 고른 종목을 한 날 하나씩 밀리고 처음 한 날에서 멈춘다`() {
        workout(me, "스쿼트")                                       // 10-05: not picked
        clock.advance(Duration.ofDays(1))
        repeat(15) {                                               // 10-06 … 10-20
            workout(me, "벤치프레스")
            clock.advance(Duration.ofDays(1))
        }

        exerciseVolumes(listOf("벤치프레스")).andExpect {
            jsonPath("$.dates") { value(contains(*days(9..20))) }
            jsonPath("$.hasPrevious") { value(true) }
        }
        exerciseVolumes(listOf("벤치프레스"), before = "2026-10-20").andExpect { jsonPath("$.dates") { value(contains(*days(8..19))) } }
        exerciseVolumes(listOf("벤치프레스"), before = "2026-10-18").andExpect {
            jsonPath("$.dates") { value(contains(*days(6..17))) }
            jsonPath("$.hasPrevious") { value(false) }
        }
    }

    @Test
    fun `ERR-005 종목은 1개 이상이고 중복이나 없는 종목은 입력값 오류다`() {
        val legs = listOf("스쿼트", "레그 프레스", "레그 익스텐션", "레그 컬", "런지", "힙 어덕션", "힙 어브덕션", "힙 쓰러스트")
        exerciseVolumes(legs).andExpect { status { isOk() } } // BR-017: a whole body part, no count limit

        fun rejects(result: ResultActionsDsl, field: String) = result.andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("VALIDATION_FAILED") }
            jsonPath("$.errors[0].field") { value(field) }
        }
        rejects(exerciseVolumes(emptyList()), "exerciseIds")
        rejects(exerciseVolumes(listOf("벤치프레스", "벤치프레스")), "exerciseIds")
        rejects(exerciseVolumesById(listOf(UUID.randomUUID().toString())), "exerciseIds") // ERR-005
        exerciseVolumes(legs, before = "2026-10-32").andExpect { status { isBadRequest() } } // ERR-002
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
        categoryVolumes("가슴").andExpect { jsonPath("$.volumes") { value(contains(byName.getValue("가슴"))) } } // 620 + 0 + 350 + 400
        categoryVolumes("하체").andExpect { jsonPath("$.volumes") { value(contains(byName.getValue("하체"))) } }
    }

    private fun exerciseVolumes(exercises: List<String>, before: String? = null): ResultActionsDsl =
        exerciseVolumesById(exercises.map { operator.exerciseId(it, me.id).toString() }, before)

    private fun exerciseVolumesById(exerciseIds: List<String>, before: String? = null): ResultActionsDsl =
        mockMvc.get("/api/v1/stats/exercise-volumes") {
            header("Authorization", "Bearer ${me.token}")
            exerciseIds.forEach { param("exerciseIds", it) }
            before?.let { param("before", it) }
        }

    private fun exercises(categoryId: String?): ResultActionsDsl =
        mockMvc.get("/api/v1/stats/exercises") {
            header("Authorization", "Bearer ${me.token}")
            categoryId?.let { param("categoryId", it) }
        }

    private fun days(range: IntRange): Array<String> = range.map { "2026-10-%02d".format(it) }.toTypedArray()

    private fun categoryVolumes(category: String = "가슴", before: String? = null): ResultActionsDsl =
        categoryVolumesById(operator.categoryId(category).toString(), before)

    private fun categoryVolumesById(categoryId: String?, before: String? = null): ResultActionsDsl =
        mockMvc.get("/api/v1/stats/category-volumes") {
            header("Authorization", "Bearer ${me.token}")
            categoryId?.let { param("categoryId", it) }
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
            content = """{"exerciseId": "${operator.exerciseId(name, user.id)}"}"""
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
