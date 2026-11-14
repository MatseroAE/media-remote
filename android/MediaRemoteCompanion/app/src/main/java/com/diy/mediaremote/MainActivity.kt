package com.diy.mediaremote

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.ComponentName
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import kotlin.math.roundToInt

// Palette: matches the remote's "Sharp" look (dark body, aluminium grey, orange power key)
private val BG = 0xFF111214.toInt()
private val SURFACE = 0xFF1B1D21.toInt()
private val SURFACE2 = 0xFF25282D.toInt()
private val OUTLINE = 0xFF30343A.toInt()
private val TEXT = 0xFFF2F3F5.toInt()
private val DIM = 0xFFA2A8B0.toInt()
private val FAINT = 0xFF6B717A.toInt()
private val ACCENT = 0xFFFF7A1A.toInt()
private val ON_ACCENT = 0xFF1A0E05.toInt()
private val GREEN = 0xFF3DD68C.toInt()
private val AMBER = 0xFFF5B83D.toInt()
private val RED = 0xFFFF6B6B.toInt()

private const val SOURCE_URL = "https://github.com/MatseroAE/media-remote"
private const val REMOTE_NAME = "Media Remote"

/**
 * Two screens built in code (no extra libraries):
 *  - Setup: three guided steps (Bluetooth, notification access, choose the remote).
 *  - Home: connection status, what's on the remote right now, settings, troubleshooting.
 */
@SuppressLint("MissingPermission", "SetTextI18n")
class MainActivity : Activity() {

    private enum class Mode { SETUP, HOME }
    private var mode: Mode? = null

    // UI state that survives a re-render
    private var showRestrictedHelp = false
    private var showAllDevices = false
    private var troubleshootOpen = false

    // Live views on the home screen (null on the setup screen)
    private var statusDot: View? = null
    private var statusTitle: TextView? = null
    private var statusBody: TextView? = null
    private var statusAction: TextView? = null
    private var artImage: ImageView? = null
    private var artPlaceholder: TextView? = null
    private var trackTitle: TextView? = null
    private var trackArtist: TextView? = null
    private var appRow: LinearLayout? = null
    private var appIcon: ImageView? = null
    private var appText: TextView? = null
    private var backgroundValue: TextView? = null
    private var diagText: TextView? = null
    private var shownArt: Bitmap? = null
    private var pulse: ObjectAnimator? = null

