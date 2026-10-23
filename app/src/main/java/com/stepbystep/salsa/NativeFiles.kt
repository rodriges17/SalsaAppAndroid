package com.stepbystep.salsa

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.WebResourceResponse
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import org.json.JSONArray
import org.json.JSONObject

/** The bridge only addresses granted document trees or explicitly registered imports. */
class NativeFiles(private val activity: Activity, private val script: (String) -> Unit) {
    private val prefs = activity.getSharedPreferences("document-trees", Activity.MODE_PRIVATE)
    private val imports = ConcurrentHashMap<String, Uri>()

    private val writes = FolderWriteSessions(File(activity.cacheDir, "saf-writes"))
    @Volatile private var closed = false

    companion object {
        const val PICK_DIRECTORY = 739
    }

    private fun quote(value: String) = JSONObject.quote(value)

    private fun path(value: String): List<String> {
        if (value.isEmpty()) return emptyList()
        val parts = value.split('/')
        require(
            parts.size <= 8 &&
                parts.all {
                    it.isNotEmpty() &&
                        it != "." &&
                        it != ".." &&
                        it.length <= 200 &&
                        !it.contains('\\') &&
                        !it.contains('\u0000')
                }
        ) {
            "Ungültiger Dateipfad"
        }
        return parts
    }

    private fun root(id: String): DocumentFile {
        val uri = prefs.getString(id, null) ?: throw SecurityException("Ordner erneut auswählen")
        val parsed = Uri.parse(uri)
        if (!activity.contentResolver.persistedUriPermissions.any {
                it.uri == parsed && it.isReadPermission && it.isWritePermission
            }) throw SecurityException("Ordnerzugriff erneut erlauben")
        return DocumentFile.fromTreeUri(activity, parsed) ?: error("Ordner nicht verfügbar")
    }

    private fun document(id: String, value: String): DocumentFile {
        var current = root(id)
        for (part in path(value)) current =
            current.findFile(part) ?: throw java.io.FileNotFoundException("Datei nicht gefunden")
        return current
    }

    private fun documentKey(file: DocumentFile): String =
        "${file.uri.authority}:${DocumentsContract.getDocumentId(file.uri)}"

    fun register(uri: Uri): String {
        val id = UUID.randomUUID().toString()
        imports[id] = uri
        return "https://appassets.androidplatform.net/native/import/$id"
    }

    fun unregister(url: String) {
        imports.remove(url.substringAfterLast('/'))
    }

    fun response(value: String): WebResourceResponse {
        return try {
            val parts = value.split('/', limit = 3)
            val uri =
                if (parts[0] == "import") imports[parts.getOrNull(1)] ?: throw SecurityException()
                else {
                    require(parts[0] == "tree")
                    document(parts[1], Uri.decode(parts.getOrElse(2) { "" })).uri
                }
            val mime = activity.contentResolver.getType(uri) ?: "application/octet-stream"
            WebResourceResponse(mime, null, activity.contentResolver.openInputStream(uri))
        } catch (_: Exception) {
            WebResourceResponse(
                "text/plain",
                "UTF-8",
                404,
                "Not Found",
                emptyMap(),
                java.io.ByteArrayInputStream(ByteArray(0)),
            )
        }
    }

