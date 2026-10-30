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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
                    Hint("Sucht Mercedes-spezifische Werte (DPF, Öltemperatur, Getriebe …) in Motor- und Getriebesteuergerät. Es wird ausschließlich gelesen – nichts gelöscht oder verändert. Zündung an bzw. Motor im Stand laufen lassen.")
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
                        TextButton(onClick = { vm.scanner.identify() }, enabled = !scan.busy) { Text("1. Erkennen") }
                        TextButton(onClick = { vm.scanner.scan(full = false) }, enabled = !scan.busy) { Text("2. Schnell-Scan") }
                        TextButton(onClick = { vm.scanner.scan(full = true) }, enabled = !scan.busy) { Text("Komplett") }
                    }
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
                        listOf("Modern", "Klassisch").forEachIndexed { i, label ->
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
                    Section("Ansagen")
                    SwitchRow("Sprachansagen", settings.voice) { on -> vm.update { it.copy(voice = on) } }
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
