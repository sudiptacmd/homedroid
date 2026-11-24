package dev.homedroid

import android.annotation.SuppressLint
import android.app.Activity
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.window.OnBackInvokedDispatcher
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.TextView

/** Shown over the app while [AppLock] is locked: the PIN, or a fingerprint or face. */
class LockActivity : Activity() {
    private lateinit var pin: EditText
    private lateinit var message: TextView
    private val ui = Handler(Looper.getMainLooper())
    private var cancel: CancellationSignal? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(if (MobileUi.dark(this)) R.style.AppThemeDark else R.style.AppTheme)
        super.onCreate(savedInstanceState)
        // The locked app shouldn't show in screenshots or the recents preview.
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val design = MobileUi(this)
        val page = design.page()
        page.gravity = Gravity.CENTER_HORIZONTAL
        val card = design.card()
        card.addView(design.text("Homedroid is locked", 22f, bold = true))
        card.addView(design.text("Enter your PIN to continue.", 14f, design.muted))
        pin = design.field(card, "PIN", "", secret = true).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            imeOptions = EditorInfo.IME_ACTION_DONE
            setOnEditorActionListener { _, action, _ -> if (action == EditorInfo.IME_ACTION_DONE) { submit(); true } else false }
        }
        message = design.text("", 13f, design.warning).apply { setPadding(0, design.dp(10), 0, 0) }
        card.addView(message)
        card.addView(design.button("Unlock", primary = true) { submit() })
        if (AppLock.biometricEnabled(this) && AppLock.biometricAvailable(this)) {
            card.addView(design.button("Use fingerprint or face") { biometric() })
        }
        page.addView(card)
        if (Build.VERSION.SDK_INT >= 33) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT) { moveTaskToBack(true) }
        }
        refresh()
    }

    override fun onResume() {
        super.onResume()
        if (!AppLock.enabled(this)) { finish(); return }
        if (AppLock.biometricEnabled(this) && AppLock.biometricAvailable(this) && AppLock.waitMs(this) == 0L) biometric()
    }

    override fun onPause() {
        cancel?.cancel(); cancel = null
        super.onPause()
    }

    override fun onDestroy() { ui.removeCallbacksAndMessages(null); super.onDestroy() }

    /**
     * Back leaves the app instead of revealing the screen underneath. Android 13+ uses the
     * callback registered in onCreate; older versions have no callback API without AndroidX.
     */
    @SuppressLint("GestureBackNavigation")
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) { moveTaskToBack(true); return true }
        return super.onKeyDown(keyCode, event)
    }

    private fun submit() {
        val given = pin.text.toString()
        pin.setText("")
        if (AppLock.tryPin(this, given)) { done(); return }
        refresh(if (AppLock.waitMs(this) == 0L) "Wrong PIN" else null)
    }

    /** Shows the lockout countdown, ticking until the PIN may be tried again. */
    private fun refresh(error: String? = null) {
        ui.removeCallbacksAndMessages(null)
        val wait = AppLock.waitMs(this)
        pin.isEnabled = wait == 0L
        message.setTextIfChanged(when {
            wait > 0 -> "Too many wrong PINs. Try again in ${(wait + 999) / 1000} s."
            else -> error.orEmpty()
        })
        if (wait > 0) ui.postDelayed({ refresh() }, 1000)
    }

    private fun biometric() {
        if (cancel != null) return
        val signal = CancellationSignal().also { cancel = it }
        val executor = mainExecutor
        BiometricPrompt.Builder(this)
            .setTitle("Unlock Homedroid")
            .setNegativeButton("Use PIN", executor) { _, _ -> cancel = null }
            .build()
            .authenticate(signal, executor, object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    cancel = null
                    AppLock.unlockedByBiometric(this@LockActivity)
                    done()
                }
                override fun onAuthenticationError(code: Int, text: CharSequence) { cancel = null }
            })
    }

    private fun done() {
        finish()
        @Suppress("DEPRECATION") overridePendingTransition(0, 0)
    }
}
