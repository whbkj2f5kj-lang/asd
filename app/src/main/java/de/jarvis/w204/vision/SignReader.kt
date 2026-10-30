package de.jarvis.w204.vision

import android.graphics.Bitmap
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import de.jarvis.w204.CrashGuard
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.TimeUnit
import kotlin.math.max

/** Kandidat für ein Schild, Koordinaten normiert (0..1). */
data class SignBox(val l: Float, val t: Float, val r: Float, val b: Float, val value: Int?)

/**
 * Tempolimit-Schilder (rund, roter Rand, helle Fläche, Zahl):
 * rote Ringe im verkleinerten Bild finden, Inneres offline mit ML Kit lesen,
 * Wert erst melden, wenn er mehrfach kurz hintereinander erkannt wurde.
 */
class SignReader(private val onLimit: (Int) -> Unit) {
    private var recognizer: TextRecognizer? = null
    private var initFailed = false
    private var runs = 0

    private fun client(): TextRecognizer? {
        if (recognizer == null && !initFailed) {
            recognizer = try {
                CrashGuard.guard("ocr_init") { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
            } catch (t: Throwable) {
                initFailed = true
                null
            }
        }
        return recognizer
    }
    private val history = ArrayDeque<Pair<Long, Int>>()

    fun detect(full: Bitmap, px: IntArray, sw: Int, sh: Int, now: Long): List<SignBox> {
        val n = sw * sh
        val scanH = (sh * 0.8f).toInt()
        val red = BooleanArray(n)
        for (i in 0 until sw * scanH) red[i] = isRed(px[i])

        val seen = BooleanArray(n)
        val queue = IntArray(n)
        val cands = ArrayList<IntArray>()

        for (start in 0 until sw * scanH) {
            if (!red[start] || seen[start]) continue
            var head = 0
            var tail = 0
            queue[tail++] = start
            seen[start] = true
            var minX = sw; var minY = sh; var maxX = 0; var maxY = 0; var count = 0
            while (head < tail) {
                val p = queue[head++]
                val x = p % sw
                val y = p / sw
                count++
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y
                var q = p - 1
                if (x > 0 && red[q] && !seen[q]) { seen[q] = true; queue[tail++] = q }
                q = p + 1
                if (x < sw - 1 && red[q] && !seen[q]) { seen[q] = true; queue[tail++] = q }
                q = p - sw
                if (y > 0 && red[q] && !seen[q]) { seen[q] = true; queue[tail++] = q }
                q = p + sw
                if (y < scanH - 1 && red[q] && !seen[q]) { seen[q] = true; queue[tail++] = q }
            }

            val w = maxX - minX + 1
            val h = maxY - minY + 1
            if (count < 30 || w < 10 || h < 10 || w > sw / 3) continue
            val aspect = w.toFloat() / h
            if (aspect < 0.7f || aspect > 1.4f) continue
            val fill = count.toFloat() / (w * h)
            if (fill < 0.12f || fill > 0.62f) continue

            val ix0 = minX + w * 3 / 10; val ix1 = maxX - w * 3 / 10
            val iy0 = minY + h * 3 / 10; val iy1 = maxY - h * 3 / 10
            var innerTotal = 0; var innerRed = 0; var innerBright = 0
            for (yy in iy0..iy1) for (xx in ix0..ix1) {
                val i = yy * sw + xx
                innerTotal++
                if (red[i]) innerRed++
                if (brightness(px[i]) > 110) innerBright++
            }
            if (innerTotal == 0 || innerRed > innerTotal * 0.2f || innerBright < innerTotal * 0.25f) continue
            cands += intArrayOf(minX, minY, maxX, maxY)
        }

        val scale = full.width.toFloat() / sw
        val boxes = cands
            .sortedByDescending { (it[2] - it[0]) * (it[3] - it[1]) }
            .take(3)
            .map { c ->
                val l = c[0] * scale; val t = c[1] * scale
                val r = (c[2] + 1) * scale; val b = (c[3] + 1) * scale
                SignBox(l / full.width, t / full.height, r / full.width, b / full.height, readNumber(full, l, t, r, b))
            }
        confirm(now, boxes)
        return boxes
    }

    fun close() {
        runCatching { recognizer?.close() }
    }

    private fun readNumber(full: Bitmap, l: Float, t: Float, r: Float, b: Float): Int? {
        val w = r - l
        val h = b - t
        val x0 = (l + w * 0.14f).toInt().coerceIn(0, full.width - 2)
        val y0 = (t + h * 0.14f).toInt().coerceIn(0, full.height - 2)
        val x1 = (r - w * 0.14f).toInt().coerceIn(x0 + 1, full.width)
        val y1 = (b - h * 0.14f).toInt().coerceIn(y0 + 1, full.height)
        var crop = Bitmap.createBitmap(full, x0, y0, x1 - x0, y1 - y0)
        if (crop.width < 160) {
            val f = 160f / crop.width
            crop = Bitmap.createScaledBitmap(crop, 160, max(1, (crop.height * f).toInt()), true)
        }
        val rec = client() ?: return null
        val text = try {
            val task = { Tasks.await(rec.process(InputImage.fromBitmap(crop, 0)), 1500, TimeUnit.MILLISECONDS).text }
            if (runs++ < 3) CrashGuard.guard("ocr_run", task) else task()
        } catch (e: Exception) {
            return null
        }
        val digits = text.uppercase()
            .replace('O', '0').replace('D', '0').replace('Q', '0')
            .replace('I', '1').replace('L', '1')
            .replace(Regex("[^0-9]"), "")
        val v = digits.toIntOrNull() ?: return null
        return v.takeIf { it in VALID_LIMITS }
    }

    private fun confirm(now: Long, boxes: List<SignBox>) {
        boxes.firstNotNullOfOrNull { it.value }?.let { history.addLast(now to it) }
        while (history.isNotEmpty() && now - history.first().first > 2500) history.removeFirst()
        val best = history.groupingBy { it.second }.eachCount().maxByOrNull { it.value } ?: return
        if (best.value >= 2) onLimit(best.key)
    }

    private fun isRed(c: Int): Boolean {
        val r = (c shr 16) and 0xFF
        val g = (c shr 8) and 0xFF
        val b = c and 0xFF
        return r > 70 && r * 10 > g * 17 && r * 10 > b * 15
    }

    private fun brightness(c: Int): Int = (((c shr 16) and 0xFF) + ((c shr 8) and 0xFF) + (c and 0xFF)) / 3

    companion object {
        private val VALID_LIMITS = setOf(10, 20, 30, 40, 50, 60, 70, 80, 90, 100, 110, 120, 130)
    }
}
