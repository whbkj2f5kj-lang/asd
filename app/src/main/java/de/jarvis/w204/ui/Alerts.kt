package de.jarvis.w204.ui

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/** Meldung im Stil einer OEM-"Check-Control"-Anzeige */
data class Alert(val level: Level, val title: String, val text: String, val at: Long) {
    enum class Level { INFO, WARN, CRIT }
}

fun alertColor(l: Alert.Level) = when (l) {
    Alert.Level.INFO -> J.accent
    Alert.Level.WARN -> J.warn
    Alert.Level.CRIT -> J.danger
}

/** Karte oben in der Mitte, gleitet herein; antippen schließt sie */
@Composable
fun AlertCard(alert: Alert?, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    AnimatedVisibility(
        visible = alert != null,
        enter = fadeIn() + slideInVertically { -it / 2 } + scaleIn(initialScale = 0.96f),
        exit = fadeOut() + slideOutVertically { -it / 3 } + scaleOut(targetScale = 0.97f),
        modifier = modifier,
    ) {
        val a = alert ?: return@AnimatedVisibility
        val c = alertColor(a.level)
        Row(
            Modifier.widthIn(min = 320.dp, max = 520.dp).height(78.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(Brush.verticalGradient(listOf(Color(0xF2141B24), Color(0xF20A0F15))))
                .glass(RoundedCornerShape(18.dp))
                .clickable(onClick = onDismiss),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.width(5.dp).fillMaxHeight().background(c))
            Spacer(Modifier.width(14.dp))
            AlertIcon(a.level, c, Modifier.size(36.dp))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.padding(end = 18.dp)) {
                Text(a.title, color = J.text, fontSize = 18.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                Text(a.text, color = J.dim, fontSize = 14.sp, maxLines = 2)
            }
        }
    }
}

@Composable
private fun AlertIcon(level: Alert.Level, c: Color, modifier: Modifier) {
    Canvas(modifier) {
        val w = size.width
        if (level == Alert.Level.INFO) {
            drawCircle(c.copy(alpha = 0.18f))
            drawCircle(c, style = Stroke(w * 0.07f))
            drawLine(c, Offset(w / 2, w * 0.45f), Offset(w / 2, w * 0.72f), strokeWidth = w * 0.09f, cap = StrokeCap.Round)
            drawCircle(c, w * 0.055f, Offset(w / 2, w * 0.3f))
        } else {
            // Warndreieck
            val p = androidx.compose.ui.graphics.Path().apply {
                moveTo(w / 2, w * 0.08f); lineTo(w * 0.95f, w * 0.88f); lineTo(w * 0.05f, w * 0.88f); close()
            }
            drawPath(p, c.copy(alpha = 0.2f))
            drawPath(p, c, style = Stroke(w * 0.07f, join = androidx.compose.ui.graphics.StrokeJoin.Round))
            drawLine(c, Offset(w / 2, w * 0.38f), Offset(w / 2, w * 0.62f), strokeWidth = w * 0.09f, cap = StrokeCap.Round)
            drawCircle(c, w * 0.05f, Offset(w / 2, w * 0.75f))
        }
    }
}

/** Gong wie im Kombiinstrument: weich ausklingende Töne, je nach Dringlichkeit 1–3 */
object Chime {
    private const val RATE = 22050
    private val cache = HashMap<Alert.Level, ShortArray>()

    private fun tone(freqs: List<Pair<Double, Double>>, len: Double): ShortArray {
        val n = (RATE * len).toInt()
        val out = ShortArray(n)
        for (i in 0 until n) {
            val t = i.toDouble() / RATE
            var v = 0.0
            for ((start, f) in freqs) {
                if (t < start) continue
                val tt = t - start
                val env = exp(-tt * 4.0) * (1 - exp(-tt * 200.0))
                v += env * (sin(2 * PI * f * tt) + 0.25 * sin(2 * PI * f * 2 * tt))
            }
            out[i] = (v * 0.28 * Short.MAX_VALUE).coerceIn(-32767.0, 32767.0).toInt().toShort()
        }
        return out
    }

    private fun pcm(l: Alert.Level) = cache.getOrPut(l) {
        when (l) {
            Alert.Level.INFO -> tone(listOf(0.0 to 1046.5), 0.9)
            Alert.Level.WARN -> tone(listOf(0.0 to 880.0, 0.22 to 659.3), 1.2)
            Alert.Level.CRIT -> tone(listOf(0.0 to 988.0, 0.18 to 988.0, 0.36 to 784.0), 1.4)
        }
    }

    fun play(l: Alert.Level) {
        Thread {
            runCatching {
                val data = pcm(l)
                val track = AudioTrack.Builder()
                    .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                    .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .setBufferSizeInBytes(data.size * 2)
                    .build()
                track.write(data, 0, data.size)
                track.play()
                Thread.sleep((data.size * 1000L / RATE) + 100)
                track.release()
            }
        }.start()
    }
}
