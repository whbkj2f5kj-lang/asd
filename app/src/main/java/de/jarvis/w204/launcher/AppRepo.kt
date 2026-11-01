package de.jarvis.w204.launcher

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.media.AudioManager
import android.os.Build
import android.os.Environment
import android.os.SystemClock
import android.provider.MediaStore
import android.provider.Settings
import android.util.LruCache
import android.view.KeyEvent
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Eine startbare App (Eintrag im App-Raster) */
data class AppEntry(val pkg: String, val cls: String, val label: String) {
    val key: String get() = "$pkg/$cls"
}

/**
 * Feste Kacheln des Startbildschirms (wie beim Original-Launcher des Radios).
 * Welche App dahinter liegt, wird anhand von Name/Paket erraten und kann per langem Druck geändert werden.
 */
enum class Tile(val id: String, val title: String, val keywords: List<String>) {
    COCKPIT("cockpit", "Cockpit", emptyList()),
    CARPLAY("carplay", "CarPlay", listOf("zlink", "carplay", "autokit", "carlink", "tlink", "easyconn", "android auto", "carapp")),
    NAVI("navi", "Navi", listOf("google maps", "maps", "navi", "waze", "amap", "here we go", "osmand", "sygic", "导航")),
    MUSIC("music", "Musik", listOf("music", "musik", "player", "spotify", "音乐", "audio")),
    PHONE("phone", "Telefon", listOf("bluetooth", "phone", "telefon", "dialer", "蓝牙", "bt")),
    CAR("car", "Fahrzeug", listOf("original", "原车", "carinfo", "car info", "canbus", "can bus", "oem", "vehicle")),
    VIDEO("video", "Video", listOf("video", "youtube", "movie", "视频")),
    SETTINGS("settings", "Komfort", listOf("car settings", "carsetting", "car setting", "设置", "settings", "einstellungen")),
    RADIO("radio", "Radio", listOf("radio", "fm", "tuner", "收音机", "dab")),
    SOUND("sound", "Klang", listOf("dsp", "equalizer", "sound", "eq", "音效")),
    APPS("apps", "Alle Apps", emptyList());

    /** nur diese Kacheln starten fremde Apps */
    val assignable: Boolean get() = keywords.isNotEmpty()
}

