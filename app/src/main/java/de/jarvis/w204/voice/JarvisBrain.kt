package de.jarvis.w204.voice

import android.os.SystemClock
import de.jarvis.w204.car.VehicleState
import de.jarvis.w204.car.W204Profile
import de.jarvis.w204.car.fmt1
import java.util.Calendar
import java.util.Locale

/** Regeln, wann Jarvis von sich aus etwas sagt, und Antworten auf Sprachfragen. */
class JarvisBrain(private val jarvis: Jarvis) {
    private var pendingGreeting = false
    private var sessionStart = 0L
    private var startedCold = false
    private var warmAnnounced = false
    private var overSince = 0L
    private var lowChargeSince = 0L
    private var gapSince = 0L
    private var lastGreetAt = 0L
    private var hotSince = 0L
    private var stoppedSince = 0L
    private var stopLead: Float? = null
    private var driveStart = 0L
    private var lastMoving = 0L
    private var coolSince = 0L
    var regenActive = false
        private set

    fun onSessionStart() {
        // Kurzer Abriss (Funkloch, Adapter-Neustart) ist keine neue Fahrt
        if (lastGreetAt != 0L && SystemClock.elapsedRealtime() - lastGreetAt < 10 * 60_000L) return
        pendingGreeting = true
        sessionStart = SystemClock.elapsedRealtime()
        warmAnnounced = false
        overSince = 0L
        lowChargeSince = 0L
        gapSince = 0L
    }

    fun evaluate(s: VehicleState, limit: Int?, tolerance: Int, leadDistance: Float?) {
        val now = SystemClock.elapsedRealtime()

        if (pendingGreeting && (s.coolant != null || (s.rpm != null && now - sessionStart > 4000))) {
            pendingGreeting = false
            greet(s)
        }

        val c = s.coolant
        val rpm = s.rpm ?: 0
        if (c != null) {
            if (startedCold && !warmAnnounced && c >= W204Profile.COOLANT_WARM) {
                warmAnnounced = true
                jarvis.say("Kühlmittel bei $c Grad. Motor ist fast betriebswarm, das Öl braucht noch ein paar Minuten.")
            }
            if (c < W204Profile.COOLANT_COLD && rpm > W204Profile.COLD_RPM_LIMIT) {
                jarvis.say("Motor noch kalt. Bitte unter ${W204Profile.COLD_RPM_LIMIT} Umdrehungen bleiben.", "cold", 45_000)
            }
            if (c >= W204Profile.COOLANT_HOT) {
                jarvis.say("Achtung: Kühlmittel $c Grad. Bitte Temperatur beobachten.", "hot", 60_000)
            }
        }

        s.intakeTemp?.let {
            if (it >= W204Profile.IAT_HIGH) jarvis.say("Ladelufttemperatur hoch: $it Grad.", "iat", 180_000)
        }

        val v = s.voltage
        if (v != null && s.engineRunning && v < W204Profile.CHARGE_LOW) {
            if (lowChargeSince == 0L) lowChargeSince = now
            else if (now - lowChargeSince > 30_000) {
                jarvis.say("Ladespannung nur ${fmt1(v)} Volt. Batterie und Lichtmaschine prüfen lassen.", "charge", 600_000)
            }
        } else {
            lowChargeSince = 0L
        }

        val speed = s.speed
        if (limit != null && speed != null && speed > limit + tolerance) {
            if (overSince == 0L) overSince = now
            else if (now - overSince > 2500) jarvis.say("Tempolimit $limit.", "over", 25_000)
        } else {
            overSince = 0L
        }

        // Vordermann fährt los: wir stehen, das Auto vor uns entfernt sich deutlich
        val sp = s.speed
        if (sp != null && sp == 0 && s.engineRunning) {
            if (stoppedSince == 0L) { stoppedSince = now; stopLead = leadDistance }
            val d0 = stopLead
            if (d0 == null && leadDistance != null && now - stoppedSince < 3000) stopLead = leadDistance
            if (d0 != null && d0 < 15f && leadDistance != null && leadDistance - d0 > 4f && now - stoppedSince > 3000) {
                jarvis.say("Der Vordermann fährt los.", "lead_go", 30_000)
                stopLead = leadDistance
            }
        } else {
            stoppedSince = 0L
            stopLead = null
        }

        // Pausenhinweis nach 2 Stunden Fahrt (kurze Stopps < 15 min zählen nicht als Pause)
        if (sp != null && sp > 5) {
            if (driveStart == 0L || now - lastMoving > 15 * 60_000L) driveStart = now
            lastMoving = now
            if (now - driveStart > 2 * 3600_000L) {
                jarvis.say("Du fährst seit über zwei Stunden. Zeit für eine kurze Pause?", "pause", 3600_000L)
            }
        }

        // DPF-Regeneration: Abgas vor dem Filter dauerhaft sehr heiß (normal < ~400 °C, Regeneration ~550–650 °C)
        val ex = s.exhaustTemp
        if (ex != null && s.engineRunning) {
            if (ex >= 480) {
                coolSince = 0L
                if (hotSince == 0L) hotSince = now
                if (!regenActive && now - hotSince > 90_000) {
                    regenActive = true
                    jarvis.say("Der Partikelfilter regeneriert gerade. Wenn möglich weiterfahren und den Motor nicht abstellen.", "regen", 20 * 60_000)
                }
            } else if (ex < 400) {
                hotSince = 0L
                if (regenActive) {
                    if (coolSince == 0L) coolSince = now
                    if (now - coolSince > 60_000) {
                        regenActive = false
                        jarvis.say("Regeneration des Partikelfilters ist abgeschlossen.", "regen_end", 10 * 60_000)
                    }
                }
            }
        }

        // Abstand: deutlich unter "halber Tacho" bei über 50 km/h
        if (leadDistance != null && speed != null && speed >= 50 && leadDistance < speed / 2f * 0.6f) {
            if (gapSince == 0L) gapSince = now
            else if (now - gapSince > 2000) jarvis.say("Abstand.", "gap", 20_000)
        } else {
            gapSince = 0L
        }
    }

