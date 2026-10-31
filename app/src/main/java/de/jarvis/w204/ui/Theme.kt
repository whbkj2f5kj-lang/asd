package de.jarvis.w204.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
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
    /** nur für das Fahrprogramm ECO (nicht auswählbar) */
    val eco = GaugePalette("Eco", Color(0xFFE3FFF2), Color(0xFF4ADE80), Color(0xFF0F7A4A), Color(0xFF34D399))
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

/**
 * Glas-Optik wie in OEM-Cockpits: leicht aufgehellte Fläche, oben eine feine Lichtkante,
 * nach unten auslaufend. (Echte Unschärfe dahinter gibt es erst ab Android 12.)
 */
fun Modifier.glass(shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(16.dp)): Modifier = this
    .clip(shape)
    .background(Brush.verticalGradient(listOf(Color(0x14FFFFFF), Color(0x06FFFFFF), Color(0x0AFFFFFF))))
    .border(1.dp, Brush.verticalGradient(listOf(Color(0x33FFFFFF), Color(0x0AFFFFFF), Color(0x14FFFFFF))), shape)

/** Feines Rauschen als Struktur im Hintergrund (verhindert "flache" Farbflächen und Farbstufen) */
@Composable
fun rememberNoiseBrush(): androidx.compose.ui.graphics.ShaderBrush {
    return androidx.compose.runtime.remember {
        val n = 96
        val rnd = java.util.Random(204)
        val px = IntArray(n * n) {
            val a = rnd.nextInt(14)            // sehr schwach
            (a shl 24) or 0xFFFFFF
        }
        val bmp = android.graphics.Bitmap.createBitmap(px, n, n, android.graphics.Bitmap.Config.ARGB_8888)
        androidx.compose.ui.graphics.ShaderBrush(
            androidx.compose.ui.graphics.ImageShader(
                bmp.asImageBitmap(),
                androidx.compose.ui.graphics.TileMode.Repeated,
                androidx.compose.ui.graphics.TileMode.Repeated,
            )
        )
    }
}

/** Farbwelt weich überblenden (z. B. beim Wechsel Komfort -> Sport) */
@Composable
fun animatePalette(target: GaugePalette): GaugePalette {
    val spec = androidx.compose.animation.core.tween<Color>(900)
    val l by androidx.compose.animation.animateColorAsState(target.light, spec, label = "pl")
    val m by androidx.compose.animation.animateColorAsState(target.mid, spec, label = "pm")
    val d by androidx.compose.animation.animateColorAsState(target.deep, spec, label = "pd")
    val g by androidx.compose.animation.animateColorAsState(target.glow, spec, label = "pg")
    return GaugePalette(target.name, l, m, d, g)
}

/** Seitlicher Abstand der Mitte zu den Instrumenten (Karte/Kamera liegen dahinter über die volle Breite) */
val LocalCenterInset = androidx.compose.runtime.compositionLocalOf { 0.dp }

/**
 * Weiche Ränder ohne Kante: blendet den Inhalt zu allen Seiten hin aus (Alpha-Maske).
 * Werte sind Anteile der Breite/Höhe: [x0..x1] wird links eingeblendet, [1-x1..1-x0] rechts ausgeblendet.
 * [area] begrenzt die Maske z. B. auf das eigentliche Kamerabild; alles außerhalb wird unsichtbar.
 */
fun Modifier.softEdges(
    x0: Float, x1: Float,
    top: Float, bottom: Float,
    area: (androidx.compose.ui.geometry.Size) -> androidx.compose.ui.geometry.Rect = { androidx.compose.ui.geometry.Rect(androidx.compose.ui.geometry.Offset.Zero, it) },
): Modifier = this
    .graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        val r = area(size)
        if (r.width <= 1f || r.height <= 1f) return@drawWithContent
        val clear = Color.Transparent
        val full = Color.Black
        drawRect(
            Brush.horizontalGradient(
                0f to clear, x0 to clear, x1.coerceAtLeast(x0 + 0.001f) to full,
                (1f - x1).coerceAtLeast(x1 + 0.001f) to full, (1f - x0).coerceAtLeast(x1 + 0.002f) to clear, 1f to clear,
                startX = r.left, endX = r.right,
            ),
            blendMode = androidx.compose.ui.graphics.BlendMode.DstIn,
        )
        drawRect(
            Brush.verticalGradient(
                0f to clear, top to full, (1f - bottom) to full, 1f to clear,
                startY = r.top, endY = r.bottom,
            ),
            blendMode = androidx.compose.ui.graphics.BlendMode.DstIn,
        )
    }
