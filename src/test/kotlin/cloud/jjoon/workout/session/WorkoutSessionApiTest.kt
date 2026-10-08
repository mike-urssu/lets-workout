package cloud.jjoon.workout.session

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
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.put
import tools.jackson.databind.ObjectMapper
import java.util.UUID
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class, TestClockConfiguration::class)
class WorkoutSessionApiTest {

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var objectMapper: ObjectMapper
    @Autowired lateinit var operator: Operator
    @Autowired lateinit var users: TestUsers
    @Autowired lateinit var clock: MutableClock
    @Autowired lateinit var jdbc: JdbcTemplate

    lateinit var me: SignedInUser

    @BeforeEach
    fun setUp() {
        operator.deleteAllAccounts()
        operator.deleteAllExercises()
        clock.reset()
        me = users.signIn("joonhee.song")
    }

    @Test
    fun `REQ-WORKOUT-001 운동을 시작하면 진행 중 세션이 만들어지고 홈에서 이어서 할 수 있다`() {
        val id = start().andExpect {
            status { isCreated() }
            jsonPath("$.status") { value("IN_PROGRESS") }
            jsonPath("$.performedDate") { value("2026-10-05") }
            jsonPath("$.startedAt") { value("2026-10-05T09:00:00Z") }
            jsonPath("$.endedAt") { value(null) }
            jsonPath("$.memo") { value(null) }
            jsonPath("$.exercises") { isEmpty() }
            jsonPath("$.summary.durationSeconds") { value(null) }
            jsonPath("$.summary.totalSets") { value(0) }
        }.andReturn().let { objectMapper.readTree(it.response.contentAsString).get("id").asString() }

        inProgress().andExpect {
            status { isOk() }
            jsonPath("$.id") { value(id) }
            jsonPath("$.status") { value("IN_PROGRESS") }
        }
    }

    @Test
    fun `WO-001 진행 중 세션이 없으면 내용 없음으로 응답한다`() {
        inProgress().andExpect { status { isNoContent() } }
    }

    @Test
    fun `BR-011 진행 중 세션이 있으면 새로 시작할 수 없다`() {
        start().andExpect { status { isCreated() } }

        start().andExpect {
            status { isConflict() }
            jsonPath("$.code") { value("WORKOUT_SESSION_ALREADY_IN_PROGRESS") }
            jsonPath("$.message") { value("이미 진행 중인 운동이 있습니다.") }
        }
    }

    @Test
    fun `BR-011 같은 사용자가 동시에 시작해도 진행 중 세션은 하나다`() {
        val pool = Executors.newFixedThreadPool(5)
        val ready = CountDownLatch(1)
        val statuses = (1..5).map {
            pool.submit<Int> {
                ready.await()
                start().andReturn().response.status
            }
        }
        ready.countDown()
        val results = statuses.map { it.get(30, TimeUnit.SECONDS) }
        pool.shutdown()

        assertEquals(1, results.count { it == 201 })
        assertEquals(4, results.count { it == 409 })
    }

    @Test
    fun `BR-004 수행 날짜는 시작 시각의 사용자 현지 날짜다`() {
        clock.advance(Duration.ofHours(6).plusMinutes(30)) // 2026-10-05T15:30Z = 2026-10-06 00:30 in Seoul

        start("Asia/Seoul").andExpect {
            jsonPath("$.performedDate") { value("2026-10-06") }
            jsonPath("$.startedAt") { value("2026-10-05T15:30:00Z") }
        }
    }

