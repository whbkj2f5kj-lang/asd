package de.jarvis.w204.tpms

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.hoho.android.usbserial.util.SerialInputOutputManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.Executors

enum class Wheel(val label: String, val short: String) {
    FL("vorne links", "VL"), FR("vorne rechts", "VR"), RL("hinten links", "HL"), RR("hinten rechts", "HR"), SPARE("Reserverad", "RES");

    val front: Boolean get() = this == FL || this == FR
}

data class TireState(
    val wheel: Wheel,
    val kpa: Int,
    val tempC: Int,
    val noSignal: Boolean,
    val leak: Boolean,
    val lowBattery: Boolean,
    val at: Long,
) {
    val bar: Double get() = kpa / 100.0
}

enum class TpmsLink { NONE, NO_PERMISSION, CONNECTED, ERROR }

data class TpmsStatus(
    val link: TpmsLink = TpmsLink.NONE,
    val message: String = "Kein TPMS-Empfänger gefunden",
    val device: String? = null,
    val genuine: Boolean? = null,   // Antwort auf die Kennungsprüfung des Empfängers
)

/**
 * Protokoll der verbreiteten USB-Reifendruck-Empfänger für Android-Radios ("Protokoll 3"):
 * 19200 Baud 8N1, Rahmen 55 AA <Länge> … <XOR-Prüfsumme über alle vorherigen Bytes>.
 * Länge 8 = Reifenstatus: Position, Druck×3,44 kPa, Temperatur−50 °C, Flags.
 */
object TpmsProtocol {
    fun xor(frame: ByteArray, len: Int): Byte {
        var c = frame[0].toInt()
        for (i in 1 until len - 1) c = c xor frame[i].toInt()
        return c.toByte()
    }

    /** Rahmen mit korrekter Prüfsumme bauen (letztes Byte wird überschrieben). */
    fun frame(vararg body: Int): ByteArray {
        val f = ByteArray(body.size) { body[it].toByte() }
        f[f.size - 1] = xor(f, f.size)
        return f
    }

    val HEARTBEAT get() = frame(0x55, 0xAA, 0x06, 0x19, 0x00, 0x00)
    val HEARTBEAT_ACK get() = frame(0x55, 0xAA, 0x06, 0x00, 0x00, 0x00)
    val STOP_PAIRING get() = frame(0x55, 0xAA, 0x06, 0x06, 0x00, 0x00)
    fun seed(seed: Int) = frame(0x55, 0xAA, 0x06, 0x5B, seed and 0xFF, 0x00)

    /** Erwartete Antwort des Empfängers auf die Kennungsprüfung. */
    fun seedAnswer(seed: Int): Int =
        (seed xor 32 xor 23 xor 1 xor -122 xor 100 xor 1 xor -126 xor 118) and 0xFF

    fun wheel(code: Int): Wheel? = when (code) {
        0x00 -> Wheel.FL
        0x01 -> Wheel.FR
        0x10 -> Wheel.RL
        0x11 -> Wheel.RR
        0x05 -> Wheel.SPARE
        else -> null
    }

    sealed interface Msg {
        data class Tire(val state: TireState) : Msg
        data object Heartbeat : Msg
        data class SeedAnswer(val value: Int) : Msg
        data class Other(val bytes: ByteArray) : Msg
    }

    /** Zerlegt den Datenstrom in Nachrichten; gibt den unverbrauchten Rest zurück. */
    fun parse(buf: ByteArray, now: Long, out: MutableList<Msg>): ByteArray {
        var i = 0
        while (i + 3 <= buf.size) {
            if (buf[i] != 0x55.toByte() || buf[i + 1] != 0xAA.toByte()) { i++; continue }
            val len = buf[i + 2].toInt() and 0xFF
            if (len < 4 || len > 32) { i++; continue }
            if (i + len > buf.size) break                      // Rahmen noch unvollständig
            val f = buf.copyOfRange(i, i + len)
            if (xor(f, len) != f[len - 1]) { i++; continue }  // Prüfsumme falsch -> neu synchronisieren
            out += decode(f, now)
            i += len
        }
        return buf.copyOfRange(i, buf.size)
    }

    private fun decode(f: ByteArray, now: Long): Msg {
        val len = f[2].toInt() and 0xFF
        if (len == 8) {
            val w = wheel(f[3].toInt() and 0xFF) ?: return Msg.Other(f)
            val flags = f[6].toInt() and 0xFF
            return Msg.Tire(
                TireState(
                    wheel = w,
                    kpa = ((f[4].toInt() and 0xFF) * 3.44).toInt(),
                    tempC = (f[5].toInt() and 0xFF) - 50,
                    noSignal = flags and 0x20 != 0,
                    leak = flags and 0x08 != 0,
                    lowBattery = flags and 0x10 != 0,
                    at = now,
                )
            )
        }
        if (len == 6) {
            val a = f[3].toInt() and 0xFF
            val b = f[4].toInt() and 0xFF
            if (a == 0x00 && b == 0x88) return Msg.Heartbeat
            if (a == 0xB5) return Msg.SeedAnswer(b)
        }
        return Msg.Other(f)
    }
}

/** Verbindung zum USB-Reifendruck-Empfänger (CH340 1A86:7523 oder FTDI 0403:6001/6015). */
class TpmsManager(context: Context, private val scope: CoroutineScope) {
    private val app = context.applicationContext
    private val usb = app.getSystemService(UsbManager::class.java)

    private val _tires = MutableStateFlow<Map<Wheel, TireState>>(emptyMap())
    val tires: StateFlow<Map<Wheel, TireState>> = _tires
    private val _status = MutableStateFlow(TpmsStatus())
    val status: StateFlow<TpmsStatus> = _status

