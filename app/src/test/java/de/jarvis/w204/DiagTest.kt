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
        listOf("0105", "0904", "1A86", "2130", "21 30", "22F187", "221234").forEach { assertTrue(it, ReadOnly.isAllowed(it)) }
    }

    @Test fun readOnlyBlocksEverythingElse() {
        // Fehler löschen, Sitzung, Freischaltung, Codieren, Routinen, Stellglieder, Reset, Schreiben
        listOf("04", "14FFFFFF", "1003", "1092", "2701", "2EF1901234", "3101FF00", "2F123403", "1101", "3B9012", "3001", "ATZ", "", "2", "22F18").forEach {
            assertFalse(it, ReadOnly.isAllowed(it))
        }
    }

    @Test fun privateIdsBlocked() {
        listOf("22F190", "22F1A0", "22F18C", "0902").forEach { assertFalse(it, ReadOnly.isAllowed(it)) }
        assertTrue(ReadOnly.isAllowed("22F187"))
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

class DtcTest {
    @Test fun decodeLetters() {
        org.junit.Assert.assertEquals("P0299", de.jarvis.w204.obd.DtcDecoder.decode(0x02, 0x99))
        org.junit.Assert.assertEquals("P2463", de.jarvis.w204.obd.DtcDecoder.decode(0x24, 0x63))
        org.junit.Assert.assertEquals("U0100", de.jarvis.w204.obd.DtcDecoder.decode(0xC1, 0x00))
        org.junit.Assert.assertEquals("C1234", de.jarvis.w204.obd.DtcDecoder.decode(0x52, 0x34))
        org.junit.Assert.assertEquals("B0001", de.jarvis.w204.obd.DtcDecoder.decode(0x80, 0x01))
    }

    @Test fun obdCanFormatWithCount() {
        val codes = de.jarvis.w204.obd.DtcDecoder.parseObd("43 02 02 99 24 63\r", 0x03)
        org.junit.Assert.assertEquals(listOf("P0299", "P2463"), codes)
    }

    @Test fun obdNoCodes() =
        org.junit.Assert.assertEquals(emptyList<String>(), de.jarvis.w204.obd.DtcDecoder.parseObd("43 00\r", 0x03))

    @Test fun obdTwoEcus() {
        val codes = de.jarvis.w204.obd.DtcDecoder.parseObd("43 01 01 28\r43 01 07 00\r", 0x03)
        org.junit.Assert.assertEquals(listOf("P0128", "P0700"), codes)
    }

    @Test fun kwp18() {
        val list = de.jarvis.w204.obd.DtcDecoder.parseKwp18("5802" + "245360" + "029920")
        org.junit.Assert.assertEquals(listOf("P2453" to 0x60, "P0299" to 0x20), list)
    }

    @Test fun uds1902() {
        val list = de.jarvis.w204.obd.DtcDecoder.parseUds1902("5902FF" + "24630009" + "01280008")
        org.junit.Assert.assertEquals("P2463", list[0].first)
        org.junit.Assert.assertEquals("aktuell", de.jarvis.w204.obd.DtcDecoder.udsState(list[0].third))
        org.junit.Assert.assertEquals("gespeichert", de.jarvis.w204.obd.DtcDecoder.udsState(list[1].third))
    }

    @Test fun databaseKnownAndFallback() {
        val known = de.jarvis.w204.obd.DtcDatabase.lookup("P0299")
        org.junit.Assert.assertTrue(known.known)
        org.junit.Assert.assertEquals(2, known.severity)
        val mb = de.jarvis.w204.obd.DtcDatabase.lookup("P1A0B")
        org.junit.Assert.assertFalse(mb.known)
        org.junit.Assert.assertTrue(mb.title.contains("Mercedes"))
        org.junit.Assert.assertTrue(de.jarvis.w204.obd.DtcDatabase.system("P0471").contains("Abgas"))
    }

    @Test fun readOnlyDtcReads() {
        listOf("03", "07", "0A", "1802FF00", "19020C", "1901FF", "190A").forEach {
            org.junit.Assert.assertTrue(it, de.jarvis.w204.obd.ReadOnly.isAllowed(it))
        }
        // 14 = Fehler löschen, 04 = OBD-Fehler löschen, 19 mit unbekannter Unterfunktion
        listOf("04", "14FFFF", "1904", "1914", "31010203").forEach {
            org.junit.Assert.assertFalse(it, de.jarvis.w204.obd.ReadOnly.isAllowed(it))
        }
    }
}
