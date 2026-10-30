package de.jarvis.w204.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material3.Icon
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.jarvis.w204.MainViewModel
import de.jarvis.w204.obd.FoundDtc

fun severityColor(sev: Int): Color = when (sev) {
    3 -> J.danger
    2 -> J.warn
    1 -> J.accent
    else -> J.dim
}

@Composable
fun DiagnoseScreen(vm: MainViewModel, onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    val diag by vm.diag.collectAsStateWithLifecycle()
    val result = diag.result

    Surface(Modifier.fillMaxSize(), color = J.bg) {
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Diagnose", color = J.text, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Text(
                        "Fehlerspeicher von Motor und Getriebe – nur lesen, löschen ist bewusst nicht möglich.",
                        color = J.dim,
                        fontSize = 13.sp,
                    )
                }
                if (diag.busy) {
                    CircularProgressIndicator(Modifier.size(22.dp), color = J.accent, strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                } else {
                    TextButton(onClick = { vm.readFaults() }) {
                        Text(if (result == null) "FEHLER AUSLESEN" else "NEU AUSLESEN", color = J.accent, fontSize = 15.sp)
                    }
                }
                TextButton(onClick = onClose) { Text("FERTIG", color = J.text, fontSize = 15.sp) }
            }
            Spacer(Modifier.height(10.dp))

            diag.error?.let {
                Text(it, color = J.warn, fontSize = 15.sp, modifier = Modifier.padding(vertical = 6.dp))
            }

            if (result == null && !diag.busy && diag.error == null) {
                Text(
                    "Zündung an bzw. Motor laufen lassen, mit dem OBD-Adapter verbinden und „Fehler auslesen“ antippen. " +
                        "Du kannst auch fragen: „Gibt es Fehler?“",
                    color = J.dim,
                    fontSize = 15.sp,
                    modifier = Modifier.padding(vertical = 12.dp),
                )
            }
            if (diag.busy) {
                Text("Lese Fehlerspeicher … (ca. 10–20 Sekunden)", color = J.dim, fontSize = 15.sp)
            }

            if (result != null) {
                SummaryRow(result.mil, result.codes, diag.readAt)
                result.notes.forEach { Text("• $it", color = J.dim, fontSize = 13.sp) }
                Spacer(Modifier.height(8.dp))
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f)) {
                    items(result.codes, key = { it.code + it.source + it.state }) { DtcCard(it) }
                    item {
                        Text(
                            "Hinweis: Die Erklärungen sind allgemeine Hinweise für den OM651 und ersetzen keine Werkstattdiagnose. " +
                                "Bei roten Einträgen, Warnleuchten im Kombiinstrument oder spürbaren Problemen bitte sicher anhalten bzw. prüfen lassen.",
                            color = J.dim,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(vertical = 12.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SummaryRow(mil: Boolean?, codes: List<FoundDtc>, at: String?) {
    val worst = codes.maxOfOrNull { it.info.severity } ?: -1
    val (color, text) = when {
        codes.isEmpty() && mil != true -> J.ok to "Keine Fehler gespeichert"
        codes.isEmpty() -> J.warn to "Motorkontrollleuchte an, aber kein Code gelesen"
        worst >= 3 -> J.danger to "${codes.size} Fehler – mindestens einer ist dringend"
        worst == 2 -> J.warn to "${codes.size} Fehler – zeitnah prüfen lassen"
        else -> J.accent to "${codes.size} Fehler – nichts Dringendes"
    }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(J.panel2).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(12.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(10.dp))
        Text(text, color = J.text, fontSize = 17.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
        mil?.let { Text(if (it) "Kontrollleuchte: AN" else "Kontrollleuchte: aus", color = if (it) J.warn else J.dim, fontSize = 13.sp) }
        at?.let {
            Spacer(Modifier.width(12.dp))
            Text("gelesen $it", color = J.dim, fontSize = 13.sp)
        }
    }
}

@Composable
private fun DtcCard(d: FoundDtc) {
    val info = d.info
    var open by remember { mutableStateOf(info.severity >= 2) }
    val color = severityColor(info.severity)
    Column(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(J.panel)
            .border(1.dp, color.copy(alpha = 0.45f), RoundedCornerShape(14.dp))
            .clickable { open = !open }
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(d.code, color = J.text, fontSize = 20.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(12.dp))
            Text(info.title, color = J.text, fontSize = 16.sp, modifier = Modifier.weight(1f))
            Text(
                info.severityText,
                color = Color.Black,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.clip(RoundedCornerShape(50)).background(color).padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }
        Text("${d.source} · ${d.state}" + (d.extra?.let { " · $it" } ?: ""), color = J.dim, fontSize = 12.sp)
        if (open) {
            Spacer(Modifier.height(8.dp))
            Label("Betrifft")
            Body(info.system)
            Label("Mögliche Ursachen")
            Body(info.causes)
            Label("Was tun?")
            Body(info.advice)
        } else {
            Text("Antippen für Erklärung", color = J.dim, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

@Composable
private fun Label(t: String) {
    Text(t.uppercase(), color = J.accent, fontSize = 11.sp, letterSpacing = 1.2.sp, modifier = Modifier.padding(top = 6.dp))
}

@Composable
private fun Body(t: String) {
    Text(t, color = J.text, fontSize = 14.sp)
}

/** Werkzeug-Knopf in der Kopfzeile, mit Zähler wenn Fehler gespeichert sind. */
@Composable
fun DiagButton(faults: Int, worst: Int, onClick: () -> Unit) {
    Box {
        Box(
            Modifier.size(38.dp).clip(CircleShape).background(J.panel2).clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.Build, contentDescription = "Diagnose", tint = if (faults > 0) severityColor(worst) else J.text, modifier = Modifier.size(18.dp))
        }
        if (faults > 0) {
            Text(
                faults.toString(),
                color = Color.Black,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.TopEnd).offset(x = 4.dp, y = (-2).dp)
                    .clip(CircleShape).background(severityColor(worst)).padding(horizontal = 5.dp, vertical = 1.dp),
            )
        }
    }
}
