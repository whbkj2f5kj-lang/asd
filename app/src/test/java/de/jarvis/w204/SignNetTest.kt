package de.jarvis.w204

import de.jarvis.w204.vision.SignNet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

/**
 * Prüft, dass die Kotlin-Umsetzung des Schild-Netzes exakt dasselbe rechnet wie das in Python
 * trainierte Original (Stichprobe aus dem GTSRB-Testsatz, erzeugt von ml/train_signs.py).
 */
class SignNetTest {
    private val modelFile = File("src/main/assets/signnet.bin")

    @Test fun matchesKerasOnTestImages() {
        val res = javaClass.getResourceAsStream("/signnet_samples.bin")
        assumeTrue("Modell noch nicht trainiert", modelFile.exists() && res != null)
        val net = modelFile.inputStream().use { SignNet.load(it) }
        val bb = ByteBuffer.wrap(res!!.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        val n = bb.int
        val s = bb.int
        val classes = bb.int
        assertEquals(SignNet.S, s)
        assertEquals(classes, net.classes)
        var correct = 0
        var maxDiff = 0f
        repeat(n) {
            val label = bb.int
            val px = IntArray(s * s)
            for (i in 0 until s * s) {
                val r = bb.get().toInt() and 0xFF
                val g = bb.get().toInt() and 0xFF
                val b = bb.get().toInt() and 0xFF
                px[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
            val expected = FloatArray(classes) { bb.float }
            val got = net.logits(px)
            for (k in 0 until classes) maxDiff = maxOf(maxDiff, abs(got[k] - expected[k]))
            if (net.classify(px).cls == label) correct++
        }
        assertTrue("Abweichung zu Keras: $maxDiff", maxDiff < 2e-3f)
        assertTrue("Treffer $correct/$n", correct >= n * 0.9)
    }

    @Test fun speedMapping() {
        assertEquals(50, SignNet.Result(2, 0.99f).speed)
        assertEquals(120, SignNet.Result(8, 0.99f).speed)
        assertEquals(null, SignNet.Result(SignNet.END_80, 0.99f).speed)
    }
}