    @Test
    fun `ERR-010 시간대 헤더가 없거나 잘못되면 입력값 오류다`() {
        start(timeZone = null).andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("VALIDATION_FAILED") }
        }
        start(timeZone = "Mars/Olympus").andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("VALIDATION_FAILED") }
        }
    }

    @Test
    fun `ERR-001 로그인하지 않으면 운동 기록 기능을 쓸 수 없다`() {
        listOf(
            mockMvc.post("/api/v1/workout-sessions") { header("X-Time-Zone", "Asia/Seoul") },
            mockMvc.get("/api/v1/workout-sessions/in-progress"),
            mockMvc.get("/api/v1/exercises"),
        ).forEach { it.andExpect { status { isUnauthorized() }; jsonPath("$.code") { value("UNAUTHORIZED") } } }
    }

    @Test
    fun `REQ-EXERCISE-001 세션에 추가한 운동은 추가한 순서로 상세에 나온다`() {
        val squat = operator.addExercise("스쿼트", "하체")
        val bench = operator.addExercise("벤치프레스", "가슴", "/images/exercises/bench-press.png")
        val session = startedSessionId()

        addExercise(session, squat).andExpect {
            status { isCreated() }
            jsonPath("$.sessionExerciseId") { isString() }
            jsonPath("$.exerciseId") { value(squat.toString()) }
            jsonPath("$.name") { value("스쿼트") }
            jsonPath("$.sets") { isEmpty() }
            jsonPath("$.totalSets") { value(0) }
        }
        clock.advance(Duration.ofMinutes(1))
        addExercise(session, bench)
        clock.advance(Duration.ofMinutes(1))
        addExercise(session, squat) // the same exercise may be added again (appendix C-3)

        detail(session).andExpect {
            status { isOk() }
            jsonPath("$.id") { value(session) }
            jsonPath("$.exercises[*].name") { value(contains("스쿼트", "벤치프레스", "스쿼트")) }
            jsonPath("$.exercises[1].category") { value("가슴") }
            jsonPath("$.exercises[1].imageUrl") { value("/images/exercises/bench-press.png") }
        }
    }

    @Test
    fun `ERR-002 목록에 없는 운동은 추가할 수 없다`() {
        addExercise(startedSessionId(), UUID.randomUUID()).andExpect {
            status { isNotFound() }
            jsonPath("$.code") { value("EXERCISE_NOT_FOUND") }
        }
    }

    @Test
    fun `ERR-010 추가할 운동을 고르지 않으면 입력값 오류다`() {
        mockMvc.post("/api/v1/workout-sessions/${startedSessionId()}/exercises") {
            header("Authorization", "Bearer ${me.token}")
            contentType = MediaType.APPLICATION_JSON
            content = "{}"
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("VALIDATION_FAILED") }
        }
    }

    @Test
    fun `ERR-009 없는 세션은 찾을 수 없다`() {
        val exercise = operator.addExercise("스쿼트", "하체")
        val missing = UUID.randomUUID().toString()

        detail(missing).andExpect {
            status { isNotFound() }
            jsonPath("$.code") { value("WORKOUT_SESSION_NOT_FOUND") }
        }
        addExercise(missing, exercise).andExpect { jsonPath("$.code") { value("WORKOUT_SESSION_NOT_FOUND") } }
    }

    @Test
    fun `ERR-003 다른 사용자의 세션은 조회하거나 바꿀 수 없다`() {
        val exercise = operator.addExercise("스쿼트", "하체")
        val othersSession = startedSessionId(users.signIn("other.user"))

        detail(othersSession).andExpect {
            status { isForbidden() }
            jsonPath("$.code") { value("FORBIDDEN") }
        }
        addExercise(othersSession, exercise).andExpect { status { isForbidden() } }
    }

    @Test
    fun `ERR-010 세션 ID가 UUID 형식이 아니면 입력값 오류다`() {
        detail("not-a-uuid").andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("VALIDATION_FAILED") }
        }
    }

    @Test
    fun `REQ-SET-001 세트를 추가하면 운동마다 1번부터 번호가 붙고 상세 요약에 반영된다`() {
        val session = startedSessionId()
        val bench = sessionExerciseId(session, operator.addExercise("벤치프레스", "가슴"))

        addSet(session, bench, """{"weight": 60, "repetitions": 10}""").andExpect {
            status { isCreated() }
            jsonPath("$.id") { isString() }
            jsonPath("$.setNumber") { value(1) }
            jsonPath("$.weight") { value(60.0) }
            jsonPath("$.repetitions") { value(10) }
        }
        addSet(session, bench, """{"weight": 62.5, "repetitions": 8}""").andExpect { jsonPath("$.setNumber") { value(2) } }

        detail(session).andExpect {
            jsonPath("$.exercises[0].sets[*].setNumber") { value(contains(1, 2)) }
            jsonPath("$.exercises[0].sets[1].weight") { value(62.5) }
            jsonPath("$.exercises[0].totalSets") { value(2) }
            jsonPath("$.exercises[0].totalRepetitions") { value(18) }
            jsonPath("$.summary.totalSets") { value(2) }
            jsonPath("$.summary.totalWeight") { value(1100.0) }
        }
    }

    @Test
    fun `BR-003 BR-005 경계값 안의 중량과 반복 횟수는 기록할 수 있다`() {
        val session = startedSessionId()
        val bench = sessionExerciseId(session, operator.addExercise("벤치프레스", "가슴"))

        addSet(session, bench, """{"weight": 0, "repetitions": 1}""").andExpect { status { isCreated() } }
        addSet(session, bench, """{"weight": 1000, "repetitions": 1000}""").andExpect { status { isCreated() } }
        addSet(session, bench, """{"weight": 62.55, "repetitions": 8}""").andExpect { status { isCreated() } }
    }

    @Test
    fun `ERR-005 ERR-010 범위를 벗어나거나 형식이 틀리거나 빠진 세트 값은 입력값 오류다`() {
        val session = startedSessionId()
        val bench = sessionExerciseId(session, operator.addExercise("벤치프레스", "가슴"))

        listOf(
            """{"weight": -0.01, "repetitions": 10}""",
            """{"weight": 1000.01, "repetitions": 10}""",
            """{"weight": 62.555, "repetitions": 10}""",
            """{"weight": 60, "repetitions": 0}""",
            """{"weight": 60, "repetitions": 1001}""",
            """{"weight": 60, "repetitions": 8.5}""",
            """{"repetitions": 10}""",
            """{"weight": 60}""",
        ).forEach { body ->
            addSet(session, bench, body).andExpect {
                status { isBadRequest() }
                jsonPath("$.code") { value("VALIDATION_FAILED") }
            }
        }
        detail(session).andExpect { jsonPath("$.summary.totalSets") { value(0) } }
    }

    @Test
    fun `REQ-SET-002 세트를 고쳐도 세트 번호는 그대로다`() {
        val session = startedSessionId()
        val bench = sessionExerciseId(session, operator.addExercise("벤치프레스", "가슴"))
        addSet(session, bench, """{"weight": 60, "repetitions": 10}""")
        val second = idOf(addSet(session, bench, """{"weight": 60, "repetitions": 10}"""))

        updateSet(session, bench, second, """{"weight": 70.25, "repetitions": 5}""").andExpect {
            status { isOk() }
            jsonPath("$.id") { value(second) }
            jsonPath("$.setNumber") { value(2) }
            jsonPath("$.weight") { value(70.25) }
            jsonPath("$.repetitions") { value(5) }
        }
        detail(session).andExpect { jsonPath("$.exercises[0].sets[1].weight") { value(70.25) } }
    }

    @Test
    fun `BR-007 세트를 지우면 남은 세트가 빈 번호 없이 다시 번호를 가진다`() {
        val session = startedSessionId()
        val bench = sessionExerciseId(session, operator.addExercise("벤치프레스", "가슴"))
        val first = idOf(addSet(session, bench, """{"weight": 60, "repetitions": 10}"""))
        val second = idOf(addSet(session, bench, """{"weight": 62.5, "repetitions": 8}"""))
        val third = idOf(addSet(session, bench, """{"weight": 65, "repetitions": 6}"""))

        deleteSet(session, bench, second).andExpect { status { isNoContent() } }

        detail(session).andExpect {
            jsonPath("$.exercises[0].sets[*].id") { value(contains(first, third)) }
            jsonPath("$.exercises[0].sets[*].setNumber") { value(contains(1, 2)) }
        }
    }

    @Test
    fun `ERR-009 세션에 없는 운동이나 그 운동에 없는 세트는 찾을 수 없다`() {
        val session = startedSessionId()
        val bench = sessionExerciseId(session, operator.addExercise("벤치프레스", "가슴"))
        val squat = sessionExerciseId(session, operator.addExercise("스쿼트", "하체"))
        val benchSet = idOf(addSet(session, bench, """{"weight": 60, "repetitions": 10}"""))
        val unknown = UUID.randomUUID().toString()

        addSet(session, unknown, """{"weight": 60, "repetitions": 10}""").andExpect {
            status { isNotFound() }
            jsonPath("$.code") { value("SESSION_EXERCISE_NOT_FOUND") }
        }
        updateSet(session, squat, benchSet, """{"weight": 60, "repetitions": 10}""").andExpect {
            status { isNotFound() }
            jsonPath("$.code") { value("WORKOUT_SET_NOT_FOUND") }
        }
        deleteSet(session, bench, unknown).andExpect { jsonPath("$.code") { value("WORKOUT_SET_NOT_FOUND") } }
        removeExercise(session, unknown).andExpect { jsonPath("$.code") { value("SESSION_EXERCISE_NOT_FOUND") } }
    }

    @Test
    fun `NFR-SEC-002 다른 사용자의 운동과 세트 ID를 내 세션 경로에 넣어도 접근할 수 없다`() {
        val other = users.signIn("other.user")
        val exercise = operator.addExercise("벤치프레스", "가슴")
        val othersSession = startedSessionId(other)
        val othersBench = sessionExerciseId(othersSession, exercise, other)
        val othersSet = idOf(addSet(othersSession, othersBench, """{"weight": 60, "repetitions": 10}""", other))
        val mySession = startedSessionId()
        val myBench = sessionExerciseId(mySession, exercise)

        addSet(mySession, othersBench, """{"weight": 1, "repetitions": 1}""").andExpect { status { isNotFound() } }
        updateSet(mySession, myBench, othersSet, """{"weight": 1, "repetitions": 1}""").andExpect { status { isNotFound() } }
        deleteSet(mySession, myBench, othersSet).andExpect { status { isNotFound() } }
        removeExercise(mySession, othersBench).andExpect { status { isNotFound() } }
        addSet(othersSession, othersBench, """{"weight": 1, "repetitions": 1}""").andExpect { status { isForbidden() } }

        detail(othersSession, other).andExpect {
            jsonPath("$.exercises[0].sets[*].weight") { value(contains(60.0)) }
        }
    }

    @Test
    fun `REQ-EXERCISE-002 세션에서 운동을 지우면 그 운동의 세트도 함께 지워진다`() {
        val session = startedSessionId()
        val bench = sessionExerciseId(session, operator.addExercise("벤치프레스", "가슴"))
        addSet(session, bench, """{"weight": 60, "repetitions": 10}""")

        removeExercise(session, bench).andExpect { status { isNoContent() } }

        detail(session).andExpect {
            jsonPath("$.exercises") { isEmpty() }
            jsonPath("$.summary.totalSets") { value(0) }
        }
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM workout_set", Int::class.java))
    }

    @Test
    fun `REQ-WORKOUT-002 세트가 있는 세션을 완료하면 메모와 요약이 담긴 기록이 된다`() {
        val session = startedSessionId()
        val bench = sessionExerciseId(session, operator.addExercise("벤치프레스", "가슴"))
        addSet(session, bench, """{"weight": 60, "repetitions": 10}""")
        clock.advance(Duration.ofMinutes(65))

        complete(session, """{"memo": "하체 위주"}""").andExpect {
            status { isOk() }
            jsonPath("$.status") { value("COMPLETED") }
            jsonPath("$.endedAt") { value("2026-10-05T10:05:00Z") }
            jsonPath("$.memo") { value("하체 위주") }
            jsonPath("$.summary.durationSeconds") { value(3900) }
            jsonPath("$.summary.exerciseCount") { value(1) }
            jsonPath("$.summary.totalWeight") { value(600.0) }
        }
        inProgress().andExpect { status { isNoContent() } }
        start().andExpect { status { isCreated() } }
    }

    @Test
    fun `REQ-WORKOUT-002 메모 없이 완료할 수 있고 메모는 500자까지다`() {
        val session = startedSessionId()
        addSet(session, sessionExerciseId(session, operator.addExercise("벤치프레스", "가슴")), """{"weight": 60, "repetitions": 10}""")

        complete(session, """{"memo": "${"가".repeat(501)}"}""").andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("VALIDATION_FAILED") }
        }
        complete(session, body = null).andExpect {
            status { isOk() }
            jsonPath("$.memo") { value(null) }
        }
    }

    @Test
    fun `BR-012 세트가 하나도 없으면 완료할 수 없다`() {
        val session = startedSessionId()
        sessionExerciseId(session, operator.addExercise("벤치프레스", "가슴"))

        complete(session).andExpect {
            status { isConflict() }
            jsonPath("$.code") { value("WORKOUT_SESSION_HAS_NO_SETS") }
            jsonPath("$.message") { value("세트를 하나 이상 기록해야 운동을 완료할 수 있습니다.") }
        }
    }

    @Test
    fun `ERR-008 이미 완료한 세션은 다시 완료할 수 없다`() {
        val session = completedSession()

        complete(session.id).andExpect {
            status { isConflict() }
            jsonPath("$.code") { value("WORKOUT_SESSION_ALREADY_COMPLETED") }
        }
    }

    @Test
    fun `BR-002 완료한 세션의 운동과 세트는 바꿀 수 없다`() {
        val session = completedSession()
        val body = """{"weight": 1, "repetitions": 1}"""

        listOf(
            addExercise(session.id, operator.addExercise("스쿼트", "하체")),
            removeExercise(session.id, session.sessionExerciseId),
            addSet(session.id, session.sessionExerciseId, body),
            updateSet(session.id, session.sessionExerciseId, session.setId, body),
            deleteSet(session.id, session.sessionExerciseId, session.setId),
        ).forEach {
            it.andExpect {
                status { isConflict() }
                jsonPath("$.code") { value("WORKOUT_SESSION_NOT_EDITABLE") }
            }
        }
    }

    @Test
    fun `BR-012 완료와 세트 추가가 동시에 와도 완료된 세션에 세트가 생기지 않는다`() {
        val session = startedSessionId()
        val bench = sessionExerciseId(session, operator.addExercise("벤치프레스", "가슴"))
        addSet(session, bench, """{"weight": 60, "repetitions": 10}""")
        val pool = Executors.newFixedThreadPool(9)
        val ready = CountDownLatch(1)
        val adds = (1..8).map {
            pool.submit<Int> {
                ready.await()
                addSet(session, bench, """{"weight": 60, "repetitions": 10}""").andReturn().response.status
            }
        }
        val completion = pool.submit<String> {
            ready.await()
            complete(session).andReturn().response.contentAsString
        }
        ready.countDown()
        val addStatuses = adds.map { it.get(30, TimeUnit.SECONDS) }
        val completedSets = objectMapper.readTree(completion.get(30, TimeUnit.SECONDS)).at("/summary/totalSets").asInt()
        pool.shutdown()

        assertEquals(emptyList(), addStatuses.filter { it != 201 && it != 409 })
        detail(session).andExpect { jsonPath("$.summary.totalSets") { value(completedSets) } }
        assertEquals(1 + addStatuses.count { it == 201 }, completedSets)
    }

    @Test
    fun `REQ-WORKOUT-003 기록 목록은 최근 수행일 순이고 같은 날은 늦게 시작한 것이 먼저다`() {
        val bench = operator.addExercise("벤치프레스", "가슴")
        val squat = operator.addExercise("스쿼트", "하체")
        val first = startedSessionId()                       // 2026-10-05 18:00 Seoul
        val firstBench = sessionExerciseId(first, bench)
        clock.advance(Duration.ofMinutes(1))
        sessionExerciseId(first, squat)
        clock.advance(Duration.ofMinutes(1))
        sessionExerciseId(first, bench)
        addSet(first, firstBench, """{"weight": 60, "repetitions": 10}""")
        addSet(first, firstBench, """{"weight": 60, "repetitions": 10}""")
        clock.advance(Duration.ofMinutes(60))
        complete(first)
        clock.advance(Duration.ofHours(1))
        val second = startedSessionId()                      // same day, later
        addSet(second, sessionExerciseId(second, squat), """{"weight": 100, "repetitions": 5}""")
        complete(second)
        clock.advance(Duration.ofDays(1))
        val third = startedSessionId()                       // next day, still in progress
        startedSessionId(users.signIn("other.user"))

        history().andExpect {
            status { isOk() }
            jsonPath("$.content[*].id") { value(contains(third, second, first)) }
            jsonPath("$.content[0].status") { value("IN_PROGRESS") }
            jsonPath("$.content[0].performedDate") { value("2026-10-06") }
            jsonPath("$.content[0].durationSeconds") { value(null) }
            jsonPath("$.content[0].exerciseNames") { isEmpty() }
            jsonPath("$.content[0].totalSets") { value(0) }
            jsonPath("$.content[2].exerciseNames") { value(contains("벤치프레스", "스쿼트")) }
            jsonPath("$.content[2].totalSets") { value(2) }
            jsonPath("$.content[2].startedAt") { value("2026-10-05T09:00:00Z") }
            jsonPath("$.content[2].endedAt") { value("2026-10-05T10:02:00Z") }
            jsonPath("$.content[2].durationSeconds") { value(3720) }
            jsonPath("$.totalElements") { value(3) }
            jsonPath("$.totalPages") { value(1) }
            jsonPath("$.page") { value(0) }
            jsonPath("$.size") { value(20) }
        }
    }

    @Test
    fun `REQ-WORKOUT-003 기록 목록은 페이지 단위로 나눠 조회한다`() {
        val bench = operator.addExercise("벤치프레스", "가슴")
        val sessions = (1..3).map {
            val session = completedSession(bench).id
            clock.advance(Duration.ofDays(1))
            session
        }

        history("page=1&size=2").andExpect {
            jsonPath("$.content[*].id") { value(contains(sessions[0])) }
            jsonPath("$.page") { value(1) }
            jsonPath("$.size") { value(2) }
            jsonPath("$.totalElements") { value(3) }
            jsonPath("$.totalPages") { value(2) }
        }
    }

    @Test
    fun `REQ-WORKOUT-003 기록이 없으면 빈 목록이다`() {
        history().andExpect {
            status { isOk() }
            jsonPath("$.content") { isEmpty() }
            jsonPath("$.totalElements") { value(0) }
        }
    }

    @Test
    fun `ERR-010 페이지 번호나 크기가 범위를 벗어나면 입력값 오류다`() {
        listOf("page=-1", "size=0", "size=101").forEach {
            history(it).andExpect {
                status { isBadRequest() }
                jsonPath("$.code") { value("VALIDATION_FAILED") }
            }
        }
    }

    @Test
    fun `REQ-WORKOUT-005 기록을 지우면 운동과 세트도 함께 지워지고 다시 지울 수 없다`() {
        val session = completedSession()

        deleteSession(session.id).andExpect { status { isNoContent() } }

        detail(session.id).andExpect { jsonPath("$.code") { value("WORKOUT_SESSION_NOT_FOUND") } }
        deleteSession(session.id).andExpect {
            status { isNotFound() }
            jsonPath("$.code") { value("WORKOUT_SESSION_NOT_FOUND") }
        }
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM workout_session_exercise", Int::class.java))
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM workout_set", Int::class.java))
    }

    @Test
    fun `REQ-WORKOUT-005 진행 중인 세션도 지울 수 있지만 다른 사용자의 기록은 지울 수 없다`() {
        deleteSession(startedSessionId()).andExpect { status { isNoContent() } }
        inProgress().andExpect { status { isNoContent() } }

        val othersSession = startedSessionId(users.signIn("other.user"))
        deleteSession(othersSession).andExpect {
            status { isForbidden() }
            jsonPath("$.code") { value("FORBIDDEN") }
        }
    }

    @Test
    fun `BR-013 시작 후 6시간이 지난 세션은 마지막 세트 시각에 완료되고 새로 시작할 수 있다`() {
        val session = startedSessionId()
        val bench = sessionExerciseId(session, operator.addExercise("벤치프레스", "가슴"))
        addSet(session, bench, """{"weight": 60, "repetitions": 10}""")
        clock.advance(Duration.ofMinutes(40))
        addSet(session, bench, """{"weight": 60, "repetitions": 10}""")  // 09:40
        clock.advance(Duration.ofHours(5).plusMinutes(21))                // 15:01

        inProgress().andExpect { status { isNoContent() } }
        detail(session).andExpect {
            jsonPath("$.status") { value("COMPLETED") }
            jsonPath("$.endedAt") { value("2026-10-05T09:40:00Z") }
        }
        start().andExpect { status { isCreated() } }
    }

    @Test
    fun `BR-013 시작 후 6시간이 지난 세트 없는 세션은 지워진다`() {
        val session = startedSessionId()
        sessionExerciseId(session, operator.addExercise("벤치프레스", "가슴"))
        clock.advance(Duration.ofHours(6).plusSeconds(1))

        detail(session).andExpect { jsonPath("$.code") { value("WORKOUT_SESSION_NOT_FOUND") } }
    }

    @Test
    fun `BR-013 6시간이 지나지 않은 세션은 그대로 진행 중이다`() {
        val session = startedSessionId()
        clock.advance(Duration.ofHours(6))

        inProgress().andExpect { jsonPath("$.id") { value(session) } }
    }

    @Test
    fun `BR-013 BR-002 6시간이 지난 세션에 세트를 추가하면 완료된 세션이라 바꿀 수 없다`() {
        val session = startedSessionId()
        val bench = sessionExerciseId(session, operator.addExercise("벤치프레스", "가슴"))
        addSet(session, bench, """{"weight": 60, "repetitions": 10}""")
        clock.advance(Duration.ofHours(7))

        addSet(session, bench, """{"weight": 60, "repetitions": 10}""").andExpect {
            status { isConflict() }
            jsonPath("$.code") { value("WORKOUT_SESSION_NOT_EDITABLE") }
        }
        detail(session).andExpect {
            jsonPath("$.status") { value("COMPLETED") }
            jsonPath("$.summary.totalSets") { value(1) }
        }
    }

    private fun history(query: String = "") =
        mockMvc.get("/api/v1/workout-sessions?$query") { header("Authorization", "Bearer ${me.token}") }

    private fun deleteSession(sessionId: String) =
        mockMvc.delete("/api/v1/workout-sessions/$sessionId") { header("Authorization", "Bearer ${me.token}") }

    private data class CompletedSession(val id: String, val sessionExerciseId: String, val setId: String)

    private fun completedSession(exerciseId: UUID = operator.addExercise("벤치프레스", "가슴")): CompletedSession {
        val session = startedSessionId()
        val bench = sessionExerciseId(session, exerciseId)
        val set = idOf(addSet(session, bench, """{"weight": 60, "repetitions": 10}"""))
        complete(session).andExpect { status { isOk() } }
        return CompletedSession(session, bench, set)
    }

    private fun complete(sessionId: String, body: String? = null) =
        mockMvc.post("/api/v1/workout-sessions/$sessionId/complete") {
            header("Authorization", "Bearer ${me.token}")
            if (body != null) {
                contentType = MediaType.APPLICATION_JSON
                content = body
            }
        }

    private fun idOf(result: ResultActionsDsl, field: String = "id"): String =
        objectMapper.readTree(result.andReturn().response.contentAsString).get(field).asString()

    private fun sessionExerciseId(sessionId: String, exerciseId: UUID, user: SignedInUser = me): String =
        idOf(addExercise(sessionId, exerciseId, user).andExpect { status { isCreated() } }, "sessionExerciseId")

    private fun setsPath(sessionId: String, sessionExerciseId: String) =
        "/api/v1/workout-sessions/$sessionId/exercises/$sessionExerciseId/sets"

    private fun addSet(sessionId: String, sessionExerciseId: String, body: String, user: SignedInUser = me) =
        mockMvc.post(setsPath(sessionId, sessionExerciseId)) {
            header("Authorization", "Bearer ${user.token}")
            contentType = MediaType.APPLICATION_JSON
            content = body
        }

    private fun updateSet(sessionId: String, sessionExerciseId: String, setId: String, body: String) =
        mockMvc.put("${setsPath(sessionId, sessionExerciseId)}/$setId") {
            header("Authorization", "Bearer ${me.token}")
            contentType = MediaType.APPLICATION_JSON
            content = body
        }

    private fun deleteSet(sessionId: String, sessionExerciseId: String, setId: String) =
        mockMvc.delete("${setsPath(sessionId, sessionExerciseId)}/$setId") { header("Authorization", "Bearer ${me.token}") }

    private fun removeExercise(sessionId: String, sessionExerciseId: String) =
        mockMvc.delete("/api/v1/workout-sessions/$sessionId/exercises/$sessionExerciseId") {
            header("Authorization", "Bearer ${me.token}")
        }

    private fun startedSessionId(user: SignedInUser = me): String =
        start(user = user).andExpect { status { isCreated() } }
            .andReturn().let { objectMapper.readTree(it.response.contentAsString).get("id").asString() }

    private fun detail(sessionId: String, user: SignedInUser = me): ResultActionsDsl =
        mockMvc.get("/api/v1/workout-sessions/$sessionId") { header("Authorization", "Bearer ${user.token}") }

    private fun addExercise(sessionId: String, exerciseId: UUID, user: SignedInUser = me): ResultActionsDsl =
        mockMvc.post("/api/v1/workout-sessions/$sessionId/exercises") {
            header("Authorization", "Bearer ${user.token}")
            contentType = MediaType.APPLICATION_JSON
            content = """{"exerciseId": "$exerciseId"}"""
        }

    private fun start(timeZone: String? = "Asia/Seoul", user: SignedInUser = me): ResultActionsDsl =
        mockMvc.post("/api/v1/workout-sessions") {
            header("Authorization", "Bearer ${user.token}")
            if (timeZone != null) header("X-Time-Zone", timeZone)
        }

    private fun inProgress(user: SignedInUser = me): ResultActionsDsl =
        mockMvc.get("/api/v1/workout-sessions/in-progress") { header("Authorization", "Bearer ${user.token}") }
}
