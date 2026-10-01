package dev.homedroid

import org.junit.Assert.*
import org.junit.Test

class PhoneSettingsTest {
    @Test fun passwordLimitsAndControlCharacters() {
        assertFalse(PhoneSettings.validPassword("short"))
        assertTrue(PhoneSettings.validPassword("a".repeat(12)))
        assertTrue(PhoneSettings.validPassword("a".repeat(128)))
        assertFalse(PhoneSettings.validPassword("a".repeat(129)))
        assertFalse(PhoneSettings.validPassword("a".repeat(12) + "\n"))
        assertFalse(PhoneSettings.validPassword("a".repeat(12) + "\u0000"))
    }
    @Test fun tailscaleDeviceNames() {
        listOf("homedroid", "living-room-2", "a", "0", "a".repeat(63)).forEach { assertTrue(it, PhoneSettings.validHostname(it)) }
        listOf("", "-phone", "phone-", "Phone", "phone.local", "a".repeat(64), "phone name").forEach { assertFalse(it, PhoneSettings.validHostname(it)) }
    }
    @Test fun webViewAndDownloadsStayOnTheExactLoopbackOrigin() {
        assertTrue(PhoneSettings.isPanelUrl("http://127.0.0.1:8800/#settings", 8800))
        assertTrue(PhoneSettings.isPanelUrl("http://127.0.0.1:8800/api/files/download?q=1", 8800))
        listOf("http://127.0.0.1:8080", "https://127.0.0.1:8800", "http://localhost:8800", "http://127.0.0.1.example.com:8800",
            "http://127.0.0.1:8800@evil.test/", "http://user@127.0.0.1:8800", "file:///etc/passwd", "javascript:alert(1)", "not a URL").forEach {
            assertFalse(it, PhoneSettings.isPanelUrl(it, 8800))
        }
    }
}
