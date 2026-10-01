package dev.homedroid

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.Rule
import java.io.File

class CameraPolicyTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun oldestCompletedCapturesAreEvictedAndActiveFilesAreProtected() {
        val dir = temp.newFolder()
        File(dir, "100-a.mp4").writeBytes(ByteArray(80))
        File(dir, "200-b.wav").writeBytes(ByteArray(50))
        File(dir, "300-c.mp4.partial").writeBytes(ByteArray(30))
        File(dir, "notes.txt").writeText("keep me")
        val store = CaptureStore(dir) { 120 }
        assertTrue(store.reserve(40))
        assertFalse(File(dir, "100-a.mp4").exists())
        assertTrue(File(dir, "200-b.wav").exists())
        assertTrue(File(dir, "300-c.mp4.partial").exists())
        assertTrue(File(dir, "notes.txt").exists())
        assertEquals(80L, store.used())
        assertEquals(30L, store.activeBytes())
    }

    @Test fun oversizedActiveClipCannotBeEvictedToSatisfyQuota() {
        val dir = temp.newFolder()
        File(dir, "100-a.mp4.partial").writeBytes(ByteArray(100))
        val store = CaptureStore(dir) { 80 }
        assertFalse(store.reserve(0))
        assertTrue(File(dir, "100-a.mp4.partial").exists())
    }

    @Test fun reducingQuotaRemovesOldestCapturesAcrossAllMediaTypes() {
        val dir = temp.newFolder()
        listOf("100-a.jpg", "200-b.wav", "300-c.mp4").forEach { File(dir, it).writeBytes(ByteArray(50)) }
        var quota = 200L
        val store = CaptureStore(dir) { quota }
        assertTrue(store.reserve(0))
        quota = 60
        assertTrue(store.reserve(0))
        assertEquals(listOf("300-c.mp4"), dir.list()!!.toList())
    }

    @Test fun brightnessChangeAndSingleNoisyFrameDoNotTriggerMotion() {
        val detector = MotionDetector()
        assertFalse(detector.changed(IntArray(100) { 80 }, 10))
        assertFalse(detector.changed(IntArray(100) { 130 }, 10))
        assertFalse(detector.changed(IntArray(100) { if (it < 20) 210 else 130 }, 10))
        assertFalse(detector.changed(IntArray(100) { if (it < 20) 210 else 130 }, 10))
    }

    @Test fun repeatedLocalMovementTriggersMotionAtSelectedThreshold() {
        val detector = MotionDetector()
        detector.changed(IntArray(100) { 80 }, 10)
        assertFalse(detector.changed(IntArray(100) { if (it < 20) 160 else 80 }, 10))
        assertTrue(detector.changed(IntArray(100) { if (it < 20) 80 else 80 }, 10))
    }

    @Test fun cameraOptionsRoundTripIndependentlyAndRejectInvalidValues() {
        val front = CameraOptions.parse(JSONObject().put("rotation", 270).put("fps", 2).put("clipSeconds", 15))
        val rear = CameraOptions.parse(JSONObject().put("rotation", 90).put("fps", 15).put("resolution", 720))
        assertEquals(front, CameraOptions.parse(front.json()))
        assertEquals(rear, CameraOptions.parse(rear.json()))
        assertNotEquals(front, rear)
        for ((key, value) in listOf("fps" to 0, "resolution" to 999, "rotation" to 45, "clipSeconds" to 4, "quietSeconds" to 0, "sensitivity" to 0)) {
            assertThrows(IllegalArgumentException::class.java) { CameraOptions.parse(JSONObject().put(key, value)) }
        }
    }
}
