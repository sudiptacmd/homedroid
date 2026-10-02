package dev.homedroid

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class WakeOnLanTest {
    @Test fun macAddressesAreReadInEveryCommonSpelling() {
        for (s in listOf("3C:7C:3F:12:34:56", "3c-7c-3f-12-34-56", "3c7c.3f12.3456", "3c7c3f123456", " 3c 7c 3f 12 34 56 ")) {
            assertEquals(s, "3c:7c:3f:12:34:56", WolCore.parseMac(s))
        }
        for (s in listOf("", "3c:7c:3f:12:34", "3c:7c:3f:12:34:5g", "00:00:00:00:00:00", "ff:ff:ff:ff:ff:ff", "3c:7c:3f:12:34:56:78")) {
            assertNull(s, WolCore.parseMac(s))
        }
    }

    @Test fun theMagicPacketIsSixFfsAndTheMacSixteenTimes() {
        val p = WolCore.magicPacket("3c:7c:3f:12:34:56")
        assertEquals(102, p.size)
        assertTrue(p.take(6).all { it == 0xFF.toByte() })
        val mac = byteArrayOf(0x3c, 0x7c, 0x3f, 0x12, 0x34, 0x56)
        for (i in 0 until 16) assertArrayEquals(mac, p.copyOfRange(6 + i * 6, 12 + i * 6))
    }

    @Test fun devicesAreValidatedAndCleaned() {
        val ok = WolCore.device(JSONObject().put("name", " Gaming PC\n").put("mac", "3C-7C-3F-12-34-56").put("host", "192.168.1.20"), "abc").getOrThrow()
        assertEquals("Gaming PC", ok.getString("name"))
        assertEquals("3c:7c:3f:12:34:56", ok.getString("mac"))
        assertEquals("abc", ok.getString("id"))
        assertTrue(WolCore.device(JSONObject().put("name", "x").put("mac", "nope"), "a").isFailure)
        assertTrue(WolCore.device(JSONObject().put("mac", "3c7c3f123456"), "a").isFailure)
        assertTrue(WolCore.device(JSONObject().put("name", "x").put("mac", "3c7c3f123456").put("host", "a b;rm"), "a").isFailure)
        assertTrue(WolCore.device(JSONObject().put("name", "x").put("mac", "3c7c3f123456").put("broadcast", "192.168.1.256"), "a").isFailure)
        assertTrue(WolCore.device(JSONObject().put("name", "x").put("mac", "3c7c3f123456").put("host", "nas.lan"), "a").isSuccess)
    }
}
