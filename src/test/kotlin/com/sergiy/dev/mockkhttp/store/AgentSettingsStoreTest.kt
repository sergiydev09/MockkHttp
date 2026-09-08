package com.sergiy.dev.mockkhttp.store

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `normalize` is the one reading of the persisted agent-control value. It used to map anything it
 * did not recognise to FULL — a hand-edited file or a value from a newer build silently granted
 * writes, while ControlAuth's own setter maps the same unknowns to OFF.
 */
class AgentSettingsStoreTest {

    @Test
    fun `the three known values and their spellings round-trip`() {
        assertEquals(AgentSettingsStore.FULL, AgentSettingsStore.normalize("full"))
        assertEquals(AgentSettingsStore.FULL, AgentSettingsStore.normalize(" FULL "))
        assertEquals(AgentSettingsStore.READ_ONLY, AgentSettingsStore.normalize("read-only"))
        assertEquals(AgentSettingsStore.READ_ONLY, AgentSettingsStore.normalize("READONLY"))
        assertEquals(AgentSettingsStore.OFF, AgentSettingsStore.normalize("disabled"))
        assertEquals(AgentSettingsStore.OFF, AgentSettingsStore.normalize("false"))
    }

    @Test
    fun `an absent value is a fresh install and defaults to full`() {
        assertEquals(AgentSettingsStore.FULL, AgentSettingsStore.normalize(null))
        assertEquals(AgentSettingsStore.FULL, AgentSettingsStore.normalize(""))
    }

    @Test
    fun `an unknown value fails closed, never open`() {
        for (bogus in listOf("full_with_pause", "yes", "1", "FULL2", "admin")) {
            assertEquals("'$bogus' must not be read as write access", AgentSettingsStore.OFF, AgentSettingsStore.normalize(bogus))
        }
    }
}
