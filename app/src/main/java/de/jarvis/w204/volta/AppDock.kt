package de.jarvis.w204.volta

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.Locale

data class DockApp(val pkg: String, val cls: String, val label: String) {
    val key get() = "$pkg/$cls"
}

/** Ein Platz in der App-Leiste; die App dahinter wird erraten und kann per langem Druck geändert werden */
enum class DockSlot(val id: String, val title: String, val keywords: List<String>) {
    PHONE("phone", "Telefon", listOf("bluetooth", "phone", "telefon", "dialer", "蓝牙")),
    CARPLAY("carplay", "CarPlay", listOf("zlink", "carplay", "autokit", "carlink", "tlink", "easyconn", "carapp")),
    MUSIC("music", "Musik", listOf("spotify", "music", "musik", "player", "音乐")),
    RADIO("radio", "Radio", listOf("radio", "fm", "tuner", "收音机", "dab")),
    VIDEO("video", "Video", listOf("youtube", "video", "movie", "视频")),
    NAVI("navi", "Navi", listOf("google maps", "maps", "waze", "navi", "osmand", "导航")),
    SETTINGS("settings", "Radio-Einstellungen", listOf("car settings", "carsetting", "car setting", "设置", "settings")),
}

class AppDock(context: Context, private val scope: CoroutineScope) {
    private val ctx = context.applicationContext
    private val pm = ctx.packageManager
    private val sp = ctx.getSharedPreferences("dock", Context.MODE_PRIVATE)
    private val icons = HashMap<String, ImageBitmap?>()

    private val _apps = MutableStateFlow<List<DockApp>>(emptyList())
    val apps: StateFlow<List<DockApp>> = _apps
    private val _slots = MutableStateFlow<Map<DockSlot, DockApp?>>(emptyMap())
    val slots: StateFlow<Map<DockSlot, DockApp?>> = _slots

    init { refresh() }

    fun refresh() = scope.launch(Dispatchers.IO) {
        val list = runCatching {
            pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
                .filter { it.activityInfo.packageName != ctx.packageName }
                .map { DockApp(it.activityInfo.packageName, it.activityInfo.name, it.loadLabel(pm).toString().trim()) }
                .distinctBy { it.key }.sortedBy { it.label.lowercase(Locale.GERMANY) }
        }.getOrDefault(emptyList())
        _apps.value = list
        _slots.value = DockSlot.entries.associateWith { resolve(it, list) }
    }

    private fun resolve(s: DockSlot, list: List<DockApp>): DockApp? {
        sp.getString(s.id, null)?.let { k ->
            if (k == "-") return null
            list.firstOrNull { it.key == k }?.let { return it }
            list.firstOrNull { it.pkg == k.substringBefore('/') }?.let { return it }
        }
        return guess(s, list)
    }

    fun assign(s: DockSlot, app: DockApp?) { sp.edit().putString(s.id, app?.key ?: "-").apply(); refresh() }
    fun reset(s: DockSlot) { sp.edit().remove(s.id).apply(); refresh() }

    fun launch(app: DockApp): Boolean = runCatching {
        ctx.startActivity(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(ComponentName(app.pkg, app.cls))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        ); true
    }.getOrDefault(false)

    fun icon(app: DockApp): ImageBitmap? = icons.getOrPut(app.key) {
        runCatching {
            val d = pm.getActivityIcon(ComponentName(app.pkg, app.cls))
            val n = 128
            (if (d is BitmapDrawable && d.bitmap != null) Bitmap.createScaledBitmap(d.bitmap, n, n, true)
            else Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888).also { b -> d.setBounds(0, 0, n, n); d.draw(Canvas(b)) }).asImageBitmap()
        }.getOrNull()
    }

    companion object {
        /** Beste Übereinstimmung aus Name und Paket; frühere Schlüsselwörter zählen mehr */
        fun guess(s: DockSlot, list: List<DockApp>): DockApp? {
            var best: DockApp? = null; var bestScore = 0
            for (a in list) {
                val label = a.label.lowercase(Locale.ROOT); val pkg = a.pkg.lowercase(Locale.ROOT)
                var score = 0
                s.keywords.forEachIndexed { i, k ->
                    val w = (s.keywords.size - i) * 2
                    when {
                        label == k -> score = maxOf(score, w + 6)
                        label.contains(k) -> score = maxOf(score, w + 3)
                        k.length >= 3 && !k.contains(' ') && pkg.contains(k) -> score = maxOf(score, w)
                    }
                }
                if (s == DockSlot.PHONE && (label.contains("music") || label.contains("musik"))) score = 0
                if (s == DockSlot.MUSIC && label.contains("bluetooth") && list.any { it.label.lowercase(Locale.ROOT).contains("spotify") }) score = 1
                if (s == DockSlot.SETTINGS && label == "settings" && list.any { it.label.lowercase(Locale.ROOT).contains("car setting") }) score = 1
                if (score > bestScore) { bestScore = score; best = a }
            }
            return best
        }
    }
}
