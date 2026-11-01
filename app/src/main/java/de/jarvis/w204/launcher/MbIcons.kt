package de.jarvis.w204.launcher

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import kotlin.math.cos
import kotlin.math.sin

/**
 * Große Symbole im Stil der Radio-Startseite (eigene Zeichnungen: glänzend, mit Licht von oben).
 * Alle Funktionen zeichnen in die volle Fläche; Zeichenfläche etwa quadratisch.
 */
enum class MbIcon { NAVI, MEDIA, PHONE, COMFORT, CAR, VIDEO, APPS, CARPLAY, INFO, RADIO }

private val SILVER = listOf(Color(0xFFFFFFFF), Color(0xFFDDE4EA), Color(0xFF9AA6B2), Color(0xFFE9EEF2))

fun DrawScope.drawMbIcon(icon: MbIcon) = when (icon) {
    MbIcon.NAVI -> navArrow()
    MbIcon.MEDIA -> note()
    MbIcon.PHONE -> handset()
    MbIcon.COMFORT -> lotus()
    MbIcon.CAR -> carFront()
    MbIcon.VIDEO -> clapper()
    MbIcon.APPS -> appsCube()
    MbIcon.CARPLAY -> carplayTile()
    MbIcon.INFO -> gauge()
    MbIcon.RADIO -> tower()
}

private fun DrawScope.p(x: Float, y: Float) = Offset(size.width * x, size.height * y)

// ---------------------------------------------------------------- Navigation: 3D-Pfeil
private fun DrawScope.navArrow() {
    val tip = p(0.5f, 0.06f); val l = p(0.16f, 0.92f); val n = p(0.5f, 0.72f); val r = p(0.84f, 0.92f)
    val left = Path().apply { moveTo(tip.x, tip.y); lineTo(l.x, l.y); lineTo(n.x, n.y); close() }
    val right = Path().apply { moveTo(tip.x, tip.y); lineTo(n.x, n.y); lineTo(r.x, r.y); close() }
    drawPath(left, Brush.linearGradient(listOf(Color(0xFFFFC56B), Color(0xFFFF6A22), Color(0xFFE5361A)), tip, l))
    drawPath(right, Brush.linearGradient(listOf(Color(0xFFE8451E), Color(0xFFB3170C), Color(0xFF7E0E07)), tip, r))
    // Unterkante (Dicke)
    val lb = p(0.17f, 0.96f); val nb = p(0.5f, 0.78f); val rb = p(0.83f, 0.96f)
    drawPath(Path().apply { moveTo(l.x, l.y); lineTo(n.x, n.y); lineTo(nb.x, nb.y); lineTo(lb.x, lb.y); close() }, Color(0xFF9C1C0C))
    drawPath(Path().apply { moveTo(n.x, n.y); lineTo(r.x, r.y); lineTo(rb.x, rb.y); lineTo(nb.x, nb.y); close() }, Color(0xFF5E0904))
    // Glanzkante
    drawLine(Brush.linearGradient(listOf(Color(0xCCFFFFFF), Color(0x00FFFFFF)), tip, n), tip, n, size.width * 0.012f)
}

