package cloud.jjoon.workout.common

import cloud.jjoon.workout.TestcontainersConfiguration
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.not
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import testsupport.FailingController

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class, FailingController::class)
class ErrorHandlingTest {

    @Autowired lateinit var mockMvc: MockMvc

    @Test
    fun `형식이 깨진 JSON 요청은 입력값 오류다`() {
        mockMvc.post("/api/v1/auth/login") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"loginId": "demo.user", "pin": """
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("VALIDATION_FAILED") }
            jsonPath("$.message") { value("입력값이 올바르지 않습니다.") }
        }
    }

    @Test
    fun `형식을 지원하지 않는 요청은 서버 오류가 아니라 입력값 오류다`() {
        mockMvc.post("/api/v1/auth/login") {
            content = "loginId=demo.user&pin=123456"
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("VALIDATION_FAILED") }
        }
    }

    @Test
    fun `정의되지 않은 URL은 찾을 수 없음 오류다`() {
        mockMvc.get("/no-such-path").andExpect {
            status { isNotFound() }
            jsonPath("$.code") { value("NOT_FOUND") }
        }
    }

    @Test
    fun `예상하지 못한 오류는 내부 정보 없이 서버 오류로 응답한다`() {
        mockMvc.get("/test/unexpected-error").andExpect {
            status { isInternalServerError() }
            jsonPath("$.code") { value("INTERNAL_ERROR") }
            jsonPath("$.message") { value("일시적인 오류가 발생했습니다. 잠시 후 다시 시도해 주세요.") }
            content { string(not(containsString("secret"))) }
        }
    }
}
