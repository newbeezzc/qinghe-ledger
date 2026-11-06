package com.qinghe.ledger.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    @Test
    fun `gitee primary keeps gitee first and adds github fallbacks`() {
        val giteeUrl =
            "https://gitee.com/owner/repo/releases/download/v1.0.20/qinghe-1.0.20.apk"
        val githubUrl =
            "https://github.com/owner/repo/releases/download/v1.0.20/qinghe-1.0.20.apk"
        val giteeInfo = info.copy(
            versionName = "1.0.20",
            downloadUrl = giteeUrl,
            apiDownloadUrl = null,
            fallbackDownloadUrls = listOf(githubUrl)
        )
        val urls = UpdateManager.buildDownloadSources(giteeInfo, "").map { it.url }
        assertEquals(giteeUrl, urls.first())
        assertTrue(urls.contains(githubUrl))
        assertTrue(urls.contains("https://ghfast.top/$githubUrl"))
        assertFalse(urls.any { it.startsWith("https://ghfast.top/https://gitee.com/") })
    }

    @Test
    fun `github primary keeps a gitee fallback`() {
        val giteeUrl =
            "https://gitee.com/owner/repo/releases/download/v1.0.19/qinghe-1.0.19.apk"
        val urls = UpdateManager.buildDownloadSources(
            info.copy(fallbackDownloadUrls = listOf(giteeUrl)),
            ""
        ).map { it.url }
        assertTrue(urls.contains(giteeUrl))
    }

    @Test
    fun `gitee apk is picked from release assets`() {
        val apk = UpdateManager.pickGiteeApk(
            assets = listOf(
                UpdateManager.GiteeAsset(
                    "v9.9.9.zip",
                    "https://gitee.com/o/r/archive/refs/tags/v9.9.9.zip",
                    0
                ),
                UpdateManager.GiteeAsset(
                    "qinghe-9.9.9.apk",
                    "https://gitee.com/o/r/releases/download/v9.9.9/qinghe-9.9.9.apk",
                    0
                )
            ),
            attachFiles = emptyList()
        )
        requireNotNull(apk)
        assertEquals("qinghe-9.9.9.apk", apk.name)
    }

    @Test
    fun `gitee attach file supplies the apk size`() {
        val url = "https://gitee.com/o/r/releases/download/v9.9.9/qinghe-9.9.9.apk"
        val apk = UpdateManager.pickGiteeApk(
            assets = listOf(UpdateManager.GiteeAsset("qinghe-9.9.9.apk", url, 0)),
            attachFiles = listOf(
                UpdateManager.GiteeAsset("qinghe-9.9.9.apk", url, 2_801_423)
            )
        )
        requireNotNull(apk)
        assertEquals(2_801_423L, apk.size)
    }

    @Test
    fun `gitee release without an apk yields no asset`() {
        val apk = UpdateManager.pickGiteeApk(
            assets = listOf(
                UpdateManager.GiteeAsset(
                    "v9.9.9.zip",
                    "https://gitee.com/o/r/archive/refs/tags/v9.9.9.zip",
                    0
                )
            ),
            attachFiles = emptyList()
        )
        assertNull(apk)
    }
}
