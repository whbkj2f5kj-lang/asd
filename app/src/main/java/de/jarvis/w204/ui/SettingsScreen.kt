package de.jarvis.w204.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.jarvis.w204.MainViewModel
import de.jarvis.w204.obd.bondedDevices
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(vm: MainViewModel, onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    val context = LocalContext.current
    val settings by vm.settings.collectAsStateWithLifecycle()
    val status by vm.obd.status.collectAsStateWithLifecycle()
    val scan by vm.scanner.state.collectAsStateWithLifecycle()
    val camCount by vm.cameraCount.collectAsStateWithLifecycle()
    val usbCams by vm.usbCameraNames.collectAsStateWithLifecycle()
    val usbAll by vm.usbAll.collectAsStateWithLifecycle()
    val tpmsStatus by vm.tpms.status.collectAsStateWithLifecycle()
    var devices by remember { mutableStateOf(bondedDevices(context)) }

    Surface(Modifier.fillMaxSize(), color = J.bg) {
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Einstellungen", color = J.text, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onClose) { Text("FERTIG", color = J.accent, fontSize = 16.sp) }
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {

                // ----- Links: OBD -----
                Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState())) {
                    Section("OBD-Adapter (ELM327)")
                    Hint("Adapter zuerst in den Bluetooth-Einstellungen des Radios koppeln (PIN meist 1234 oder 0000). Zündung an.")
                    Spacer(Modifier.height(8.dp))
                    if (devices.isEmpty()) Hint("Keine gekoppelten Bluetooth-Geräte gefunden.")
                    devices.forEach { (name, addr) ->
                        val selected = settings.deviceAddress == addr && !settings.demo
                        Row(
                            Modifier.fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (selected) J.accent.copy(alpha = 0.15f) else J.panel)
                                .clickable { vm.update { it.copy(deviceAddress = addr, deviceName = name, demo = false) } }
                                .padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = selected, onClick = null)
                            Spacer(Modifier.width(8.dp))
                            Column {
                                Text(name, color = J.text, fontSize = 15.sp)
                                Text(addr, color = J.dim, fontSize = 11.sp)
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                    }
                    Row {
                        TextButton(onClick = { devices = bondedDevices(context) }) { Text("Liste aktualisieren") }
                        TextButton(onClick = { vm.connect() }) { Text("Neu verbinden") }
                    }
                    SwitchRow("Demo-Modus (ohne Auto testen)", settings.demo) { on -> vm.update { it.copy(demo = on) } }

                    Spacer(Modifier.height(12.dp))
                    Section("Verbindung")
                    Hint(status.message.ifBlank { status.state.name })
                    status.elmVersion?.let { Hint("Adapter: $it") }
                    Hint("Abfragerate: ${String.format(Locale.GERMANY, "%.1f", status.rate)} Werte/s" + if (status.fastMode) " · Schnellmodus" else "")
                    if (status.supportedPids.isNotEmpty()) {
                        Hint("Vom Steuergerät gemeldete PIDs: " + status.supportedPids.sorted().joinToString(" ") { "%02X".format(it) })
                    }

                    Spacer(Modifier.height(12.dp))
                    Section("Steuergeräte-Scan (nur lesen)")
                    Hint("Sucht Mercedes-spezifische Werte (DPF, Öltemperatur, Getriebe …) in Motor- und Getriebesteuergerät. Es wird ausschließlich gelesen – nichts gelöscht, codiert oder verändert. Die Programmier-Sitzung ist fest gesperrt.")
                    Text(scan.phase, color = J.text, fontSize = 14.sp, modifier = Modifier.padding(vertical = 4.dp))
                    if (scan.busy) {
                        androidx.compose.material3.LinearProgressIndicator(
                            progress = { scan.progress },
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        )
                    }
                    Hint("Gefundene Werte: ${scan.hits}" + if (scan.snapshots > 0) " · Messpunkte: ${scan.snapshots}" else "")
                    scan.idents.forEach { Hint("• $it") }
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = { vm.scanner.identify() }, enabled = !scan.busy) { Text("1. Steuergeräte erkennen") }
                        TextButton(onClick = { vm.scanner.adapterTest() }, enabled = !scan.busy) { Text("Adapter-Test") }
                    }
                    Text("2. Scan wählen", color = J.text, fontSize = 15.sp, modifier = Modifier.padding(top = 4.dp, bottom = 2.dp))
                    ScanOption(
                        title = "Tiefen-Scan",
                        badge = "EMPFOHLEN",
                        text = "Erweiterte Diagnosesitzung wie in der Werkstatt – hier geben Mercedes-Steuergeräte ihre Messwerte frei (DPF, Öl). Prüft alle 65.536 Nummern des Motorsteuergeräts, nur lesen – im Turbo-Modus 3 auf einmal. Dauert ca. 15–30 Minuten, darf auch während einer normalen Fahrt laufen (Anzeigen aktualisieren dann etwas langsamer). Jederzeit anhalten und später fortsetzen.",
                        enabled = !scan.busy,
                        highlight = true,
                    ) { vm.scanner.scan(de.jarvis.w204.obd.EcuScanner.Mode.DEEP) }
                    ScanOption(
                        title = "Standard-Scan",
                        badge = null,
                        text = "Alle Nummern, aber nur in der normalen Sitzung. Findet bei deinem Motor bisher nur Gerätedaten.",
                        enabled = !scan.busy,
                    ) { vm.scanner.scan(de.jarvis.w204.obd.EcuScanner.Mode.STANDARD) }
                    ScanOption(
                        title = "Schnell-Scan",
                        badge = null,
                        text = "Nur Gerätedaten (Teilenummer, Software). Ca. 1 Minute.",
                        enabled = !scan.busy,
                    ) { vm.scanner.scan(de.jarvis.w204.obd.EcuScanner.Mode.QUICK) }
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (scan.busy) TextButton(onClick = { vm.scanner.stop() }) { Text("Anhalten") }
                        TextButton(onClick = { vm.scanner.toggleRecording() }) {
                            Text(if (scan.recording) "Aufzeichnung stoppen" else "3. Fahrt aufzeichnen")
                        }
                        TextButton(onClick = { vm.scanner.export() }) { Text("4. Exportieren") }
                    }
                    scan.lastFile?.let { Hint("Gespeichert: $it – mit Dateimanager auf USB-Stick kopieren.") }
                    TextButton(onClick = { vm.scanner.reset() }, enabled = !scan.busy && !scan.recording) {
                        Text("Scan-Daten löschen", color = J.dim)
                    }
                }

                // ----- Rechts: Jarvis, Kamera, Fahrzeug -----
                Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState())) {
                    Section("Darstellung")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("Modern", "Klassisch", "Volta").forEachIndexed { i, label ->
                            val sel = settings.layout == i
                            Text(
                                label,
                                color = if (sel) J.bg else J.text,
                                fontSize = 14.sp,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(50))
                                    .background(if (sel) J.accent else J.panel2)
                                    .clickable { vm.update { it.copy(layout = i) } }
                                    .padding(horizontal = 14.dp, vertical = 8.dp),
                            )
                        }
                    }
                    if (settings.layout == 2) VoltaSettings(vm)
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        de.jarvis.w204.ui.Palettes.all.forEachIndexed { i, p ->
                            val sel = settings.palette == i
                            Row(
                                Modifier
                                    .clip(RoundedCornerShape(50))
                                    .background(if (sel) J.panel2 else J.panel)
                                    .border(1.dp, if (sel) p.glow else J.track, RoundedCornerShape(50))
                                    .clickable { vm.update { it.copy(palette = i) } }
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Box(Modifier.size(12.dp).clip(RoundedCornerShape(50)).background(p.mid))
                                Spacer(Modifier.width(6.dp))
                                Text(p.name, color = J.text, fontSize = 14.sp)
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    SwitchRow("Farbwelt folgt dem Fahrstil (Komfort/Sport/Eco)", settings.driveModeLook) { on -> vm.update { it.copy(driveModeLook = on) } }
                    Spacer(Modifier.height(12.dp))
                    Section("Ansagen")
                    SwitchRow("Sprachansagen", settings.voice) { on -> vm.update { it.copy(voice = on) } }
                    if (settings.voice) VoicePicker(vm)
                    SwitchRow("Erkannte Tempolimits ansagen", settings.announceSigns) { on -> vm.update { it.copy(announceSigns = on) } }
                    Spacer(Modifier.height(6.dp))
                    Text("Warnung ab Tempolimit + ${settings.tolerance} km/h", color = J.text, fontSize = 15.sp)
                    Slider(
                        value = settings.tolerance.toFloat(),
                        onValueChange = { f -> vm.update { it.copy(tolerance = f.roundToInt()) } },
                        valueRange = 0f..15f,
                        steps = 14,
                    )
                    Hint("Die OBD-Geschwindigkeit liegt meist etwas unter der Tachoanzeige.")

                    Spacer(Modifier.height(12.dp))
                    Section("Kamera")
                    SwitchRow("Kamera verwenden", settings.cameraEnabled) { on -> vm.update { it.copy(cameraEnabled = on) } }
                    SwitchRow("Schildererkennung", settings.signRecognition) { on -> vm.update { it.copy(signRecognition = on) } }
                    SwitchRow("Fahrzeug- und Spurerkennung", settings.objectRecognition) { on -> vm.update { it.copy(objectRecognition = on) } }
                    SwitchRow("Spurverlassenswarnung (Ton, ab 60 km/h)", settings.laneWarn) { on -> vm.update { it.copy(laneWarn = on) } }
                    Text("Schilder-Empfindlichkeit", color = J.text, fontSize = 15.sp, modifier = Modifier.padding(top = 6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(vertical = 4.dp)) {
                        listOf("Vorsichtig", "Normal", "Empfindlich").forEachIndexed { i, label ->
                            val sel = settings.signSensitivity == i
                            Text(
                                label,
                                color = if (sel) androidx.compose.ui.graphics.Color.Black else J.text,
                                fontSize = 14.sp,
                                modifier = Modifier.clip(RoundedCornerShape(50)).background(if (sel) J.accent else J.panel2)
                                    .clickable { vm.update { it.copy(signSensitivity = i) } }
                                    .padding(horizontal = 14.dp, vertical = 6.dp),
                            )
                        }
                    }
                    Hint("Empfindlich: schon eine sichere Sichtung reicht (mehr Treffer, gelegentlich ein falsches Schild). Vorsichtig: mehrere Sichtungen nötig.")
                    SwitchRow("Vermutete Schilder klein mit „?“ zeigen", settings.signProvisional) { on -> vm.update { it.copy(signProvisional = on) } }
                    var signFps by remember { mutableStateOf(0f) }
                    var perf by remember { mutableStateOf("") }
                    androidx.compose.runtime.LaunchedEffect(Unit) {
                        while (true) { signFps = vm.vision.signFps; perf = vm.vision.gov.summary(); kotlinx.coroutines.delay(1000) }
                    }
                    Hint("Schilder-Auswertung: " + String.format(Locale.GERMANY, "%.1f", signFps) + " Bilder/s (Kamera muss laufen)")
                    Hint("Leistung (automatisch, Schilder haben Vorrang): $perf")
                    Spacer(Modifier.height(6.dp))
                    Text("Kameraquelle", color = J.text, fontSize = 15.sp)
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("Automatisch", "Android-Kamera", "USB direkt").forEachIndexed { i, label ->
                            val sel = settings.cameraSource == i
                            Text(
                                label,
                                color = if (sel) J.bg else J.text,
                                fontSize = 14.sp,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(50))
                                    .background(if (sel) J.accent else J.panel2)
                                    .clickable { vm.update { it.copy(cameraSource = i) } }
                                    .padding(horizontal = 14.dp, vertical = 8.dp),
                            )
                        }
                    }
                    Hint(
                        if (usbCams.isEmpty()) "USB-Kameras: keine gefunden"
                        else "USB-Kameras: " + usbCams.joinToString()
                    )
                    Spacer(Modifier.height(6.dp))
                    Text("USB-Diagnose (alle Geräte, alle 3 s)", color = J.text, fontSize = 15.sp)
                    usbAll.forEach { Hint("• $it") }
                    Hint("„Automatisch“ nimmt die USB-Kamera direkt, sobald eine steckt – auch wenn das Radio sie Android nicht meldet.")
                    Spacer(Modifier.height(6.dp))
                    if (camCount == 0) {
                        Hint("Android-Kameras: keine")
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            repeat(camCount) { i ->
                                val sel = settings.cameraIndex == i
                                Text(
                                    "Kamera ${i + 1}",
                                    color = if (sel) J.bg else J.text,
                                    fontSize = 14.sp,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(50))
                                        .background(if (sel) J.accent else J.panel2)
                                        .clickable { vm.update { it.copy(cameraIndex = i) } }
                                        .padding(horizontal = 14.dp, vertical = 8.dp),
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Hint("Erkennt runde Tempolimit-Schilder (10–130). Nur als Hinweis – es gilt immer die echte Beschilderung.")

                    Spacer(Modifier.height(12.dp))
                    DashcamSettings(vm)

                    Spacer(Modifier.height(12.dp))
                    Section("Tempolimits aus Karte (GPS)")
                    val ms by vm.map.state.collectAsStateWithLifecycle()
                    SwitchRow("Tempolimits aus OpenStreetMap", settings.mapLimits) { on -> vm.update { it.copy(mapLimits = on) } }
                    if (settings.mapLimits) {
                        Hint(buildString {
                            append(ms.gps)
                            if (ms.running) append(" · ${ms.tiles} Kartenstücke" + if (ms.loading) " (lade …)" else "")
                        })
                        if (ms.hasFix) Hint(
                            "Straße: " + (ms.road ?: "nicht zugeordnet") + " · " + when {
                                ms.limit == null -> "kein Limit eingetragen"
                                ms.limit!!.none -> "ohne Limit"
                                ms.limit!!.kmh != null -> "${ms.limit!!.kmh} km/h" + (if (ms.limit!!.estimated) " (geschätzt)" else "") + (ms.limit!!.note?.let { " $it" } ?: "")
                                else -> "Wechselverkehrszeichen"
                            }
                        )
                        ms.error?.let { Hint("⚠ $it – bereits geladene Strecken funktionieren weiter.") }
                        val ctx = LocalContext.current
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextButton(onClick = {
                                runCatching {
                                    ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                                }
                            }) { Text("Standort-Einstellungen", color = J.accent) }
                            TextButton(onClick = { vm.restartGps() }) { Text("GPS neu starten", color = J.accent) }
                            TextButton(onClick = { vm.map.clearCache() }) { Text("Kartenspeicher leeren", color = J.dim) }
                        }
                        Hint("Erster Start nach langer Standzeit kann einige Minuten dauern (Radio-GPS hat oft keine Online-Hilfe wie ein Handy). Bleibt „0 sichtbar“, ist meist die GPS-Antenne nicht angeschlossen oder verdeckt.")
                    }
                    Hint("Kamera-Schilder haben Vorrang. Nach dem Abbiegen oder wenn laut Karte ein neuer Abschnitt beginnt, gilt wieder die Karte. Geschätzte Werte (≈) lösen keine Warnung aus. Daten werden für ~2×2 km geladen und 30 Tage gespeichert.")
                    Hint("Kartendaten © OpenStreetMap-Mitwirkende (ODbL)")

                    Spacer(Modifier.height(12.dp))
                    Section("Reifendruck (TPMS)")
                    SwitchRow("USB-Empfänger verwenden", settings.tpms) { on -> vm.update { it.copy(tpms = on) } }
                    Hint(tpmsStatus.message + (tpmsStatus.device?.let { " · $it" } ?: "") +
                        (if (tpmsStatus.genuine == false) " · Kennung abweichend" else ""))
                    Hint("Wichtig: Die alte TPMS-App darf nicht gleichzeitig laufen – sonst ist der Empfänger belegt.")
                    Text("Solldruck vorn: ${fmtBar(settings.sollFront)} bar", color = J.text, fontSize = 15.sp)
                    Slider(
                        value = settings.sollFront,
                        onValueChange = { f -> vm.update { it.copy(sollFront = (f * 10).roundToInt() / 10f) } },
                        valueRange = 1.8f..3.4f,
                    )
                    Text("Solldruck hinten: ${fmtBar(settings.sollRear)} bar", color = J.text, fontSize = 15.sp)
                    Slider(
                        value = settings.sollRear,
                        onValueChange = { f -> vm.update { it.copy(sollRear = (f * 10).roundToInt() / 10f) } },
                        valueRange = 1.8f..3.4f,
                    )
                    Text("Warnung ab ${fmtBar(settings.tpmsDelta)} bar unter Soll", color = J.text, fontSize = 15.sp)
                    Slider(
                        value = settings.tpmsDelta,
                        onValueChange = { f -> vm.update { it.copy(tpmsDelta = (f * 10).roundToInt() / 10f) } },
                        valueRange = 0.1f..0.6f,
                    )
                    Hint("Solldruck steht auf dem Aufkleber im Tankdeckel (für normale Beladung).")
                    Spacer(Modifier.height(12.dp))
                    Section("Kamera einmessen")
                    Text("Einbauhöhe: ${String.format(Locale.GERMANY, "%.2f", settings.camHeight)} m", color = J.text, fontSize = 15.sp)
                    Slider(
                        value = settings.camHeight,
                        onValueChange = { f -> vm.update { it.copy(camHeight = (f * 20).roundToInt() / 20f) } },
                        valueRange = 0.8f..1.6f,
                    )
                    Text("Blickwinkel: ${settings.camFov.roundToInt()}°", color = J.text, fontSize = 15.sp)
                    Slider(
                        value = settings.camFov,
                        onValueChange = { f -> vm.update { it.copy(camFov = (f / 5).roundToInt() * 5f) } },
                        valueRange = 60f..140f,
                    )
                    Hint("Höhe = Kameralinse über der Straße (hinter dem Spiegel ca. 1,25 m). Blickwinkel steht im Datenblatt der Kamera (horizontal). Den Horizont stellst du im Kamera-Vollbild ein: Spuransicht → Kamera → aufs Bild tippen.")

                    Spacer(Modifier.height(12.dp))
                    Section("Fahrzeug")
                    Hint("Mercedes-Benz W204 C 200 CDI (2010, Automatik) · OM651\nDiagnose: ISO 15765-4 CAN, 11 Bit, 500 kBaud")
                    Hint("App-Version ${de.jarvis.w204.BuildConfig.VERSION_NAME}")
                }
            }
        }
    }
}

