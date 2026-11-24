package dev.homedroid

import android.Manifest
import android.app.Activity
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher

/**
 * Dock mode: a full-screen bedside/desk clock with the server's vitals. Keeps the screen on,
 * hides the system bars, and when opened because the phone was plugged in, shows over the lock
 * screen and closes again when it's unplugged.
 */
class DockActivity : Activity(), DockScreen.Host {
    companion object {
        const val EXTRA_CHARGER = "dev.homedroid.dock.CHARGER"

        /** True while the dock is in the foreground, so plugging in doesn't offer it twice. */
        @Volatile var visible = false
            private set

        /** Opens Dock mode (the entry point for the main screen). */
        fun open(ctx: Context) = ctx.startActivity(intent(ctx, false))

        fun intent(ctx: Context, charger: Boolean): Intent = Intent(ctx, DockActivity::class.java)
            .putExtra(EXTRA_CHARGER, charger)
            .addFlags(if (ctx is Activity) 0 else Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    private lateinit var screen: DockScreen
    private var unplugged: BroadcastReceiver? = null
    private var backCallback: Any? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        screen = DockScreen(this, true, this)
        setContentView(screen.root)
        hideSystemBars()
        enter(intent)
        if (Build.VERSION.SDK_INT >= 33) {
            val cb = OnBackInvokedCallback { if (!screen.back()) finish() }
            onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, cb)
            backCallback = cb
        }
    }

    /** Before Android 13 there's no OnBackInvokedCallback: catch the back key to close the sheet first. */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (Build.VERSION.SDK_INT < 33 && event.keyCode == KeyEvent.KEYCODE_BACK && screen.settingsOpen) {
            if (event.action == KeyEvent.ACTION_UP) screen.back()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        enter(intent)
    }

    /** How the dock was opened: by plugging in (over the lock screen, closes when unplugged) or from settings. */
    private fun enter(intent: Intent) {
        DockLauncher.cancelNotification(this)
        val charger = intent.getBooleanExtra(EXTRA_CHARGER, false)
        setShowWhenLocked(charger)
        setTurnScreenOn(charger)
        if (charger && unplugged == null) {
            unplugged = object : BroadcastReceiver() {
                override fun onReceive(c: Context, i: Intent) = finish()
            }.also { DockLauncher.registerSystem(this, it, IntentFilter(Intent.ACTION_POWER_DISCONNECTED)) }
        } else if (!charger) {
            unplugged?.let { runCatching { unregisterReceiver(it) } }
            unplugged = null
        }
        // Opened from the screen-saver settings' gear (see the DockSettings alias in the manifest).
        if (intent.component?.className?.endsWith(".DockSettings") == true) screen.root.post { screen.openSettings() }
    }

    override fun onResume() {
        super.onResume()
        visible = true
        hideSystemBars()
        screen.start()
    }

    override fun onPause() {
        visible = false
        screen.stop()
        super.onPause()
    }

    override fun onDestroy() {
        unplugged?.let { runCatching { unregisterReceiver(it) } }
        if (Build.VERSION.SDK_INT >= 33) (backCallback as? OnBackInvokedCallback)?.let { onBackInvokedDispatcher.unregisterOnBackInvokedCallback(it) }
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        screen.root.post { screen.rotated() }
    }

    private fun hideSystemBars() {
        if (Build.VERSION.SDK_INT >= 30) {
            // Edge-to-edge is the default from Android 15.
            if (Build.VERSION.SDK_INT < 35) @Suppress("DEPRECATION") window.setDecorFitsSystemWindows(false)
            window.insetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        }
    }

    // ---- DockScreen.Host -------------------------------------------------------------------

    override fun exit() = finish()

    override fun setDim(low: Boolean) {
        window.attributes = window.attributes.apply {
            screenBrightness = if (low) NIGHT_BRIGHTNESS else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        }
    }

    override fun setKeepScreenOn(on: Boolean) {
        if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun requestUnlock(then: () -> Unit) {
        val km = getSystemService(KeyguardManager::class.java)
        if (km?.isKeyguardLocked != true) return then()
        km.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
            override fun onDismissSucceeded() = then()
        })
    }

    override fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 41)
        }
    }
}

/** Night-mode backlight: low enough for a dark bedroom, high enough to stay readable. */
private const val NIGHT_BRIGHTNESS = 0.03f
