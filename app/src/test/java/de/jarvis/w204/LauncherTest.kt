package de.jarvis.w204

import de.jarvis.w204.launcher.AppEntry
import de.jarvis.w204.launcher.Tile
import de.jarvis.w204.launcher.guessTile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LauncherTest {
    // so ungefähr sieht das App-Raster des Radios aus (aus dem Video)
    private val apps = listOf(
        AppEntry("com.syu.bt", "com.syu.bt.Main", "Bluetooth"),
        AppEntry("com.syu.carsetting", "com.syu.carsetting.Main", "Car Settings"),
        AppEntry("com.syu.music", "com.syu.music.Main", "Music"),
        AppEntry("com.syu.btmusic", "com.syu.btmusic.Main", "Bluetooth Music"),
        AppEntry("com.syu.dsp", "com.syu.dsp.Main", "DSP"),
        AppEntry("com.google.android.youtube", "x.Main", "YouTube"),
        AppEntry("com.zjinnova.zlink", "x.Main", "ZLINK"),
        AppEntry("com.autonavi.amapauto", "x.Main", "Amap Auto"),
        AppEntry("com.android.settings", "x.Settings", "Settings"),
        AppEntry("com.syu.canbus", "x.Main", "Original car info"),
        AppEntry("com.syu.video", "x.Main", "Video"),
    )

    @Test fun carplayIsZlink() = assertEquals("ZLINK", guessTile(Tile.CARPLAY, apps)?.label)
    @Test fun musicIsMusic() = assertEquals("Music", guessTile(Tile.MUSIC, apps)?.label)
    @Test fun phoneIsNotBtMusic() = assertEquals("Bluetooth", guessTile(Tile.PHONE, apps)?.label)
    @Test fun carSettingsBeatsAndroidSettings() = assertEquals("Car Settings", guessTile(Tile.SETTINGS, apps)?.label)
    @Test fun originalCarInfo() = assertEquals("Original car info", guessTile(Tile.CAR, apps)?.label)
    @Test fun navi() = assertEquals("Amap Auto", guessTile(Tile.NAVI, apps)?.label)
    @Test fun video() = assertEquals("Video", guessTile(Tile.VIDEO, apps)?.label)
    @Test fun cockpitNotAssignable() = assertNull(guessTile(Tile.COCKPIT, apps))
    @Test fun googleMapsPreferred() {
        val more = apps + AppEntry("com.google.android.apps.maps", "x.Main", "Maps")
        assertEquals("Maps", guessTile(Tile.NAVI, more)?.label)
    }
}
