package com.diy.mediaremote

import android.graphics.Bitmap

/** What the app last sent to the remote: shown on the main screen, plus text lines for troubleshooting. */
object NowPlaying {
    // ---- shown on the main screen
    @Volatile var hasPlayer: Boolean = false
        private set
    @Volatile var title: String = ""
        private set
    @Volatile var artist: String = ""
        private set
    @Volatile var playing: Boolean = false
        private set
    /** Package of the app that's playing, e.g. com.spotify.music. */
    @Volatile var appPackage: String? = null
        private set
    /** Cover as sent to the remote, or null when the app shares none. */
    @Volatile var art: Bitmap? = null
        private set

    // ---- troubleshooting
    @Volatile var text: String = "(nothing yet)"
        private set
    @Volatile var updatedAtMs: Long = 0L
        private set
    @Volatile var listenerConnected: Boolean = false
    /** Album-art status, e.g. "Cover: sent to remote" or why it wasn't sent. */
    @Volatile var cover: String = ""
        private set
    var onChange: (() -> Unit)? = null

    fun setTrack(hasPlayer: Boolean, title: String, artist: String, playing: Boolean, appPackage: String?) {
        this.hasPlayer = hasPlayer
        this.title = title
        this.artist = artist
        this.playing = playing
        this.appPackage = appPackage
    }

    fun setArt(bmp: Bitmap?) { art = bmp }

    fun setCover(s: String) {
        if (s != cover) { cover = s; onChange?.invoke() }
    }

    fun set(s: String) {
        if (s != text) { text = s; onChange?.invoke() }
        updatedAtMs = android.os.SystemClock.elapsedRealtime()
    }
}
