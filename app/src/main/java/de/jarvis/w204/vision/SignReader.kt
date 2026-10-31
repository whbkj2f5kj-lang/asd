package de.jarvis.w204.vision

import android.graphics.Bitmap
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import de.jarvis.w204.CrashGuard
import java.util.concurrent.TimeUnit
import kotlin.math.max

/** Gemeinsame Zeitbasis für Debug-Protokoll und Video (monoton, ms – wie die Video-Zeitstempel) */
object DebugTime {
    fun now(): Long = System.nanoTime() / 1_000_000
}

/** Kandidat für ein Schild, Koordinaten normiert (0..1). */
data class SignBox(val l: Float, val t: Float, val r: Float, val b: Float, val value: Int?, val label: String? = null)

/** Was die Schildererkennung meldet (erst nach Bestätigung in mehreren Bildern). */
sealed interface SignEvent {
    val key: String

    /** [note]: null, "bei Nässe", "Lkw" (gilt nicht für Pkw), "7–17 h" (zeitlich), [active] = gilt gerade */
    data class Limit(val value: Int, val note: String? = null, val active: Boolean = true) : SignEvent {
        override val key get() = "L$value|$note"
    }
    data class EndLimit(val value: Int?) : SignEvent { override val key get() = "E" }
    data object EndAll : SignEvent { override val key get() = "EA" }
    data class TownEntry(val name: String?) : SignEvent { override val key get() = "T" }
    data class TownExit(val name: String?) : SignEvent { override val key get() = "X" }
    data object NoPassing : SignEvent { override val key get() = "NP" }
    data object EndNoPassing : SignEvent { override val key get() = "ENP" }
}

/**
 * Schilder im Kamerabild:
 *  • Tempolimit (roter Ring, Zahl) + Zusatzzeichen darunter (Nässe, Uhrzeit, Lkw)
 *  • Aufhebung: Zeichen 278 (Ende Höchstgeschwindigkeit) und 282 (Ende aller Streckenverbote)
 *  • Ortsschild Anfang/Ende (gelb; Ende mit rotem Schrägstrich)
 * Runde Tempo-Aufkleber hinten auf Lkw werden ignoriert (liegen innerhalb einer Fahrzeug-Box).
 */
