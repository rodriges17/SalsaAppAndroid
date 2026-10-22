package com.stepbystep.salsa

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import android.view.View
import android.webkit.*
import android.webkit.JavascriptInterface
import android.widget.*
import androidx.core.content.FileProvider
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewClientCompat
import java.io.File
import java.io.FileOutputStream

open class WebAppActivity : Activity() {
    protected lateinit var web: WebView
    protected lateinit var frame: android.widget.FrameLayout
    protected lateinit var nativeFiles: NativeFiles
    protected lateinit var nativeMedia: NativeMedia
    private lateinit var nativeNetwork: NativeNetwork
    private var fullscreenView: View? = null
    private var fullscreenCallback: WebChromeClient.CustomViewCallback? = null
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private val fileRequest = 734
    private val saveRequest = 735
    private var pendingSave: File? = null
    private var exportFile: File? = null
    private var exportStream: FileOutputStream? = null
    private val exportLock = Any()

    private inner class AndroidDownloads {
        @JavascriptInterface
        fun start(name: String): Boolean {
            synchronized(exportLock) {
                if (exportStream != null) return false
                // The privileged WebView only loads bundled app assets; main-frame navigation is
                // blocked.
                val safe =
                    name
                        .substringAfterLast('/')
                        .replace(Regex("[^A-Za-z0-9._-]"), "_")
                        .take(100)
                        .ifBlank { "export.zip" }
                val dir = File(cacheDir, "exports").apply { mkdirs() }
                val target = File(dir, "${System.currentTimeMillis()}-$safe")
                return try {
                    exportFile = target
                    exportStream = FileOutputStream(target)
                    true
                } catch (_: Exception) {
                    false
                }
            }
        }

        @JavascriptInterface
        fun chunk(data: String): Boolean {
            synchronized(exportLock) {
                val out = exportStream ?: return false
                return try {
                    val bytes = Base64.decode(data, Base64.DEFAULT)
                    if (
                        bytes.size > 262144 ||
                            (exportFile?.length() ?: 0) + bytes.size > 300L * 1024 * 1024
                    ) {
                        abort()
                        false
                    } else {
                        out.write(bytes)
                        true
                    }
                } catch (_: Exception) {
                    abort()
                    false
                }
            }
        }

        @JavascriptInterface
        fun finish(mime: String): Boolean {
            val file =
                synchronized(exportLock) {
                    val f = exportFile ?: return false
                    try {
                        exportStream?.close()
                    } catch (_: Exception) {}
                    exportStream = null
                    exportFile = null
                    f
                }
            runOnUiThread {
                AlertDialog.Builder(this@WebAppActivity)
                    .setTitle("Export bereit")
                    .setItems(arrayOf("Auf Gerät speichern", "Mit anderer App teilen")) { _, choice
                        ->
                        if (choice == 0) {
                            pendingSave?.delete()
                            pendingSave = file
                            val intent =
                                Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                                    addCategory(Intent.CATEGORY_OPENABLE)
                                    type =
                                        if (mime == "application/zip") "application/zip"
                                        else "application/octet-stream"
                                    putExtra(Intent.EXTRA_TITLE, file.name.substringAfter("-"))
                                }
                            try {
                                startActivityForResult(intent, saveRequest)
                            } catch (_: Exception) {
                                pendingSave = null
                                file.delete()
                                Toast.makeText(
                                        this@WebAppActivity,
                                        "Speichern nicht verfügbar",
                                        Toast.LENGTH_LONG,
                                    )
                                    .show()
                            }
                        } else {
                            try {
                                val uri =
                                    FileProvider.getUriForFile(
                                        this@WebAppActivity,
                                        "${packageName}.files",
                                        file,
                                    )
                                val send =
                                    Intent(Intent.ACTION_SEND).apply {
                                        type =
                                            if (mime == "application/zip") "application/zip"
                                            else "application/octet-stream"
                                        putExtra(Intent.EXTRA_STREAM, uri)
                                        clipData = ClipData.newUri(contentResolver, "Export", uri)
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                startActivity(Intent.createChooser(send, "Datei teilen"))
                            } catch (_: Exception) {
                                Toast.makeText(
                                        this@WebAppActivity,
                                        "Teilen fehlgeschlagen",
                                        Toast.LENGTH_LONG,
                                    )
                                    .show()
                            }
                        }
                    }
                    .setNegativeButton("Abbrechen") { _, _ -> file.delete() }
                    .show()
            }
            return true
        }

        @JavascriptInterface
        fun abort() {
            synchronized(exportLock) {
                try {
                    exportStream?.close()
                } catch (_: Exception) {}
                exportStream = null
                exportFile?.delete()
                exportFile = null
            }
        }
    }

