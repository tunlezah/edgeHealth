package au.mark.kinetiq

import au.mark.kinetiq.health.HealthConnectManager
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.util.TimeZone

class HealthConnectIdTest {

    private lateinit var defaultZone: TimeZone

    @Before
    fun rememberZone() { defaultZone = TimeZone.getDefault() }

    @After
    fun restoreZone() { TimeZone.setDefault(defaultZone) }

    @Test
    fun `client record ids are deterministic per session and record`() {
        assertThat(HealthConnectManager.clientRecordIdFor(123, "block", 0)).isEqualTo("kinetiq-123-block-0")
        assertThat(HealthConnectManager.clientRecordIdFor(123, "kcal")).isEqualTo("kinetiq-123-kcal")
        assertThat(HealthConnectManager.clientRecordIdFor(123, "block", 0))
            .isEqualTo(HealthConnectManager.clientRecordIdFor(123, "block", 0))
        assertThat(HealthConnectManager.clientRecordIdFor(123, "block", 1))
            .isNotEqualTo(HealthConnectManager.clientRecordIdFor(123, "block", 0))
    }

    @Test
    fun `zone offset is computed per timestamp across a dst transition`() {
        TimeZone.setDefault(TimeZone.getTimeZone("Australia/Sydney"))
        // AEST (+10) before the October DST switch, AEDT (+11) after (2026-10-04 02:00 local).
        val before = Instant.parse("2026-10-03T00:00:00Z").toEpochMilli()
        val after = Instant.parse("2026-10-05T00:00:00Z").toEpochMilli()
        val offsetBefore = HealthConnectManager.offsetAt(before)
        val offsetAfter = HealthConnectManager.offsetAt(after)
        assertThat(offsetBefore).isNotEqualTo(offsetAfter)
        assertThat(offsetBefore.totalSeconds).isEqualTo(10 * 3600)
        assertThat(offsetAfter.totalSeconds).isEqualTo(11 * 3600)
        // Sanity: matches java.time's own resolution.
        assertThat(offsetBefore)
            .isEqualTo(ZoneId.of("Australia/Sydney").rules.getOffset(Instant.ofEpochMilli(before)))
    }
}