@Composable
private fun ScanOption(title: String, badge: String?, text: String, enabled: Boolean, highlight: Boolean = false, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 3.dp).clip(RoundedCornerShape(10.dp))
            .background(if (highlight) J.accent.copy(alpha = 0.14f) else J.panel2)
            .then(if (highlight) Modifier.border(1.dp, J.accent, RoundedCornerShape(10.dp)) else Modifier)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = if (enabled) J.text else J.dim, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            if (badge != null) {
                Spacer(Modifier.width(8.dp))
                Text(
                    badge, color = androidx.compose.ui.graphics.Color.Black, fontSize = 10.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(J.accent).padding(horizontal = 6.dp, vertical = 1.dp),
                )
            }
            Spacer(Modifier.weight(1f))
            Text("Starten ›", color = if (enabled) J.accent else J.dim, fontSize = 14.sp)
        }
        Text(text, color = J.dim, fontSize = 13.sp)
    }
}

@Composable
private fun Section(title: String) {
    Text(title.uppercase(), color = J.accent, fontSize = 13.sp, letterSpacing = 1.5.sp, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(6.dp))
}

@Composable
private fun Hint(text: String) {
    Text(text, color = J.dim, fontSize = 13.sp, modifier = Modifier.padding(vertical = 2.dp))
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = J.text, fontSize = 15.sp, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}


