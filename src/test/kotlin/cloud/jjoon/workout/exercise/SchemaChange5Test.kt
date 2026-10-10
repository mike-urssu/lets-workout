package cloud.jjoon.workout.exercise

import cloud.jjoon.workout.TestcontainersConfiguration
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import java.util.UUID
import javax.sql.DataSource

/** Schema change 5 keeps existing records and moves them onto each user's own exercises (DEC-WORKOUT-028). */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
class SchemaChange5Test {

    @Autowired lateinit var dataSource: DataSource

    private val jdbc by lazy { JdbcTemplate(dataSource) }

    @AfterEach
    fun dropSchema() {
        jdbc.execute("DROP SCHEMA IF EXISTS $SCHEMA CASCADE")
    }

    @Test
    fun `기존 기록은 그 사용자의 같은 종목으로 이어진다`() {
        flyway("4").migrate()
        val a = user("first.user")
        val b = user("second.user")
        val aSet = sessionWithSet(a, "벤치프레스")
        sessionWithSet(b, "벤치프레스")

        flyway(null).migrate()

        assertEquals(24, count("SELECT count(*) FROM $SCHEMA.exercise WHERE user_id = ?", a))
        assertEquals(24, count("SELECT count(*) FROM $SCHEMA.exercise WHERE user_id = ?", b))
        val (owner, name) = jdbc.queryForObject(
            """
            SELECT e.user_id, e.name FROM $SCHEMA.workout_set ws
              JOIN $SCHEMA.workout_session_exercise wse ON wse.id = ws.workout_session_exercise_id
              JOIN $SCHEMA.exercise e ON e.id = wse.exercise_id
             WHERE ws.id = ?
            """.trimIndent(),
            { rs, _ -> rs.getObject(1, UUID::class.java) to rs.getString(2) }, aSet,
        )!!
        assertEquals(a, owner)
        assertEquals("벤치프레스", name)
        assertEquals(24, count("SELECT count(*) FROM $SCHEMA.exercise WHERE user_id = ?", user("third.user")))
    }

    private fun flyway(target: String?): Flyway = Flyway.configure()
        .dataSource(dataSource)
        .schemas(SCHEMA)
        .locations("classpath:db/migration")
        .apply { if (target != null) target(target) }
        .load()

    private fun user(loginId: String): UUID =
        jdbc.queryForObject("INSERT INTO $SCHEMA.users (login_id, pin) VALUES (?, '123456') RETURNING id", UUID::class.java, loginId)!!

    private fun sessionWithSet(userId: UUID, exercise: String): UUID {
        val session = UUID.randomUUID()
        jdbc.update(
            "INSERT INTO $SCHEMA.workout_session (id, user_id, status, performed_date, started_at, ended_at) " +
                "VALUES (?, ?, 'COMPLETED', DATE '2026-10-01', now(), now())",
            session, userId,
        )
        val sessionExercise = UUID.randomUUID()
        jdbc.update(
            "INSERT INTO $SCHEMA.workout_session_exercise (id, workout_session_id, exercise_id) " +
                "SELECT ?, ?, id FROM $SCHEMA.exercise WHERE name = ?",
            sessionExercise, session, exercise,
        )
        val set = UUID.randomUUID()
        jdbc.update(
            "INSERT INTO $SCHEMA.workout_set (id, workout_session_exercise_id, weight, repetitions) VALUES (?, ?, 60, 10)",
            set, sessionExercise,
        )
        return set
    }

    private fun count(sql: String, userId: UUID): Int = jdbc.queryForObject(sql, Int::class.java, userId)!!

    companion object {
        private const val SCHEMA = "schema_change_5"
    }
}
