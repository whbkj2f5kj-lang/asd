package de.jarvis.w204.volta

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.service.notification.NotificationListenerService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Nur dafür da, dass Android uns die laufenden Medien-Sitzungen (Musik-Apps) zeigt. */
class MediaListener : NotificationListenerService() {
    override fun onListenerConnected() { NowPlaying.instance?.refresh() }
}

data class Track(
    val title: String,
    val artist: String,
    val art: Bitmap?,
    val playing: Boolean,
    val durationMs: Long,
    /** Position zum Zeitpunkt [at] */
    val positionMs: Long,
    val at: Long,
    val app: String,
) {
    fun positionNow(): Long =
        if (!playing || durationMs <= 0) positionMs
        else (positionMs + (SystemClock.elapsedRealtime() - at)).coerceAtMost(durationMs)
}

/** Was gerade spielt – aus jeder App, die Android eine Medien-Sitzung meldet (Spotify, Bluetooth-Musik, …). */
class NowPlaying(context: Context) {
    private val ctx = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val msm = ctx.getSystemService(MediaSessionManager::class.java)
    private val component = ComponentName(ctx, MediaListener::class.java)
    private var controller: MediaController? = null

    private val _track = MutableStateFlow<Track?>(null)
    val track: StateFlow<Track?> = _track
    private val _allowed = MutableStateFlow(false)
    val allowed: StateFlow<Boolean> = _allowed

    private val cb = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = publish()
        override fun onPlaybackStateChanged(state: PlaybackState?) = publish()
        override fun onSessionDestroyed() { refresh() }
    }
    private val sessions = MediaSessionManager.OnActiveSessionsChangedListener { refresh() }
    private var listening = false

    init { instance = this; refresh() }

    fun hasAccess(): Boolean = runCatching {
        val flat = Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners") ?: ""
        flat.split(':').any { ComponentName.unflattenFromString(it)?.packageName == ctx.packageName }
    }.getOrDefault(false)

    fun openAccessSettings() {
        runCatching { ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    fun refresh() { main.post { refreshNow() } }

    private fun refreshNow() {
        val ok = hasAccess()
        _allowed.value = ok
        if (!ok) { _track.value = null; return }
        val list = runCatching { msm.getActiveSessions(component) }.getOrNull().orEmpty()
        if (!listening) runCatching { msm.addOnActiveSessionsChangedListener(sessions, component, main); listening = true }
        val best = list.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: list.firstOrNull()
        if (best?.sessionToken != controller?.sessionToken) {
            controller?.unregisterCallback(cb)
            controller = best
            best?.registerCallback(cb, main)
        }
        publish()
    }

    private fun publish() {
        val c = controller
        val m = c?.metadata
        if (c == null || m == null) { _track.value = null; return }
        val st = c.playbackState
        val label = runCatching {
            ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(c.packageName, 0)).toString()
        }.getOrDefault(c.packageName)
        _track.value = Track(
            title = m.getString(MediaMetadata.METADATA_KEY_TITLE) ?: m.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE) ?: "",
            artist = m.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: m.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
                ?: m.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE) ?: "",
            art = m.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) ?: m.getBitmap(MediaMetadata.METADATA_KEY_ART)
                ?: m.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON),
            playing = st?.state == PlaybackState.STATE_PLAYING,
            durationMs = m.getLong(MediaMetadata.METADATA_KEY_DURATION),
            positionMs = st?.position ?: 0L,
            at = st?.lastPositionUpdateTime?.takeIf { it > 0 } ?: SystemClock.elapsedRealtime(),
            app = label,
        )
    }

    fun playPause() { val c = controller ?: return; if (c.playbackState?.state == PlaybackState.STATE_PLAYING) c.transportControls.pause() else c.transportControls.play() }
    fun next() { controller?.transportControls?.skipToNext() }
    fun previous() { controller?.transportControls?.skipToPrevious() }
    fun seek(ms: Long) { controller?.transportControls?.seekTo(ms) }

    companion object { @Volatile var instance: NowPlaying? = null }
}