    private val ui = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            if (computeMode() != mode) render() else updateLive()
            ui.postDelayed(this, 1000)
        }
    }

    private val appInfoCache = HashMap<String, Pair<String, Drawable?>>()
    private val openedAtMs = SystemClock.elapsedRealtime()

    // ================================================================ lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        render()
    }

    override fun onResume() {
        super.onResume()
        render()
        BleLink.statusListener = { _ -> runOnUiThread { updateLive() } }
        // If Android has paused the media listener, ask it to start again
        if (hasNotificationAccess()) {
            NotificationListenerService.requestRebind(ComponentName(this, MediaListenerService::class.java))
        }
        ui.removeCallbacks(tick)
        ui.postDelayed(tick, 1000)
        // Opening the app always gives a stuck link a fresh start
        if (BleLink.hasPermission(this) && BleLink.savedDevice(this) != null && !BleLink.isLinked) {
            BleLink.restart(this)
        }
    }

    override fun onPause() {
        ui.removeCallbacks(tick)
        BleLink.statusListener = null
        pulse?.cancel()
        super.onPause()
    }

    // Folding / unfolding: re-lay out without restarting the activity
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        render()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        render()
        BleLink.restart(this)
    }

    // ================================================================ state

    private fun hasNotificationAccess(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: return false
        val me = ComponentName(this, MediaListenerService::class.java)
        return enabled.split(':').any { ComponentName.unflattenFromString(it) == me }
    }

    private fun computeMode(): Mode =
        if (BleLink.hasPermission(this) && hasNotificationAccess() && BleLink.savedDevice(this) != null) Mode.HOME
        else Mode.SETUP

    private fun bondedDevices(): List<BluetoothDevice> {
        if (!BleLink.hasPermission(this)) return emptyList()
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter ?: return emptyList()
        return try { adapter.bondedDevices?.sortedBy { it.name ?: "" }.orEmpty() } catch (e: SecurityException) { emptyList() }
    }

    private fun isRemote(d: BluetoothDevice) = d.name?.startsWith(REMOTE_NAME) == true

    /** Display name; adds the end of the address only when two devices share a name. */
    private fun labelFor(d: BluetoothDevice, all: List<BluetoothDevice>): String {
        val name = d.name ?: "Unnamed device"
        val dup = all.count { it.name == d.name } > 1
        return if (dup) "$name  ·  ${d.address.takeLast(5)}" else name
    }

    private fun savedDeviceName(): String {
        val addr = BleLink.savedDevice(this) ?: return "None"
        return bondedDevices().firstOrNull { it.address == addr }?.name ?: REMOTE_NAME
    }

    private fun chooseDevice(address: String) {
        BleLink.saveDevice(this, address)
        BleLink.restart(this)
        render()
    }

    /** First run with exactly one paired remote: pick it, no need to ask. */
    private fun autoPickRemote() {
        if (BleLink.savedDevice(this) != null) return
        val remotes = bondedDevices().filter(::isRemote)
        if (remotes.size == 1) {
            BleLink.saveDevice(this, remotes[0].address)
            BleLink.restart(this)
        }
    }

    private fun appInfo(pkg: String): Pair<String, Drawable?> = appInfoCache.getOrPut(pkg) {
        try {
            @Suppress("DEPRECATION")
            val ai = packageManager.getApplicationInfo(pkg, 0)
            packageManager.getApplicationLabel(ai).toString() to packageManager.getApplicationIcon(ai)
        } catch (e: Exception) {
            "your music app" to null
        }
    }

    private fun versionName(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
    } catch (e: Exception) { "?" }

    // ================================================================ actions

    private fun requestBluetooth() {
        if (Build.VERSION.SDK_INT < 31) return
        val prefs = getSharedPreferences("ui", MODE_PRIVATE)
        val askedBefore = prefs.getBoolean("askedBt", false)
        if (askedBefore && !shouldShowRequestPermissionRationale(Manifest.permission.BLUETOOTH_CONNECT)) {
            // Android won't show the prompt again: send them to the app's permission page
            openAppInfo()
            toast("Tap Permissions, then Nearby devices, then Allow")
            return
        }
        prefs.edit().putBoolean("askedBt", true).apply()
        requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), 1)
    }

    /** Straight to Media Remote's own switch where Android allows it, else the full list. */
    private fun openNotificationAccess() {
        val me = ComponentName(this, MediaListenerService::class.java)
        if (Build.VERSION.SDK_INT >= 30) {
            val detail = Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, me.flattenToString())
            if (tryStart(detail)) return
        }
        tryStart(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
    }

    private fun openAppInfo() {
        tryStart(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
    }

    private fun openBluetoothSettings() { tryStart(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }

    private fun ignoringBatteryLimits(): Boolean =
        getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(packageName) == true

    @SuppressLint("BatteryLife")
    private fun requestBackground() {
        if (!ignoringBatteryLimits()) {
            val ask = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
            if (tryStart(ask)) return
        }
        tryStart(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
    }

    private fun turnOnBluetooth() {
        if (!tryStart(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))) openBluetoothSettings()
    }

    private fun openSource() { tryStart(Intent(Intent.ACTION_VIEW, Uri.parse(SOURCE_URL))) }

    private fun tryStart(i: Intent): Boolean = try { startActivity(i); true } catch (e: Exception) { false }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    private fun showRemoteChooser() {
        val all = bondedDevices().sortedWith(compareBy({ !isRemote(it) }, { it.name ?: "" }))
        if (all.isEmpty()) { openBluetoothSettings(); return }
        val saved = BleLink.savedDevice(this)
        val labels = all.map { labelFor(it, all) }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Choose your remote")
            .setSingleChoiceItems(labels, all.indexOfFirst { it.address == saved }) { d, i ->
                d.dismiss()
                chooseDevice(all[i].address)
            }
            .setNeutralButton("Pair a new one") { _, _ -> openBluetoothSettings() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ================================================================ rendering

    private fun render() {
        if (BleLink.hasPermission(this)) autoPickRemote()
        mode = computeMode()
        clearLiveViews()
        val wide = resources.configuration.screenWidthDp >= 600

        val content = if (mode == Mode.SETUP) buildSetup() else buildHome(wide)
        val maxW = if (mode == Mode.SETUP || !wide) 560 else 1000
        val frame = FrameLayout(this).apply {
            addView(content, FrameLayout.LayoutParams(
                minOf(dp(maxW), resources.displayMetrics.widthPixels),
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL))
        }
        setContentView(ScrollView(this).apply {
            setBackgroundColor(BG)
            isFillViewport = true
            addView(frame)
        })
        updateLive()
    }

    private fun clearLiveViews() {
        pulse?.cancel(); pulse = null
        statusDot = null; statusTitle = null; statusBody = null; statusAction = null
        artImage = null; artPlaceholder = null; shownArt = null
        trackTitle = null; trackArtist = null; appRow = null; appIcon = null; appText = null
        backgroundValue = null; diagText = null
    }

    private fun header(): View = row().apply {
        addView(ImageView(this@MainActivity).apply { setImageResource(R.mipmap.ic_launcher) }, lp(dp(44), dp(44)))
        addView(column().apply {
            addView(text("Media Remote", 20f, TEXT, bold = true))
            addView(text("Now playing, on your remote", 13f, DIM))
        }, lp(0, wrap, weight = 1f).also { it.leftMargin = dp(12) })
    }

    // ---------------------------------------------------------------- setup screen

    private fun buildSetup(): View {
        val perm = BleLink.hasPermission(this)
        val access = hasNotificationAccess()
        val chosen = BleLink.savedDevice(this) != null
        val active = when { !perm -> 1; !access -> 2; !chosen -> 3; else -> 0 }

        return column().apply {
            setPadding(dp(20), dp(20), dp(20), dp(28))
            addView(header())
            addView(text("Set up your remote", 26f, TEXT, bold = true), lp(match, wrap, top = 28))
            addView(text("Three quick steps, about a minute. After that the app works in the background " +
                "and you won't need to open it.", 15f, DIM), lp(match, wrap, top = 6))

            addView(stepCard(1, "Allow Nearby devices", perm, active == 1) { body ->
                body.addView(para("Your remote talks to this phone over Bluetooth. Android calls this permission " +
                    "“Nearby devices”."))
                body.addView(checkLine("It can't see your location"), lp(match, wrap, top = 10))
                body.addView(checkLine("It only connects to the remote you choose"), lp(match, wrap, top = 6))
                body.addView(primaryButton("Allow") { requestBluetooth() }, lp(match, dp(48), top = 16))
            }, lp(match, wrap, top = 20))

            addView(stepCard(2, "Let it see what's playing", access, active == 2) { body ->
                body.addView(para("To show the song on your remote, the app needs to see what your music app is " +
                    "playing. Android only allows that through “notification access”, so it will show " +
                    "you a strong warning. Here's what Media Remote actually does with it:"))
                body.addView(checkLine("Reads the song, artist, artwork and position from whatever's playing"), lp(match, wrap, top = 12))
                body.addView(checkLine("Ignores every other notification. Your messages are never read or saved"), lp(match, wrap, top = 6))
                body.addView(checkLine("Has no internet access, so nothing can leave your phone except to your remote"), lp(match, wrap, top = 6))
                body.addView(primaryButton("Open Settings") { openNotificationAccess() }, lp(match, dp(48), top = 16))
                body.addView(text("Switch on Media Remote, then come back here.", 13f, FAINT).apply {
                    gravity = Gravity.CENTER
                }, lp(match, wrap, top = 8))
                body.addView(link(if (showRestrictedHelp) "Hide help" else "Switch greyed out?") {
                    showRestrictedHelp = !showRestrictedHelp; render()
                }, lp(wrap, wrap, top = 6).also { it.gravity = Gravity.CENTER_HORIZONTAL })
                if (showRestrictedHelp) {
                    body.addView(column().apply {
                        background = rounded(SURFACE2, 14)
                        setPadding(dp(14), dp(12), dp(14), dp(14))
                        addView(para("Android locks this switch for apps installed from a file instead of the " +
                            "Play Store. To unlock it: open App info, tap ⋮ in the top right, choose " +
                            "“Allow restricted settings”, then try again."))
                        addView(secondaryButton("Open App info") { openAppInfo() }, lp(match, dp(44), top = 12))
                    }, lp(match, wrap, top = 8))
                }
            }, lp(match, wrap, top = 12))

            addView(stepCard(3, "Choose your remote", chosen, active == 3) { body -> buildDevicePicker(body) },
                lp(match, wrap, top = 12))

            addView(privacyCard(), lp(match, wrap, top = 20))
        }
    }

    private fun buildDevicePicker(body: LinearLayout) {
        val bonded = bondedDevices()
        val remotes = bonded.filter(::isRemote)
        val list = if (showAllDevices) bonded else remotes

        if (list.isEmpty()) {
            body.addView(para("Turn your remote on, then pair it in Bluetooth settings. " +
                "It shows up as “$REMOTE_NAME”."))
            body.addView(primaryButton("Open Bluetooth settings") { openBluetoothSettings() }, lp(match, dp(48), top = 16))
        } else {
            body.addView(para(if (showAllDevices) "All paired devices:" else "Tap your remote:"))
            for (d in list) {
                body.addView(deviceRow(labelFor(d, bonded), isRemote(d)) { chooseDevice(d.address) },
                    lp(match, wrap, top = 8))
            }
            body.addView(link("Pair a new remote") { openBluetoothSettings() },
                lp(wrap, wrap, top = 10))
        }
        if (bonded.size > remotes.size) {
            body.addView(link(if (showAllDevices) "Only show remotes" else "Don't see it? Show all paired devices") {
                showAllDevices = !showAllDevices; render()
            }, lp(wrap, wrap, top = 2))
        }
    }

    private fun stepCard(n: Int, title: String, done: Boolean, active: Boolean, body: (LinearLayout) -> Unit): View {
        val card = card()
        card.addView(row().apply {
            val badge = text(if (done) "✓" else "$n", 14f, if (done || active) ON_ACCENT else DIM, bold = true).apply {
                gravity = Gravity.CENTER
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(when { done -> GREEN; active -> ACCENT; else -> SURFACE2 })
                }
            }
            addView(badge, lp(dp(28), dp(28)))
            addView(text(title, 17f, if (done || active) TEXT else DIM, bold = true),
                lp(0, wrap, weight = 1f).also { it.leftMargin = dp(12) })
            if (done) addView(text("Done", 13f, GREEN))
        })
        if (active) {
            val inner = column()
            body(inner)
            card.addView(inner, lp(match, wrap, top = 14))
        }
        return card
    }

    private fun deviceRow(label: String, looksLikeRemote: Boolean, onClick: () -> Unit): View = row().apply {
        background = ripple(rounded(SURFACE2, 14, OUTLINE), 14)
        setPadding(dp(14), dp(12), dp(14), dp(12))
        addView(View(this@MainActivity).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(if (looksLikeRemote) ACCENT else FAINT) }
        }, lp(dp(10), dp(10)))
        addView(text(label, 15f, TEXT).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END },
            lp(0, wrap, weight = 1f).also { it.leftMargin = dp(12) })
        addView(text("Use", 14f, ACCENT, bold = true))
        isClickable = true
        setOnClickListener { onClick() }
    }

    // ---------------------------------------------------------------- home screen

    private fun buildHome(wide: Boolean): View {
        val root = column().apply { setPadding(dp(20), dp(20), dp(20), dp(28)) }
        root.addView(header())

        val left = column()
        left.addView(statusCard(), lp(match, wrap, top = 0))
        left.addView(nowPlayingCard(wide), lp(match, wrap, top = 12))

        val right = column()
        right.addView(settingsCard(), lp(match, wrap, top = 0))
        right.addView(privacyCard(), lp(match, wrap, top = 12))
        right.addView(troubleshootCard(), lp(match, wrap, top = 12))

        if (wide) {
            // Unfolded: two columns side by side
            root.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(left, lp(0, wrap, weight = 1f).also { it.rightMargin = dp(8) })
                addView(right, lp(0, wrap, weight = 1f).also { it.leftMargin = dp(8) })
            }, lp(match, wrap, top = 20))
        } else {
            root.addView(left, lp(match, wrap, top = 20))
            root.addView(right, lp(match, wrap, top = 12))
        }
        root.addView(text("Version ${versionName()}  ·  Open source", 12f, FAINT).apply {
            gravity = Gravity.CENTER
        }, lp(match, wrap, top = 20))
        return root
    }

    private fun statusCard(): View = card().apply {
        addView(row().apply {
            val dot = View(this@MainActivity).apply {
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(FAINT) }
            }
            statusDot = dot
            addView(dot, lp(dp(12), dp(12)))
            addView(column().apply {
                statusTitle = text("", 17f, TEXT, bold = true).also { addView(it) }
                statusBody = text("", 14f, DIM).also { addView(it, lp(match, wrap, top = 2)) }
            }, lp(0, wrap, weight = 1f).also { it.leftMargin = dp(14) })
        })
        statusAction = secondaryButton("") {}.also { addView(it, lp(match, dp(44), top = 14)) }
    }

    private fun nowPlayingCard(wide: Boolean): View = card().apply {
        addView(text("ON YOUR REMOTE", 11f, DIM, bold = true).apply { letterSpacing = 0.12f })
        val artSize = if (wide) 140 else 96
        val art = FrameLayout(this@MainActivity).apply {
            background = rounded(SURFACE2, 14)
            outlineProvider = ViewOutlineProvider.BACKGROUND
            clipToOutline = true
            artPlaceholder = text("♪", 34f, FAINT).apply { gravity = Gravity.CENTER }
                .also { addView(it, FrameLayout.LayoutParams(match, match)) }
            artImage = ImageView(this@MainActivity).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
                .also { addView(it, FrameLayout.LayoutParams(match, match)) }
        }
        addView(row().apply {
            gravity = Gravity.TOP
            addView(art, lp(dp(artSize), dp(artSize)))
            addView(column().apply {
                trackTitle = text("", if (wide) 20f else 18f, TEXT, bold = true).apply {
                    maxLines = 2; ellipsize = TextUtils.TruncateAt.END
                }.also { addView(it) }
                trackArtist = text("", 15f, DIM).apply {
                    maxLines = 2; ellipsize = TextUtils.TruncateAt.END
                }.also { addView(it, lp(match, wrap, top = 2)) }
                appRow = row().apply {
                    appIcon = ImageView(this@MainActivity).also { addView(it, lp(dp(16), dp(16))) }
                    appText = text("", 13f, FAINT).also {
                        addView(it, lp(wrap, wrap).also { p -> p.leftMargin = dp(6) })
                    }
                }.also { addView(it, lp(match, wrap, top = 10)) }
            }, lp(0, wrap, weight = 1f).also { it.leftMargin = dp(16) })
        }, lp(match, wrap, top = 12))
    }

    private fun settingsCard(): View = card(padV = 6).apply {
        addView(settingRow("Remote", savedDeviceName()) { showRemoteChooser() })
        addView(divider())
        backgroundValue = settingRow("Run in background", "", onClick = ::requestBackground).let { r ->
            addView(r); r.getChildAt(1) as TextView
        }
        addView(divider())
        addView(settingRow("Notification access", "On") { openNotificationAccess() })
        addView(divider())
        addView(settingRow("Reconnect", "") {
            BleLink.restart(this@MainActivity)
            toast("Reconnecting to your remote")
        })
    }

    private fun troubleshootCard(): View = card(padV = 6).apply {
        addView(settingRow("Troubleshooting", if (troubleshootOpen) "▴" else "▾", chevron = false) {
            troubleshootOpen = !troubleshootOpen; render()
        })
        if (troubleshootOpen) {
            diagText = text("", 12f, DIM).apply {
                typeface = Typeface.MONOSPACE
                setTextIsSelectable(true)
                setPadding(0, dp(4), 0, dp(14))
            }.also { addView(it) }
        }
    }

    private fun privacyCard(): View = card().apply {
        addView(text("Private by design", 15f, TEXT, bold = true))
        addView(text("No internet access and nothing is stored. Only the song, artist, artwork and " +
            "position go to your remote, over Bluetooth.", 14f, DIM), lp(match, wrap, top = 4))
        addView(link("View the source code") { openSource() }, lp(wrap, wrap, top = 6))
    }

    // ---------------------------------------------------------------- live updates

    private fun updateLive() {
        updateStatus()
        updateNowPlaying()
        backgroundValue?.text = if (ignoringBatteryLimits()) "Allowed" else "Recommended"
        backgroundValue?.setTextColor(if (ignoringBatteryLimits()) DIM else ACCENT)
        diagText?.let { d ->
            val age = (SystemClock.elapsedRealtime() - NowPlaying.updatedAtMs) / 1000
            d.text = buildString {
                appendLine("Link:    ${BleLink.status}")
                appendLine("Reader:  ${if (NowPlaying.listenerConnected) "running" else "not running"}")
                appendLine("Sending: ${NowPlaying.text}")
                if (NowPlaying.cover.isNotEmpty()) appendLine(NowPlaying.cover)
                append("Checked ${age}s ago  ·  app ${versionName()}")
            }
        }
    }

    private fun updateStatus() {
        val title = statusTitle ?: return
        val adapterOn = getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled == true
        data class S(val color: Int, val title: String, val body: String, val action: String? = null,
                     val onAction: (() -> Unit)? = null, val pulsing: Boolean = false)
        val s = when {
            !adapterOn -> S(RED, "Bluetooth is off", "Turn it on so the app can reach your remote.",
                "Turn on Bluetooth", ::turnOnBluetooth)
            BleLink.link == BleLink.Link.NEEDS_REPAIR -> S(RED, "Pair your remote again",
                "Your phone remembers an older version of the remote. Forget it in Bluetooth settings, " +
                    "pair it again, then tap Reconnect.", "Open Bluetooth settings", ::openBluetoothSettings)
            !NowPlaying.listenerConnected && SystemClock.elapsedRealtime() - openedAtMs > 5000 -> S(AMBER, "Can't see what's playing",
                "Android paused the part of the app that reads your music. Switch notification access " +
                    "off and on again.", "Open Settings", ::openNotificationAccess)
            BleLink.link == BleLink.Link.LINKED -> S(GREEN, "Connected", "Your ${savedDeviceName()} is up to date.")
            BleLink.link == BleLink.Link.CONNECTING -> S(AMBER, "Connecting…", "Found your remote, setting it up.",
                pulsing = true)
            else -> S(AMBER, "Looking for your remote…", "Make sure it's switched on and nearby.", pulsing = true)
        }
        title.text = s.title
        statusBody?.text = s.body
        (statusDot?.background as? GradientDrawable)?.setColor(s.color)
        statusAction?.let { a ->
            a.visibility = if (s.action != null) View.VISIBLE else View.GONE
            a.text = s.action ?: ""
            a.setOnClickListener { s.onAction?.invoke() }
        }
        val dot = statusDot ?: return
        if (s.pulsing && pulse == null) {
            pulse = ObjectAnimator.ofFloat(dot, View.ALPHA, 1f, 0.25f).apply {
                duration = 800; repeatMode = ValueAnimator.REVERSE; repeatCount = ValueAnimator.INFINITE; start()
            }
        } else if (!s.pulsing && pulse != null) {
            pulse?.cancel(); pulse = null; dot.alpha = 1f
        }
    }

    private fun updateNowPlaying() {
        val t = trackTitle ?: return
        if (!NowPlaying.hasPlayer) {
            t.text = "Nothing playing"
            trackArtist?.text = "Start some music and it'll show up here and on your remote."
            appRow?.visibility = View.GONE
        } else {
            t.text = NowPlaying.title.ifBlank { "Unknown title" }
            trackArtist?.text = NowPlaying.artist.ifBlank { "Unknown artist" }
            val pkg = NowPlaying.appPackage
            if (pkg != null) {
                val (label, icon) = appInfo(pkg)
                appRow?.visibility = View.VISIBLE
                appIcon?.setImageDrawable(icon)
                appIcon?.visibility = if (icon != null) View.VISIBLE else View.GONE
                appText?.text = "$label  ·  ${if (NowPlaying.playing) "Playing" else "Paused"}"
            } else appRow?.visibility = View.GONE
        }
        val art = if (NowPlaying.hasPlayer) NowPlaying.art else null
        if (art !== shownArt) {
            shownArt = art
            artImage?.setImageBitmap(art)
            artImage?.visibility = if (art != null) View.VISIBLE else View.GONE
            artPlaceholder?.visibility = if (art != null) View.GONE else View.VISIBLE
        }
    }

    // ================================================================ view kit

    private val match = ViewGroup.LayoutParams.MATCH_PARENT
    private val wrap = ViewGroup.LayoutParams.WRAP_CONTENT

    private fun dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()

    private fun lp(w: Int, h: Int, top: Int = 0, weight: Float = 0f) =
        LinearLayout.LayoutParams(w, h, weight).also { it.topMargin = dp(top) }

    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

    private fun row() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    private fun rounded(color: Int, radiusDp: Int, stroke: Int? = null) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp).toFloat()
        stroke?.let { setStroke(dp(1), it) }
    }

    private fun ripple(content: Drawable?, radiusDp: Int) =
        RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), content, rounded(Color.WHITE, radiusDp))

    private fun text(s: CharSequence, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = s
        textSize = size
        setTextColor(color)
        typeface = Typeface.create(if (bold) "sans-serif-medium" else "sans-serif", Typeface.NORMAL)
        setLineSpacing(0f, 1.15f)
    }

    private fun para(s: String) = text(s, 15f, DIM)

    private fun checkLine(s: String): View = row().apply {
        gravity = Gravity.TOP
        addView(text("✓", 15f, GREEN, bold = true), lp(dp(24), wrap))
        addView(text(s, 14f, TEXT), lp(0, wrap, weight = 1f))
    }

    private fun card(padV: Int = 18) = column().apply {
        background = rounded(SURFACE, 20)
        setPadding(dp(18), dp(padV), dp(18), dp(padV))
    }

    private fun divider() = View(this).apply { setBackgroundColor(OUTLINE) }
        .also { it.layoutParams = lp(match, dp(1)) }

    private fun button(label: String, fill: Int, textColor: Int, stroke: Int?, onClick: () -> Unit) =
        text(label, 15f, textColor, bold = true).apply {
            gravity = Gravity.CENTER
            background = ripple(rounded(fill, 24, stroke), 24)
            setPadding(dp(20), 0, dp(20), 0)
            isClickable = true
            setOnClickListener { onClick() }
        }

    private fun primaryButton(label: String, onClick: () -> Unit) = button(label, ACCENT, ON_ACCENT, null, onClick)
    private fun secondaryButton(label: String, onClick: () -> Unit) = button(label, SURFACE2, TEXT, OUTLINE, onClick)

    private fun link(label: String, onClick: () -> Unit) = text(label, 14f, ACCENT, bold = true).apply {
        background = ripple(null, 8)
        setPadding(dp(4), dp(8), dp(4), dp(8))
        isClickable = true
        setOnClickListener { onClick() }
    }

    /** Title on the left, value on the right; child 1 is the value TextView. */
    private fun settingRow(title: String, value: String, chevron: Boolean = true, onClick: () -> Unit): LinearLayout =
        row().apply {
            background = ripple(null, 12)
            setPadding(0, dp(14), 0, dp(14))
            addView(text(title, 15f, TEXT), lp(0, wrap, weight = 1f))
            addView(text(value, 14f, DIM).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
            if (chevron) addView(text("›", 20f, FAINT), lp(wrap, wrap).also { it.leftMargin = dp(10) })
            isClickable = true
            setOnClickListener { onClick() }
        }
}
