package cloud.jjoon.workout.auth.controller

import cloud.jjoon.workout.auth.service.LoginResult
import cloud.jjoon.workout.auth.service.LoginService
import cloud.jjoon.workout.common.error.BusinessException
import cloud.jjoon.workout.common.security.BearerToken
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import org.springframework.http.HttpHeaders
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/auth")
class AuthController(private val loginService: LoginService) {

    @PostMapping("/login")
    fun login(@Valid @RequestBody request: LoginRequest): LoginResponse =
        when (val result = loginService.login(request.loginId!!, request.pin!!)) {
            is LoginResult.Succeeded -> LoginResponse(result.token)
            is LoginResult.Failed -> throw BusinessException(result.code, result.details)
        }

    @PostMapping("/logout")
    fun logout(@RequestHeader(HttpHeaders.AUTHORIZATION, required = false) authorization: String?): ResponseEntity<Unit> {
        loginService.logout(BearerToken.from(authorization))
        return ResponseEntity.noContent().build()
    }

    @GetMapping("/session")
    fun session(): ResponseEntity<Unit> = ResponseEntity.noContent().build()
}

data class LoginRequest(
    @field:NotBlank @field:Size(max = 100)
    val loginId: String?,

    @field:NotNull @field:Pattern(regexp = "^[0-9]{6}$", message = "숫자 6자리여야 합니다.")
    val pin: String?,
)

data class LoginResponse(val token: String)