class AppRepo(context: Context) {
    private val ctx = context.applicationContext
    private val pm = ctx.packageManager
    private val sp = ctx.getSharedPreferences("launcher", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val icons = object : LruCache<String, ImageBitmap>(160) {}

    private val _apps = MutableStateFlow<List<AppEntry>>(emptyList())
    val apps: StateFlow<List<AppEntry>> = _apps

    /** Kachel -> App (gespeichert oder erraten) */
    private val _tiles = MutableStateFlow<Map<Tile, AppEntry?>>(emptyMap())
    val tiles: StateFlow<Map<Tile, AppEntry?>> = _tiles

    // ---- eigene Symbole (bleiben nur auf dem Gerät, im App-Speicher)
    private val iconDir = java.io.File(ctx.filesDir, "symbole").apply { mkdirs() }
    private val _customIcons = MutableStateFlow<Map<String, ImageBitmap>>(emptyMap())
    val customIcons: StateFlow<Map<String, ImageBitmap>> = _customIcons

    private fun loadCustomIcons() {
        _customIcons.value = (iconDir.listFiles() ?: emptyArray()).mapNotNull { f ->
            val id = f.nameWithoutExtension.lowercase(Locale.ROOT)
            runCatching { android.graphics.BitmapFactory.decodeFile(f.absolutePath)?.asImageBitmap() }.getOrNull()?.let { id to it }
        }.toMap()
    }

    /**
     * Bilder aus einem gewählten Ordner übernehmen (navi.png, medien.png, …).
     * Werden in den App-Speicher kopiert – der Ordner muss danach nicht bleiben.
     */
    fun importIcons(tree: android.net.Uri, ids: List<String>, onDone: (Int) -> Unit) {
        scope.launch {
            var n = 0
            runCatching {
                val cr = ctx.contentResolver
                val treeId = android.provider.DocumentsContract.getTreeDocumentId(tree)
                val children = android.provider.DocumentsContract.buildChildDocumentsUriUsingTree(tree, treeId)
                cr.query(children, arrayOf(
                    android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                ), null, null, null)?.use { c ->
                    while (c.moveToNext()) {
                        val docId = c.getString(0)
                        val name = c.getString(1) ?: continue
                        val id = name.substringBeforeLast('.').lowercase(Locale.ROOT)
                        if (id !in ids) continue
                        val uri = android.provider.DocumentsContract.buildDocumentUriUsingTree(tree, docId)
                        val bmp = cr.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it) } ?: continue
                        // auf vernünftige Größe bringen (Speicher)
                        val maxSide = 512
                        val sc = minOf(1f, maxSide / maxOf(bmp.width, bmp.height).toFloat())
                        val out = if (sc < 1f) Bitmap.createScaledBitmap(bmp, (bmp.width * sc).toInt(), (bmp.height * sc).toInt(), true) else bmp
                        java.io.File(iconDir, "$id.png").outputStream().use { out.compress(Bitmap.CompressFormat.PNG, 100, it) }
                        n++
                    }
                }
            }
            loadCustomIcons()
            onDone(n)
        }
    }

    fun clearCustomIcons() {
        iconDir.listFiles()?.forEach { it.delete() }
        loadCustomIcons()
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) = refresh()
    }

    init {
        runCatching {
            ctx.registerReceiver(receiver, IntentFilter().apply {
                addAction(Intent.ACTION_PACKAGE_ADDED)
                addAction(Intent.ACTION_PACKAGE_REMOVED)
                addAction(Intent.ACTION_PACKAGE_CHANGED)
                addDataScheme("package")
            })
        }
        refresh()
        scope.launch { loadCustomIcons() }
    }

    fun refresh() {
        scope.launch {
            val list = runCatching {
                pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
                    .filter { it.activityInfo.packageName != ctx.packageName }
                    .map { AppEntry(it.activityInfo.packageName, it.activityInfo.name, it.loadLabel(pm).toString().trim()) }
                    .distinctBy { it.key }
                    .sortedBy { it.label.lowercase(Locale.GERMANY) }
            }.getOrDefault(emptyList())
            _apps.value = list
            _tiles.value = Tile.entries.filter { it.assignable }.associateWith { resolve(it, list) }
        }
    }

    private fun resolve(tile: Tile, list: List<AppEntry>): AppEntry? {
        sp.getString("tile_" + tile.id, null)?.let { key ->
            if (key == NONE) return null
            list.firstOrNull { it.key == key }?.let { return it }
            // Klasse geändert (App-Update) -> gleiches Paket nehmen
            list.firstOrNull { it.pkg == key.substringBefore('/') }?.let { return it }
        }
        return guess(tile, list)
    }

    /** Beste Übereinstimmung aus Name und Paket; frühere Schlüsselwörter zählen mehr */
    internal fun guess(tile: Tile, list: List<AppEntry>): AppEntry? = guessTile(tile, list)

    fun assign(tile: Tile, app: AppEntry?) {
        sp.edit().putString("tile_" + tile.id, app?.key ?: NONE).apply()
        refresh()
    }

    fun resetAssignment(tile: Tile) {
        sp.edit().remove("tile_" + tile.id).apply()
        refresh()
    }

    fun icon(app: AppEntry): ImageBitmap? {
        icons.get(app.key)?.let { return it }
        val bmp = runCatching {
            val d = pm.getActivityIcon(ComponentName(app.pkg, app.cls))
            val size = 144
            val b = if (d is BitmapDrawable && d.bitmap != null) Bitmap.createScaledBitmap(d.bitmap, size, size, true)
            else Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).also { bm ->
                d.setBounds(0, 0, size, size)
                d.draw(Canvas(bm))
            }
            b.asImageBitmap()
        }.getOrNull() ?: return null
        icons.put(app.key, bmp)
        return bmp
    }

    fun launch(app: AppEntry): Boolean = runCatching {
        ctx.startActivity(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                .setComponent(ComponentName(app.pkg, app.cls))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        )
        true
    }.getOrDefault(false)

    fun appInfo(app: AppEntry) {
        runCatching {
            ctx.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:" + app.pkg))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    /** Andere Startbildschirme (der Original-Launcher des Radios) – der Notausgang */
    fun otherHomes(): List<AppEntry> = runCatching {
        pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
            .filter { it.activityInfo.packageName != ctx.packageName && it.activityInfo.packageName != "com.android.settings" }
            .map { AppEntry(it.activityInfo.packageName, it.activityInfo.name, it.loadLabel(pm).toString()) }
    }.getOrDefault(emptyList())

    fun launchHome(app: AppEntry): Boolean = runCatching {
        ctx.startActivity(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                .setComponent(ComponentName(app.pkg, app.cls))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        true
    }.getOrDefault(false)

    /** Ist unsere App gerade der Standard-Startbildschirm? */
    fun isDefaultHome(): Boolean = runCatching {
        pm.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY)
            ?.activityInfo?.packageName == ctx.packageName
    }.getOrDefault(false)

    /** Android-Auswahl "Standard-Startbildschirm" öffnen */
    fun openHomeSettings() {
        val tries = listOf(
            Intent(Settings.ACTION_HOME_SETTINGS),
            Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS),
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
        )
        for (i in tries) {
            if (runCatching { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true }.getOrDefault(false)) return
        }
    }

    /** Medientaste an die laufende Musik-App (Bluetooth-Musik, Spotify …) schicken */
    fun mediaKey(code: Int) {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val t = SystemClock.uptimeMillis()
        runCatching {
            am.dispatchMediaKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0))
            am.dispatchMediaKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_UP, code, 0))
        }
    }

    /**
     * Liste aller Apps und ihrer Bildschirme nach Download/W204 schreiben.
     * Damit lässt sich herausfinden, wie z. B. "Original car info" (Umschalten aufs Mercedes-Bild) aufgerufen wird.
     */
    fun export(onDone: (String?) -> Unit) {
        scope.launch {
            val name = "W204-Apps-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.GERMANY).format(Date()) + ".txt"
            val text = buildString {
                appendLine("Geräte: ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} · ${Build.DISPLAY}")
                appendLine()
                appendLine("== Startbildschirme (HOME)")
                otherHomes().forEach { appendLine("${it.label} | ${it.key}") }
                appendLine()
                appendLine("== Kacheln")
                _tiles.value.forEach { (t, a) -> appendLine("${t.title}: ${a?.let { "${it.label} | ${it.key}" } ?: "–"}") }
                appendLine()
                appendLine("== Apps im App-Raster")
                _apps.value.forEach { appendLine("${it.label} | ${it.key}") }
                appendLine()
                appendLine("== Alle Pakete mit aufrufbaren Bildschirmen")
                @Suppress("DEPRECATION")
                val pkgs = runCatching { pm.getInstalledPackages(PackageManager.GET_ACTIVITIES) }.getOrDefault(emptyList())
                pkgs.sortedBy { it.packageName }.forEach { p ->
                    val acts = p.activities?.filter { it.exported }.orEmpty()
                    if (acts.isEmpty()) return@forEach
                    val label = runCatching { p.applicationInfo?.loadLabel(pm)?.toString() }.getOrNull() ?: ""
                    appendLine("${p.packageName}  [$label]  v${p.versionName ?: "?"}")
                    acts.forEach { a -> appendLine("    ${a.name}") }
                }
            }
            val path = runCatching {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, name)
                    put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/W204")
                }
                val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: error("nicht anlegbar")
                ctx.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) } ?: error("nicht beschreibbar")
                "Download/W204/$name"
            }.getOrNull()
            onDone(path)
        }
    }

    fun close() {
        runCatching { ctx.unregisterReceiver(receiver) }
    }

    companion object {
        private const val NONE = "-"
    }
}

/** Reine Zuordnungslogik (testbar ohne Gerät) */
fun guessTile(tile: Tile, list: List<AppEntry>): AppEntry? {
    if (!tile.assignable) return null
    var best: AppEntry? = null
    var bestScore = 0
    for (a in list) {
        val label = a.label.lowercase(Locale.ROOT)
        val pkg = a.pkg.lowercase(Locale.ROOT)
        var score = 0
        tile.keywords.forEachIndexed { i, k ->
            val weight = (tile.keywords.size - i) * 2
            when {
                label == k -> score = maxOf(score, weight + 6)
                label.contains(k) -> score = maxOf(score, weight + 3)
                k.length >= 3 && !k.contains(' ') && pkg.contains(k) -> score = maxOf(score, weight)
            }
        }
        // "Bluetooth-Musik" gehört zur Musik, nicht zum Telefon
        if (tile == Tile.PHONE && (label.contains("music") || label.contains("musik") || label.contains("音乐"))) score = 0
        if (tile == Tile.SETTINGS && label == "settings" && list.any { it.label.lowercase(Locale.ROOT).contains("car setting") }) score = 1
        if (score > bestScore) { bestScore = score; best = a }
    }
    return best
}
