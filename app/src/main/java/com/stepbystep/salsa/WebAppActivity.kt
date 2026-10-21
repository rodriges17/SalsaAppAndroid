package com.stepbystep.salsa

import android.app.Activity
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
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private val fileRequest = 734
    private val appUrl = "https://appassets.androidplatform.net/assets/index.html"

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        window.statusBarColor = Color.rgb(23, 18, 31)
        window.navigationBarColor = Color.rgb(23, 18, 31)
        val frame = android.widget.FrameLayout(this)
        web = WebView(this)
        frame.addView(web, android.widget.FrameLayout.LayoutParams(-1, -1))
        setContentView(frame)
        val assets = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()
        web.webViewClient = object : WebViewClientCompat() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                assets.shouldInterceptRequest(request.url)
            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) Toast.makeText(this@WebAppActivity, "App konnte nicht geladen werden", Toast.LENGTH_LONG).show()
            }
        }
        web.webChromeClient = object : WebChromeClient() {
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
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, false)
        web.loadUrl(appUrl)
    }

    @Deprecated("Android system file selection")
    override fun onActivityResult(code: Int, result: Int, data: Intent?) {
        super.onActivityResult(code, result, data)
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
    @Deprecated("Android navigation")
    override fun onBackPressed() {
        if (web.canGoBack()) web.goBack()
        else AlertDialog.Builder(this).setMessage("Step by Step schließen?")
            .setNegativeButton("Abbrechen", null)
            .setPositiveButton("Schließen") { _, _ -> finish() }.show()
    }
    override fun onDestroy() {
        fileCallback?.onReceiveValue(null)
        fileCallback = null
        web.destroy()
        super.onDestroy()
    }
}
