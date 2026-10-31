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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
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
    /** -1/+1: Spurverlassenswarnung links/rechts -> Linie rot */
    departure: Int = 0,
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
    val heading by animateFloatAsState(scene.laneHeading.coerceIn(-0.15f, 0.15f), tween(300), label = "laneH")
    val curve by animateFloatAsState(scene.laneCurve.coerceIn(-0.01f, 0.01f), tween(300), label = "laneC")
    val measurer = rememberTextMeasurer()
    val glow = LocalGaugePalette.current.glow

    Canvas(
        modifier.clipToBounds().graphicsLayer {
            // Eigene Ebene, damit die Ränder echt transparent ausblenden können
            if (transparent) compositingStrategy = CompositingStrategy.Offscreen
        }
    ) {
        val cam = ChaseCam(size.width, size.height)
        val zNear = -5f

        if (!transparent) {
            // Ferner Himmel/Straße: sanfter Verlauf zum Horizont
            drawRect(
                Brush.verticalGradient(
                    listOf(J.panel2, J.road),
                    startY = 0f,
                    endY = cam.horizonY + size.height * 0.15f,
                )
            )
        }

        // Spur folgt der erkannten Kurve
        val bend = { z: Float -> val zz = max(z, 0f); heading * zz + curve * zz * zz }
        val steps = (0..16).map { zNear + (FAR_Z - zNear) * it / 16f }

        // Eigene Spur leicht blau hinterlegt
        val lanePath = Path().apply {
            steps.forEachIndexed { i, z -> if (i == 0) moveTo(cam.x(laneL + bend(z), z), cam.y(z)) else lineTo(cam.x(laneL + bend(z), z), cam.y(z)) }
            steps.asReversed().forEach { z -> lineTo(cam.x(laneR + bend(z), z), cam.y(z)) }
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
            val p = Path().apply {
                steps.forEachIndexed { i, z -> if (i == 0) moveTo(cam.x(x + bend(z), z), cam.y(z)) else lineTo(cam.x(x + bend(z), z), cam.y(z)) }
            }
            drawPath(p, Color.White.copy(alpha = 0.10f), style = Stroke(2f))
        }

        // Eigene Spurlinien, gestrichelt und mitlaufend (rot bei Spurverlassenswarnung)
        val warnColor = Color(0xFFFF3B30)
        drawDashes(cam, laneL, phase, laneAlpha, zNear, bend, if (departure < 0) warnColor else Color.White)
        drawDashes(cam, laneR, phase, laneAlpha, zNear, bend, if (departure > 0) warnColor else Color.White)

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
        drawOwnCar(cam.x(0f, OWN_REAR_Z), cam.y(OWN_REAR_Z), cam.scale(OWN_REAR_Z), glow)

        // Frei schwebend: Ränder weich ausblenden (radiert die Ebene zum Rand hin aus)
        if (transparent) {
            val w = size.width
            val h = size.height
            drawRect(
                Brush.verticalGradient(listOf(Color.Transparent, Color.Black), startY = h * 0.90f, endY = h),
                blendMode = BlendMode.DstOut,
            )
            drawRect(
                Brush.horizontalGradient(listOf(Color.Black, Color.Transparent), startX = 0f, endX = w * 0.14f),
                blendMode = BlendMode.DstOut,
            )
            drawRect(
                Brush.horizontalGradient(listOf(Color.Transparent, Color.Black), startX = w * 0.86f, endX = w),
                blendMode = BlendMode.DstOut,
            )
        }
    }
}

private fun DrawScope.drawDashes(
    cam: ChaseCam, x0: Float, phase: Float, alpha: Float, zNear: Float,
    bend: (Float) -> Float = { 0f }, color: Color = Color.White,
) {
    val half = if (color == Color.White) 0.07f else 0.11f
    var z = zNear - phase
    while (z < FAR_Z) {
        val z0 = max(z, zNear)
        val z1 = z + DASH_LEN
        if (z1 > zNear) {
            val xa = x0 + bend(z0)
            val xb = x0 + bend(z1)
            val p = Path().apply {
                moveTo(cam.x(xa - half, z0), cam.y(z0))
                lineTo(cam.x(xa + half, z0), cam.y(z0))
                lineTo(cam.x(xb + half, z1), cam.y(z1))
                lineTo(cam.x(xb - half, z1), cam.y(z1))
                close()
            }
            val fade = (1f - (z0 / FAR_Z)).coerceIn(0.15f, 1f)
            drawPath(p, color.copy(alpha = alpha * fade))
        }
        z += DASH_PERIOD
    }
}

private fun Color.darken(f: Float) = Color(red * f, green * f, blue * f, alpha)

