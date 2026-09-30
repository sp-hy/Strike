package com.strike.update

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ReleaseTest {
    @Test
    fun versionsCompareNumericallyAndAcceptShortTags() {
        assertTrue(compareVersions("v0.10", "0.9") > 0)
        assertTrue(compareVersions("v1.0", "0.99.9") > 0)
        assertTrue(compareVersions("0.1", "0.2") < 0)
        assertEquals(0, compareVersions("v0.1", "0.1.0"))
        assertEquals(0, compareVersions("1", "1.0.0"))
        assertTrue(compareVersions("v2026.09.30-2105", "0.5") > 0)
        assertTrue(compareVersions("v2026.09.30-2105", "2026.09.30-0959") > 0)
        assertTrue(compareVersions("v2026.10.01-0000", "2026.09.30-2359") > 0)
        assertEquals(0, compareVersions("v2026.09.30-2105", "2026.9.30-2105"))
        for (version in listOf("", "v", "-1", "1.2.3.4", "1.0-beta", "1.2/evil", "2147483648",
                "2026.09.30-", "2026.09.30-1-2")) {
            assertNull(version, versionParts(version))
        }
    }

    @Test
    fun releaseKeepsTheExactTagAndSurvivesCaching() {
        val release = releaseFrom(payload())!!
        assertEquals("v0.2", release.version)
        assertEquals("Changes", release.notes)
        assertEquals(release, savedRelease(release.json()))

        val dated = payload().put("tag_name", "v2026.09.30-2105")
        asset(dated).put("browser_download_url",
            "https://github.com/sp-hy/Strike/releases/download/v2026.09.30-2105/Strike.apk")
        assertEquals("v2026.09.30-2105", releaseFrom(dated)!!.version)
    }

    @Test
    fun onlyAPublishedStableReleaseWithTheNamedApkIsOffered() {
        assertNull(releaseFrom(payload().put("draft", true)))
        assertNull(releaseFrom(payload().put("prerelease", true)))
        assertNull(releaseFrom(payload().put("assets", JSONArray())))
        assertNull(releaseFrom(payload().also { asset(it).put("name", "app-release.apk") }))
        assertNull(releaseFrom(payload().also { asset(it).put("state", "new") }))
        val duplicate = payload()
        duplicate.getJSONArray("assets").put(JSONObject(asset(duplicate).toString()))
        assertNull(releaseFrom(duplicate))
    }

    @Test
    fun metadataCannotSendTheDownloaderToAnUntrustedAddress() {
        val good = asset(payload()).getString("browser_download_url")
        for (url in listOf(good.replace("https:", "http:"), good.replace("github.com", "github.com.evil"),
                good.replace("sp-hy", "UnrealSalty"), good.replace("v0.2", "v0.3"),
                good.replace("github.com", "name@github.com"), "$good?extra=1", "$good#fragment")) {
            assertThrows(url, IllegalArgumentException::class.java) {
                releaseFrom(payload().also { asset(it).put("browser_download_url", url) })
            }
        }
    }

    @Test
    fun missingChecksumsAndUnboundedSizesAreRejected() {
        for (digest in listOf("", "sha256:bad", "sha512:" + "a".repeat(64))) {
            assertThrows(IllegalArgumentException::class.java) {
                releaseFrom(payload().also { asset(it).put("digest", digest) })
            }
        }
        for (bytes in listOf(0L, -1L, MAX_APK_BYTES + 1)) {
            assertThrows(IllegalArgumentException::class.java) {
                releaseFrom(payload().also { asset(it).put("size", bytes) })
            }
        }
    }

    private fun asset(payload: JSONObject) = payload.getJSONArray("assets").getJSONObject(0)

    private fun payload() = JSONObject().put("tag_name", "v0.2").put("body", "Changes")
        .put("assets", JSONArray().put(JSONObject().put("name", "Strike.apk").put("state", "uploaded")
            .put("size", 1024).put("digest", "sha256:" + "a".repeat(64))
            .put("browser_download_url", "https://github.com/sp-hy/Strike/releases/download/v0.2/Strike.apk")))
}
