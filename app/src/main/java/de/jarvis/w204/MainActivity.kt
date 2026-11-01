package de.jarvis.w204

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import de.jarvis.w204.launcher.LauncherRoot
import de.jarvis.w204.ui.JarvisTheme

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Notausgang: stürzt die App als Startbildschirm mehrfach kurz hintereinander ab,
        // wird der Original-Startbildschirm des Radios geöffnet statt in einer Schleife zu hängen.
        if (isHomeIntent(intent) && CrashGuard.recentCrashes(180_000L) >= 2 && openOriginalHome()) {
            CrashGuard.clearCrashTimes()
            Toast.makeText(this, "Dashboard ist abgestürzt – Original-Startbildschirm geöffnet", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.statusBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        setContent {
            JarvisTheme { LauncherRoot(vm) }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Home-Taste, während die App schon vorne ist
        if (isHomeIntent(intent)) vm.homePressed()
    }

    private fun isHomeIntent(i: Intent?) = i?.action == Intent.ACTION_MAIN && i.hasCategory(Intent.CATEGORY_HOME)

    private fun openOriginalHome(): Boolean = runCatching {
        val other = packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
            .firstOrNull { it.activityInfo.packageName != packageName && it.activityInfo.packageName != "com.android.settings" }
            ?: return false
        startActivity(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                .setComponent(ComponentName(other.activityInfo.packageName, other.activityInfo.name))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        true
    }.getOrDefault(false)
}
