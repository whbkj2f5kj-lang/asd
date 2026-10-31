package de.jarvis.w204.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.jarvis.w204.MainViewModel

/**
 * REC-Knopf in der oberen Leiste.
 * Antippen: Dashcam aus -> einschalten, Dashcam an -> aktuelles Video sichern. Lange drücken: ausschalten.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DashcamButton(vm: MainViewModel) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val st by vm.dashcam.status.collectAsStateWithLifecycle()
    val pulse = rememberInfiniteTransition(label = "rec")
    val a by pulse.animateFloat(1f, 0.25f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "recA")
    val on = settings.dashcam
    val recording = on && st.recording
    Row(
        Modifier.clip(RoundedCornerShape(50))
            .background(if (recording) Color(0x33FF3B30) else J.panel2)
            .combinedClickable(onClick = { vm.dashcamTap() }, onLongClick = { if (on) vm.update { it.copy(dashcam = false) } })
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(9.dp).clip(CircleShape)
                .then(if (recording) Modifier.alpha(a) else Modifier)
                .background(if (recording) Color(0xFFFF3B30) else if (on) J.warn else J.dim)
        )
        Spacer(Modifier.width(6.dp))
        Text(
            when {
                recording -> "REC"
                on -> "CAM …"
                else -> "CAM"
            },
            color = if (recording) Color(0xFFFF8A80) else J.dim,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

/** Einstellungsbereich "Dashcam" */
@Composable
fun DashcamSettings(vm: MainViewModel) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val st by vm.dashcam.status.collectAsStateWithLifecycle()
    val volumes = remember { vm.dashcam.volumes() }

    Text("DASHCAM", color = J.accent, fontSize = 13.sp, letterSpacing = 1.5.sp, fontWeight = FontWeight.Bold)
    Spacer(Modifier.size(6.dp))
    DcSwitch("Dashcam aufnehmen", settings.dashcam) { on -> vm.update { it.copy(dashcam = on) } }
    if (settings.dashcam) {
        DcHint(buildString {
            append(st.message)
            if (st.recording) append(" · ${st.size} · ${st.fps} Bilder/s")
            append(" · Ring ${st.ringCount}/${settings.dashcamLoop} Min · ${st.savedCount} gesichert")
        })
        st.lastSaved?.let { DcHint("Zuletzt gesichert: $it") }
        st.error?.let { DcHint("⚠ $it") }
    }

    Text("Ringspeicher (wird laufend überschrieben)", color = J.text, fontSize = 15.sp, modifier = Modifier.padding(top = 6.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 4.dp)) {
        listOf(3 to "3 Min", 30 to "30 Min", 120 to "2 Std").forEach { (m, label) ->
            DcChip(label, settings.dashcamLoop == m) { vm.update { it.copy(dashcamLoop = m) } }
        }
    }
    DcSwitch("Bei starker Bremsung automatisch sichern", settings.dashcamAutoSave) { on -> vm.update { it.copy(dashcamAutoSave = on) } }
    DcSwitch("Uhrzeit & Tempo ins Video einblenden", settings.dashcamOverlay) { on -> vm.update { it.copy(dashcamOverlay = on) } }

    if (volumes.size > 1) {
        Text("Speicherort", color = J.text, fontSize = 15.sp, modifier = Modifier.padding(top = 6.dp))
        Column(Modifier.padding(vertical = 4.dp)) {
            volumes.forEach { (v, label) ->
                DcChip(label, settings.dashcamVolume == v, Modifier.padding(vertical = 2.dp)) { vm.update { it.copy(dashcamVolume = v) } }
            }
        }
    }
    DcHint("Videos: Movies/W204-Dashcam · gesicherte in …/Gesichert (werden nie automatisch gelöscht). Sichern: REC-Knopf antippen, „Video sichern“ sagen oder automatisch bei starker Bremsung – dann bleiben die letzte Minute davor und 30 s danach erhalten. REC lange drücken = Dashcam aus. Aufnahme nur, solange die App im Vordergrund ist; kein Ton.")
    DcHint("Hinweis Datenschutz: Dauerhaftes, anlassloses Filmen ist in Deutschland problematisch. Empfohlen ist ein kurzer Ringspeicher (3 Min) und Sichern nur bei einem Ereignis.")
}

@Composable
private fun DcSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = J.text, fontSize = 15.sp, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun DcHint(text: String) {
    Text(text, color = J.dim, fontSize = 13.sp, modifier = Modifier.padding(vertical = 2.dp))
}

@Composable
private fun DcChip(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Text(
        label,
        color = if (selected) Color.Black else J.text,
        fontSize = 14.sp,
        modifier = modifier.clip(RoundedCornerShape(50))
            .background(if (selected) J.accent else J.panel2)
            .then(if (selected) Modifier else Modifier.border(1.dp, J.track, RoundedCornerShape(50)))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 6.dp),
    )
}
