package de.jarvis.w204.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** Farbwelt: tiefes Schwarz, kühles Blau/Cyan als Akzent (an MBUX/Tesla angelehnt). */
object J {
    val bg = Color(0xFF000000)
    val panel = Color(0xFF070A0E)
    val panel2 = Color(0xFF10161D)
    val track = Color(0xFF1B232C)
    val hairline = Color(0x0FFFFFFF)
    val accent = Color(0xFF39C6FF)
    val accent2 = Color(0xFF2A6CFF)
    val ok = Color(0xFF34D399)
    val warn = Color(0xFFFFB020)
    val danger = Color(0xFFFF4D4D)
    val cold = Color(0xFF5AA9FF)
    val text = Color(0xFFF2F5F8)
    val dim = Color(0xFF7B8794)
    val ownCar = Color(0xFFE4E9EE)
    val otherCar = Color(0xFF8E9AA6)
    val road = Color(0xFF0C1117)
}

/** Farbwelt der Instrumente (Füllung, Leuchten). */
data class GaugePalette(val name: String, val light: Color, val mid: Color, val deep: Color, val glow: Color)

object Palettes {
    val copper = GaugePalette("Kupfer", Color(0xFFF7E8DA), Color(0xFFDDA88A), Color(0xFF9E5D63), Color(0xFFFFB38A))
    val ice = GaugePalette("Eisblau", Color(0xFFE2F6FF), Color(0xFF5FB8FF), Color(0xFF1C4FD8), Color(0xFF3FA9FF))
    val red = GaugePalette("Sport-Rot", Color(0xFFFFDADA), Color(0xFFFF5A5F), Color(0xFF8E0E1E), Color(0xFFFF3B4A))
    val amber = GaugePalette("Amber", Color(0xFFFFF1CC), Color(0xFFFFB020), Color(0xFFB85C00), Color(0xFFFFB020))
    val all = listOf(copper, ice, red, amber)
}

val LocalGaugePalette = androidx.compose.runtime.staticCompositionLocalOf { Palettes.copper }

@Composable
fun JarvisTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = J.accent,
            onPrimary = Color.Black,
            secondary = J.accent2,
            background = J.bg,
            onBackground = J.text,
            surface = J.panel,
            onSurface = J.text,
        ),
        content = content,
    )
}

private val panelShape = RoundedCornerShape(24.dp)

fun Modifier.panel(): Modifier = this
    .clip(panelShape)
    .background(Brush.verticalGradient(listOf(J.panel2, J.panel)))
    .border(1.dp, J.hairline, panelShape)