class SignReader(
    private val onEvent: (SignEvent) -> Unit,
    private val netLoader: () -> SignNet? = { null },
) {
    private var recognizer: TextRecognizer? = null
    private var net: SignNet? = null
    private var netTried = false

    /** Debug-Aufnahme: jede Entscheidung als JSON-Zeile (null = aus) */
    @Volatile var debug: ((String) -> Unit)? = null

    private fun f3(v: Float) = String.format(java.util.Locale.US, "%.3f", v)
    private fun dbgCand(typ: String, n: FloatArray, wPx: Int, cr: SignNet.Result?, res: String) {
        val d = debug ?: return
        d("{\"k\":\"sc\",\"t\":${DebugTime.now()},\"typ\":\"$typ\",\"b\":[${f3(n[0])},${f3(n[1])},${f3(n[2])},${f3(n[3])}],\"px\":$wPx" +
            (if (cr != null) ",\"cls\":${cr.cls},\"p\":${f3(cr.p)}" else "") + ",\"res\":\"${res.replace("\"", "'")}\"}")
    }

    private fun net(): SignNet? {
        if (!netTried) { netTried = true; net = runCatching { netLoader() }.getOrNull() }
        return net
    }

    /** Mehrere Kandidaten gleichzeitig vom Netz prüfen lassen (nutzt freie Prozessorkerne) */
    private val pool = java.util.concurrent.Executors.newFixedThreadPool(3) { r -> Thread(r, "signnet").apply { priority = Thread.NORM_PRIORITY + 1 } }

    private fun classifyAll(full: Bitmap, boxes: List<FloatArray>, pad: Float): List<SignNet.Result?> {
        if (boxes.isEmpty()) return emptyList()
        if (net() == null) return boxes.map { null }
        if (boxes.size == 1) return listOf(classify(full, boxes[0], pad))
        return runCatching {
            pool.invokeAll(boxes.map { b -> java.util.concurrent.Callable { classify(full, b, pad) } }).map { it.get() }
        }.getOrElse { boxes.map { b -> classify(full, b, pad) } }
    }

    /** Schildausschnitt (mit [pad] Rand je Seite) vom Netz bestimmen lassen. */
    private fun classify(full: Bitmap, n: FloatArray, pad: Float): SignNet.Result? {
        val nn = net() ?: return null
        val w = (n[2] - n[0]) * full.width
        val h = (n[3] - n[1]) * full.height
        val x0 = (n[0] * full.width - w * pad).toInt().coerceIn(0, full.width - 2)
        val y0 = (n[1] * full.height - h * pad).toInt().coerceIn(0, full.height - 2)
        val x1 = (n[2] * full.width + w * pad).toInt().coerceIn(x0 + 1, full.width)
        val y1 = (n[3] * full.height + h * pad).toInt().coerceIn(y0 + 1, full.height)
        return runCatching {
            val crop = Bitmap.createBitmap(full, x0, y0, x1 - x0, y1 - y0)
            val small = Bitmap.createScaledBitmap(crop, SignNet.S, SignNet.S, true)
            val px = IntArray(SignNet.S * SignNet.S)
            small.getPixels(px, 0, SignNet.S, 0, 0, SignNet.S, SignNet.S)
            nn.classify(px)
        }.getOrNull()
    }
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

    private val history = ArrayDeque<Pair<Long, SignEvent>>()
    private val lastEmit = HashMap<String, Long>()

    fun detect(
        full: Bitmap, px: IntArray, sw: Int, sh: Int, now: Long,
        vehicles: List<Detection> = emptyList(),
        hour: Int = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY),
    ): List<SignBox> {
        val rows = (sh * 0.8f).toInt()
        val scale = full.width.toFloat() / sw
        val boxes = ArrayList<SignBox>()
        val events = ArrayList<SignEvent>()
        // vom Netz sehr sicher erkannt -> zählt schon nach einer Sichtung (Stadt: Schild nur kurz im Bild)
        val strong = HashSet<String>()
        // unscharfes Bild, Netz nur mäßig sicher -> zählt halb (mehrere Sichtungen nötig)
        val weak = HashSet<String>()

        fun norm(bl: SignShapes.Blob) = floatArrayOf(
            bl.minX * scale / full.width, bl.minY * scale / full.height,
            (bl.maxX + 1) * scale / full.width, (bl.maxY + 1) * scale / full.height,
        )
        // Mitte der Box liegt in einem Auto/Lkw -> Aufkleber, kein Verkehrszeichen
        fun onVehicle(n: FloatArray): Boolean {
            val cx = (n[0] + n[2]) / 2f; val cy = (n[1] + n[3]) / 2f
            return vehicles.any {
                (it.kind == ObjKind.TRUCK || it.kind == ObjKind.CAR) && cx in it.l..it.r && cy in it.t..it.b
            }
        }

        // ---------- 1) rote Ringe: Tempolimits
        val red = BooleanArray(sw * sh)
        for (i in 0 until sw * rows) red[i] = SignShapes.isRed(px[i])
        val rings = SignShapes.blobs(red, sw, rows, 20).filter { bl ->
            if (bl.w < 10 || bl.h < 10 || bl.w > sw / 3) return@filter false
            if (bl.aspect < 0.7f || bl.aspect > 1.4f) return@filter false
            if (bl.fill < 0.12f || bl.fill > 0.62f) return@filter false
            val ix0 = bl.minX + bl.w * 3 / 10; val ix1 = bl.maxX - bl.w * 3 / 10
            val iy0 = bl.minY + bl.h * 3 / 10; val iy1 = bl.maxY - bl.h * 3 / 10
            var tot = 0; var rd = 0; var br = 0
            for (yy in iy0..iy1) for (xx in ix0..ix1) {
                val c = px[yy * sw + xx]
                tot++
                if (red[yy * sw + xx]) rd++
                if (SignShapes.bright(c) > 110) br++
            }
            tot > 0 && rd <= tot * 0.2f && br >= tot * 0.25f
        }.sortedByDescending { it.w * it.h }.take(3)
        debug?.invoke("{\"k\":\"sr\",\"t\":${DebugTime.now()},\"w\":$sw,\"rings\":${rings.size}}")

        val ringResults = classifyAll(full, rings.map { norm(it) }, 0.12f)
        for ((ri, bl) in rings.withIndex()) {
            val n = norm(bl)
            if (onVehicle(n)) { boxes += SignBox(n[0], n[1], n[2], n[3], null, "Fahrzeug"); dbgCand("ring", n, bl.w, null, "Fahrzeug"); continue }
            val cr = ringResults[ri]
            val sure = cr != null && cr.p >= 0.85f
            var weakHit = false
            if (sure && cr!!.cls == SignNet.NO_PASSING) {
                dbgCand("ring", n, bl.w, cr, "Ueberholverbot")
                events += SignEvent.NoPassing
                if (cr.p >= 0.97f) strong += SignEvent.NoPassing.key
                boxes += SignBox(n[0], n[1], n[2], n[3], null, "Überholverbot")
                continue
            }
            // Netz ist sicher: kein Tempolimit (anderes rundes Schild, Rücklicht, …)
            if (sure && cr!!.speed == null) { dbgCand("ring", n, bl.w, cr, "kein Tempo"); continue }
            val v: Int? = if (sure) {
                val netV = cr!!.speed!!
                // 130, 110, 90, 40, 10 kennt das Netz nicht – bei ähnlichen Ziffern die Texterkennung fragen
                if (netV in setOf(100, 120)) {
                    val ocr = cached("L", n, now) { readNumber(full, n, inset = 0.14f) }
                    if (ocr != null && ocr in NOT_IN_NET) ocr else netV
                } else netV
            } else {
                // Texterkennung ist teuer: nur wenn das Netz gar nichts weiß und das Schild groß genug ist
                val netGuess = cr?.takeIf { it.p >= 0.5f }?.speed
                if (netGuess != null) { weakHit = true; netGuess }
                else if (cr == null || bl.w >= 22) cached("L", n, now) { readNumber(full, n, inset = 0.14f) }
                else null
            }
            var note: String? = null
            var active = true
            if (v != null) {
                supplement(full, px, sw, sh, bl, scale, now, hour)?.let { (nt, ac) -> note = nt; active = ac }
                val ev = SignEvent.Limit(v, note, active)
                events += ev
                if (sure && cr!!.p >= 0.97f && cr.speed == v) strong += ev.key
                if (weakHit) weak += ev.key
            }
            boxes += SignBox(n[0], n[1], n[2], n[3], v, note)
            dbgCand("ring", n, bl.w, cr, (v?.toString() ?: "?") + (if (weakHit) " schwach" else if (sure) " sicher" else " Text") + (note?.let { " $it" } ?: ""))
        }

        // ---------- 2) Aufhebungszeichen (weiß, schwarze Schrägstreifen)
        val white = BooleanArray(sw * sh)
        for (i in 0 until sw * rows) white[i] = SignShapes.isWhite(px[i])
        val ringBoxes = rings.map { it }
        val pieces = SignShapes.blobs(white, sw, rows, 8).filter { it.w <= sw / 3 && it.h <= rows / 2 }
            .sortedByDescending { it.count }.take(80)    // Zusammenfügen ist teuer: nur die größten Teile
        val whiteCands = SignShapes.merge(pieces, 2)
            .filter { it.w in 10..(sw / 3) && it.aspect in 0.75f..1.33f && it.fill in 0.3f..0.9f }
            // Innenfläche eines Tempolimit-Schilds ist kein Aufhebungszeichen
            .filter { bl -> ringBoxes.none { r -> bl.minX >= r.minX - 2 && bl.maxX <= r.maxX + 2 && bl.minY >= r.minY - 2 && bl.maxY <= r.maxY + 2 } }
            .sortedByDescending { it.count }
            .take(5)
            .filter { !onVehicle(norm(it)) }
        val whiteResults = classifyAll(full, whiteCands.map { norm(it) }, 0.15f)
        whiteCands.forEachIndexed { wi, bl ->
                val n = norm(bl)
                // zuerst das Netz fragen (kennt Ende 80, Ende aller Verbote, Ende Überholverbot)
                val cr = whiteResults[wi]
                // Innenfläche eines Tempo-Schilds: der rote Ring ist bei vielen Kameras zu dünn/blass für die Ringsuche
                val spd = cr?.speed
                if (cr != null && spd != null && cr.p >= 0.5f) {
                    // Zusatzzeichen sitzt unter dem ganzen Schild (Ring ca. 14 % breiter als die Innenfläche)
                    val gx = (bl.w * 0.14f).toInt(); val gy = (bl.h * 0.14f).toInt()
                    val outer = SignShapes.Blob(max(0, bl.minX - gx), max(0, bl.minY - gy), minOf(sw - 1, bl.maxX + gx), minOf(sh - 1, bl.maxY + gy), bl.count)
                    val sup = supplement(full, px, sw, sh, outer, scale, now, hour)
                    val ev = SignEvent.Limit(spd, sup?.first, sup?.second ?: true)
                    events += ev
                    if (cr.p >= 0.97f) strong += ev.key else if (cr.p < 0.85f) weak += ev.key
                    boxes += SignBox(n[0], n[1], n[2], n[3], spd, sup?.first)
                    dbgCand("weiss", n, bl.w, cr, "$spd " + (if (cr.p >= 0.85f) "sicher" else "schwach") + (sup?.first?.let { " $it" } ?: ""))
                    return@forEachIndexed
                }
                if (cr != null && cr.cls == SignNet.NO_PASSING && cr.p >= 0.85f) {
                    events += SignEvent.NoPassing
                    if (cr.p >= 0.97f) strong += SignEvent.NoPassing.key
                    boxes += SignBox(n[0], n[1], n[2], n[3], null, "Überholverbot")
                    dbgCand("weiss", n, bl.w, cr, "Ueberholverbot")
                    return@forEachIndexed
                }
                dbgCand("weiss", n, bl.w, cr, "")
                if (cr != null && cr.p >= 0.8f) {
                    if (cr.p >= 0.97f) when (cr.cls) {
                        SignNet.END_ALL -> strong += SignEvent.EndAll.key
                        SignNet.END_80 -> strong += SignEvent.EndLimit(80).key
                        SignNet.END_NO_PASSING -> strong += SignEvent.EndNoPassing.key
                    }
                    when (cr.cls) {
                        SignNet.END_ALL -> { events += SignEvent.EndAll; boxes += SignBox(n[0], n[1], n[2], n[3], null, "Ende"); return@forEachIndexed }
                        SignNet.END_80 -> { events += SignEvent.EndLimit(80); boxes += SignBox(n[0], n[1], n[2], n[3], 80, "Ende"); return@forEachIndexed }
                        SignNet.END_NO_PASSING -> { events += SignEvent.EndNoPassing; boxes += SignBox(n[0], n[1], n[2], n[3], null, "Ende Überholverbot"); return@forEachIndexed }
                        SignNet.END_NO_PASSING_TRUCK -> return@forEachIndexed
                        else -> if (cr.p >= 0.9f) return@forEachIndexed   // sicher etwas anderes
                    }
                }
                val kind = SignShapes.endSign(px, sw, bl) ?: return@forEachIndexed
                if (kind == SignShapes.EndKind.END_ALL) {
                    events += SignEvent.EndAll
                    boxes += SignBox(n[0], n[1], n[2], n[3], null, "Ende")
                } else {
                    val v = cached("E", n, now) { readNumber(full, n, inset = 0.14f) }
                    events += SignEvent.EndLimit(v)
                    boxes += SignBox(n[0], n[1], n[2], n[3], v, "Ende")
                }
            }

        // ---------- 3) Ortsschilder
        for (t in SignShapes.towns(px, sw, sh)) {
            val n = norm(t.blob)
            if (onVehicle(n)) continue
            val text = cachedText(if (t.exit) "X" else "T", n, now) { readText(full, n, 0.05f) } ?: continue
            val name = SignShapes.townName(text) ?: continue
            events += if (t.exit) SignEvent.TownExit(name) else SignEvent.TownEntry(name)
            boxes += SignBox(n[0], n[1], n[2], n[3], null, if (t.exit) "Ortsende" else name)
        }

        confirm(now, events, strong, weak)
        return boxes
    }

    /** Zusatzzeichen unter einem Schild lesen: (Hinweis, gilt gerade) oder null */
    private fun supplement(full: Bitmap, px: IntArray, sw: Int, sh: Int, sign: SignShapes.Blob, scale: Float, now: Long, hour: Int): Pair<String?, Boolean>? {
        val s = SignShapes.supplementBelow(px, sw, sh, sign) ?: return null
        val sn = floatArrayOf(s[0] * scale / full.width, s[1] * scale / full.height, (s[2] + 1) * scale / full.width, (s[3] + 1) * scale / full.height)
        val text = cachedText("Z", sn, now) { readText(full, sn, 0f) } ?: return null
        val sup = SignShapes.parseSupplement(text)
        return when (sup.kind) {
            SignShapes.Supplement.Kind.WET -> "bei Nässe" to true
            SignShapes.Supplement.Kind.TRUCK -> "Lkw" to true
            SignShapes.Supplement.Kind.TIME -> "${sup.fromH}–${sup.toH} h" to SignShapes.timeActive(sup, hour)
            SignShapes.Supplement.Kind.OTHER -> null
        }
    }

    fun close() {
        runCatching { pool.shutdown() }
        runCatching { recognizer?.close() }
    }

    // ---------------------------------------------------------------- Bestätigung

    /** Ein Ereignis zählt erst, wenn es ≥ 2× in 2,5 s gesehen wurde; danach 15 s Ruhe für dasselbe. */
    private val weighted = ArrayDeque<Triple<Long, SignEvent, Float>>()

    /**
     * Ein Ereignis zählt, wenn seine Sichtungen in 2,5 s zusammen ≥ 2 Punkte ergeben:
     * sehr sicher erkannt = 2 (sofort), normal = 1, unscharf/unsicher = 0,5. Danach 15 s Ruhe für dasselbe.
     */
    private fun confirm(now: Long, events: List<SignEvent>, strong: Set<String> = emptySet(), weak: Set<String> = emptySet()) {
        for (e in events) {
            val w = when (e.key) { in strong -> 2f; in weak -> 0.5f; else -> 1f }
            weighted.addLast(Triple(now, e, w))
        }
        while (weighted.isNotEmpty() && now - weighted.first().first > 2500) weighted.removeFirst()
        val sums = HashMap<String, Float>()
        for ((_, e, w) in weighted) sums[e.key] = (sums[e.key] ?: 0f) + w
        for ((key, sum) in sums) {
            if (sum < 2f) continue
            val last = lastEmit[key]
            if (last != null && now - last < 15_000) continue
            lastEmit[key] = now
            debug?.invoke("{\"k\":\"ev\",\"t\":${DebugTime.now()},\"key\":\"${key.replace("\"", "'")}\",\"sum\":$sum}")
            onEvent(weighted.last { it.second.key == key }.second)
        }
    }

    // ---------------------------------------------------------------- Texterkennung (mit Kurzzeit-Cache)

    private data class Read(val tag: String, val box: FloatArray, val text: String?, val at: Long)
    private val recent = ArrayDeque<Read>()

    private fun iou(a: FloatArray, b: FloatArray): Float {
        val iw = (minOf(a[2], b[2]) - maxOf(a[0], b[0])).coerceAtLeast(0f)
        val ih = (minOf(a[3], b[3]) - maxOf(a[1], b[1])).coerceAtLeast(0f)
        val inter = iw * ih
        val union = (a[2] - a[0]) * (a[3] - a[1]) + (b[2] - b[0]) * (b[3] - b[1]) - inter
        return if (union <= 0f) 0f else inter / union
    }

    /** Ergebnis wiederverwenden, wenn dieselbe Stelle gerade (≤ 350 ms) schon gelesen wurde. */
    private fun cachedText(tag: String, box: FloatArray, now: Long, read: () -> String?): String? {
        while (recent.isNotEmpty() && now - recent.first().at > 350) recent.removeFirst()
        recent.firstOrNull { it.tag == tag && iou(it.box, box) > 0.5f }?.let { return it.text }
        val t = read()
        recent.addLast(Read(tag, box, t, now))
        return t
    }

    private fun cached(tag: String, box: FloatArray, now: Long, read: () -> Int?): Int? =
        cachedText(tag, box, now) { read()?.toString() }?.toIntOrNull()

    private fun readText(full: Bitmap, n: FloatArray, inset: Float): String? {
        val w = (n[2] - n[0]) * full.width
        val h = (n[3] - n[1]) * full.height
        val x0 = (n[0] * full.width + w * inset).toInt().coerceIn(0, full.width - 2)
        val y0 = (n[1] * full.height + h * inset).toInt().coerceIn(0, full.height - 2)
        val x1 = (n[2] * full.width - w * inset).toInt().coerceIn(x0 + 1, full.width)
        val y1 = (n[3] * full.height - h * inset).toInt().coerceIn(y0 + 1, full.height)
        var crop = Bitmap.createBitmap(full, x0, y0, x1 - x0, y1 - y0)
        if (crop.width < 160) {
            val f = 160f / crop.width
            crop = Bitmap.createScaledBitmap(crop, 160, max(1, (crop.height * f).toInt()), true)
        }
        val rec = client() ?: return null
        return try {
            val task = { Tasks.await(rec.process(InputImage.fromBitmap(crop, 0)), 1500, TimeUnit.MILLISECONDS).text }
            if (runs++ < 3) CrashGuard.guard("ocr_run", task) else task()
        } catch (e: Exception) {
            null
        }
    }

    private fun readNumber(full: Bitmap, n: FloatArray, inset: Float): Int? {
        val text = readText(full, n, inset) ?: return null
        return digitsToLimit(text)
    }

    companion object {
        private val VALID_LIMITS = setOf(5, 10, 20, 30, 40, 50, 60, 70, 80, 90, 100, 110, 120, 130)
        /** Tempolimits, die im Trainingsdatensatz fehlen */
        private val NOT_IN_NET = setOf(5, 10, 40, 90, 110, 130)

        /** OCR-Text in ein gültiges Tempolimit wandeln (verwechselte Buchstaben korrigieren). */
        fun digitsToLimit(text: String): Int? {
            val digits = text.uppercase()
                .replace('O', '0').replace('D', '0').replace('Q', '0')
                .replace('I', '1').replace('L', '1').replace('S', '5').replace('B', '8')
                .replace(Regex("[^0-9]"), "")
            if (digits.isEmpty()) return null
            digits.toIntOrNull()?.takeIf { it in VALID_LIMITS }?.let { return it }
            // häufige Fehllesungen: "800" (Rand als 0 gelesen) -> 80, "1100" -> 110
            if (digits.length >= 3) digits.dropLast(1).toIntOrNull()?.takeIf { it in VALID_LIMITS }?.let { return it }
            return null
        }
    }
}
