package com.diy.mediaremote

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.notification.NotificationListenerService

/**
 * Android keeps this service running while "Notification access" is granted.
 * It watches whichever app is playing media (Spotify, YouTube Music, podcasts...)
 * and forwards title / artist / position / volume to the remote.
 */
class MediaListenerService : NotificationListenerService(), BleLink.Listener {

    private val main = Handler(Looper.getMainLooper())
    private lateinit var sessions: MediaSessionManager
    private lateinit var audio: AudioManager
    private var controller: MediaController? = null
    private var receiverRegistered = false

    private val myComponent get() = ComponentName(this, MediaListenerService::class.java)

    private val sessionsChanged = MediaSessionManager.OnActiveSessionsChangedListener { list ->
        pickController(list.orEmpty())
    }

    private val controllerCallback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = push()
        override fun onPlaybackStateChanged(state: PlaybackState?) {
            // A different app may have started playing: re-pick
            refreshSessions()
            push()
        }
        override fun onSessionDestroyed() = refreshSessions()
    }

    private val volumeReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) = push()
    }

    // Poll every 2 s instead of relying only on callbacks: some players (and some
    // phones) stop delivering them, which left the remote stuck on an old song.
    // BleLink only transmits fields that actually changed, so this is cheap.
    private var lastPositionPushMs = 0L
    private val periodic = object : Runnable {
        override fun run() {
            refreshSessions()
            val playing = controller?.playbackState?.state == PlaybackState.STATE_PLAYING
            val now = SystemClock.elapsedRealtime()
            if (!playing || now - lastPositionPushMs >= 5000) {  // position re-sync every 5 s while playing
                push()
            } else {
                pushIfInfoChanged()
            }
            main.postDelayed(this, 2000)
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        NowPlaying.listenerConnected = true
        sessions = getSystemService(MediaSessionManager::class.java)
        audio = getSystemService(AudioManager::class.java)
        sessions.addOnActiveSessionsChangedListener(sessionsChanged, myComponent, main)
        refreshSessions()

        if (!receiverRegistered) {
            val filter = IntentFilter("android.media.VOLUME_CHANGED_ACTION")
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(volumeReceiver, filter, Context.RECEIVER_EXPORTED)
            else registerReceiver(volumeReceiver, filter)
            receiverRegistered = true
        }

        BleLink.listener = this
        BleLink.start(this)
        main.removeCallbacks(periodic)
        main.post(periodic)
    }

    override fun onListenerDisconnected() {
        NowPlaying.listenerConnected = false
        NowPlaying.set("(not reading media: Android paused the listener - open the app to restart it)")
        cleanup()
        NotificationListenerService.requestRebind(myComponent)
        super.onListenerDisconnected()
    }

    override fun onDestroy() {
        cleanup()
        super.onDestroy()
    }

    private fun cleanup() {
        main.removeCallbacks(periodic)
        if (::sessions.isInitialized) sessions.removeOnActiveSessionsChangedListener(sessionsChanged)
        controller?.unregisterCallback(controllerCallback)
        controller = null
        if (receiverRegistered) { unregisterReceiver(volumeReceiver); receiverRegistered = false }
        if (BleLink.listener === this) BleLink.listener = null
    }

    private fun refreshSessions() {
        try {
            pickController(sessions.getActiveSessions(myComponent))
        } catch (e: SecurityException) {
            // Notification access was revoked
        }
    }

    /**
     * Prefer the session that is playing; if several claim to be (a stale browser
     * tab often does), take the one whose playback state changed most recently.
     * Otherwise keep the current one, else the most recent session.
     */
    private fun pickController(list: List<MediaController>) {
        // Android returns the sessions in priority order: the app that most recently started
        // playing comes first (the same order the phone's own media controls use). Follow it:
        // switching apps moves the new one to the top, and the order only changes when playback
        // starts somewhere, so two "playing" apps can't make the remote flip back and forth.
        // The list is re-read every 2 s (see `periodic`), so a missed callback can't leave it stuck.
        val best = list.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: list.firstOrNull { it.sessionToken == controller?.sessionToken }   // nothing playing: keep the current one
            ?: list.firstOrNull()
        if (best?.sessionToken == controller?.sessionToken) return
        controller?.unregisterCallback(controllerCallback)
        controller = best
        best?.registerCallback(controllerCallback, main)
        push()
    }

    private fun currentPosition(ps: PlaybackState?): Long {
        if (ps == null || ps.position < 0) return 0
        var pos = ps.position
        if (ps.state == PlaybackState.STATE_PLAYING) {
            val elapsed = SystemClock.elapsedRealtime() - ps.lastPositionUpdateTime
            pos += (elapsed * ps.playbackSpeed).toLong()
        }
        return pos
    }

    private fun push() {
        if (!::audio.isInitialized) return
        val c = controller
        val md = c?.metadata
        val ps = c?.playbackState

        val title = md?.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?: md?.description?.title?.toString()
        val artist = md?.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: md?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            ?: md?.description?.subtitle?.toString()

        val playing = ps?.state == PlaybackState.STATE_PLAYING ||
            ps?.state == PlaybackState.STATE_BUFFERING
        val duration = md?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
        val timeValid = duration > 0 && ps != null && ps.position >= 0

        val state = Protocol.state(
            playing = playing,
            timeValid = timeValid,
            positionMs = currentPosition(ps).coerceIn(0, if (duration > 0) duration else Long.MAX_VALUE),
            durationMs = duration,
            volume = audio.getStreamVolume(AudioManager.STREAM_MUSIC),
            volumeMax = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC),
        )
        lastInfoKey = infoKey()
        if (playing) lastPositionPushMs = SystemClock.elapsedRealtime()
        BleLink.update(title ?: "", artist ?: "", state)
        pushArt(c, md, title, artist)        // after the text, so the title shows first
        NowPlaying.set(
            if (c == null) "(no media app found)"
            else "${title ?: "?"} - ${artist ?: "?"}  [${if (playing) "playing" else "paused"}, ${c.packageName}]"
        )
    }

    // ---------------------------------------------------------------- album art
    private var lastArtKey: String? = null
    private var lastArtHadBitmap = false

    /** Sends the cover once per track. Re-checks a track that had none, since some players add it late. */
    private fun pushArt(c: MediaController?, md: MediaMetadata?, title: String?, artist: String?) {
        val album = md?.getString(MediaMetadata.METADATA_KEY_ALBUM)
        val key = "${c?.packageName}|$title|$artist|$album"
        if (key == lastArtKey && lastArtHadBitmap) return
        val bmp = artBitmap(md)
        if (key == lastArtKey && bmp == null) return            // still nothing: don't resend "no art"
        lastArtKey = key
        lastArtHadBitmap = bmp != null
        val data = try { bmp?.let { Protocol.artToRgb565(it) } } catch (e: Exception) { null }
        BleLink.updateArt(data)
    }

    private fun artBitmap(md: MediaMetadata?): Bitmap? {
        if (md == null) return null
        for (k in listOf(MediaMetadata.METADATA_KEY_ALBUM_ART, MediaMetadata.METADATA_KEY_ART,
                         MediaMetadata.METADATA_KEY_DISPLAY_ICON)) {
            md.getBitmap(k)?.let { return it }
        }
        // Some players only give a content:// link to the image
        for (k in listOf(MediaMetadata.METADATA_KEY_ALBUM_ART_URI, MediaMetadata.METADATA_KEY_ART_URI,
                         MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI)) {
            val s = md.getString(k) ?: continue
            val uri = Uri.parse(s)
            if (uri.scheme != "content" && uri.scheme != "android.resource" && uri.scheme != "file") continue
            try {
                contentResolver.openInputStream(uri)?.use { input ->
                    val opts = BitmapFactory.Options().apply { inSampleSize = 4 }   // keep it small
                    BitmapFactory.decodeStream(input, null, opts)?.let { return it }
                }
            } catch (e: Exception) { /* not readable by us: no art */ }
        }
        return null
    }

    // Title/artist/play-state/volume, used to push immediately when any of them change
    private var lastInfoKey = ""
    private fun infoKey(): String {
        val c = controller
        val md = c?.metadata
        return listOf(
            c?.packageName, md?.getString(MediaMetadata.METADATA_KEY_TITLE),
            md?.getString(MediaMetadata.METADATA_KEY_ARTIST), c?.playbackState?.state,
            md?.getLong(MediaMetadata.METADATA_KEY_DURATION),
            if (::audio.isInitialized) audio.getStreamVolume(AudioManager.STREAM_MUSIC) else 0,
        ).joinToString("|")
    }
    private fun pushIfInfoChanged() { if (infoKey() != lastInfoKey) push() }

    // ---------------------------------------------------------------- BleLink.Listener

    override fun onRemoteReady() = push()

    override fun onRemoteCommand(cmd: Int) {
        val c = controller ?: return
        val ps = c.playbackState
        val duration = c.metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
        val pos = currentPosition(ps)
        when (cmd) {
            Protocol.CMD_SEEK_FWD -> {
                val target = pos + Protocol.SEEK_STEP_MS
                c.transportControls.seekTo(if (duration > 0) minOf(target, duration - 1000) else target)
            }
            Protocol.CMD_SEEK_BACK -> c.transportControls.seekTo(maxOf(0L, pos - Protocol.SEEK_STEP_MS))
        }
        main.postDelayed({ push() }, 300)
    }
}
