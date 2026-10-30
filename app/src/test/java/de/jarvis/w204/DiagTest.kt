package de.jarvis.w204

import de.jarvis.w204.obd.DiagParser
import de.jarvis.w204.obd.DiagParser.Reply
import de.jarvis.w204.obd.ReadOnly
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagTest {
    @Test fun readOnlyAllowsReading() {
        listOf("0105", "0902", "1A86", "2130", "21 30", "22F187", "221234").forEach { assertTrue(it, ReadOnly.isAllowed(it)) }
    }

    @Test fun readOnlyBlocksEverythingElse() {
        // Fehler löschen, Sitzung, Freischaltung, Codieren, Routinen, Stellglieder, Reset, Schreiben
        listOf("04", "14FFFFFF", "1003", "1092", "2701", "2EF1901234", "3101FF00", "2F123403", "1101", "3B9012", "3001", "ATZ", "", "2", "22F18").forEach {
            assertFalse(it, ReadOnly.isAllowed(it))
        }
    }

    @Test fun singleFramePositive() =
        assertEquals(Reply.Positive("6130AABB"), DiagParser.parse("61 30 AA BB\r\r", 0x21))

    @Test fun negativeResponse() =
        assertEquals(Reply.Negative(0x31), DiagParser.parse("7F 21 31", 0x21))

    @Test fun pendingThenPositive() =
        assertEquals(Reply.Positive("62123401"), DiagParser.parse("7F2278\r62123401\r", 0x22))

    @Test fun noData() = assertEquals(Reply.None, DiagParser.parse("NO DATA", 0x22))

    @Test fun multiFrame() {
        val raw = "014\r0:62F187413132\r1:33343536373839\r2:30313200000000\r"
        val r = DiagParser.parse(raw, 0x22) as Reply.Positive
        assertEquals(40, r.hex.length)                // 0x14 = 20 Byte
        assertEquals("A123456789012", DiagParser.ascii(r.hex, 3))
    }

    @Test fun multiFrameWithSpaces() {
        val raw = "0 1 0\r0: 61 87 41 42 43 44\r1: 45 46 47 48 49 4A 4B\r"
        val r = DiagParser.parse(raw.replace("0 1 0", "010"), 0x1A + 0) // falscher Dienst -> keine positive Antwort
        assertEquals(Reply.None, r)
        val ok = DiagParser.parse(raw.replace("0 1 0", "010").replace("61 87", "5A 87"), 0x1A) as Reply.Positive
        assertTrue(ok.hex.startsWith("5A87"))
    }
}
