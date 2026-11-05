package com.qinghe.ledger.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateSourceTest {
    private val info = UpdateInfo(
        versionName = "1.0.19",
        changelog = "",
        downloadUrl =
            "https://github.com/owner/repo/releases/download/v1.0.19/qinghe-1.0.19.apk",
        assetName = "qinghe-1.0.19.apk",
        sizeBytes = 2_785_039L,
        apiDownloadUrl = "https://api.github.com/repos/owner/repo/releases/assets/1"
    )

    @Test
    fun `mirror prefix is prepended to the full url`() {
        assertEquals(
            "https://ghfast.top/${info.downloadUrl}",
            UpdateManager.applyMirror("https://ghfast.top", info.downloadUrl)
        )
    }

    @Test
    fun `mirror placeholder is replaced`() {
        assertEquals(
            "https://proxy.test/?u=https://github.com/x",
            UpdateManager.applyMirror("https://proxy.test/?u={url}", "https://github.com/x")
        )
    }

    @Test
    fun `blank mirror leaves the url unchanged`() {
        assertEquals(
            "https://github.com/x",
            UpdateManager.applyMirror("", "https://github.com/x")
        )
    }

    @Test
    fun `custom mirror is the first download source`() {
        val sources = UpdateManager.buildDownloadSources(info, "https://proxy.test/")
        assertEquals("https://proxy.test/${info.downloadUrl}", sources.first().url)
    }

    @Test
    fun `download sources include built-in mirrors and official routes`() {
        val urls = UpdateManager.buildDownloadSources(info, "").map { it.url }
        assertTrue(urls.any { it.startsWith("https://ghfast.top/") })
        assertTrue(urls.any { it.startsWith("https://gh-proxy.com/") })
        assertTrue(urls.any { it.startsWith("https://ghproxy.net/") })
        assertTrue(urls.contains(info.apiDownloadUrl))
        assertTrue(urls.contains(info.downloadUrl))
        assertEquals(urls.size, urls.distinct().size)
    }

    @Test
    fun `check urls include the built-in api mirror`() {
        val api = "https://api.github.com/repos/owner/repo/releases/latest"
        val urls = UpdateManager.buildCheckUrls(api, "")
        assertEquals(api, urls.first())
        assertTrue(urls.contains("https://gh-proxy.com/$api"))
    }
}
