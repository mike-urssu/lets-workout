package cloud.jjoon.workout.common.security

import cloud.jjoon.workout.auth.service.AuthenticationResult
import cloud.jjoon.workout.auth.service.LoginSessionService
import cloud.jjoon.workout.common.error.ErrorResponseWriter
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpHeaders
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.filter.OncePerRequestFilter

/** Not a @Component: registered only inside the security filter chain so it does not run twice. */
class LoginTokenFilter(
    private val loginSessionService: LoginSessionService,
    private val errorResponseWriter: ErrorResponseWriter,
) : OncePerRequestFilter() {

    override fun shouldNotFilter(request: HttpServletRequest): Boolean =
        request.method == "POST" && request.requestURI in PUBLIC_POST_PATHS

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        val token = BearerToken.from(request.getHeader(HttpHeaders.AUTHORIZATION))
        if (token == null) {
            chain.doFilter(request, response)
            return
        }
        when (val result = loginSessionService.authenticate(token)) {
            is AuthenticationResult.Rejected -> errorResponseWriter.write(response, result.code)
            is AuthenticationResult.Authenticated -> {
                SecurityContextHolder.getContext().authentication =
                    UsernamePasswordAuthenticationToken(result.userId, null, emptyList())
                chain.doFilter(request, response)
            }
        }
    }

    companion object {
        const val LOGIN_PATH = "/api/v1/auth/login"
        const val LOGOUT_PATH = "/api/v1/auth/logout"
        private val PUBLIC_POST_PATHS = setOf(LOGIN_PATH, LOGOUT_PATH)
    }
}