/** Stimme für die Ansagen wählen (alle deutschen Stimmen der installierten Sprachausgabe) */
@Composable
private fun VoicePicker(vm: MainViewModel) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val voices by vm.jarvis.voices.collectAsStateWithLifecycle()
    val engines = remember(voices) { vm.jarvis.engines() }
    val current = vm.jarvis.currentEngine()
    if (engines.size > 1) {
        Text("Sprachausgabe", color = J.text, fontSize = 15.sp, modifier = Modifier.padding(top = 4.dp))
        Column(Modifier.padding(vertical = 4.dp)) {
            engines.forEach { (pkg, label) ->
                val sel = pkg == current
                Text(
                    label, color = if (sel) Color.Black else J.text, fontSize = 14.sp,
                    modifier = Modifier.padding(vertical = 2.dp).clip(RoundedCornerShape(50))
                        .background(if (sel) J.accent else J.panel2)
                        .clickable { vm.update { it.copy(ttsEngine = pkg, ttsVoice = null) } }
                        .padding(horizontal = 14.dp, vertical = 6.dp),
                )
            }
        }
    }
    Text("Stimme", color = J.text, fontSize = 15.sp, modifier = Modifier.padding(top = 4.dp))
    if (voices.isEmpty()) Hint("Keine deutsche Stimme zur Auswahl gefunden – es wird die Standardstimme benutzt.")
    Column(Modifier.padding(vertical = 4.dp)) {
        voices.forEach { v ->
            val sel = v.name == settings.ttsVoice
            Text(
                v.label, color = if (sel) Color.Black else J.text, fontSize = 14.sp,
                modifier = Modifier.padding(vertical = 2.dp).clip(RoundedCornerShape(50))
                    .background(if (sel) J.accent else J.panel2)
                    .clickable {
                        vm.update { it.copy(ttsVoice = v.name) }
                        vm.jarvis.setVoice(v.name)
                        vm.jarvis.preview()
                    }
                    .padding(horizontal = 14.dp, vertical = 6.dp),
            )
        }
    }
    Text(
        "Probe hören", color = J.accent, fontSize = 14.sp,
        modifier = Modifier.clickable { vm.jarvis.preview() }.padding(vertical = 6.dp),
    )
    Hint("Antippen = auswählen und Probe hören. Die natürlichsten Stimmen hat die App „Sprachausgabe von Google“ (Play Store): danach in den Android-Einstellungen unter Sprache → Sprachausgabe die deutsche Stimme herunterladen. Stimmen mit „braucht Internet“ klingen am besten, fallen ohne Netz aber auf eine einfache Stimme zurück.")
}


