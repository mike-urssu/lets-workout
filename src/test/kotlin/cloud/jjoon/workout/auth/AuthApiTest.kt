package cloud.jjoon.workout.auth

import cloud.jjoon.workout.TestcontainersConfiguration
import cloud.jjoon.workout.support.MutableClock
import cloud.jjoon.workout.support.Operator
import cloud.jjoon.workout.support.TestClockConfiguration
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class, TestClockConfiguration::class)
class AuthApiTest {

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var objectMapper: ObjectMapper
    @Autowired lateinit var operator: Operator
    @Autowired lateinit var clock: MutableClock

    @BeforeEach
    fun setUp() {
        operator.deleteAllAccounts()
        clock.reset()
    }

    @Test
    fun `REQ-AUTH-001 발급받은 아이디와 PIN으로 로그인하면 받은 토큰으로 로그인이 유지된다`() {
        operator.issueAccount("demo.user", "123456")

        val token = loginToken("demo.user", "123456")

        checkSession(token).andExpect { status { isNoContent() } }
    }

    @Test
    fun `ERR-007 로그인 토큰 없이 로그인이 필요한 기능을 요청하면 인증 오류다`() {
        checkSession(null).andExpect {
            status { isUnauthorized() }
            jsonPath("$.code") { value("UNAUTHORIZED") }
        }
    }

    @Test
    fun `ERR-001 PIN이 틀리면 로그인되지 않는다`() {
        operator.issueAccount("demo.user", "123456")

        login("demo.user", "000000").andExpect {
            status { isUnauthorized() }
            jsonPath("$.code") { value("AUTH_INVALID_CREDENTIALS") }
            jsonPath("$.message") { value("PIN이 올바르지 않습니다. 다시 입력해 주세요.") }
        }
    }

    @Test
    fun `BR-010 없는 아이디도 PIN이 틀렸을 때와 같은 응답이다`() {
        login("nobody.here", "123456").andExpect {
            status { isUnauthorized() }
            jsonPath("$.code") { value("AUTH_INVALID_CREDENTIALS") }
            jsonPath("$.message") { value("PIN이 올바르지 않습니다. 다시 입력해 주세요.") }
        }
    }