    @JavascriptInterface
    fun pick() {
        activity.runOnUiThread {
            try {
                activity.startActivityForResult(
                    Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                        addFlags(
                            Intent.FLAG_GRANT_READ_URI_PERMISSION or
                                Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                                Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
                        )
                    },
                    PICK_DIRECTORY,
                )
            } catch (_: Exception) {
                script("window.AndroidFSResult(null,'Ordnerauswahl nicht verfügbar')")
            }
        }
    }

    fun picked(result: Int, data: Intent?) {
        if (result != Activity.RESULT_OK || data?.data == null) {
            script("window.AndroidFSResult(null,'Abgebrochen')")
            return
        }
        try {
            val uri = data.data!!
            activity.contentResolver.takePersistableUriPermission(
                uri,
                data.flags and
                    (Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION),
            )
            val id =
                prefs.all.entries.firstOrNull { it.value == uri.toString() }?.key
                    ?: UUID.randomUUID().toString()
            prefs.edit().putString(id, uri.toString()).apply()
            val name = root(id).name ?: "Geräteordner"
            script(
                "window.AndroidFSResult({root:${quote(id)},path:'',name:${quote(name)},kind:'directory'})"
            )
        } catch (_: Exception) {
            script("window.AndroidFSResult(null,'Ordnerzugriff konnte nicht gespeichert werden')")
        }
    }

    @JavascriptInterface
    fun call(json: String): String {
        return try {
            check(!closed) { "Dateizugriff bereits geschlossen" }
            val a = JSONObject(json)
            val op = a.getString("op")
            val id = a.optString("root")
            val p = a.optString("path")
            val value: Any =
                when (op) {
                    "permission" ->
                        runCatching {
                                root(id)
                                "granted"
                            }
                            .getOrDefault("denied")
                    "child" -> {
                        val parent = document(id, p)
                        require(parent.isDirectory)
                        val name = a.getString("name")
                        require(path(name).size == 1)
                        var child = parent.findFile(name)
                        val dir = a.getBoolean("directory")
                        if (child == null && a.optBoolean("create"))
                            child =
                                if (dir) parent.createDirectory(name)
                                else parent.createFile("application/octet-stream", name)
                        val found = child ?: throw java.io.FileNotFoundException()
                        require(found.isDirectory == dir) { "Dateiart stimmt nicht überein" }
                        JSONObject()
                            .put("root", id)
                            .put("path", if (p.isEmpty()) name else "$p/$name")
                            .put("name", name)
                            .put("kind", if (dir) "directory" else "file")
                    }
                    "list" -> JSONArray(document(id, p).listFiles().mapNotNull { it.name })
                    "stat" -> {
                        val f = document(id, p)
                        JSONObject()
                            .put("size", f.length())
                            .put("lastModified", f.lastModified())
                            .put("name", f.name)
                            .put(
                                "url",
                                "https://appassets.androidplatform.net/native/tree/$id/${Uri.encode(p)}",
                            )
                    }
                    "remove" -> {
                        val name = a.getString("name")
                        require(path(name).size == 1)
                        val f =
                            document(id, p).findFile(name) ?: throw java.io.FileNotFoundException()
                        require(!f.isDirectory)
                        require(f.delete()) { "Datei konnte nicht entfernt werden" }
                        true
                    }
                    "begin" -> {
                        val original = document(id, p)
                        require(!original.isDirectory) { "Keine Datei" }
                        val key = documentKey(original)
                        writes.begin(key, object : FolderWriteSessions.Target {
                            private var opened: DocumentFile? = null
                            override fun open(): OutputStream {
                                // Recheck the grant at commit time, not only when staging begins.
                                val target = document(id, p)
                                check(documentKey(target) == key) { "Zieldatei wurde inzwischen geändert" }
                                opened = target
                                return activity.contentResolver.openOutputStream(target.uri, "wt")
                                    ?: error("Zieldatei nicht schreibbar")
                            }
                            override fun length(): Long = opened!!.length()
                        })
                    }
                    "write" -> {
                        writes.append(a.getString("token"), Base64.decode(a.getString("data"), Base64.DEFAULT))
                        true
                    }
                    "close" -> {
                        writes.finish(a.getString("token"))
                        true
                    }
                    "abort" -> {
                        writes.abort(a.getString("token"))
                        true
                    }
                    else -> error("Unbekannter Dateivorgang")
                }
            JSONObject().put("value", value).toString()
        } catch (e: Exception) {
            failure(e).toString()
        }
    }

    private fun failure(error: Throwable): JSONObject = JSONObject()
        .put("error", error.message ?: "Dateizugriff fehlgeschlagen")
        .put("name", when (error) {
            is java.io.FileNotFoundException -> "NotFoundError"
            is SecurityException -> "NotAllowedError"
            else -> "InvalidStateError"
        })

    /** Claim the commit before queuing it; provider I/O never owns the bridge monitor. */
    @JavascriptInterface
    fun closeAsync(token: String, request: String) {
        try {
            writes.finishAsync(token) { result ->
                if (!closed) {
                    val response = result.fold(
                        onSuccess = { JSONObject().put("value", true) },
                        onFailure = ::failure,
                    )
                    script("window.AndroidFSWriteResult?.(${quote(request)}, $response);")
                }
            }
        } catch (error: Exception) {
            if (!closed) script("window.AndroidFSWriteResult?.(${quote(request)}, ${failure(error)});")
        }
    }

    fun close() {
        closed = true
        writes.close()
        imports.clear()
    }
}
