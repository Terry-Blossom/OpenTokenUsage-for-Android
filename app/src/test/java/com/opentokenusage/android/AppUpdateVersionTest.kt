package com.opentokenusage.android

import com.opentokenusage.android.data.update.AppUpdater
import com.opentokenusage.android.data.update.GitHubAsset
import com.opentokenusage.android.data.update.fetchLatestRelease
import com.opentokenusage.android.data.update.isNewerVersion
import com.opentokenusage.android.data.update.selectReleaseApkAsset
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

class AppUpdateVersionTest {
    @Test
    fun latestReleaseUrlTargetsOfficialRepository() {
        assertEquals(
            "https://api.github.com/repos/Terry-Blossom/OpenTokenUsage-for-Android/releases/latest",
            AppUpdater.LATEST_RELEASE_URL,
        )
    }

    @Test
    fun detectsNewerVersionTags() {
        assertTrue(isNewerVersion("0.1.9", "0.1.8"))
        assertTrue(isNewerVersion("v0.2.0", "0.1.9"))
        assertTrue(isNewerVersion("0.1.10", "0.1.9"))
        assertTrue(isNewerVersion("1.0", "0.9.9"))
    }

    @Test
    fun rejectsSameOrOlderVersions() {
        assertFalse(isNewerVersion("0.1.9", "0.1.9"))
        assertFalse(isNewerVersion("v0.1.8", "0.1.9"))
        assertFalse(isNewerVersion("0.1.9-beta", "0.1.9"))
    }

    @Test
    fun selectsSignedOpenTokenUsageApkOverOtherAndUnsignedAssets() {
        val assets = listOf(
            GitHubAsset("SHA256SUMS.txt", "https://example.com/SHA256SUMS.txt", 128L),
            GitHubAsset("OpenTokenUsage-0.3.0-release-unsigned.apk", "https://example.com/unsigned.apk", 1000L),
            GitHubAsset("app-release.apk", "https://example.com/app-release.apk", 2000L),
            GitHubAsset("OpenTokenUsage-0.3.0.apk", "https://example.com/OpenTokenUsage-0.3.0.apk", 2048L),
        )
        val selected = selectReleaseApkAsset(assets)
        assertNotNull(selected)
        assertEquals("OpenTokenUsage-0.3.0.apk", selected?.name)
    }

    @Test
    fun fallsBackToOtherSignedApkWhenPrefixedApkIsAbsent() {
        val assets = listOf(
            GitHubAsset("OpenTokenUsage-0.3.0-UNSIGNED.apk", "https://example.com/unsigned.apk", 1000L),
            GitHubAsset("custom-build.apk", "https://example.com/custom-build.apk", 1500L),
        )
        val selected = selectReleaseApkAsset(assets)
        assertNotNull(selected)
        assertEquals("custom-build.apk", selected?.name)
    }

    @Test
    fun returnsNullWhenOnlyUnsignedOrBlankUrlApksExist() {
        val onlyUnsigned = listOf(
            GitHubAsset("OpenTokenUsage-0.3.0-release-unsigned.apk", "https://example.com/unsigned.apk", 1000L),
            GitHubAsset("OpenTokenUsage-0.3.0.apk", "", 2048L),
            GitHubAsset("SHA256SUMS.txt", "https://example.com/SHA256SUMS.txt", 128L),
        )
        assertNull(selectReleaseApkAsset(onlyUnsigned))
    }

    @Test
    fun fetchLatestReleaseSkipsUnsignedApkAndPicksSignedApk() {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    """
                    {
                      "tag_name": "v0.3.0",
                      "body": "Release notes",
                      "assets": [
                        {
                          "name": "OpenTokenUsage-0.3.0-release-unsigned.apk",
                          "browser_download_url": "https://example.com/unsigned.apk",
                          "size": 1000
                        },
                        {
                          "name": "app-debug.apk",
                          "browser_download_url": "https://example.com/app-debug.apk",
                          "size": 1200
                        },
                        {
                          "name": "OpenTokenUsage-0.3.0.apk",
                          "browser_download_url": "https://example.com/OpenTokenUsage-0.3.0.apk",
                          "size": 2500
                        }
                      ]
                    }
                    """.trimIndent(),
                ),
            )
            val update = fetchLatestRelease(
                httpClient = OkHttpClient(),
                releaseUrl = server.url("/releases/latest").toString(),
                currentVersion = "0.2.1",
            )
            assertNotNull(update)
            assertEquals("0.3.0", update?.versionName)
            assertEquals("https://example.com/OpenTokenUsage-0.3.0.apk", update?.apkUrl)
            assertEquals(2500L, update?.apkSizeBytes)

            // When only unsigned APK is attached, check fails cleanly with IOException.
            server.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    """
                    {
                      "tag_name": "v0.3.0",
                      "body": "Unsigned only",
                      "assets": [
                        {
                          "name": "OpenTokenUsage-0.3.0-release-unsigned.apk",
                          "browser_download_url": "https://example.com/unsigned.apk",
                          "size": 1000
                        }
                      ]
                    }
                    """.trimIndent(),
                ),
            )
            try {
                fetchLatestRelease(
                    httpClient = OkHttpClient(),
                    releaseUrl = server.url("/releases/latest").toString(),
                    currentVersion = "0.2.1",
                )
                fail("Expected IOException when only unsigned APK exists")
            } catch (_: IOException) {
                // expected
            }
        } finally {
            server.shutdown()
        }
    }
}