    private val downloads = AndroidDownloads()
    private val appUrl = "https://appassets.androidplatform.net/android-bootstrap.html"

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        window.statusBarColor = Color.rgb(23, 18, 31)
        window.navigationBarColor = Color.rgb(23, 18, 31)
        frame = android.widget.FrameLayout(this)
        web = WebView(this)
        frame.addView(web, android.widget.FrameLayout.LayoutParams(-1, -1))
        setContentView(frame)
        frame.setOnApplyWindowInsetsListener { view, insets ->
            view.setPadding(
                insets.systemWindowInsetLeft,
                insets.systemWindowInsetTop,
                insets.systemWindowInsetRight,
                insets.systemWindowInsetBottom,
            )
            // Do not pass the same system bars to WebView as another safe-area inset.
            if (android.os.Build.VERSION.SDK_INT >= 30) android.view.WindowInsets.CONSUMED
            else insets.consumeSystemWindowInsets()
        }
        nativeFiles =
            NativeFiles(this) { source -> runOnUiThread { web.evaluateJavascript(source, null) } }
        nativeMedia = NativeMedia(this)
        nativeNetwork =
            NativeNetwork(this, nativeMedia) { source ->
                runOnUiThread { if (!isDestroyed) web.evaluateJavascript(source, null) }
            }
        val assets =
            WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
                .addPathHandler(
                    "/native/",
                    WebViewAssetLoader.PathHandler { path -> nativeFiles.response(path) },
                )
                .addPathHandler(
                    "/network/",
                    WebViewAssetLoader.PathHandler { path -> nativeNetwork.response(path) },
                )
                .addPathHandler(
                    "/",
                    WebViewAssetLoader.PathHandler { path ->
                        when (path) {
                            "android-bootstrap.html" ->
                                WebResourceResponse(
                                    "text/html",
                                    "UTF-8",
                                    applicationContext.assets.open("android-bootstrap.html"),
                                )
                            "android-bootstrap.js" ->
                                WebResourceResponse(
                                    "application/javascript",
                                    "UTF-8",
                                    applicationContext.assets.open("android-bootstrap.js"),
                                )
                            else -> null
                        }
                    },
                )
                .build()
        web.webViewClient =
            object : WebViewClientCompat() {
                override fun shouldOverrideUrlLoading(
                    view: WebView,
                    request: WebResourceRequest,
                ): Boolean {
                    if (
                        request.url.scheme == "https" &&
                            request.url.host == "appassets.androidplatform.net" &&
                            (request.url.path.orEmpty().startsWith("/assets/") ||
                                request.url.path == "/android-bootstrap.html")
                    )
                        return false
                    if (request.isForMainFrame) {
                        if (request.url.scheme in listOf("https", "mailto"))
                            runCatching { startActivity(Intent(Intent.ACTION_VIEW, request.url)) }
                        return true
                    }
                    return false
                }

                override fun shouldInterceptRequest(
                    view: WebView,
                    request: WebResourceRequest,
                ): WebResourceResponse? = assets.shouldInterceptRequest(request.url)
            }
        web.webChromeClient =
            object : WebChromeClient() {
                override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                    if (fullscreenView != null) {
                        callback.onCustomViewHidden()
                        return
                    }
                    fullscreenView = view
                    fullscreenCallback = callback
                    frame.addView(view, android.widget.FrameLayout.LayoutParams(-1, -1))
                    web.visibility = View.GONE
                    window.decorView.systemUiVisibility =
                        View.SYSTEM_UI_FLAG_FULLSCREEN or
                            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                }

                override fun onHideCustomView() {
                    closeFullscreen()
                }

