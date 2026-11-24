package dev.homedroid

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class DesktopCameraCoreTest {
    private val camera = JSONObject("""{"id":"0","facing":"back","sizes":[[1920,1080],[1280,720],[640,480]],"fps":[15,30,60],"capabilities":{
        "torch":true,"focus":["continuous","auto","manual"],"focusDistance":10.0,"tapFocus":true,"zoom":[1.0,8.0],"ev":[-4.0,4.0],"evStep":0.5,
        "aeLock":true,"awbLock":true,"wb":["auto","daylight"],"antiBanding":["auto","50hz"],"stabilization":false,"manualSensor":true,"iso":[50,3200],"shutterNs":[100000,500000000]}}""")
    private fun merge(patch: String, current: JSONObject = DesktopCameraCore.defaults(camera)) = DesktopCameraCore.merge(current, JSONObject(patch), camera)
    private fun rejects(patch: String) { try { merge(patch); fail("Expected rejection of $patch") } catch (_: IllegalArgumentException) {} }

    @Test fun defaultsPickTheClosestSizeAndOnlyAdvertisedControls() {
        val d = DesktopCameraCore.defaults(camera)
        assertEquals(1280, d.getInt("width")); assertEquals(30.0, d.getDouble("fps"), 0.0); assertEquals("continuous", d.getString("focus"))
        assertTrue(d.has("torch") && !d.has("stabilization")); assertEquals(1.0, d.getDouble("zoom"), 0.0)
    }
    @Test fun validPatchesMergeIntoTheCurrentSettings() {
        val o = merge("""{"zoom":2.5,"torch":true,"ev":-1.5,"wb":"daylight","rotate":90,"mirror":true,"quality":60,"antiBanding":"50hz"}""")
        assertEquals(2.5, o.getDouble("zoom"), 0.0); assertTrue(o.getBoolean("torch")); assertEquals(90, o.getInt("rotate")); assertEquals(60, o.getInt("quality"))
        assertEquals(1280, o.getInt("width"))
        assertEquals(1920, merge("""{"width":1920,"height":1080,"fps":60}""").getInt("width"))
    }
    @Test fun manualFocusExposureAndTapAreCoupled() {
        assertEquals("manual", merge("""{"focus":"manual","focusDistance":4.0}""").getString("focus"))
        rejects("""{"focus":"manual"}"""); rejects("""{"focusDistance":2.0}""")
        assertEquals("auto", merge("""{"x":0.3,"y":0.7}""").getString("focus")); rejects("""{"x":0.3}"""); rejects("""{"x":0.3,"y":0.7,"focus":"manual","focusDistance":1.0}""")
        assertEquals(400, merge("""{"manualSensor":true,"iso":400,"shutterNs":10000000}""").getInt("iso"))
        rejects("""{"manualSensor":true}"""); rejects("""{"iso":400,"shutterNs":10000000}""")
    }
    @Test fun unsupportedOutOfRangeAndMistypedValuesChangeNothing() {
        for (p in listOf("""{"stabilization":true}""", """{"zoom":9}""", """{"zoom":0.5}""", """{"ev":0.25}""", """{"ev":5}""", """{"wb":"cloudy"}""", """{"antiBanding":"60hz"}""",
            """{"rotate":45}""", """{"rotate":"90"}""", """{"torch":"yes"}""", """{"quality":0}""", """{"quality":101}""", """{"width":1000,"height":1000}""", """{"fps":24}""",
            """{"focus":"macro"}""", """{"bogus":1}""", """{"reset":"true"}""", """{"zoom":"2"}""")) rejects(p)
        val before = DesktopCameraCore.defaults(camera)
        try { merge("""{"zoom":3,"wb":"bad"}""", before) } catch (_: IllegalArgumentException) {}
        assertEquals(1.0, before.getDouble("zoom"), 0.0)
    }
    @Test fun resetRestoresDefaultsBeforeApplyingTheRestOfThePatch() {
        val changed = merge("""{"zoom":4,"mirror":true}""")
        val o = merge("""{"reset":true,"torch":true}""", changed)
        assertEquals(1.0, o.getDouble("zoom"), 0.0); assertFalse(o.getBoolean("mirror")); assertTrue(o.getBoolean("torch"))
    }
    @Test fun tapPointsAreMappedBackThroughMirrorFlipAndRotation() {
        fun p(json: String) = DesktopCameraCore.sensorPoint(0.2, 0.9, JSONObject(json))
        assertEquals(0.8, p("""{"mirror":true}""").first, 1e-9); assertEquals(0.1, p("""{"flip":true}""").second, 1e-9)
        p("""{"rotate":90}""").let { assertEquals(0.9, it.first, 1e-9); assertEquals(0.8, it.second, 1e-9) }
        p("""{"rotate":180}""").let { assertEquals(0.8, it.first, 1e-9); assertEquals(0.1, it.second, 1e-9) }
    }
}