// ---------------------------------------------------------------- Medien: Notenpaar
private fun DrawScope.note() {
    val w = size.width; val h = size.height
    val path = Path().apply {
        // Balken
        moveTo(w * 0.34f, h * 0.18f); lineTo(w * 0.86f, h * 0.06f); lineTo(w * 0.86f, h * 0.18f); lineTo(w * 0.34f, h * 0.30f); close()
        // Hälse
        addRect(androidx.compose.ui.geometry.Rect(w * 0.31f, h * 0.20f, w * 0.38f, h * 0.80f))
        addRect(androidx.compose.ui.geometry.Rect(w * 0.79f, h * 0.10f, w * 0.86f, h * 0.70f))
        // Köpfe
        addOval(androidx.compose.ui.geometry.Rect(w * 0.08f, h * 0.70f, w * 0.38f, h * 0.92f))
        addOval(androidx.compose.ui.geometry.Rect(w * 0.56f, h * 0.60f, w * 0.86f, h * 0.82f))
    }
    drawPath(path, Color(0x55FF4FD8), style = Stroke(w * 0.07f))
    drawPath(path, Brush.linearGradient(listOf(Color.White, Color(0xFFF4EEFF), Color(0xFFCFC2E8)), Offset(0f, 0f), Offset(w, h)))
    drawPath(path, Color(0xFFFF5FDF), style = Stroke(w * 0.018f))
    // Glanz auf dem Balken
    drawLine(Color(0xAAFFFFFF), Offset(w * 0.37f, h * 0.2f), Offset(w * 0.83f, h * 0.095f), w * 0.02f, StrokeCap.Round)
}

// ---------------------------------------------------------------- Telefon: Hörer + Funkwellen
private fun DrawScope.handset() {
    val w = size.width; val h = size.height
    val body = Path().apply {
        moveTo(w * 0.10f, h * 0.84f)
        cubicTo(w * 0.08f, h * 0.60f, w * 0.30f, h * 0.30f, w * 0.62f, h * 0.20f)
        cubicTo(w * 0.70f, h * 0.17f, w * 0.80f, h * 0.18f, w * 0.84f, h * 0.24f)
        cubicTo(w * 0.86f, h * 0.30f, w * 0.80f, h * 0.33f, w * 0.72f, h * 0.33f)
        cubicTo(w * 0.52f, h * 0.36f, w * 0.30f, h * 0.56f, w * 0.24f, h * 0.84f)
        cubicTo(w * 0.22f, h * 0.92f, w * 0.12f, h * 0.92f, w * 0.10f, h * 0.84f)
        close()
    }
    drawPath(body, Brush.linearGradient(SILVER, Offset(w * 0.1f, h * 0.2f), Offset(w * 0.6f, h * 0.9f)))
    drawPath(body, Color(0x66FFFFFF), style = Stroke(w * 0.01f))
    drawPath(Path().apply {
        moveTo(w * 0.16f, h * 0.78f); cubicTo(w * 0.18f, h * 0.58f, w * 0.36f, h * 0.36f, w * 0.64f, h * 0.25f)
    }, Color(0xCCFFFFFF), style = Stroke(w * 0.022f, cap = StrokeCap.Round))
    // Wellen (waagerecht gestapelt, grün-türkis)
    val c = Offset(w * 0.56f, h * 0.52f)
    for (i in 0 until 4) {
        val r = w * (0.14f + i * 0.075f)
        val a = 0.95f - i * 0.2f
        drawArc(
            Color(0xFF3DF2B0).copy(alpha = a), -38f, 76f, false,
            topLeft = Offset(c.x - r, c.y - r * 0.55f), size = Size(r * 2, r * 1.1f),
            style = Stroke(w * 0.035f, cap = StrokeCap.Round),
        )
    }
}

