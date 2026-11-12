package com.diy.mediaremote

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothManager
import android.content.ComponentName
import android.content.Intent
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView

/** One-screen setup: permissions, notification access, pick the remote, see status. */
@SuppressLint("MissingPermission", "SetTextI18n")
class MainActivity : Activity() {

    private lateinit var permStatus: TextView
    private lateinit var permButton: Button
    private lateinit var accessStatus: TextView
    private lateinit var deviceGroup: RadioGroup
    private lateinit var deviceHint: TextView
    private lateinit var linkStatus: TextView
    private lateinit var nowPlaying: TextView
    private val ui = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() { showNowPlaying(); ui.postDelayed(this, 1000) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        fun heading(t: String) = TextView(this).apply {
            text = t; textSize = 18f; setTypeface(typeface, Typeface.BOLD); setPadding(0, pad, 0, pad / 4)
        }
        fun body(t: String = "") = TextView(this).apply { text = t; textSize = 15f }

        root.addView(TextView(this).apply {
            text = "Media Remote"; textSize = 26f; setTypeface(typeface, Typeface.BOLD)
        })
        root.addView(body("Sends what's playing on this phone to your XIAO remote. " +
            "Pair the remote first in Settings > Bluetooth (it shows up as \"Media Remote\")."))

        root.addView(heading("1. Bluetooth permission"))
        permStatus = body(); root.addView(permStatus)
        permButton = Button(this).apply {
            text = "Allow Bluetooth"
            setOnClickListener {
                if (Build.VERSION.SDK_INT >= 31) requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), 1)
            }
        }
        root.addView(permButton)

        root.addView(heading("2. Notification access"))
        root.addView(body("Needed to read the song title from your music app. " +
            "Nothing else is read or stored."))
        accessStatus = body(); root.addView(accessStatus)
        root.addView(Button(this).apply {
            text = "Open notification access settings"
            setOnClickListener { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
        })

        root.addView(heading("3. Choose your remote"))
        deviceHint = body(); root.addView(deviceHint)
        deviceGroup = RadioGroup(this)
        root.addView(deviceGroup)

        root.addView(heading("Status"))
        linkStatus = body(); root.addView(linkStatus)
        root.addView(heading("Sending to remote"))
        nowPlaying = body(); root.addView(nowPlaying)
        root.addView(Button(this).apply {
            text = "Reconnect now"
            setOnClickListener { BleLink.restart(this@MainActivity) }
        })

        root.addView(heading("Optional"))
        root.addView(body("If the remote stops updating after a while, let this app run without battery restrictions."))
        root.addView(Button(this).apply {
            text = "Battery optimisation settings"
            setOnClickListener { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
        })

        setContentView(ScrollView(this).apply { addView(root) })
    }

    override fun onResume() {
        super.onResume()
        refresh()
        BleLink.statusListener = { s -> runOnUiThread { linkStatus.text = s } }
        linkStatus.text = BleLink.status
        // If Android has paused the media listener, ask it to start again
        if (hasNotificationAccess()) {
            NotificationListenerService.requestRebind(ComponentName(this, MediaListenerService::class.java))
        }
        ui.post(tick)
        // Opening the app always gives a stuck link a fresh start
        if (BleLink.hasPermission(this) && BleLink.savedDevice(this) != null && !BleLink.isLinked) {
            BleLink.restart(this)
        }
    }

    override fun onPause() {
        ui.removeCallbacks(tick)
        BleLink.statusListener = null
        super.onPause()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refresh()
        BleLink.restart(this)
    }

    private fun showNowPlaying() {
        val age = (SystemClock.elapsedRealtime() - NowPlaying.updatedAtMs) / 1000
        nowPlaying.text = when {
            !hasNotificationAccess() -> "Notification access is off (step 2)"
            !NowPlaying.listenerConnected -> "Media listener not running yet. If this stays, switch notification access off and on again."
            else -> "${NowPlaying.text}\nchecked ${age}s ago"
        }
    }

    private fun hasNotificationAccess(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: return false
        val me = ComponentName(this, MediaListenerService::class.java)
        return enabled.split(':').any { ComponentName.unflattenFromString(it) == me }
    }

    private fun refresh() {
        val perm = BleLink.hasPermission(this)
        permStatus.text = if (perm) "Granted" else "Not granted yet"
        permButton.visibility = if (perm) View.GONE else View.VISIBLE

        accessStatus.text = if (hasNotificationAccess()) "Granted" else
            "Not granted: tap below, then switch on \"Media Remote\"."

        deviceGroup.setOnCheckedChangeListener(null)
        deviceGroup.removeAllViews()
        if (!perm) { deviceHint.text = "Grant Bluetooth permission first."; return }

        val adapter = getSystemService(BluetoothManager::class.java)?.adapter
        val bonded = adapter?.bondedDevices?.sortedBy { it.name ?: "" }.orEmpty()
        if (bonded.isEmpty()) {
            deviceHint.text = "No paired devices. Pair the remote in Settings > Bluetooth, then come back."
            return
        }
        deviceHint.text = "Paired devices:"

        var saved = BleLink.savedDevice(this)
        if (saved == null) {
            // First run: auto-select anything called "Media Remote"
            bonded.firstOrNull { it.name?.startsWith("Media Remote") == true }?.let {
                saved = it.address
                BleLink.saveDevice(this, it.address)
                BleLink.restart(this)
            }
        }

        val ids = HashMap<Int, String>()
        for (d in bonded) {
            val rb = RadioButton(this).apply {
                id = View.generateViewId()
                text = "${d.name ?: "Unknown"}  (${d.address})"
                isChecked = d.address == saved
            }
            ids[rb.id] = d.address
            deviceGroup.addView(rb)
        }
        deviceGroup.setOnCheckedChangeListener { _, checkedId ->
            val addr = ids[checkedId] ?: return@setOnCheckedChangeListener
            BleLink.saveDevice(this, addr)
            BleLink.restart(this)
        }
    }
}
