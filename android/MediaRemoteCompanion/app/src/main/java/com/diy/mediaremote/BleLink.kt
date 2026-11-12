package com.diy.mediaremote

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.UUID

/**
 * Keeps a GATT connection to the remote (alongside Android's own HID connection)
 * and writes title / artist / state to it. All state is touched on the main thread.
 */
@SuppressLint("MissingPermission")
object BleLink {
    private const val TAG = "BleLink"
    private const val PREFS = "prefs"
    private const val KEY_DEVICE = "device"

    interface Listener {
        fun onRemoteCommand(cmd: Int)
        fun onRemoteReady()
    }

    var listener: Listener? = null
    var statusListener: ((String) -> Unit)? = null
    var status: String = "Not started"
        private set

    private val main = Handler(Looper.getMainLooper())
    private var ctx: Context? = null
    private var gatt: BluetoothGatt? = null
    private var chars = HashMap<UUID, BluetoothGattCharacteristic>()
    private var ready = false
    private var discovering = false
    private var mtu = 23

    // Latest values to show, and what the remote already has
    private val wanted = HashMap<UUID, ByteArray>()
    private val sent = HashMap<UUID, ByteArray>()

    private sealed class Op {
        data class Write(val uuid: UUID) : Op()
        data class EnableNotify(val uuid: UUID) : Op()
        data class ArtChunk(val id: Int, val index: Int) : Op()   // index -1 = "no art"
    }

    // Album art: latest image to show (null = none) and which id the remote already has
    private var artId = 0
    private var artData: ByteArray? = null
    private var artHave = false            // updateArt() has been called at least once
    private var artSentId = -1
    private var mtuRetried = false          // asked for a bigger MTU a second time on this link
    private var lastArtOp: Op.ArtChunk? = null
    private val queue = ArrayDeque<Op>()
    private var busy = false
    private val opTimeout = Runnable { Log.w(TAG, "GATT op timed out"); busy = false; next() }

    // Reconnect handling. Android's autoConnect often gives up silently after the
    // remote reboots, so a dropped link is always closed and re-opened by us, and a
    // watchdog re-opens it if it hasn't linked for a while.
    private var retryDelayMs = RETRY_MIN_MS
    private const val RETRY_MIN_MS = 2_000L
    private const val RETRY_MAX_MS = 30_000L
    private const val WATCHDOG_MS = 30_000L
    private var lastLinkedOrTriedMs = 0L
    private val reconnect = Runnable { if (gatt == null) connect() }
    private val watchdog: Runnable = object : Runnable {
        override fun run() {
            val now = android.os.SystemClock.elapsedRealtime()
            if (ctx != null && !ready && now - lastLinkedOrTriedMs >= WATCHDOG_MS) {
                Log.i(TAG, "Watchdog: not linked, reopening connection")
                closeGatt()
                connect()
            }
            main.postDelayed(this, WATCHDOG_MS)
        }
    }

    /** True once the 'Now Playing' service is set up. */
    val isLinked: Boolean get() = ready

    // ---------------------------------------------------------------- settings