// ---------------------------------------------------------------- Komfort: Lotusblüte
private fun DrawScope.lotus() {
    val w = size.width; val h = size.height
    val base = Offset(w * 0.5f, h * 0.80f)
    fun petal(angle: Float, len: Float, wid: Float, shade: Float) {
        val path = Path().apply {
            moveTo(0f, 0f)
            cubicTo(-wid, -len * 0.35f, -wid * 0.7f, -len * 0.8f, 0f, -len)
            cubicTo(wid * 0.7f, -len * 0.8f, wid, -len * 0.35f, 0f, 0f)
            close()
        }
        translate(base.x, base.y) {
            rotate(angle, pivot = Offset.Zero) {
                drawPath(path, Brush.verticalGradient(listOf(Color.White, Color(0xFFF2F6FA), Color(0xFFB8C4CF).copy(alpha = 1f)), startY = -len, endY = 0f))
                if (shade > 0f) drawPath(path, Color.Black.copy(alpha = shade))
                drawPath(path, Color(0xFF9FB0BF), style = Stroke(w * 0.008f))
                drawLine(Color(0x55A0B0C0), Offset(0f, -len * 0.1f), Offset(0f, -len * 0.85f), w * 0.006f)
            }
        }
    }
    // hinten -> vorn
    petal(-84f, w * 0.40f, w * 0.10f, 0.10f); petal(84f, w * 0.40f, w * 0.10f, 0.10f)
    petal(-58f, w * 0.44f, w * 0.12f, 0.06f); petal(58f, w * 0.44f, w * 0.12f, 0.06f)
    petal(-30f, w * 0.52f, w * 0.13f, 0.03f); petal(30f, w * 0.52f, w * 0.13f, 0.03f)
    petal(0f, w * 0.60f, w * 0.14f, 0f)
    petal(-14f, w * 0.40f, w * 0.12f, 0f); petal(14f, w * 0.40f, w * 0.12f, 0f)
    // flache Blätter unten
    drawOval(Brush.verticalGradient(listOf(Color(0xFFE7EEF3), Color(0xFF8C9AA7))), topLeft = Offset(w * 0.16f, h * 0.76f), size = Size(w * 0.68f, h * 0.11f))
}

// ---------------------------------------------------------------- Fahrzeug: Limousine von vorn (ohne Markenzeichen)
private fun DrawScope.carFront() {
    val w = size.width; val h = size.height
    val body = Path().apply {
        moveTo(w * 0.04f, h * 0.80f); lineTo(w * 0.04f, h * 0.60f)
        cubicTo(w * 0.05f, h * 0.52f, w * 0.10f, h * 0.49f, w * 0.18f, h * 0.48f)
        lineTo(w * 0.27f, h * 0.30f)
        cubicTo(w * 0.40f, h * 0.24f, w * 0.60f, h * 0.24f, w * 0.73f, h * 0.30f)
        lineTo(w * 0.82f, h * 0.48f)
        cubicTo(w * 0.90f, h * 0.49f, w * 0.95f, h * 0.52f, w * 0.96f, h * 0.60f)
        lineTo(w * 0.96f, h * 0.80f); close()
    }
    // Räder
    drawRoundRect(Color(0xFF1C2228), Offset(w * 0.08f, h * 0.72f), Size(w * 0.14f, h * 0.16f), CornerRadius(w * 0.03f))
    drawRoundRect(Color(0xFF1C2228), Offset(w * 0.78f, h * 0.72f), Size(w * 0.14f, h * 0.16f), CornerRadius(w * 0.03f))
    drawPath(body, Brush.verticalGradient(listOf(Color(0xFFF7F9FB), Color(0xFFD5DCE2), Color(0xFFA9B4BE), Color(0xFFEFF3F6)), startY = h * 0.25f, endY = h * 0.8f))
    drawPath(body, Color(0x99FFFFFF), style = Stroke(w * 0.008f))
    // Scheibe
    val glass = Path().apply {
        moveTo(w * 0.30f, h * 0.32f); cubicTo(w * 0.42f, h * 0.28f, w * 0.58f, h * 0.28f, w * 0.70f, h * 0.32f)
        lineTo(w * 0.77f, h * 0.46f); lineTo(w * 0.23f, h * 0.46f); close()
    }
    drawPath(glass, Brush.verticalGradient(listOf(Color(0xFF8FA4B8), Color(0xFF3A4A5C), Color(0xFF1E2833)), startY = h * 0.3f, endY = h * 0.46f))
    drawLine(Color(0x88FFFFFF), Offset(w * 0.34f, h * 0.34f), Offset(w * 0.48f, h * 0.31f), w * 0.01f)
    // Motorhaube-Linien
    drawLine(Color(0x553A4652), Offset(w * 0.30f, h * 0.50f), Offset(w * 0.40f, h * 0.56f), w * 0.006f)
    drawLine(Color(0x553A4652), Offset(w * 0.70f, h * 0.50f), Offset(w * 0.60f, h * 0.56f), w * 0.006f)
    // Scheinwerfer
    val hl = Path().apply { moveTo(w * 0.08f, h * 0.58f); lineTo(w * 0.30f, h * 0.60f); lineTo(w * 0.28f, h * 0.65f); lineTo(w * 0.09f, h * 0.63f); close() }
    val hr = Path().apply { moveTo(w * 0.92f, h * 0.58f); lineTo(w * 0.70f, h * 0.60f); lineTo(w * 0.72f, h * 0.65f); lineTo(w * 0.91f, h * 0.63f); close() }
    for (x in listOf(hl, hr)) {
        drawPath(x, Brush.horizontalGradient(listOf(Color(0xFF2B3540), Color(0xFF55636F))))
        drawPath(x, Color(0xFFDFF4FF), style = Stroke(w * 0.007f))
    }
    // Kühlergrill: waagerechte Lamellen
    drawRoundRect(Color(0xFF242C35), Offset(w * 0.34f, h * 0.58f), Size(w * 0.32f, h * 0.12f), CornerRadius(w * 0.02f))
    for (i in 1..3) drawLine(Color(0xFFC8D2DA), Offset(w * 0.36f, h * (0.58f + i * 0.03f)), Offset(w * 0.64f, h * (0.58f + i * 0.03f)), w * 0.008f)
    // Lufteinlass
    drawRoundRect(Color(0xFF2A333C), Offset(w * 0.26f, h * 0.74f), Size(w * 0.48f, h * 0.05f), CornerRadius(w * 0.02f))
}