                override fun onShowFileChooser(
                    view: WebView,
                    callback: ValueCallback<Array<Uri>>,
                    params: FileChooserParams,
                ): Boolean {
                    fileCallback?.onReceiveValue(null)
                    fileCallback = callback
                    return try {
                        val intent =
                            params.createIntent().apply {
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                        startActivityForResult(intent, fileRequest)
                        true
                    } catch (e: Exception) {
                        fileCallback = null
                        Toast.makeText(
                                this@WebAppActivity,
                                "Dateiauswahl nicht verfügbar",
                                Toast.LENGTH_LONG,
                            )
                            .show()
                        false
                    }
                }

                override fun onPermissionRequest(request: PermissionRequest) {
                    // Never blindly grant mic/camera; the embedded app only needs local file
                    // selection.
                    request.deny()
                }
            }
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            allowFileAccess = false
            allowContentAccess = true
            javaScriptCanOpenWindowsAutomatically = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            mediaPlaybackRequiresUserGesture = true
        }
        web.addJavascriptInterface(downloads, "AndroidDownloads")
        web.addJavascriptInterface(nativeFiles, "AndroidFiles")
        web.addJavascriptInterface(nativeMedia, "NativeMedia")
        web.addJavascriptInterface(nativeNetwork, "AndroidNetwork")
        web.addJavascriptInterface(
            object {
                @JavascriptInterface
                fun rotate(landscape: Boolean) {
                    runOnUiThread {
                        requestedOrientation =
                            if (landscape) ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                            else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                    }
                }

                @JavascriptInterface
                fun keepAwake(enabled: Boolean) {
                    runOnUiThread {
                        if (enabled)
                            window.addFlags(
                                android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                            )
                        else
                            window.clearFlags(
                                android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                            )
                    }
                }

                @JavascriptInterface
                fun fullscreen(enabled: Boolean) {
                    runOnUiThread {
                        window.decorView.systemUiVisibility =
                            if (enabled)
                                View.SYSTEM_UI_FLAG_FULLSCREEN or
                                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                            else 0
                    }
                }
            },
            "AndroidPlatform",
        )
        configureNativeBridge()
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, false)
        handleSharedLogin(intent)
        if (web.url.isNullOrEmpty()) web.loadUrl(appUrl)
    }

    protected open fun configureNativeBridge() {}

    protected fun handleSharedLogin(incoming: Intent?) {
        val candidate =
            when (incoming?.action) {
                Intent.ACTION_VIEW -> incoming.dataString
                Intent.ACTION_SEND ->
                    if (incoming.type == "text/plain") {
                        Regex("""https://[^\s<>"\']+""")
                            .find(incoming.getStringExtra(Intent.EXTRA_TEXT).orEmpty())
                            ?.value
                    } else null
                else -> null
            } ?: return
        val link = runCatching { Uri.parse(candidate.trimEnd('.', ',', ')')) }.getOrNull() ?: return
        if (link.scheme != "https") return
        val validSource =
            (link.host == "rodriges17.github.io" &&
                link.path.orEmpty().startsWith("/SalsaAppCloud/")) ||
                (link.host == "tzaprobvauikssnpiucl.supabase.co" &&
                    link.path.orEmpty().startsWith("/auth/v1/verify"))
        if (!validSource || link.getQueryParameter("type") != "magiclink") return
        val token = link.getQueryParameter("token_hash") ?: return
        if (!token.matches(Regex("[A-Za-z0-9_-]{16,512}"))) return
        // Never open an external origin with the privileged JavaScript bridge.
        web.loadUrl(appUrl + "?token_hash=" + Uri.encode(token) + "&type=magiclink&cloud=1")
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleSharedLogin(intent)
    }

    @Deprecated("Android system file selection")
    override fun onActivityResult(code: Int, result: Int, data: Intent?) {
        super.onActivityResult(code, result, data)
        if (code == NativeFiles.PICK_DIRECTORY) {
            nativeFiles.picked(result, data)
            return
        }
        if (code == saveRequest) {
            val source = pendingSave
            pendingSave = null
            if (source != null) {
                if (result == RESULT_OK && data?.data != null) {
                    val target = data.data!!
                    Thread {
                            val saved =
                                runCatching {
                                        source.inputStream().use { input ->
                                            val output =
                                                contentResolver.openOutputStream(target, "w")
                                                    ?: error("Nicht schreibbar")
                                            output.use { input.copyTo(it, 65536) }
                                        }
                                    }
                                    .isSuccess
                            source.delete()
                            runOnUiThread {
                                Toast.makeText(
                                        this,
                                        if (saved) "Export gespeichert"
                                        else "Speichern fehlgeschlagen",
                                        Toast.LENGTH_LONG,
                                    )
                                    .show()
                            }
                        }
                        .start()
                } else source.delete()
            }
            return
        }
        if (code != fileRequest) return
        val uris =
            if (result == RESULT_OK && data != null) {
                val clips = data.clipData
                if (clips != null) Array(clips.itemCount) { clips.getItemAt(it).uri }
                else data.data?.let { arrayOf(it) }
            } else null
        fileCallback?.onReceiveValue(uris)
        fileCallback = null
    }

    private fun closeFullscreen() {
        val view = fullscreenView ?: return
        frame.removeView(view)
        fullscreenView = null
        web.visibility = View.VISIBLE
        window.decorView.systemUiVisibility = 0
        fullscreenCallback?.onCustomViewHidden()
        fullscreenCallback = null
    }

    protected fun closeFullscreenIfOpen(): Boolean {
        if (fullscreenView == null) return false
        closeFullscreen()
        return true
    }

    @Deprecated("Android navigation")
    override fun onBackPressed() {
        if (fullscreenView != null) closeFullscreen()
        else if (web.canGoBack()) web.goBack()
        else
            AlertDialog.Builder(this)
                .setMessage("Step by Step schließen?")
                .setNegativeButton("Abbrechen", null)
                .setPositiveButton("Schließen") { _, _ -> finish() }
                .show()
    }

    override fun onPause() {
        web.evaluateJavascript(
            "document.querySelectorAll('video').forEach(v=>v.pause()); window.dispatchEvent(new Event('nativepause'))",
            null,
        )
        web.onPause()
        window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        if (::web.isInitialized) web.onResume()
    }

    override fun onDestroy() {
        fileCallback?.onReceiveValue(null)
        fileCallback = null
        closeFullscreen()
        downloads.abort()
        pendingSave?.delete()
        nativeNetwork.close()
        nativeFiles.close()
        nativeMedia.close()
        web.destroy()
        super.onDestroy()
    }
}
