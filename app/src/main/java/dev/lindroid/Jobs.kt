package dev.lindroid

/** State of the current (or last) app install/uninstall, shown by [AppsActivity]. */
object Jobs {
    @Volatile var title: String? = null; private set
    @Volatile var running = false; private set
    @Volatile var error: String? = null; private set
    val log = LogRing(400)

    fun begin(title: String) {
        this.title = title
        error = null
        running = true
        log.add("── $title")
    }

    fun finish(error: String?) {
        this.error = error
        running = false
        log.add(if (error == null) "── done" else "── failed: $error")
    }

    fun line(s: String) = log.add(s)
}