// ---------------------------------------------------------------- Video: Klappe + Filmrolle
private fun DrawScope.clapper() {
    val w = size.width; val h = size.height
    // Klappe (Brett)
    rotate(-8f, pivot = p(0.4f, 0.6f)) {
        drawRoundRect(Brush.verticalGradient(listOf(Color(0xFFE6EBEF), Color(0xFF8E99A3))), Offset(w * 0.06f, h * 0.38f), Size(w * 0.58f, h * 0.48f), CornerRadius(w * 0.02f))
        // Streifenleiste unten
        drawRect(Color(0xFF2B3036), Offset(w * 0.06f, h * 0.38f), Size(w * 0.58f, h * 0.09f))
        for (i in 0 until 5) {
            val x = w * (0.08f + i * 0.12f)
            drawPath(Path().apply { moveTo(x, h * 0.47f); lineTo(x + w * 0.05f, h * 0.38f); lineTo(x + w * 0.1f, h * 0.38f); lineTo(x + w * 0.05f, h * 0.47f); close() }, Color.White)
        }
    }
    // geöffnete Klappe
    rotate(-26f, pivot = p(0.08f, 0.36f)) {
        drawRect(Color(0xFF2B3036), Offset(w * 0.06f, h * 0.26f), Size(w * 0.58f, h * 0.09f))
        for (i in 0 until 5) {
            val x = w * (0.08f + i * 0.12f)
            drawPath(Path().apply { moveTo(x, h * 0.35f); lineTo(x + w * 0.05f, h * 0.26f); lineTo(x + w * 0.1f, h * 0.26f); lineTo(x + w * 0.05f, h * 0.35f); close() }, Color.White)
        }
    }
    // Filmrolle
    val c = p(0.70f, 0.58f); val r = w * 0.26f
    drawCircle(Brush.radialGradient(listOf(Color(0xFFFFFFFF), Color(0xFFB4BEC8), Color(0xFF6E7A86)), c, r), r, c)
    drawCircle(Color(0xFF4A545E), r, c, style = Stroke(w * 0.015f))
    for (i in 0 until 5) {
        val a = Math.toRadians((i * 72 - 90).toDouble())
        drawCircle(Color(0xFF39424B), r * 0.24f, Offset(c.x + (cos(a) * r * 0.55f).toFloat(), c.y + (sin(a) * r * 0.55f).toFloat()))
    }
    drawCircle(Color(0xFF39424B), r * 0.12f, c)
}

