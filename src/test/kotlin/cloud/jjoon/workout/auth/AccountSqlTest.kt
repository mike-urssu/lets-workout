package cloud.jjoon.workout.auth

import cloud.jjoon.workout.TestcontainersConfiguration
import cloud.jjoon.workout.support.Operator
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.dao.DataIntegrityViolationException

/** The operator's interface is SQL, so the database itself must reject accounts that break the rules. */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
class AccountSqlTest {

    @Autowired lateinit var operator: Operator

    @BeforeEach
    fun setUp() {
        operator.deleteAllAccounts()
    }

    @ParameterizedTest
    @ValueSource(strings = ["joonhee.song", "joonhee.song2", "Joonhee.Song12"])
    fun `BR-002 이름-점-성 형식의 아이디는 발급할 수 있다`(loginId: String) {
        assertDoesNotThrow { operator.issueAccount(loginId, "123456") }
    }

    @ParameterizedTest
    @ValueSource(strings = ["joonhee", "joonhee.", ".song", "joonhee.song1", "joonhee.song02", "joon hee.song", "준희.송"])
    fun `BR-002 형식에 맞지 않는 아이디는 발급할 수 없다`(loginId: String) {
        assertThrows<DataIntegrityViolationException> { operator.issueAccount(loginId, "123456") }
    }

    @ParameterizedTest
    @ValueSource(strings = ["12345", "1234567", "12345a", "abcdef", "12 456"])
    fun `BR-003 숫자 6자리가 아닌 PIN은 발급할 수 없다`(pin: String) {
        assertThrows<DataIntegrityViolationException> { operator.issueAccount("joonhee.song", pin) }
    }

    @Test
    fun `BR-003 PIN을 숫자 6자리가 아닌 값으로 재발급할 수 없다`() {
        operator.issueAccount("joonhee.song", "123456")

        assertThrows<DataIntegrityViolationException> { operator.reissuePin("joonhee.song", "12345") }
    }

    @Test
    fun `BR-002 같은 아이디는 두 번 발급할 수 없다`() {
        operator.issueAccount("joonhee.song", "123456")

        assertThrows<DataIntegrityViolationException> { operator.issueAccount("joonhee.song", "654321") }
    }
}
