package dev.homedroid

import android.app.Activity
import android.app.DownloadManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.view.View
import android.view.WindowInsets
import android.webkit.*
import android.widget.LinearLayout
import android.widget.Toast
import java.util.concurrent.Executors

/** Uses the existing local dashboard and its expiring HttpOnly session, without a JS bridge. */
class DashboardActivity : MobileActivity() {
    companion object {
        private val pages = setOf("overview", "modules", "files", "ai", "deploys", "cluster", "camera", "ssh", "settings")
        fun open(activity: Activity, page: String) {
            activity.startActivity(Intent(activity, DashboardActivity::class.java).putExtra("page", page))
        }
    }
    private lateinit var web: WebView
    private val worker = Executors.newSingleThreadExecutor()
    private var upload: ValueCallback<Array<Uri>>? = null
    private var starting = false
    private lateinit var message: android.widget.TextView
    private lateinit var retry: android.widget.Button
    private lateinit var base: String
    private var port = 8800

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        port = Config(this).dashboardPort
        base = "http://127.0.0.1:$port"
        val root = design.column().apply { setBackgroundColor(design.background) }
        root.setOnApplyWindowInsetsListener { view, insets ->
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                val edges = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
                view.setPadding(edges.left, edges.top, edges.right, edges.bottom)
            } else {
                @Suppress("DEPRECATION")
                view.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            }
            insets
        }
        root.addView(design.button("‹  Back to Homedroid") { finish() }.apply {
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(design.dp(12), design.dp(6), design.dp(12), design.dp(6)) }
        })
        message = design.text("Connecting to your phone…", 15f, design.muted).apply { setPadding(design.dp(24), design.dp(24), design.dp(24), design.dp(12)) }
        root.addView(message)
        retry = design.button("Start server & connect", true) {
            if (!ServerService.active) ServerService.start(this)
            connect()
        }.apply { layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(design.dp(24), 0, design.dp(24), design.dp(12)) } }
        root.addView(retry)
        val browser = design.button("Open in browser") {
            if (!ServerService.active) ServerService.start(this)
            runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("$base/#${intent.getStringExtra("page").takeIf { it in pages } ?: "overview"}"))) }
                .onFailure { Toast.makeText(this, "Install a browser to open the dashboard", Toast.LENGTH_LONG).show() }
        }.apply {
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(design.dp(24), 0, design.dp(24), design.dp(12)) }
        }
        root.addView(browser)
        web = WebView(this).apply {
            setBackgroundColor(design.background)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = true // User-selected document uploads.
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val url = request.url.toString()
                    if (PhoneSettings.isPanelUrl(url, port)) return false
                    if (request.isForMainFrame && request.url.scheme in setOf("http", "https")) {
                        runCatching { startActivity(Intent(Intent.ACTION_VIEW, request.url)) }
                    }
                    return true
                }
                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                    if (request.isForMainFrame) showError("The control panel is unavailable. Start the server or retry the connection.")
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onShowFileChooser(view: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
                    upload?.onReceiveValue(null); upload = callback
                    val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE); type = "*/*"
                        putExtra(Intent.EXTRA_ALLOW_MULTIPLE, params.mode == FileChooserParams.MODE_OPEN_MULTIPLE)
                    }
                    try { startActivityForResult(intent, 10) } catch (_: android.content.ActivityNotFoundException) {
                        callback.onReceiveValue(null); upload = null
                    }
                    return true
                }
            }
            setDownloadListener { url, _, disposition, mime, _ ->
                if (PhoneSettings.isPanelUrl(url, port)) {
                    try {
                        val name = URLUtil.guessFileName(url, disposition, mime)
                        val download = DownloadManager.Request(Uri.parse(url))
                            .addRequestHeader("Cookie", CookieManager.getInstance().getCookie(base).orEmpty())
                            .setTitle(name).setMimeType(mime)
                            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
                        getSystemService(DownloadManager::class.java).enqueue(download)
                        Toast.makeText(this@DashboardActivity, "Saving to Downloads", Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) { Toast.makeText(this@DashboardActivity, "Download failed: ${e.message}", Toast.LENGTH_LONG).show() }
                } else Toast.makeText(this@DashboardActivity, "Use the browser dashboard to save this generated file", Toast.LENGTH_LONG).show()
            }
        }
        root.addView(web, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root); root.requestApplyInsets()
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT) { goBack() }
        }
        val engineVersion = WebView.getCurrentWebViewPackage()?.versionName?.substringBefore('.')?.toIntOrNull()
        if (engineVersion != null && engineVersion < 86) {
            showError("Update Android System WebView to use the control panel inside Homedroid. You can also use a current browser. Use Back to Homedroid for native settings and app controls.")
            browser.visibility = View.VISIBLE
            retry.text = "Update System WebView"
            retry.setOnClickListener {
                runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=com.google.android.webview"))) }
                    .onFailure { Toast.makeText(this, "Update your WebView provider through your phone's app store", Toast.LENGTH_LONG).show() }
            }
        } else if (ServerService.active) connect()
        else showError("Start your server to use the control panel on this phone.")
    }

    private fun connect() {
        if (starting) return
        starting = true; retry.isEnabled = false; web.visibility = View.GONE
        message.text = "Connecting to your phone…"
        message.visibility = View.VISIBLE
        worker.execute {
            val result = runCatching {
                // Wait for our listener. Never send the password to a possibly occupied port.
                var cookie: String? = null
                for (attempt in 0..19) {
                    cookie = Dashboard.sessionForPhone()
                    if (cookie != null) break
                    Thread.sleep(250)
                }
                checkNotNull(cookie) { "The local dashboard could not start. Check service logs, then retry." }
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                starting = false; retry.isEnabled = true
                result.onSuccess { cookie ->
                    CookieManager.getInstance().setCookie(base, cookie) {
                        if (!isFinishing && !isDestroyed) {
                            message.visibility = View.GONE; retry.visibility = View.GONE; web.visibility = View.VISIBLE
                            val page = intent.getStringExtra("page").takeIf { it in pages } ?: "overview"
                            web.loadUrl("$base/#$page")
                        }
                    }
                }.onFailure { showError(it.message ?: "Could not connect to your phone. Try again.") }
            }
        }
    }
    private fun showError(value: String) {
        message.text = value; message.visibility = View.VISIBLE
        retry.text = if (ServerService.active) "Retry connection" else "Start server & connect"
        retry.visibility = View.VISIBLE; web.visibility = View.GONE
    }
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 10) {
            val uris = if (resultCode != RESULT_OK) null else data?.clipData?.let { clips -> Array(clips.itemCount) { clips.getItemAt(it).uri } }
                ?: data?.data?.let { arrayOf(it) }
            upload?.onReceiveValue(uris); upload = null
        }
    }
    private fun goBack() {
        // Dismiss dashboard overlays before navigating away, including with a back gesture.
        web.evaluateJavascript("(function(){var dialog=document.querySelector('dialog[open]');if(dialog){dialog.close();return true;}if(typeof sidebarOpen!=='undefined'&&sidebarOpen){setSidebar(false);return true;}return false;})()") { dismissed ->
            if (!isFinishing && !isDestroyed && dismissed != "true") {
                if (web.canGoBack()) web.goBack() else finish()
            }
        }
    }
    // API 33+ uses OnBackInvokedDispatcher above; this callback supports Android 10–12.
    @android.annotation.SuppressLint("GestureBackNavigation")
    @Deprecated("Legacy back callback for API 29–32")
    override fun onBackPressed() { goBack() }
    override fun onPause() { web.onPause(); super.onPause() }
    override fun onResume() { super.onResume(); if (::web.isInitialized) web.onResume() }
    override fun onDestroy() {
        upload?.onReceiveValue(null); upload = null; worker.shutdownNow()
        web.stopLoading(); web.destroy(); super.onDestroy()
    }
}