// ---------------------------------------------------------------- Apps: Glas-Kacheln mit Globus
private fun DrawScope.appsCube() {
    val w = size.width; val h = size.height
    fun tile(x: Float, y: Float, tw: Float, th: Float, c1: Color, c2: Color) =
        drawRoundRect(Brush.linearGradient(listOf(c1, c2), Offset(x, y), Offset(x + tw, y + th)), Offset(x, y), Size(tw, th), CornerRadius(tw * 0.14f))
    tile(w * 0.02f, h * 0.30f, w * 0.30f, h * 0.38f, Color(0xFFBFF1FF), Color(0xFF2A8FD0))
    tile(w * 0.68f, h * 0.30f, w * 0.30f, h * 0.38f, Color(0xFFBFF1FF), Color(0xFF2A8FD0))
    // kleine Zeichen auf den Seitenkacheln
    drawCircle(Color(0xCCFFFFFF), w * 0.05f, p(0.15f, 0.49f))
    drawCircle(Color(0xCCFFFFFF), w * 0.035f, p(0.84f, 0.44f)); drawOval(Color(0xCCFFFFFF), p(0.78f, 0.50f), Size(w * 0.12f, h * 0.10f))
    // Mittelkachel
    val o = p(0.24f, 0.16f); val sz = Size(w * 0.52f, h * 0.62f)
    drawRoundRect(Color(0x553FD8FF), o - Offset(w * 0.02f, w * 0.02f), Size(sz.width + w * 0.04f, sz.height + w * 0.04f), CornerRadius(w * 0.09f))
    drawRoundRect(Brush.verticalGradient(listOf(Color(0xFF3E5D84), Color(0xFF0E2340)), o.y, o.y + sz.height), o, sz, CornerRadius(w * 0.08f))
    drawRoundRect(Color(0xFFBFF4FF), o, sz, CornerRadius(w * 0.08f), style = Stroke(w * 0.014f))
    // Globus
    val c = p(0.5f, 0.47f); val r = w * 0.17f
    drawCircle(Brush.radialGradient(listOf(Color(0xFF9FE7FF), Color(0xFF2C7DC0)), c, r), r, c)
    drawCircle(Color.White, r, c, style = Stroke(w * 0.012f))
    drawOval(Color.White, Offset(c.x - r * 0.45f, c.y - r), Size(r * 0.9f, r * 2), style = Stroke(w * 0.01f))
    drawLine(Color.White, Offset(c.x - r, c.y), Offset(c.x + r, c.y), w * 0.01f)
    drawArc(Color.White, 200f, 140f, false, Offset(c.x - r * 1.25f, c.y - r * 0.8f), Size(r * 2.5f, r * 1.4f), style = Stroke(w * 0.012f))
}

// ---------------------------------------------------------------- CarPlay: leuchtende grüne Kachel
private fun DrawScope.carplayTile() {
    val w = size.width; val h = size.height
    val o = p(0.18f, 0.14f); val sz = Size(w * 0.64f, h * 0.70f)
    drawRoundRect(Brush.radialGradient(listOf(Color(0x8866FF88), Color(0x0066FF88)), p(0.5f, 0.5f), w * 0.5f), Offset.Zero, size, CornerRadius(w * 0.2f))
    drawRoundRect(Brush.verticalGradient(listOf(Color(0xFFB9FFC6), Color(0xFF4FE07A), Color(0xFF1DB24C)), o.y, o.y + sz.height), o, sz, CornerRadius(w * 0.14f))
    drawRoundRect(Brush.radialGradient(listOf(Color(0xFFFFFFFF), Color(0x00FFFFFF)), p(0.5f, 0.56f), w * 0.26f), o, sz, CornerRadius(w * 0.14f))
    drawRoundRect(Color(0xCCFFFFFF), o, sz, CornerRadius(w * 0.14f), style = Stroke(w * 0.012f))
    val tri = Path().apply { moveTo(w * 0.44f, h * 0.38f); lineTo(w * 0.62f, h * 0.49f); lineTo(w * 0.44f, h * 0.60f); close() }
    drawPath(tri, Color(0xFF1DA548).copy(alpha = 0.55f))
}

