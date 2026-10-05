package cloud.jjoon.workout.common.security

import cloud.jjoon.workout.auth.service.LoginSessionService
import cloud.jjoon.workout.common.error.ErrorCode
import cloud.jjoon.workout.common.error.ErrorResponseWriter
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpMethod
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter

@Configuration(proxyBeanMethods = false)
class SecurityConfig {

    @Bean
    fun securityFilterChain(
        http: HttpSecurity,
        loginSessionService: LoginSessionService,
        errorResponseWriter: ErrorResponseWriter,
    ): SecurityFilterChain {
        http
            .csrf { it.disable() }
            .cors { it.disable() }
            .httpBasic { it.disable() }
            .formLogin { it.disable() }
            .logout { it.disable() }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .authorizeHttpRequests {
                it.requestMatchers(HttpMethod.POST, LoginTokenFilter.LOGIN_PATH, LoginTokenFilter.LOGOUT_PATH).permitAll()
                    .requestMatchers("/api/**").authenticated()
                    .anyRequest().permitAll()
            }
            .exceptionHandling {
                it.authenticationEntryPoint { _, response, _ ->
                    errorResponseWriter.write(response, ErrorCode.UNAUTHORIZED)
                }
            }
            .addFilterBefore(
                LoginTokenFilter(loginSessionService, errorResponseWriter),
                AnonymousAuthenticationFilter::class.java,
            )
        return http.build()
    }
}