    fun savedDevice(c: Context): String? =
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_DEVICE, null)

    fun saveDevice(c: Context, address: String) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_DEVICE, address).apply()
    }

    fun hasPermission(c: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            c.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    // ---------------------------------------------------------------- lifecycle

    fun start(context: Context) = main.post {
        ctx = context.applicationContext
        if (gatt == null) connect()
        main.removeCallbacks(watchdog)
        main.postDelayed(watchdog, WATCHDOG_MS)
    }

    fun restart(context: Context) = main.post {
        ctx = context.applicationContext
        retryDelayMs = RETRY_MIN_MS
        closeGatt()
        connect()
        main.removeCallbacks(watchdog)
        main.postDelayed(watchdog, WATCHDOG_MS)
    }

    fun stop() = main.post {
        main.removeCallbacks(watchdog)
        main.removeCallbacks(reconnect)
        closeGatt()
        setStatus("Stopped")
    }

    private fun connect() {
        val c = ctx ?: return
        if (!hasPermission(c)) { setStatus("Needs Bluetooth permission"); return }
        val address = savedDevice(c) ?: run { setStatus("No remote selected"); return }
        val adapter = c.getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null || !adapter.isEnabled) { setStatus("Bluetooth is off"); return }
        val device = adapter.getRemoteDevice(address)
        main.removeCallbacks(reconnect)
        lastLinkedOrTriedMs = android.os.SystemClock.elapsedRealtime()
        setStatus("Waiting for remote...")
        // autoConnect = true: Android reconnects by itself whenever the remote is in range.
        gatt = device.connectGatt(c, true, callback, BluetoothDevice.TRANSPORT_LE)
    }

    private fun closeGatt() {
        main.removeCallbacks(opTimeout)
        gatt?.let { it.disconnect(); it.close() }
        gatt = null
        resetLinkState()
    }

    private fun resetLinkState() {
        artSentId = -1
        mtuRetried = false
        ready = false
        discovering = false
        mtu = 23
        chars.clear()
        queue.clear()
        busy = false
        sent.clear()
    }

    private fun setStatus(s: String) {
        status = s
        Log.i(TAG, s)
        statusListener?.invoke(s)
    }

    // ---------------------------------------------------------------- public API

    /** Called whenever the phone's media info changes. Only changed fields are sent. */
    fun update(title: String?, artist: String?, state: ByteArray) = main.post {
        wantedTitle = title ?: ""
        wantedArtist = artist ?: ""
        wanted[Protocol.STATE] = state
        flush()
    }

    private var wantedTitle = ""
    private var wantedArtist = ""

    /** New album art for the current track (null = the track has none). Sent after the text. */
    fun updateArt(art: ByteArray?) = main.post {
        artId = (artId + 1) and 0xFF
        artData = art
        artHave = true
        flush()
    }

    private fun flush() {
        if (!ready) return
        // Encode here (not in update) so the negotiated MTU is known
        val maxText = minOf(Protocol.TEXT_MAX, mtu - 3)
        wanted[Protocol.TITLE] = Protocol.toAscii(wantedTitle, maxText)
        wanted[Protocol.ARTIST] = Protocol.toAscii(wantedArtist, maxText)
        for (uuid in listOf(Protocol.TITLE, Protocol.ARTIST, Protocol.STATE)) {
            val v = wanted[uuid] ?: continue
            if (!v.contentEquals(sent[uuid])) {
                val op = Op.Write(uuid)
                // text/state go ahead of any album-art chunks still waiting
                if (!queue.contains(op)) {
                    val firstArt = queue.indexOfFirst { it is Op.ArtChunk }
                    if (firstArt < 0) queue.addLast(op) else queue.add(firstArt, op)
                }
            }
        }
        // album art: (re)send when the remote doesn't have the current image yet
        if (artHave && artSentId != artId) {
            when {
                chars[Protocol.ART] == null -> if (artData != null) NowPlaying.setCover(
                    "Cover: the remote isn't offering its cover channel. Forget the remote in your phone's " +
                    "Bluetooth settings, pair it again, then tap Reconnect now.")
                mtu < Protocol.ART_PACKET + 3 -> if (artData != null) NowPlaying.setCover(
                    "Cover: Bluetooth packet size too small (MTU $mtu, needs ${Protocol.ART_PACKET + 3}). Try Reconnect now.")
                else -> {
                    queue.removeAll { it is Op.ArtChunk }            // drop chunks of an older image
                    if (artData == null) queue.addLast(Op.ArtChunk(artId, -1))
                    else {
                        for (i in 0 until Protocol.ART_CHUNKS) queue.addLast(Op.ArtChunk(artId, i))
                        NowPlaying.setCover("Cover: sending...")
                    }
                    artSentId = artId
                }
            }
        }
        next()
    }

    // ---------------------------------------------------------------- op queue

    private fun next() {
        if (busy) return
        val g = gatt ?: return
        val op = queue.removeFirstOrNull() ?: return
        val ok = when (op) {
            is Op.Write -> {
                val ch = chars[op.uuid]
                val value = wanted[op.uuid]
                if (ch == null || value == null) false else {
                    sent[op.uuid] = value
                    writeChar(g, ch, value)
                }
            }
            is Op.ArtChunk -> {
                val ch = chars[Protocol.ART]
                val art = artData
                if (ch == null || op.id != artId) false
                else {
                    lastArtOp = op
                    // Acknowledged writes: each piece is confirmed, so a lost one is noticed and resent
                    if (op.index < 0) writeChar(g, ch, Protocol.artNone(op.id))
                    else if (art == null) false
                    else writeChar(g, ch, Protocol.artChunk(op.id, art, op.index))
                }
            }
            is Op.EnableNotify -> {
                val ch = chars[op.uuid]
                val d = ch?.getDescriptor(Protocol.CCCD)
                if (ch == null || d == null) false else {
                    g.setCharacteristicNotification(ch, true)
                    writeDesc(g, d, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                }
            }
        }
        if (ok) {
            busy = true
            main.postDelayed(opTimeout, 3000)
        } else {
            if (op is Op.Write) sent.remove(op.uuid)
            main.post { next() }
        }
    }

    private fun opDone() {
        main.removeCallbacks(opTimeout)
        busy = false
        next()
    }

    @Suppress("DEPRECATION")
    private fun writeChar(g: BluetoothGatt, ch: BluetoothGattCharacteristic, v: ByteArray,
                          type: Int = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT): Boolean {
        return if (Build.VERSION.SDK_INT >= 33) {
            g.writeCharacteristic(ch, v, type) == BluetoothGatt.GATT_SUCCESS
        } else {
            ch.writeType = type
            ch.value = v
            g.writeCharacteristic(ch)
        }
    }

    @Suppress("DEPRECATION")
    private fun writeDesc(g: BluetoothGatt, d: BluetoothGattDescriptor, v: ByteArray): Boolean {
        return if (Build.VERSION.SDK_INT >= 33) {
            g.writeDescriptor(d, v) == BluetoothGatt.GATT_SUCCESS
        } else {
            d.value = v
            g.writeDescriptor(d)
        }
    }

    // ---------------------------------------------------------------- GATT callbacks (binder thread)

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            main.post {
                if (g != gatt) return@post
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    retryDelayMs = RETRY_MIN_MS
                    setStatus("Connected, setting up...")
                    resetLinkState()
                    discovering = true
                    if (!g.requestMtu(247)) g.discoverServices()
                    // Some phones never answer the MTU request: discover anyway.
                    main.postDelayed({ if (g == gatt && discovering && chars.isEmpty()) g.discoverServices() }, 2000)
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    Log.i(TAG, "Disconnected (status $status), retrying in ${retryDelayMs / 1000} s")
                    // Close this connection object and open a fresh one: a dropped
                    // autoConnect link often never comes back by itself.
                    closeGatt()
                    setStatus("Remote out of range / off - retrying...")
                    main.removeCallbacks(reconnect)
                    main.postDelayed(reconnect, retryDelayMs)
                    retryDelayMs = minOf(retryDelayMs * 2, RETRY_MAX_MS)
                }
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, newMtu: Int, status: Int) {
            main.post {
                if (g != gatt) return@post
                if (status == BluetoothGatt.GATT_SUCCESS) mtu = newMtu
                if (chars.isEmpty()) g.discoverServices()
                else if (!ready) finishSetup()                           // answer to the second MTU request
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            main.post {
                if (g != gatt || !discovering) return@post
                discovering = false
                val svc = g.getService(Protocol.SERVICE)
                if (svc == null) {
                    setStatus("Connected, but 'Now Playing' service missing. Re-pair the remote (see README).")
                    return@post
                }
                for (u in listOf(Protocol.TITLE, Protocol.ARTIST, Protocol.STATE, Protocol.COMMAND, Protocol.ART)) {
                    svc.getCharacteristic(u)?.let { chars[u] = it }
                }
                // If the first MTU request was never confirmed (common when the phone is already
                // connected for the buttons), ask once more before using the link: covers need
                // big packets, and titles are cut short at small ones.
                if (mtu < Protocol.ART_PACKET + 3 && !mtuRetried && g.requestMtu(247)) {
                    mtuRetried = true
                    main.postDelayed({ if (g == gatt && !ready) finishSetup() }, 2000)
                    return@post
                }
                finishSetup()
            }
        }

        private fun finishSetup() {
            if (ready) return
            queue.clear()
            busy = false
            queue.addLast(Op.EnableNotify(Protocol.COMMAND))
            ready = true
            lastLinkedOrTriedMs = android.os.SystemClock.elapsedRealtime()
            setStatus("Linked to remote")
            listener?.onRemoteReady()
            flush()
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int) {
            main.post {
                if (ch.uuid == Protocol.ART && status == BluetoothGatt.GATT_SUCCESS) {
                    val op = lastArtOp
                    if (op != null && op.id == artId && op.index == Protocol.ART_CHUNKS - 1) NowPlaying.setCover("Cover: sent to remote")
                }
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    if (ch.uuid == Protocol.ART) {
                        artSentId = -1                                       // resend the image on the next update
                        NowPlaying.setCover("Cover: a piece failed to send (error $status), retrying")
                    }
                    else sent.remove(ch.uuid)                            // retry on next update
                }
                opDone()
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            main.post { opDone() }
        }

        // Android 13+
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray) {
            handleNotify(ch.uuid, value)
        }

        // Android 12 and older
        @Deprecated("Deprecated in Java")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
            handleNotify(ch.uuid, ch.value ?: return)
        }
    }

    private fun handleNotify(uuid: UUID, value: ByteArray) {
        if (uuid != Protocol.COMMAND || value.isEmpty()) return
        val cmd = value[0].toInt() and 0xFF
        main.post { listener?.onRemoteCommand(cmd) }
    }
}
