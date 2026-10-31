package de.jarvis.w204

import de.jarvis.w204.map.MapLimit
import de.jarvis.w204.map.Plausibility
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlausibilityTest {
    @Test fun cityRejects120() {
        // Stadt, Hauptstraße mit eingetragenem 50
        assertEquals(70, Plausibility.maxLimit("primary", true, MapLimit(50)))
        // Wohnstraße
        assertEquals(50, Plausibility.maxLimit("residential", null, null))
        // nur Kartenlimit 50, Lage unbekannt
        assertEquals(80, Plausibility.maxLimit("secondary", null, MapLimit(50)))
    }

    @Test fun motorwayAllowsEverything() {
        assertNull(Plausibility.maxLimit("motorway", true, MapLimit(100)))
        assertNull(Plausibility.maxLimit("trunk", null, null))
    }

    @Test fun ruralAndUnknown() {
        assertEquals(130, Plausibility.maxLimit("primary", false, MapLimit(100)))
        assertNull(Plausibility.maxLimit(null, null, null))
        // geschätzte Kartenwerte schränken nicht ein
        assertNull(Plausibility.maxLimit("primary", null, MapLimit(50, estimated = true)))
    }
}