// ---------------------------------------------------------------- Info: Tacho
private fun DrawScope.gauge() {
    val w = size.width
    val c = p(0.5f, 0.52f); val r = w * 0.42f
    drawCircle(Brush.radialGradient(listOf(Color(0xFFFFFFFF), Color(0xFF9AA6B2), Color(0xFF404A54)), c, r), r, c)
    drawCircle(Brush.radialGradient(listOf(Color(0xFF3A4652), Color(0xFF12181E)), c, r * 0.86f), r * 0.86f, c)
    // rote Zone
    drawArc(Color(0xFFE53935), -40f, 75f, false, Offset(c.x - r * 0.74f, c.y - r * 0.74f), Size(r * 1.48f, r * 1.48f), style = Stroke(r * 0.12f))
    // Striche
    for (i in 0..20) {
        val a = Math.toRadians((135 + i * 13.5).toDouble())
        val long = i % 4 == 0
        val r1 = r * (if (long) 0.6f else 0.68f); val r2 = r * 0.8f
        drawLine(if (i > 15) Color(0xFFFF8A80) else Color.White,
            Offset(c.x + (cos(a) * r1).toFloat(), c.y + (sin(a) * r1).toFloat()),
            Offset(c.x + (cos(a) * r2).toFloat(), c.y + (sin(a) * r2).toFloat()), if (long) w * 0.016f else w * 0.008f)
    }
    // Nadel
    val na = Math.toRadians(30.0)
    drawLine(Color(0xFFFF7A2A), c, Offset(c.x + (cos(na) * r * 0.66f).toFloat(), c.y + (sin(na) * r * 0.66f).toFloat()), w * 0.025f, StrokeCap.Round)
    drawCircle(Brush.radialGradient(listOf(Color(0xFFE0E6EA), Color(0xFF59636D)), c, r * 0.14f), r * 0.14f, c)
    // Glas-Glanz
    drawArc(Color(0x33FFFFFF), 200f, 120f, true, Offset(c.x - r * 0.8f, c.y - r * 0.85f), Size(r * 1.6f, r * 1.2f))
}

// ---------------------------------------------------------------- Radio: Antenne mit Wellen
private fun DrawScope.tower() {
    val w = size.width; val h = size.height
    val top = p(0.5f, 0.18f)
    // Mast
    drawLine(Brush.horizontalGradient(SILVER, w * 0.46f, w * 0.54f), top, p(0.5f, 0.92f), w * 0.05f)
    for (i in 0 until 5) drawLine(Color(0xFF7D8894), p(0.47f, 0.32f + i * 0.12f), p(0.53f, 0.32f + i * 0.12f), w * 0.012f)
    drawRoundRect(Brush.horizontalGradient(SILVER, w * 0.42f, w * 0.58f), p(0.42f, 0.44f), Size(w * 0.16f, h * 0.07f), CornerRadius(w * 0.02f))
    drawCircle(Brush.radialGradient(listOf(Color.White, Color(0xFF9AA6B2)), top, w * 0.05f), w * 0.05f, top)
    // Wellen
    for (i in 0 until 3) {
        val r = w * (0.12f + i * 0.09f)
        val col = Color(0xFFFFC21A).copy(alpha = 1f - i * 0.22f)
        drawArc(col, 140f, 80f, false, Offset(top.x - r, top.y - r), Size(r * 2, r * 2), style = Stroke(w * 0.035f, cap = StrokeCap.Round))
        drawArc(col, -40f, 80f, false, Offset(top.x - r, top.y - r), Size(r * 2, r * 2), style = Stroke(w * 0.035f, cap = StrokeCap.Round))
    }
}

