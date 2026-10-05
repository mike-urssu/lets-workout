package cloud.jjoon.workout.support

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

class MutableClock(private var now: Instant = Instant.parse("2026-10-05T09:00:00Z")) : Clock() {
    override fun instant(): Instant = now
    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId): Clock = this

    fun advance(duration: Duration) {
        now = now.plus(duration)
    }

    fun reset() {
        now = Instant.parse("2026-10-05T09:00:00Z")
    }
}
