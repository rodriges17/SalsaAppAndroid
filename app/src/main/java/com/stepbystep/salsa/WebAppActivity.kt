package com.stepbystep.salsa

import android.app.Activity
import android.content.ClipData
import android.util.Base64
import android.webkit.JavascriptInterface
import java.io.File
import java.io.FileOutputStream
import androidx.core.content.FileProvider
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Bundle
import android.os.Environment
import android.webkit.*
import android.widget.*
import android.view.View
import android.graphics.Color
import android.net.Uri
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewClientCompat

class WebAppActivity : Activity() {
    private lateinit var web: WebView
    private lateinit var frame: android.widget.FrameLayout
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
        @JavascriptInterface fun start(name: String): Boolean {
            synchronized(exportLock) {
                if (exportStream != null) return false
                if (!web.url.orEmpty().startsWith("https://appassets.androidplatform.net/assets/")) return false
                val safe = name.substringAfterLast('/').replace(Regex("[^A-Za-z0-9._-]"), "_").take(100).ifBlank { "export.zip" }
                val dir = File(cacheDir, "exports").apply { mkdirs() }
                val target = File(dir, "${System.currentTimeMillis()}-$safe")
                return try { exportFile = target; exportStream = FileOutputStream(target); true }
                    catch (_: Exception) { false }
            }
        }
        @JavascriptInterface fun chunk(data: String): Boolean {
            synchronized(exportLock) {
                val out = exportStream ?: return false
                return try {
                    val bytes = Base64.decode(data, Base64.DEFAULT)
                    if (bytes.size > 262144 || (exportFile?.length() ?: 0) + bytes.size > 300L * 1024 * 1024) {
                        abort(); false
                    } else { out.write(bytes); true }
                } catch (_: Exception) { abort(); false }
            }
        }
        @JavascriptInterface fun finish(mime: String): Boolean {
            val file = synchronized(exportLock) {
                val f = exportFile ?: return false
                try { exportStream?.close() } catch (_: Exception) {}
                exportStream = null; exportFile = null; f
            }
            runOnUiThread {
                AlertDialog.Builder(this@WebAppActivity).setTitle("Export bereit")
                    .setItems(arrayOf("Auf Gerät speichern", "Mit anderer App teilen")) { _, choice ->
                        if (choice == 0) {
                            pendingSave?.delete()
                            pendingSave = file
                            val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                                addCategory(Intent.CATEGORY_OPENABLE)
                                type = if (mime == "application/zip") "application/zip" else "application/octet-stream"
                                putExtra(Intent.EXTRA_TITLE, file.name.substringAfter("-"))
                            }
                            try { startActivityForResult(intent, saveRequest) }
                            catch (_: Exception) { pendingSave = null; file.delete(); Toast.makeText(this@WebAppActivity, "Speichern nicht verfügbar", Toast.LENGTH_LONG).show() }
                        } else {
                            try {
                                val uri = FileProvider.getUriForFile(this@WebAppActivity, "${packageName}.files", file)
                                val send = Intent(Intent.ACTION_SEND).apply {
                                    type = if (mime == "application/zip") "application/zip" else "application/octet-stream"
                                    putExtra(Intent.EXTRA_STREAM, uri)
                                    clipData = ClipData.newUri(contentResolver, "Export", uri)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                startActivity(Intent.createChooser(send, "Datei teilen"))
                            } catch (_: Exception) { Toast.makeText(this@WebAppActivity, "Teilen fehlgeschlagen", Toast.LENGTH_LONG).show() }
                        }
                    }.setNegativeButton("Abbrechen") { _, _ -> file.delete() }.show()
            }
            return true
        }
        @JavascriptInterface fun abort() {
            synchronized(exportLock) {
                try { exportStream?.close() } catch (_: Exception) {}
                exportStream = null
                exportFile?.delete()
                exportFile = null
            }
        }
    }
    private val downloads = AndroidDownloads()
    private val appUrl = "https://appassets.androidplatform.net/assets/index.html"

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        window.statusBarColor = Color.rgb(23, 18, 31)
        window.navigationBarColor = Color.rgb(23, 18, 31)
        frame = android.widget.FrameLayout(this)
        web = WebView(this)
        frame.addView(web, android.widget.FrameLayout.LayoutParams(-1, -1))
        setContentView(frame)
        val assets = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()
        web.webViewClient = object : WebViewClientCompat() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (request.url.host == "appassets.androidplatform.net") return false
                if (request.isForMainFrame) {
                    runCatching { startActivity(Intent(Intent.ACTION_VIEW, request.url)) }
                    return true
                }
                return false
            }
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                assets.shouldInterceptRequest(request.url)
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                if (fullscreenView != null) { callback.onCustomViewHidden(); return }
                fullscreenView = view
                fullscreenCallback = callback
                frame.addView(view, android.widget.FrameLayout.LayoutParams(-1, -1))
                web.visibility = View.GONE
                window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            }
            override fun onHideCustomView() { closeFullscreen() }

            override fun onShowFileChooser(view: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
                fileCallback?.onReceiveValue(null)
                fileCallback = callback
                return try {
                    val intent = params.createIntent().apply {
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    startActivityForResult(intent, fileRequest)
                    true
                } catch (e: Exception) {
                    fileCallback = null
                    Toast.makeText(this@WebAppActivity, "Dateiauswahl nicht verfügbar", Toast.LENGTH_LONG).show()
                    false
                }
            }
            override fun onPermissionRequest(request: PermissionRequest) {
                // Never blindly grant mic/camera; the embedded app only needs local file selection.
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
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, false)
        handleSharedLogin(intent)
        if (web.url.isNullOrEmpty()) web.loadUrl(appUrl)
    }

    private fun handleSharedLogin(incoming: Intent?) {
        val candidate = when (incoming?.action) {
            Intent.ACTION_VIEW -> incoming.dataString
            Intent.ACTION_SEND -> if (incoming.type == "text/plain") {
                Regex("""https://[^\\s<>"']+""").find(incoming.getStringExtra(Intent.EXTRA_TEXT).orEmpty())?.value
            } else null
            else -> null
        } ?: return
        val link = runCatching { Uri.parse(candidate.trimEnd('.', ',', ')')) }.getOrNull() ?: return
        if (link.scheme != "https") return
        val validSource = (link.host == "rodriges17.github.io" &&
            link.path.orEmpty().startsWith("/SalsaAppCloud/")) ||
            (link.host == "tzaprobvauikssnpiucl.supabase.co" &&
            link.path.orEmpty().startsWith("/auth/v1/verify"))
        if (!validSource || link.getQueryParameter("type") != "magiclink") return
        val token = link.getQueryParameter("token_hash") ?: return
        if (!token.matches(Regex("[A-Za-z0-9_-]{16,512}"))) return
        // Never open an external origin with the privileged JavaScript bridge.
        web.loadUrl(appUrl + "?token_hash=" + Uri.encode(token) + "&type=magiclink")
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleSharedLogin(intent)
    }

    @Deprecated("Android system file selection")
    override fun onActivityResult(code: Int, result: Int, data: Intent?) {
        super.onActivityResult(code, result, data)
        if (code == saveRequest) {
            val source = pendingSave
            pendingSave = null
            if (source != null) {
                if (result == RESULT_OK && data?.data != null) {
                    val target = data.data!!
                    Thread {
                        val saved = runCatching {
                            source.inputStream().use { input ->
                                val output = contentResolver.openOutputStream(target, "w") ?: error("Nicht schreibbar")
                                output.use { input.copyTo(it, 65536) }
                            }
                        }.isSuccess
                        source.delete()
                        runOnUiThread { Toast.makeText(this, if (saved) "Export gespeichert" else "Speichern fehlgeschlagen", Toast.LENGTH_LONG).show() }
                    }.start()
                } else source.delete()
            }
            return
        }
        if (code != fileRequest) return
        val uris = if (result == RESULT_OK && data != null) {
            val clips = data.clipData
            if (clips != null) Array(clips.itemCount) { clips.getItemAt(it).uri }
            else data.data?.let { arrayOf(it) }
        } else null
        fileCallback?.onReceiveValue(uris)
        fileCallback = null
    }
    override fun onCreateOptionsMenu(menu: android.view.Menu): Boolean {
        menu.add("Native Offline-Version").setOnMenuItemClickListener {
            startActivity(Intent(this, MainActivity::class.java)); true
        }
        menu.add("Seite neu laden").setOnMenuItemClickListener { web.reload(); true }
        return true
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
    @Deprecated("Android navigation")
    override fun onBackPressed() {
        if (fullscreenView != null) closeFullscreen()
        else if (web.canGoBack()) web.goBack()
        else AlertDialog.Builder(this).setMessage("Step by Step schließen?")
            .setNegativeButton("Abbrechen", null)
            .setPositiveButton("Schließen") { _, _ -> finish() }.show()
    }
    override fun onDestroy() {
        fileCallback?.onReceiveValue(null)
        fileCallback = null
        closeFullscreen()
        downloads.abort()
        pendingSave?.delete()
        web.destroy()
        super.onDestroy()
    }
}