    private var port: UsbSerialPort? = null
    private var io: SerialInputOutputManager? = null
    private val executor = Executors.newSingleThreadExecutor()
    private var loop: Job? = null
    private var rest = ByteArray(0)
    private var seed = (System.currentTimeMillis() and 0xFF).toInt()
    @Volatile private var enabled = true

    private val ids = setOf(0x1A86 to 0x7523, 0x0403 to 0x6001, 0x0403 to 0x6015)
    private val action = "de.jarvis.w204.TPMS_USB_PERMISSION"

    private val permissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (i.action == action) scope.launch(Dispatchers.IO) { tryOpen() }
        }
    }

    init {
        val filter = IntentFilter(action)
        if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(permissionReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else app.registerReceiver(permissionReceiver, filter)
    }

    fun setEnabled(on: Boolean) {
        enabled = on
        if (on) start() else stop()
    }

    /** Sucht alle 5 s nach dem Empfänger und hält die Verbindung (Herzschlag 1×/s). */
    fun start() {
        if (loop?.isActive == true || !enabled) return
        loop = scope.launch(Dispatchers.IO) {
            var tick = 0
            while (isActive) {
                if (port == null) {
                    if (tick % 5 == 0) tryOpen()
                } else {
                    write(TpmsProtocol.HEARTBEAT)
                    if (_status.value.genuine != true) write(TpmsProtocol.seed(seed))
                    // Reifen ohne neue Daten seit 10 min als veraltet markieren
                    val now = SystemClock.elapsedRealtime()
                    _tires.update { m -> m.filterValues { now - it.at < 10 * 60_000L } }
                }
                tick++
                delay(1000)
            }
        }
    }

    fun stop() {
        loop?.cancel()
        loop = null
        close()
        _status.value = TpmsStatus(message = "TPMS ausgeschaltet")
    }

    private fun tryOpen() {
        if (port != null || usb == null) return
        try {
            val driver = UsbSerialProber.getDefaultProber().findAllDrivers(usb)
                .firstOrNull { (it.device.vendorId to it.device.productId) in ids }
            if (driver == null) {
                _status.value = TpmsStatus(message = "Kein TPMS-Empfänger gefunden")
                return
            }
            val dev = driver.device
            val name = "%04X:%04X".format(dev.vendorId, dev.productId)
            if (!usb.hasPermission(dev)) {
                val flags = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
                val pi = PendingIntent.getBroadcast(app, 0, Intent(action).setPackage(app.packageName), flags)
                usb.requestPermission(dev, pi)
                _status.value = TpmsStatus(TpmsLink.NO_PERMISSION, "USB-Zugriff für den TPMS-Empfänger erlauben", name)
                return
            }
            val conn = usb.openDevice(dev) ?: run {
                _status.value = TpmsStatus(TpmsLink.ERROR, "Empfänger belegt – läuft noch die alte TPMS-App?", name)
                return
            }
            val p = driver.ports.first()
            p.open(conn)
            p.setParameters(19200, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            port = p
            rest = ByteArray(0)
            val manager = SerialInputOutputManager(p, object : SerialInputOutputManager.Listener {
                override fun onNewData(data: ByteArray) = onData(data)
                override fun onRunError(e: Exception) {
                    Log.w(TAG, "Verbindung verloren", e)
                    close()
                    _status.value = TpmsStatus(TpmsLink.ERROR, "Verbindung zum Empfänger verloren – suche neu …", name)
                }
            })
            io = manager
            executor.submit(manager)
            write(TpmsProtocol.STOP_PAIRING)
            _status.value = TpmsStatus(TpmsLink.CONNECTED, "Verbunden – warte auf Sensoren (kann 1–2 min dauern, Rad muss sich drehen)", name)
        } catch (t: Throwable) {
            Log.w(TAG, "Öffnen fehlgeschlagen", t)
            close()
            _status.value = TpmsStatus(TpmsLink.ERROR, "Empfänger konnte nicht geöffnet werden: ${t.message}")
        }
    }

    private fun onData(data: ByteArray) {
        val msgs = mutableListOf<TpmsProtocol.Msg>()
        synchronized(this) {
            rest = TpmsProtocol.parse(rest + data, SystemClock.elapsedRealtime(), msgs)
            if (rest.size > 256) rest = ByteArray(0)
        }
        for (m in msgs) when (m) {
            is TpmsProtocol.Msg.Tire -> {
                _tires.update { it + (m.state.wheel to m.state) }
                if (_status.value.message.startsWith("Verbunden – warte")) {
                    _status.update { it.copy(message = "Verbunden") }
                }
            }
            TpmsProtocol.Msg.Heartbeat -> write(TpmsProtocol.HEARTBEAT_ACK)
            is TpmsProtocol.Msg.SeedAnswer -> _status.update { it.copy(genuine = m.value == TpmsProtocol.seedAnswer(seed)) }
            is TpmsProtocol.Msg.Other -> {}
        }
    }

    private fun write(bytes: ByteArray) {
        try {
            port?.write(bytes, 500)
        } catch (t: Throwable) {
            Log.w(TAG, "Schreiben fehlgeschlagen", t)
        }
    }

    private fun close() {
        runCatching { io?.stop() }
        io = null
        runCatching { port?.close() }
        port = null
    }

    fun release() {
        stop()
        runCatching { app.unregisterReceiver(permissionReceiver) }
        executor.shutdownNow()
    }

    companion object {
        private const val TAG = "Tpms"
    }
}
