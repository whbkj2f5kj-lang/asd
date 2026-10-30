package de.jarvis.w204.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import de.jarvis.w204.vision.ObjKind
import de.jarvis.w204.vision.Scene
import kotlin.math.max
import kotlin.math.roundToInt

private const val DASH_PERIOD = 12f     // Leitlinie: 3 m Strich + 9 m Lücke (wie Landstraße)
private const val DASH_LEN = 3f
private const val OWN_REAR_Z = -2.6f    // Heck des eigenen Autos relativ zur Kamera
private const val FAR_Z = 90f

/** Virtuelle Verfolgerkamera schräg hinter dem eigenen Auto. */
private class ChaseCam(val w: Float, val h: Float) {
    private val horizon = h * 0.30f
    private val f = w * 1.44f
    private val back = 10f
    private val camH = 4.0f
    fun x(worldX: Float, z: Float) = w / 2f + f * worldX / (z + back)
    fun y(z: Float, height: Float = 0f) = horizon + f * (camH - height) / (z + back)
    fun scale(z: Float) = f / (z + back)
    val horizonY get() = horizon
}

/**
 * Fahrspur-Ansicht im Stil moderner Assistenzsysteme: eigenes Auto von hinten,
 * erkannte Spur, Fahrzeuge in geschätztem Abstand, Leitlinien laufen mit der echten Geschwindigkeit.
 */
@Composable
fun LaneView(
    scene: Scene,
    speedKmh: Int?,
    gapColor: Color,
    modifier: Modifier = Modifier,
    transparent: Boolean = false,
    animate: Boolean = true,
) {
    val speed by rememberUpdatedState((speedKmh ?: 0) / 3.6f)
    var phase by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(animate) {
        if (!animate) return@LaunchedEffect
        var last = 0L
        while (true) {
            withFrameNanos { t ->
                if (last != 0L) phase = (phase + speed * (t - last) / 1e9f) % DASH_PERIOD
                last = t
            }
        }
    }
    val laneL by animateFloatAsState(scene.laneLeft, tween(300), label = "laneL")
    val laneR by animateFloatAsState(scene.laneRight, tween(300), label = "laneR")
    val laneAlpha by animateFloatAsState(if (scene.laneDetected) 0.95f else 0.35f, tween(400), label = "laneA")
    val measurer = rememberTextMeasurer()

    Canvas(modifier) {
        val cam = ChaseCam(size.width, size.height)
        val zNear = -5f

        if (transparent) {
            // Schwebt frei im Cockpit: nur ein sanfter Lichtschein unter der Straße
            drawRect(
                Brush.verticalGradient(
                    listOf(Color.Transparent, J.accent.copy(alpha = 0.05f), Color.Transparent),
                    startY = cam.horizonY,
                    endY = size.height,
                )
            )
        } else {
            // Ferner Himmel/Straße: sanfter Verlauf zum Horizont
            drawRect(
                Brush.verticalGradient(
                    listOf(J.panel2, J.road),
                    startY = 0f,
                    endY = cam.horizonY + size.height * 0.15f,
                )
            )
        }

        // Eigene Spur leicht blau hinterlegt
        val lanePath = Path().apply {
            moveTo(cam.x(laneL, zNear), cam.y(zNear))
            lineTo(cam.x(laneR, zNear), cam.y(zNear))
            lineTo(cam.x(laneR, FAR_Z), cam.y(FAR_Z))
            lineTo(cam.x(laneL, FAR_Z), cam.y(FAR_Z))
            close()
        }
        drawPath(
            lanePath,
            Brush.verticalGradient(
                listOf(J.accent.copy(alpha = 0f), J.accent.copy(alpha = 0.16f)),
                startY = cam.y(FAR_Z),
                endY = size.height,
            ),
        )

        // Nachbarspuren (angedeutet)
        val laneW = laneR - laneL
        listOf(laneL - laneW, laneR + laneW).forEach { x ->
            drawLine(
                Color.White.copy(alpha = 0.10f),
                Offset(cam.x(x, zNear), cam.y(zNear)),
                Offset(cam.x(x, FAR_Z), cam.y(FAR_Z)),
                strokeWidth = 2f,
            )
        }

        // Eigene Spurlinien, gestrichelt und mitlaufend
        listOf(laneL, laneR).forEach { x -> drawDashes(cam, x, phase, laneAlpha, zNear) }

        // Andere Verkehrsteilnehmer, von hinten nach vorn gezeichnet
        val labelStyle = TextStyle(color = gapColor, fontSize = (size.width * 0.045f).toSp(), fontWeight = FontWeight.Medium)
        scene.objects.filter { it.z < FAR_Z }.sortedByDescending { it.z }.forEach { o ->
            val s = cam.scale(o.z)
            val color = when {
                o.lead -> gapColor
                o.kind == ObjKind.PERSON || o.kind == ObjKind.BICYCLE -> J.warn
                else -> J.otherCar
            }
            val bx = cam.x(o.x, o.z)
            val by = cam.y(o.z)
            drawVehicle(bx, by, s, o.kind, color)
            if (o.lead) {
                scene.leadDistance?.let { d ->
                    val layout = measurer.measure("${d.roundToInt()} m", labelStyle)
                    val top = by - o.kind.heightM * s - layout.size.height - 6f
                    drawText(layout, topLeft = Offset(bx - layout.size.width / 2f, top))
                }
            }
        }

        // Eigenes Auto
        drawVehicle(cam.x(0f, OWN_REAR_Z), cam.y(OWN_REAR_Z), cam.scale(OWN_REAR_Z), ObjKind.CAR, J.ownCar, own = true)
    }
}

