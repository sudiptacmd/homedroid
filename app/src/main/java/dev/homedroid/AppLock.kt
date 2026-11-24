package dev.homedroid

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.hardware.biometrics.BiometricManager
import android.os.Build
import android.os.Bundle
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/** PIN hashing and wrong-PIN backoff for [AppLock], kept free of Android so it can be tested. */
object AppLockCore {
    const val ITERATIONS = 60_000
    const val FREE_ATTEMPTS = 5
    private const val FIRST_LOCKOUT = 30_000L
    private const val MAX_LOCKOUT = 3600_000L

    fun validPin(pin: String) = Regex("[0-9]{4,8}").matches(pin)

    fun newSalt(): ByteArray = ByteArray(16).also(SecureRandom()::nextBytes)

    fun hash(pin: String, salt: ByteArray, iterations: Int = ITERATIONS): ByteArray =
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(PBEKeySpec(pin.toCharArray(), salt, iterations, 256)).encoded

    /** "iterations:salt:hash", base64. */
    fun encode(pin: String, salt: ByteArray = newSalt(), iterations: Int = ITERATIONS): String {
        val b = Base64.getEncoder()
        return "$iterations:${b.encodeToString(salt)}:${b.encodeToString(hash(pin, salt, iterations))}"
    }

    fun verify(pin: String, stored: String): Boolean = try {
        val (it, salt, hash) = stored.split(':').also { require(it.size == 3) }
        val d = Base64.getDecoder()
        MessageDigest.isEqual(hash(pin, d.decode(salt), it.toInt()), d.decode(hash))
    } catch (_: Exception) { false }

    /** How long to wait after [failures] wrong PINs in a row: none for the first five, then 30 s doubling to an hour. */
    fun lockoutMs(failures: Int): Long =
        if (failures < FREE_ATTEMPTS) 0 else minOf(MAX_LOCKOUT, FIRST_LOCKOUT shl minOf(failures - FREE_ATTEMPTS, 20))

    /** Milliseconds until another attempt is allowed; a lock time far in the future (clock changed) is capped. */
    fun waitMs(failures: Int, lastFailure: Long, now: Long): Long {
        val lockout = lockoutMs(failures)
        if (lockout == 0L) return 0
        val until = minOf(lastFailure, now) + lockout
        return (until - now).coerceAtLeast(0)
    }
}

/**
 * Optional lock for the phone app: a 4–8 digit PIN (salted PBKDF2), optionally unlocked with a
 * fingerprint or face, asked for again once the app has been in the background for [timeoutSeconds].
 *
 * Screens call [requireUnlock] in onResume; it shows [LockActivity] over them when needed.
 */
object AppLock {
    private const val PREFS = "applock"
    @Volatile private var unlocked = false
    @Volatile private var backgroundSince = 0L
    private var started = 0
    private var watching = false

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun enabled(ctx: Context) = prefs(ctx).getString("pin", null) != null

    /** Sets or replaces the PIN. Returns false if it isn't 4–8 digits. */
    fun setPin(ctx: Context, pin: String): Boolean {
        if (!AppLockCore.validPin(pin)) return false
        prefs(ctx).edit().putString("pin", AppLockCore.encode(pin)).putInt("failures", 0).commit()
        unlocked = true
        return true
    }

    fun clearPin(ctx: Context) {
        prefs(ctx).edit().clear().commit()
        unlocked = false
    }

    /** Fingerprint or face unlock, with the PIN as the fallback; only meaningful with a PIN set. */
    fun biometricEnabled(ctx: Context) = enabled(ctx) && prefs(ctx).getBoolean("biometric", false)

    fun setBiometricEnabled(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean("biometric", on).apply()

    /** Whether this phone has a fingerprint or face enrolled that the app can use. */
    fun biometricAvailable(ctx: Context): Boolean {
        val m = ctx.getSystemService(BiometricManager::class.java) ?: return false
        return if (Build.VERSION.SDK_INT >= 30) m.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK) == BiometricManager.BIOMETRIC_SUCCESS
        else @Suppress("DEPRECATION") (m.canAuthenticate() == BiometricManager.BIOMETRIC_SUCCESS)
    }

    /** Seconds in the background before the PIN is asked for again; 0 is at once. */
    fun timeoutSeconds(ctx: Context) = prefs(ctx).getInt("timeout", 60)

    fun setTimeoutSeconds(ctx: Context, seconds: Int) = prefs(ctx).edit().putInt("timeout", seconds.coerceIn(0, 86400)).apply()

    /** Milliseconds before another PIN may be tried, after too many wrong ones. */
    fun waitMs(ctx: Context): Long = prefs(ctx).let { AppLockCore.waitMs(it.getInt("failures", 0), it.getLong("last_failure", 0), System.currentTimeMillis()) }

    /** Checks [pin], counting wrong ones. Returns false while locked out, too. */
    fun tryPin(ctx: Context, pin: String): Boolean = synchronized(this) {
        val p = prefs(ctx)
        if (waitMs(ctx) > 0) return false
        val stored = p.getString("pin", null) ?: return true
        if (AppLockCore.verify(pin, stored)) {
            p.edit().putInt("failures", 0).apply()
            unlocked = true
            return true
        }
        p.edit().putInt("failures", p.getInt("failures", 0) + 1).putLong("last_failure", System.currentTimeMillis()).commit()
        return false
    }

    /** After a successful fingerprint or face check. */
    fun unlockedByBiometric(ctx: Context) {
        prefs(ctx).edit().putInt("failures", 0).apply()
        unlocked = true
    }

    /**
     * Call from every screen's onResume. Returns true when the app is unlocked (or has no PIN);
     * otherwise opens the lock screen over [activity] and returns false.
     */
    fun requireUnlock(activity: Activity): Boolean {
        if (activity is LockActivity) return true
        watch(activity.application)
        if (!enabled(activity)) return true
        val away = backgroundSince
        if (unlocked && away > 0 && System.currentTimeMillis() - away >= timeoutSeconds(activity) * 1000L) unlocked = false
        backgroundSince = 0
        if (unlocked) return true
        activity.startActivity(Intent(activity, LockActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        return false
    }

    /** Notes when the whole app goes to the background (no screen started), for the timeout. */
    @Synchronized private fun watch(app: Application) {
        if (watching) return
        watching = true
        started = 1 // the screen calling requireUnlock
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(a: Activity) { synchronized(AppLock) { started++ } }
            override fun onActivityStopped(a: Activity) {
                synchronized(AppLock) {
                    started = maxOf(0, started - 1)
                    if (started == 0 && !a.isChangingConfigurations) backgroundSince = System.currentTimeMillis()
                }
            }
            override fun onActivityCreated(a: Activity, b: Bundle?) {}
            override fun onActivityResumed(a: Activity) {}
            override fun onActivityPaused(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
            override fun onActivityDestroyed(a: Activity) {}
        })
    }
}
