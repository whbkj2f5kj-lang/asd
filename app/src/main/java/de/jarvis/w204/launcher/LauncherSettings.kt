package de.jarvis.w204.launcher

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.jarvis.w204.MainViewModel
import de.jarvis.w204.ui.J
import kotlinx.coroutines.delay

/** Einstellungsbereich "Startbildschirm" */
@Composable
fun LauncherSettings(vm: MainViewModel) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    var isDefault by remember { mutableStateOf(vm.apps.isDefaultHome()) }
    var exported by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        while (true) { delay(1500); isDefault = vm.apps.isDefaultHome() }
    }
    val homes = remember { vm.apps.otherHomes() }

    Text("STARTBILDSCHIRM", color = J.accent, fontSize = 13.sp, letterSpacing = 1.5.sp, fontWeight = FontWeight.Bold)
    Spacer(Modifier.size(6.dp))
    Text(
        if (isDefault) "✓ Dashboard ist der Startbildschirm des Radios (Home-Taste)."
        else "Das Dashboard ist noch nicht der Startbildschirm. Unten antippen und „Dashboard W204“ wählen (falls gefragt: „Immer“).",
        color = if (isDefault) J.ok else J.dim, fontSize = 13.sp,
    )
    Row(Modifier.padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Btn(if (isDefault) "Startbildschirm ändern" else "Als Startbildschirm festlegen") { vm.apps.openHomeSettings() }
        homes.firstOrNull()?.let { h -> Btn("Original öffnen") { vm.apps.launchHome(h) } }
    }
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Beim Start gleich das Cockpit zeigen", color = J.text, fontSize = 15.sp, modifier = Modifier.weight(1f))
        Switch(checked = settings.startCockpit, onCheckedChange = { on -> vm.update { it.copy(startCockpit = on) } })
    }
    Row(Modifier.padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Btn("App-Liste exportieren") { vm.apps.export { exported = it ?: "Fehler beim Speichern" } }
    }
    exported?.let { Text("Gespeichert: $it – bitte an Claude schicken.", color = J.dim, fontSize = 13.sp) }

    // Eigene Symbole
    var iconMsg by remember { mutableStateOf<String?>(null) }
    val custom by vm.apps.customIcons.collectAsStateWithLifecycle()
    val pick = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            iconMsg = "Lade …"
            vm.apps.importIcons(uri, ICON_IDS) { n -> iconMsg = if (n == 0) "Keine passenden Bilder gefunden." else "$n Symbole übernommen." }
        }
    }
    Row(Modifier.padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Btn("Eigene Symbole laden (Ordner wählen)") { pick.launch(null) }
        if (custom.isNotEmpty()) Btn("Eigene Symbole entfernen") { vm.apps.clearCustomIcons(); iconMsg = "Entfernt – wieder Standard-Symbole." }
    }
    Text(
        "Bilder (PNG/JPG, am besten mit durchsichtigem Hintergrund) in einen Ordner legen und so benennen: " +
            ICON_IDS.joinToString(", ") { "$it.png" } + ". Fehlende Bilder bleiben Standard. " +
            "Die Bilder bleiben nur auf diesem Radio." + (if (custom.isNotEmpty()) " Aktiv: ${custom.keys.sorted().joinToString()}." else ""),
        color = J.dim, fontSize = 13.sp, modifier = Modifier.padding(vertical = 2.dp),
    )
    iconMsg?.let { Text(it, color = J.accent, fontSize = 13.sp) }
    Text(
        "Kacheln: lange drücken, um eine andere App zu wählen. Im App-Raster eine App lange drücken, um sie auf eine Kachel zu legen. " +
            "Der Original-Startbildschirm bleibt installiert: im App-Raster oben „Original-Startbildschirm“. " +
            "Stürzt das Dashboard zweimal kurz hintereinander ab, öffnet sich automatisch das Original.",
        color = J.dim, fontSize = 13.sp, modifier = Modifier.padding(vertical = 2.dp),
    )
    if (homes.isEmpty()) Text("Kein anderer Startbildschirm gefunden.", color = J.warn, fontSize = 13.sp)
}

@Composable
private fun Btn(label: String, onClick: () -> Unit) {
    Text(
        label, color = Color.Black, fontSize = 14.sp,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(J.accent).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 7.dp),
    )
}