    @Test
    fun `ERR-002 PIN이 숫자 6자리가 아니면 입력값 오류다`() {
        operator.issueAccount("demo.user", "123456")

        login("demo.user", "12345a").andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("VALIDATION_FAILED") }
            jsonPath("$.errors[0].field") { value("pin") }
        }
    }

    @Test
    fun `ERR-003 아이디가 비어 있으면 입력값 오류다`() {
        mockMvc.post("/api/v1/auth/login") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"pin":"123456"}"""
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("VALIDATION_FAILED") }
            jsonPath("$.errors[0].field") { value("loginId") }
        }
    }

    @Test
    fun `BR-007 PIN을 5회 연속 틀리면 5분 동안 올바른 PIN으로도 로그인할 수 없다`() {
        operator.issueAccount("demo.user", "123456")
        repeat(5) { login("demo.user", "000000") }

        login("demo.user", "123456").andExpect {
            status { isUnauthorized() }
            jsonPath("$.code") { value("AUTH_ACCOUNT_LOCKED") }
            jsonPath("$.details.retryAt") { value("2026-10-05T09:05:00Z") }
        }
    }

    @Test
    fun `BR-008 잠금이 풀리면 실패 횟수를 0부터 다시 센다`() {
        operator.issueAccount("demo.user", "123456")
        repeat(5) { login("demo.user", "000000") }
        clock.advance(Duration.ofMinutes(5))

        repeat(4) { login("demo.user", "000000") }

        login("demo.user", "123456").andExpect { status { isOk() } }
    }

    @Test
    fun `BR-008 로그인에 성공하면 연속 실패 횟수가 0이 된다`() {
        operator.issueAccount("demo.user", "123456")
        repeat(4) { login("demo.user", "000000") }
        login("demo.user", "123456").andExpect { status { isOk() } }

        repeat(4) { login("demo.user", "000000") }

        login("demo.user", "123456").andExpect { status { isOk() } }
    }

    @Test
    fun `BR-002 아이디는 대소문자까지 발급한 값과 정확히 일치해야 한다`() {
        operator.issueAccount("demo.user", "123456")

        login("Demo.user", "123456").andExpect {
            status { isUnauthorized() }
            jsonPath("$.code") { value("AUTH_INVALID_CREDENTIALS") }
        }
    }

    @Test
    fun `ERR-006 다른 기기에서 로그인하면 기존 기기는 다른 기기 로그인으로 로그아웃된다`() {
        operator.issueAccount("demo.user", "123456")
        val deviceA = loginToken("demo.user", "123456")

        val deviceB = loginToken("demo.user", "123456")

        checkSession(deviceA).andExpect {
            status { isUnauthorized() }
            jsonPath("$.code") { value("AUTH_SESSION_REPLACED") }
        }
        checkSession(deviceB).andExpect { status { isNoContent() } }
    }

    @Test
    fun `ERR-005 마지막으로 사용한 뒤 30일이 지나면 로그인이 만료된다`() {
        operator.issueAccount("demo.user", "123456")
        val token = loginToken("demo.user", "123456")

        clock.advance(Duration.ofDays(30))

        checkSession(token).andExpect {
            status { isUnauthorized() }
            jsonPath("$.code") { value("AUTH_SESSION_EXPIRED") }
        }
    }

    @Test
    fun `BR-006 사용할 때마다 로그인 유지 기간이 30일로 다시 시작된다`() {
        operator.issueAccount("demo.user", "123456")
        val token = loginToken("demo.user", "123456")
        clock.advance(Duration.ofDays(29))
        checkSession(token).andExpect { status { isNoContent() } }

        clock.advance(Duration.ofDays(29))

        checkSession(token).andExpect { status { isNoContent() } }
    }

    @Test
    fun `ERR-005 만료된 뒤 다른 기기에서 로그인해도 기존 기기에는 만료로 안내한다`() {
        operator.issueAccount("demo.user", "123456")
        val deviceA = loginToken("demo.user", "123456")
        clock.advance(Duration.ofDays(31))

        loginToken("demo.user", "123456")

        checkSession(deviceA).andExpect {
            status { isUnauthorized() }
            jsonPath("$.code") { value("AUTH_SESSION_EXPIRED") }
        }
    }

    @Test
    fun `REQ-AUTH-002 로그아웃하면 그 토큰으로는 더 이상 로그인이 유지되지 않는다`() {
        operator.issueAccount("demo.user", "123456")
        val token = loginToken("demo.user", "123456")

        logout(token).andExpect { status { isNoContent() } }

        checkSession(token).andExpect {
            status { isUnauthorized() }
            jsonPath("$.code") { value("UNAUTHORIZED") }
        }
    }

    @Test
    fun `REQ-AUTH-002 이미 끝난 로그인으로 로그아웃해도 성공한다`() {
        operator.issueAccount("demo.user", "123456")
        val deviceA = loginToken("demo.user", "123456")
        loginToken("demo.user", "123456")

        logout(deviceA).andExpect { status { isNoContent() } }
    }

    @Test
    fun `BR-011 운영자가 PIN을 재발급하면 기존 로그인은 바로 끝난다`() {
        operator.issueAccount("demo.user", "123456")
        val token = loginToken("demo.user", "123456")

        operator.reissuePin("demo.user", "654321")

        checkSession(token).andExpect {
            status { isUnauthorized() }
            jsonPath("$.code") { value("AUTH_SESSION_REVOKED") }
        }
    }

    @Test
    fun `BR-011 PIN을 바꾸지 않는 운영자 SQL은 로그인을 끝내지 않는다`() {
        operator.issueAccount("demo.user", "123456")
        val token = loginToken("demo.user", "123456")

        operator.unlock("demo.user")

        checkSession(token).andExpect { status { isNoContent() } }
    }

    @Test
    fun `운영자가 잠금을 풀면 5분을 기다리지 않고 로그인할 수 있다`() {
        operator.issueAccount("demo.user", "123456")
        repeat(5) { login("demo.user", "000000") }

        operator.unlock("demo.user")

        login("demo.user", "123456").andExpect { status { isOk() } }
    }

    @Test
    fun `ERR-008 운영자가 계정을 삭제하면 그 기기는 다시 로그인해야 한다`() {
        operator.issueAccount("demo.user", "123456")
        val token = loginToken("demo.user", "123456")

        operator.deleteAccount("demo.user")

        checkSession(token).andExpect {
            status { isUnauthorized() }
            jsonPath("$.code") { value("UNAUTHORIZED") }
        }
    }

    @Test
    fun `NFR-AVAIL-001 틀린 PIN이 동시에 들어와도 실패 횟수가 빠짐없이 반영되어 잠긴다`() {
        operator.issueAccount("demo.user", "123456")
        val pool = Executors.newFixedThreadPool(10)
        val start = CountDownLatch(1)
        val attempts = (1..10).map {
            pool.submit {
                start.await()
                login("demo.user", "000000")
            }
        }
        start.countDown()
        attempts.forEach { it.get(30, TimeUnit.SECONDS) }
        pool.shutdown()

        login("demo.user", "123456").andExpect {
            jsonPath("$.code") { value("AUTH_ACCOUNT_LOCKED") }
        }
    }

    @Test
    fun `DEC-AUTH-002 끝난 지 30일이 지난 로그인 기록은 다음 로그인 때 정리된다`() {
        operator.issueAccount("demo.user", "123456")
        val deviceA = loginToken("demo.user", "123456")
        loginToken("demo.user", "123456")
        clock.advance(Duration.ofDays(31))

        loginToken("demo.user", "123456")

        checkSession(deviceA).andExpect { jsonPath("$.code") { value("UNAUTHORIZED") } }
    }

    private fun logout(token: String?): ResultActionsDsl =
        mockMvc.post("/api/v1/auth/logout") {
            if (token != null) header("Authorization", "Bearer $token")
        }

    private fun login(loginId: String, pin: String): ResultActionsDsl =
        mockMvc.post("/api/v1/auth/login") {
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(mapOf("loginId" to loginId, "pin" to pin))
        }

    private fun loginToken(loginId: String, pin: String): String {
        val body = login(loginId, pin)
            .andExpect { status { isOk() } }
            .andReturn().response.contentAsString
        return objectMapper.readTree(body).get("token").asString()
    }

    private fun checkSession(token: String?): ResultActionsDsl =
        mockMvc.get("/api/v1/auth/session") {
            if (token != null) header("Authorization", "Bearer $token")
        }
}