/** Volta: 3D-Modell und Musik-Anzeige */
@Composable
private fun VoltaSettings(vm: MainViewModel) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val ver by vm.carVersion.collectAsStateWithLifecycle()
    val allowed by vm.nowPlaying.allowed.collectAsStateWithLifecycle()
    var msg by remember { androidx.compose.runtime.mutableStateOf<String?>(null) }
    val has = remember(ver) { de.jarvis.w204.volta.CarModel.exists(ctx) }
    val pick = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val err = de.jarvis.w204.volta.CarModel.import(ctx, uri)
            msg = err ?: "3D-Modell geladen."
            if (err == null) vm.carVersion.value++
        }
    }
    LaunchedEffect(Unit) { vm.nowPlaying.refresh() }
    Spacer(Modifier.height(6.dp))
    Hint(if (has) "3D-Modell: geladen" else "3D-Modell: keins geladen")
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        TextButton(onClick = { pick.launch(arrayOf("*/*")) }) { Text(if (has) "Anderes Modell laden" else "3D-Modell laden (.glb)") }
        if (has) TextButton(onClick = { de.jarvis.w204.volta.CarModel.remove(ctx); vm.carVersion.value++; msg = "Modell entfernt." }) { Text("Entfernen", color = J.dim) }
    }
    msg?.let { Hint(it) }
    Hint("Tipp: Auf sketchfab.com nach „Mercedes W204“ suchen, ein kostenloses Modell im Format glTF/GLB herunterladen (Lizenz beachten) und hier laden. Am besten unter 30 MB.")
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(if (allowed) "Musik-Anzeige: erlaubt" else "Musik-Anzeige: Zugriff fehlt", color = if (allowed) J.ok else J.warn, fontSize = 13.sp, modifier = Modifier.weight(1f))
        TextButton(onClick = { vm.nowPlaying.openAccessSettings() }) { Text(if (allowed) "Ändern" else "Erlauben") }
    }
}