private fun DrawScope.drawDashes(cam: ChaseCam, x: Float, phase: Float, alpha: Float, zNear: Float) {
    val half = 0.07f
    var z = zNear - phase
    while (z < FAR_Z) {
        val z0 = max(z, zNear)
        val z1 = z + DASH_LEN
        if (z1 > zNear) {
            val p = Path().apply {
                moveTo(cam.x(x - half, z0), cam.y(z0))
                lineTo(cam.x(x + half, z0), cam.y(z0))
                lineTo(cam.x(x + half, z1), cam.y(z1))
                lineTo(cam.x(x - half, z1), cam.y(z1))
                close()
            }
            val fade = (1f - (z0 / FAR_Z)).coerceIn(0.15f, 1f)
            drawPath(p, Color.White.copy(alpha = alpha * fade))
        }
        z += DASH_PERIOD
    }
}

private fun Color.darken(f: Float) = Color(red * f, green * f, blue * f, alpha)

/** Fahrzeug von hinten, (cx, by) = Mitte unten, s = Pixel pro Meter. */
private fun DrawScope.drawVehicle(cx: Float, by: Float, s: Float, kind: ObjKind, body: Color, own: Boolean = false) {
    val w = kind.widthM * s
    val h = kind.heightM * s

    // Schatten
    drawOval(Color.Black.copy(alpha = 0.55f), topLeft = Offset(cx - w * 0.62f, by - h * 0.07f), size = Size(w * 1.24f, h * 0.18f))

    when (kind) {
        ObjKind.CAR -> {
            val bodyH = h * 0.55f
            val cabin = Path().apply {
                moveTo(cx - w * 0.42f, by - bodyH + 1f)
                lineTo(cx - w * 0.31f, by - h)
                lineTo(cx + w * 0.31f, by - h)
                lineTo(cx + w * 0.42f, by - bodyH + 1f)
                close()
            }
            drawPath(cabin, body.darken(0.8f))
            val glass = Path().apply {
                moveTo(cx - w * 0.36f, by - bodyH - h * 0.03f)
                lineTo(cx - w * 0.28f, by - h * 0.93f)
                lineTo(cx + w * 0.28f, by - h * 0.93f)
                lineTo(cx + w * 0.36f, by - bodyH - h * 0.03f)
                close()
            }
            drawPath(glass, Color(0xFF0B1015))
            drawRoundRect(body, Offset(cx - w / 2f, by - bodyH), Size(w, bodyH * 0.9f), CornerRadius(w * 0.12f))
            // Räder
            val tyreW = w * 0.17f
            drawRoundRect(Color(0xFF050709), Offset(cx - w * 0.47f, by - bodyH * 0.16f), Size(tyreW, bodyH * 0.2f), CornerRadius(tyreW * 0.2f))
            drawRoundRect(Color(0xFF050709), Offset(cx + w * 0.47f - tyreW, by - bodyH * 0.16f), Size(tyreW, bodyH * 0.2f), CornerRadius(tyreW * 0.2f))
            // Rückleuchten (eigenes Auto: durchgehendes Leuchtband)
            val tlH = max(1.5f, bodyH * 0.13f)
            val ty = by - bodyH * 0.74f
            val red = Color(0xFFFF2D3A)
            if (own) {
                drawRoundRect(red, Offset(cx - w * 0.45f, ty), Size(w * 0.9f, tlH * 0.6f), CornerRadius(tlH))
            }
            drawRoundRect(red, Offset(cx - w * 0.46f, ty), Size(w * 0.2f, tlH), CornerRadius(tlH / 2f))
            drawRoundRect(red, Offset(cx + w * 0.26f, ty), Size(w * 0.2f, tlH), CornerRadius(tlH / 2f))
        }
        ObjKind.TRUCK -> {
            drawRoundRect(body.darken(0.85f), Offset(cx - w / 2f, by - h), Size(w, h * 0.96f), CornerRadius(w * 0.05f))
            drawLine(body.darken(0.6f), Offset(cx, by - h * 0.92f), Offset(cx, by - h * 0.1f), strokeWidth = max(1f, w * 0.02f))
            val tlH = max(1.5f, h * 0.04f)
            drawRect(Color(0xFFFF2D3A), Offset(cx - w * 0.46f, by - h * 0.12f), Size(w * 0.12f, tlH))
            drawRect(Color(0xFFFF2D3A), Offset(cx + w * 0.34f, by - h * 0.12f), Size(w * 0.12f, tlH))
        }
        ObjKind.MOTORBIKE, ObjKind.BICYCLE -> {
            drawLine(body, Offset(cx, by), Offset(cx, by - h * 0.45f), strokeWidth = w * 0.35f, cap = StrokeCap.Round)
            drawLine(body, Offset(cx, by - h * 0.45f), Offset(cx, by - h * 0.82f), strokeWidth = w * 0.55f, cap = StrokeCap.Round)
            drawCircle(body, w * 0.22f, Offset(cx, by - h * 0.9f))
        }
        ObjKind.PERSON -> {
            drawLine(body, Offset(cx, by - h * 0.05f), Offset(cx, by - h * 0.78f), strokeWidth = w * 0.6f, cap = StrokeCap.Round)
            drawCircle(body, w * 0.26f, Offset(cx, by - h * 0.9f))
        }
    }
}