    private fun greet(s: VehicleState) {
        lastGreetAt = SystemClock.elapsedRealtime()
        val hello = when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
            in 5..10 -> "Guten Morgen"
            in 11..17 -> "Guten Tag"
            else -> "Guten Abend"
        }
        val c = s.coolant
        startedCold = c != null && c < W204Profile.COOLANT_COLD
        val sb = StringBuilder("$hello. Alle Systeme online.")
        if (c != null) sb.append(" Kühlmittel $c Grad.")
        if (startedCold) sb.append(" Der Motor ist kalt, bitte schonend warmfahren.")
        val v = s.voltage
        if (v != null && !s.engineRunning && v < W204Profile.BATTERY_LOW) sb.append(" Batteriespannung ist niedrig.")
        jarvis.say(sb.toString())
    }

    fun answer(raw: String, s: VehicleState, limit: Int?, leadDistance: Float?): String {
        val q = raw.lowercase(Locale.GERMAN)
        fun has(vararg words: String) = words.any { it in q }
        val noData = "Dazu habe ich gerade keine Daten vom Motor."
        return when {
            has("außen", "aussen", "draußen", "draussen") ->
                s.ambient?.let { "Draußen sind es $it Grad." } ?: "Die Außentemperatur liefert dein Steuergerät nicht."
            has("ladeluft", "ansaug") ->
                s.intakeTemp?.let { "Die Ladeluft hat $it Grad." } ?: noData
            has("temperatur", "kühlmittel", "kuehlmittel", "warm", "kalt") ->
                s.coolant?.let { coolantText(it) } ?: noData
            has("rail") ->
                s.railBar?.let { "Raildruck $it bar." } ?: noData
            has("ladedruck", "turbo", "boost", "druck") -> when {
                !s.engineRunning -> "Der Motor läuft gerade nicht."
                s.boostBar != null -> "Ladedruck ${fmt1(s.boostBar!!)} bar."
                else -> noData
            }
            has("batterie", "spannung", "volt", "lichtmaschine") ->
                s.voltage?.let { "Bordnetz ${fmt1(it)} Volt." + if (s.engineRunning && it >= W204Profile.CHARGE_LOW) " Ladung in Ordnung." else "" } ?: noData
            has("limit", "tempo", "schnell darf", "begrenzung", "schild") ->
                limit?.let { "Zuletzt erkannt: Tempolimit $it." } ?: "Ich habe noch kein Tempolimit erkannt."
            has("abstand", "vordermann", "vorne", "vor mir") ->
                leadDistance?.let { "Etwa ${it.toInt()} Meter Abstand zum Vordermann." } ?: "Vor dir erkenne ich gerade kein Fahrzeug."
            has("partikelfilter", "dpf", "regeneration", "abgas") ->
                when {
                    regenActive -> "Der Partikelfilter regeneriert gerade. Abgas vor dem Filter: ${s.exhaustTemp} Grad."
                    s.exhaustTemp != null -> "Keine Regeneration aktiv. Abgas vor dem Filter: ${s.exhaustTemp} Grad."
                    else -> "Die Abgastemperatur ist gerade nicht verfügbar."
                }
            has("tank", "reichweite") ->
                s.fuelLevel?.let { "Der Tank ist zu $it Prozent voll." } ?: "Den Tankfüllstand liefert dein Steuergerät gerade nicht."
            has("verbrauch", "verbrauche", "sprit", "diesel") ->
                s.consumption?.let { "Aktuell ${fmt1(it)} Liter auf 100 Kilometer." }
                    ?: s.fuelRate?.let { "Aktuell ${fmt1(it)} Liter pro Stunde." }
                    ?: "Den Verbrauch liefert dein Steuergerät nicht."
            has("drehzahl", "touren") ->
                s.rpm?.let { "$it Umdrehungen pro Minute." } ?: noData
            has("status", "bericht", "check", "wie geht", "alles") -> statusText(s)
            has("danke") -> "Gern geschehen."
            has("hallo", "hey", "jarvis") -> "Ich höre. Frag mich nach Temperatur, Ladedruck, Batterie, Abstand, Tempolimit oder Status."
            else -> "Das habe ich nicht verstanden. Frag mich nach Temperatur, Ladedruck, Batterie, Tempolimit oder Status."
        }
    }

    private fun coolantText(c: Int): String = when {
        c < W204Profile.COOLANT_COLD -> "Kühlmittel $c Grad. Der Motor ist noch kalt."
        c < W204Profile.COOLANT_WARM -> "Kühlmittel $c Grad. Fast warm."
        c >= W204Profile.COOLANT_HOT -> "Kühlmittel $c Grad. Das ist zu heiß!"
        else -> "Kühlmittel $c Grad. Betriebswarm."
    }

    private fun statusText(s: VehicleState): String {
        if (s.coolant == null && s.voltage == null && s.rpm == null) return "Ich habe gerade keine Verbindung zum Auto."
        val parts = mutableListOf<String>()
        val issues = mutableListOf<String>()
        s.coolant?.let {
            parts += "Kühlmittel $it Grad"
            if (it >= W204Profile.COOLANT_HOT) issues += "Kühlmittel zu heiß"
        }
        s.voltage?.let {
            parts += "Bordnetz ${fmt1(it)} Volt"
            if (s.engineRunning && it < W204Profile.CHARGE_LOW) issues += "Ladespannung niedrig"
        }
        if (s.engineRunning) s.boostBar?.let { parts += "Ladedruck ${fmt1(it)} bar" }
        s.intakeTemp?.let { if (it >= W204Profile.IAT_HIGH) issues += "Ladeluft heiß" }
        val head = parts.joinToString(", ") + "."
        return if (issues.isEmpty()) "$head Alles im grünen Bereich." else "$head Achtung: ${issues.joinToString(", ")}."
    }
}
