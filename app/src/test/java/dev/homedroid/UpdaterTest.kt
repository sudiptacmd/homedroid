package dev.homedroid

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class UpdaterTest {
    private fun release(tag: String, apk: Boolean = true, draft: Boolean = false, pre: Boolean = true) =
        JSONObject().put("tag_name", tag).put("draft", draft).put("prerelease", pre)
            .put("assets", JSONArray().apply {
                put(JSONObject().put("name", "SHA256SUMS"))
                if (apk) put(JSONObject().put("name", "homedroid-${tag.removePrefix("v")}-arm64-v8a.apk"))
            })

    @Test fun picksTheHighestReleaseIncludingPreReleases() {
        val list = JSONArray().put(release("v0.9.2-beta")).put(release("v0.9.10-beta")).put(release("v0.8.4", pre = false))
        assertEquals("v0.9.10-beta", Updater.newest(list)?.getString("tag_name"))
    }

    @Test fun skipsDraftsAndReleasesStillWithoutAnApk() {
        val list = JSONArray().put(release("v0.9.5-beta", draft = true)).put(release("v0.9.4-beta", apk = false)).put(release("v0.9.3-beta"))
        assertEquals("v0.9.3-beta", Updater.newest(list)?.getString("tag_name"))
        assertNull(Updater.newest(JSONArray()))
    }

    @Test fun aFinalReleaseBeatsItsBeta() {
        assertTrue(Updater.newer("1.0.0", "1.0.0-beta"))
        assertFalse(Updater.newer("0.9.3-beta", "0.9.3-beta"))
        assertTrue(Updater.newer("0.9.10-beta", "0.9.9-beta"))
    }
}