// ================================================================= kleine weiße Zeichen für die runden Knöpfe

enum class Glyph { PIN, HOME, PREV, NEXT, PLAY, CALLS, CONTACTS, GEAR, SOUND, MAP, INFO, GRID, GLOBE, PHONE, GAUGE, TIRE, BARS, LINK }

fun DrawScope.drawGlyph(g: Glyph, c: Color = Color.White) {
    val w = size.width; val h = size.height
    val st = Stroke(w * 0.12f, cap = StrokeCap.Round)
    when (g) {
        Glyph.PIN -> {
            drawCircle(c, w * 0.26f, p(0.5f, 0.38f), style = Stroke(w * 0.12f))
            drawPath(Path().apply { moveTo(w * 0.27f, h * 0.52f); lineTo(w * 0.5f, h * 0.95f); lineTo(w * 0.73f, h * 0.52f); close() }, c)
        }
        Glyph.HOME -> drawPath(Path().apply {
            moveTo(w * 0.5f, h * 0.08f); lineTo(w * 0.95f, h * 0.5f); lineTo(w * 0.82f, h * 0.5f); lineTo(w * 0.82f, h * 0.92f)
            lineTo(w * 0.6f, h * 0.92f); lineTo(w * 0.6f, h * 0.66f); lineTo(w * 0.4f, h * 0.66f); lineTo(w * 0.4f, h * 0.92f)
            lineTo(w * 0.18f, h * 0.92f); lineTo(w * 0.18f, h * 0.5f); lineTo(w * 0.05f, h * 0.5f); close()
        }, c)
        Glyph.PREV -> {
            drawRect(c, p(0.12f, 0.15f), Size(w * 0.14f, h * 0.7f))
            drawPath(Path().apply { moveTo(w * 0.9f, h * 0.15f); lineTo(w * 0.3f, h * 0.5f); lineTo(w * 0.9f, h * 0.85f); close() }, c)
        }
        Glyph.NEXT -> {
            drawRect(c, p(0.74f, 0.15f), Size(w * 0.14f, h * 0.7f))
            drawPath(Path().apply { moveTo(w * 0.1f, h * 0.15f); lineTo(w * 0.7f, h * 0.5f); lineTo(w * 0.1f, h * 0.85f); close() }, c)
        }
        Glyph.PLAY -> drawPath(Path().apply { moveTo(w * 0.25f, h * 0.1f); lineTo(w * 0.9f, h * 0.5f); lineTo(w * 0.25f, h * 0.9f); close() }, c)
        Glyph.CALLS -> {
            for (i in 0 until 3) drawLine(c, p(0.1f, 0.22f + i * 0.24f), p(if (i == 2) 0.55f else 0.9f, 0.22f + i * 0.24f), w * 0.11f, StrokeCap.Round)
            drawPath(Path().apply { moveTo(w * 0.66f, h * 0.62f); lineTo(w * 0.96f, h * 0.62f); lineTo(w * 0.81f, h * 0.9f); close() }, c)
        }
        Glyph.CONTACTS -> {
            drawCircle(c, w * 0.2f, p(0.5f, 0.34f))
            drawPath(Path().apply { moveTo(w * 0.12f, h * 0.92f); cubicTo(w * 0.14f, h * 0.55f, w * 0.86f, h * 0.55f, w * 0.88f, h * 0.92f); close() }, c)
        }
        Glyph.GEAR -> {
            val ctr = p(0.5f, 0.5f)
            for (i in 0 until 8) rotate(i * 45f, ctr) { drawRect(c, p(0.42f, 0.04f), Size(w * 0.16f, h * 0.2f)) }
            drawCircle(c, w * 0.3f, ctr); drawCircle(Color(0xFF16345C), w * 0.12f, ctr)
        }
        Glyph.SOUND -> for (i in 0 until 4) {
            val x = w * (0.14f + i * 0.24f); val hh = listOf(0.5f, 0.8f, 0.4f, 0.65f)[i]
            drawLine(c, Offset(x, h * 0.9f), Offset(x, h * (0.9f - hh * 0.8f)), w * 0.12f, StrokeCap.Round)
        }
        Glyph.BARS -> for (i in 0 until 4) {
            val x = w * (0.14f + i * 0.24f)
            drawLine(c, Offset(x, h * 0.9f), Offset(x, h * (0.7f - i * 0.15f)), w * 0.12f, StrokeCap.Round)
        }
        Glyph.MAP -> drawPath(Path().apply {
            moveTo(w * 0.06f, h * 0.2f); lineTo(w * 0.36f, h * 0.08f); lineTo(w * 0.64f, h * 0.2f); lineTo(w * 0.94f, h * 0.08f)
            lineTo(w * 0.94f, h * 0.8f); lineTo(w * 0.64f, h * 0.92f); lineTo(w * 0.36f, h * 0.8f); lineTo(w * 0.06f, h * 0.92f); close()
        }, c, style = Stroke(w * 0.1f))
        Glyph.INFO -> {
            drawCircle(c, w * 0.09f, p(0.55f, 0.16f))
            drawLine(c, p(0.52f, 0.4f), p(0.44f, 0.88f), w * 0.16f, StrokeCap.Round)
        }
        Glyph.GRID -> for (i in 0 until 2) for (j in 0 until 2) drawRoundRect(c, p(0.08f + i * 0.46f, 0.08f + j * 0.46f), Size(w * 0.38f, h * 0.38f), CornerRadius(w * 0.08f))
        Glyph.GLOBE -> {
            drawCircle(c, w * 0.42f, p(0.5f, 0.5f), style = Stroke(w * 0.1f))
            drawOval(c, p(0.32f, 0.08f), Size(w * 0.36f, h * 0.84f), style = Stroke(w * 0.08f))
            drawLine(c, p(0.08f, 0.5f), p(0.92f, 0.5f), w * 0.08f)
        }
        Glyph.PHONE -> drawPath(Path().apply {
            moveTo(w * 0.22f, h * 0.08f); lineTo(w * 0.4f, h * 0.08f); lineTo(w * 0.46f, h * 0.32f); lineTo(w * 0.34f, h * 0.42f)
            cubicTo(w * 0.42f, h * 0.58f, w * 0.5f, h * 0.64f, w * 0.6f, h * 0.68f); lineTo(w * 0.7f, h * 0.56f); lineTo(w * 0.92f, h * 0.62f)
            lineTo(w * 0.92f, h * 0.8f); cubicTo(w * 0.92f, h * 0.9f, w * 0.84f, h * 0.94f, w * 0.76f, h * 0.92f)
            cubicTo(w * 0.36f, h * 0.84f, w * 0.12f, h * 0.56f, w * 0.08f, h * 0.24f); cubicTo(w * 0.07f, h * 0.14f, w * 0.12f, h * 0.08f, w * 0.22f, h * 0.08f); close()
        }, c)
        Glyph.GAUGE -> {
            drawArc(c, 150f, 240f, false, p(0.08f, 0.1f), Size(w * 0.84f, h * 0.84f), style = st)
            drawLine(c, p(0.5f, 0.52f), p(0.76f, 0.28f), w * 0.12f, StrokeCap.Round)
        }
        Glyph.TIRE -> {
            drawCircle(c, w * 0.42f, p(0.5f, 0.5f), style = Stroke(w * 0.14f))
            drawCircle(c, w * 0.13f, p(0.5f, 0.5f))
        }
        Glyph.LINK -> {
            drawRoundRect(c, p(0.24f, 0.06f), Size(w * 0.52f, h * 0.88f), CornerRadius(w * 0.1f), style = Stroke(w * 0.1f))
            drawCircle(c, w * 0.06f, p(0.5f, 0.8f))
        }
    }
}
