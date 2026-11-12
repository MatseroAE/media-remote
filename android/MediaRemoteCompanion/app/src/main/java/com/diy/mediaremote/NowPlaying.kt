package com.diy.mediaremote

/** What the app last sent to the remote, shown on the setup screen for troubleshooting. */
object NowPlaying {
    @Volatile var text: String = "(nothing yet)"
        private set
    @Volatile var updatedAtMs: Long = 0L
        private set
    @Volatile var listenerConnected: Boolean = false
    /** Album-art status, e.g. "Cover: sent to remote" or why it wasn't sent. */
    @Volatile var cover: String = ""
        private set
    var onChange: (() -> Unit)? = null

    fun setCover(s: String) {
        if (s != cover) { cover = s; onChange?.invoke() }
    }

    fun set(s: String) {
        if (s != text) { text = s; onChange?.invoke() }
        updatedAtMs = android.os.SystemClock.elapsedRealtime()
    }
}
