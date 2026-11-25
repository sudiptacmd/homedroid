package dev.homedroid

import android.service.dreams.DreamService

/**
 * Dock mode as the phone's screen saver (Settings → Display → Screen saver → Homedroid Dock).
 * Android starts it by itself while the phone charges or sits in a dock, which is more reliable
 * than any app-started activity. Touching the screen ends it, as with any screen saver.
 */
class DockDream : DreamService(), DockScreen.Host {
    private var screen: DockScreen? = null

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isInteractive = false
        isFullscreen = true
        isScreenBright = true
        val s = DockScreen(this, false, this)
        screen = s
        setContentView(s.root)
    }

    override fun onDreamingStarted() {
        super.onDreamingStarted()
        screen?.start()
    }

    override fun onDreamingStopped() {
        screen?.stop()
        super.onDreamingStopped()
    }

    override fun onDetachedFromWindow() {
        screen?.stop()
        screen = null
        super.onDetachedFromWindow()
    }

    override fun exit() = finish()

    override fun setDim(low: Boolean) {
        isScreenBright = !low
    }

    /** "Let it sleep" hours: end the screen saver so the system can turn the screen off. */
    override fun setKeepScreenOn(on: Boolean) {
        if (!on) wakeUp()
    }
}
