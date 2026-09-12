package com.onetake.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * R25: both frozen demo loaners must show the same flag-vector checksum and config
 * version before the demo. That check is only worth ten seconds of someone's attention
 * if the checksum actually moves when the flags do — so this test exists.
 *
 * It is also the smallest possible proof that the test harness runs. Deleting it is fine
 * once there are real tests here.
 */
class RuntimeConfigTest {

    @Test
    fun `checksum is stable for the same flags and version`() {
        val a = RuntimeConfig(configVersion = "2026-09-12.1")
        val b = RuntimeConfig(configVersion = "2026-09-12.1")
        assertEquals(a.flagVectorChecksum(), b.flagVectorChecksum())
    }

    @Test
    fun `checksum moves when a flag moves`() {
        val off = RuntimeConfig(configVersion = "2026-09-12.1")
        val on = off.copy(flags = off.flags.copy(mustSayLines = true))
        assertNotEquals(off.flagVectorChecksum(), on.flagVectorChecksum())
    }

    @Test
    fun `checksum moves when the config version moves`() {
        val v1 = RuntimeConfig(configVersion = "2026-09-12.1")
        val v2 = RuntimeConfig(configVersion = "2026-09-12.2")
        assertNotEquals(v1.flagVectorChecksum(), v2.flagVectorChecksum())
    }

    @Test
    fun `every Tier 1 plus feature is off by default`() {
        val flags = FeatureFlags()
        val on = flags.asOrderedList().filter { it.second }
        assertEquals("features must default to off and turn on only when their check passes", emptyList<Pair<String, Boolean>>(), on)
    }
}
