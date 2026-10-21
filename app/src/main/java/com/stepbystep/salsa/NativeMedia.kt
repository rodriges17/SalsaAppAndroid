package com.stepbystep.salsa

import android.content.Context
import android.util.Base64
import android.webkit.JavascriptInterface
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/** Bounded bridge for playing Cache Storage blobs in Android's native media player. */
class NativeMedia(context: Context) {
    private val directory = File(context.cacheDir,"native-playback").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
    private val streams = mutableMapOf<String,FileOutputStream>()
    private val complete = mutableSetOf<String>()
    @Synchronized @JavascriptInterface fun begin(): String = try {
        val token=UUID.randomUUID().toString(); streams[token]=FileOutputStream(File(directory,token)); token
    } catch (_: Exception) { "" }
    @Synchronized @JavascriptInterface fun chunk(token: String, data: String): Boolean = try {
        val out=streams[token] ?: error("Missing stream"); require(data.length<=90000)
        val bytes=Base64.decode(data,Base64.DEFAULT);require(bytes.size<=65536);out.write(bytes);true
    } catch (_: Exception) { abort(token);false }
    @Synchronized @JavascriptInterface fun finish(token: String): Boolean = try {
        val out=streams.remove(token) ?: error("Missing stream");out.fd.sync();out.close();complete.add(token);true
    } catch (_: Exception) { abort(token);false }
    @Synchronized @JavascriptInterface fun abort(token: String) { streams.remove(token)?.let { runCatching { it.close() } };if(token.matches(Regex("[a-f0-9-]{36}")))File(directory,token).delete();complete.remove(token) }
    @Synchronized fun file(token: String): File { require(complete.contains(token));return File(directory,token) }
    @Synchronized fun close() { streams.keys.toList().forEach { abort(it) };complete.toList().forEach { abort(it) } }
}
