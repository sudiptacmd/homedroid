package android.content

import java.io.File

// Host-only doubles. Preferences are in memory; assets come from the repository.
class Context(val filesDir: File) {
    val cacheDir = File(filesDir, "cache").apply { mkdirs() }
    val applicationInfo = ApplicationInfo()
    val assets = Assets()
    private val prefs = mutableMapOf<String, Preferences>()
    fun getSharedPreferences(name: String, mode: Int) = prefs.getOrPut(name) { Preferences() }
    companion object { const val MODE_PRIVATE = 0 }
}
class ApplicationInfo { val nativeLibraryDir = "/unused/x86_64" }
class Assets { fun open(name: String) = File("app/src/main/assets", name).inputStream() }
class Preferences {
    private val values = mutableMapOf<String, Any>()
    fun getBoolean(key: String, default: Boolean) = values[key] as? Boolean ?: default
    fun getString(key: String, default: String?) = values[key] as? String ?: default
    fun edit() = Editor()
    inner class Editor {
        fun putBoolean(key: String, value: Boolean) = apply { values[key] = value }
        fun putString(key: String, value: String?) = apply { if (value == null) values.remove(key) else values[key] = value }
        fun remove(key: String) = apply { values.remove(key) }
        fun apply() {}
    }
}