/** Eigenes Auto von hinten: schwarzer Lack, Lichtkanten, durchgehendes Rücklicht, Farbschein darunter. */
private fun DrawScope.drawOwnCar(cx: Float, by: Float, s: Float, glow: Color) {
    val w = 1.82f * s
    val h = 1.45f * s
    val bodyH = h * 0.54f
    val bodyTop = by - bodyH

    // Farbschein auf der Straße
    for (k in 3 downTo 1) {
        drawOval(
            glow.copy(alpha = 0.07f * (4 - k)),
            topLeft = Offset(cx - w * (0.55f + 0.12f * k), by - h * (0.05f + 0.02f * k)),
            size = Size(w * (1.1f + 0.24f * k), h * (0.14f + 0.05f * k)),
        )
    }
    drawOval(Color.Black.copy(alpha = 0.75f), topLeft = Offset(cx - w * 0.56f, by - h * 0.06f), size = Size(w * 1.12f, h * 0.15f))

    // Räder
    val tyreW = w * 0.16f
    for (side in listOf(-1f, 1f)) {
        val x = if (side < 0) cx - w * 0.47f else cx + w * 0.47f - tyreW
        drawRoundRect(Color(0xFF040506), Offset(x, by - bodyH * 0.18f), Size(tyreW, bodyH * 0.22f), CornerRadius(tyreW * 0.25f))
    }

    // Dach/Kabine, leicht nach oben verjüngt
    val cabin = Path().apply {
        moveTo(cx - w * 0.43f, bodyTop + 2f)
        cubicTo(cx - w * 0.40f, by - h * 0.80f, cx - w * 0.34f, by - h * 0.97f, cx - w * 0.22f, by - h)
        lineTo(cx + w * 0.22f, by - h)
        cubicTo(cx + w * 0.34f, by - h * 0.97f, cx + w * 0.40f, by - h * 0.80f, cx + w * 0.43f, bodyTop + 2f)
        close()
    }
    drawPath(cabin, Brush.verticalGradient(listOf(Color(0xFF2A3139), Color(0xFF0C0F12)), startY = by - h, endY = bodyTop))
    // Heckscheibe mit Spiegelung
    val glass = Path().apply {
        moveTo(cx - w * 0.36f, bodyTop - h * 0.04f)
        cubicTo(cx - w * 0.33f, by - h * 0.78f, cx - w * 0.28f, by - h * 0.92f, cx - w * 0.19f, by - h * 0.94f)
        lineTo(cx + w * 0.19f, by - h * 0.94f)
        cubicTo(cx + w * 0.28f, by - h * 0.92f, cx + w * 0.33f, by - h * 0.78f, cx + w * 0.36f, bodyTop - h * 0.04f)
        close()
    }
    drawPath(glass, Brush.verticalGradient(listOf(Color(0xFF1B232C), Color(0xFF07090B)), startY = by - h, endY = bodyTop))
    drawPath(
        glass,
        Brush.linearGradient(
            listOf(Color.Transparent, glow.copy(alpha = 0.28f), Color.White.copy(alpha = 0.22f), Color.Transparent),
            start = Offset(cx - w * 0.36f, by - h * 0.94f),
            end = Offset(cx + w * 0.10f, bodyTop),
        ),
    )
    // Dachkante
    drawLine(Color.White.copy(alpha = 0.55f), Offset(cx - w * 0.21f, by - h + 1f), Offset(cx + w * 0.21f, by - h + 1f), strokeWidth = s * 0.025f, cap = StrokeCap.Round)

    // Karosserie: schwarzer Lack mit Glanzverlauf
    val body = Path().apply {
        addRoundRect(androidx.compose.ui.geometry.RoundRect(cx - w / 2f, bodyTop, cx + w / 2f, by - bodyH * 0.06f, CornerRadius(w * 0.11f)))
    }
    drawPath(body, Brush.verticalGradient(listOf(Color(0xFF3A434D), Color(0xFF171B20), Color(0xFF07080A)), startY = bodyTop, endY = by))
    // Lichtkanten links/rechts in Farbwelt
    drawPath(
        body,
        Brush.horizontalGradient(
            listOf(glow.copy(alpha = 0.9f), Color.Transparent, Color.Transparent, glow.copy(alpha = 0.9f)),
            startX = cx - w / 2f, endX = cx + w / 2f,
        ),
        style = Stroke(s * 0.03f),
    )
    // Schulterlinie
    drawLine(Color.White.copy(alpha = 0.35f), Offset(cx - w * 0.44f, bodyTop + bodyH * 0.10f), Offset(cx + w * 0.44f, bodyTop + bodyH * 0.10f), strokeWidth = s * 0.018f, cap = StrokeCap.Round)

    // Durchgehendes Rücklicht mit Leuchten
    val ty = bodyTop + bodyH * 0.24f
    val red = Color(0xFFFF2D3A)
    for (k in 3 downTo 1) {
        drawLine(red.copy(alpha = 0.12f * (4 - k)), Offset(cx - w * 0.43f, ty), Offset(cx + w * 0.43f, ty), strokeWidth = s * 0.045f * k, cap = StrokeCap.Round)
    }
    drawLine(red, Offset(cx - w * 0.44f, ty), Offset(cx + w * 0.44f, ty), strokeWidth = s * 0.03f, cap = StrokeCap.Round)
    drawLine(Color(0xFFFFB3B8), Offset(cx - w * 0.30f, ty), Offset(cx + w * 0.30f, ty), strokeWidth = s * 0.008f, cap = StrokeCap.Round)

    // Kennzeichenfeld + Diffusor
    drawRoundRect(Color(0xFF0B0E11), Offset(cx - w * 0.16f, bodyTop + bodyH * 0.42f), Size(w * 0.32f, bodyH * 0.18f), CornerRadius(s * 0.03f))
    drawRoundRect(Color.White.copy(alpha = 0.10f), Offset(cx - w * 0.16f, bodyTop + bodyH * 0.42f), Size(w * 0.32f, bodyH * 0.18f), CornerRadius(s * 0.03f), style = Stroke(s * 0.01f))
    drawLine(glow.copy(alpha = 0.5f), Offset(cx - w * 0.30f, by - bodyH * 0.14f), Offset(cx + w * 0.30f, by - bodyH * 0.14f), strokeWidth = s * 0.012f, cap = StrokeCap.Round)
}

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
