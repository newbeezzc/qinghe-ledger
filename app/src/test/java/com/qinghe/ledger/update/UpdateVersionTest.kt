package com.qinghe.ledger.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateVersionTest {
    @Test
    fun `newer patch version is detected`() {
        assertTrue(UpdateManager.compareVersions("1.0.8", "1.0.7") > 0)
    }

    @Test
    fun `v prefix is ignored`() {
        assertEquals(0, UpdateManager.compareVersions("v1.0.8", "1.0.8"))
    }

    @Test
    fun `multi digit segments compare numerically`() {
        assertTrue(UpdateManager.compareVersions("1.0.10", "1.0.9") > 0)
        assertTrue(UpdateManager.compareVersions("1.10.0", "1.9.9") > 0)
    }

    @Test
    fun `older version is not an update`() {
        assertTrue(UpdateManager.compareVersions("1.0.7", "1.0.8") < 0)
    }
}
