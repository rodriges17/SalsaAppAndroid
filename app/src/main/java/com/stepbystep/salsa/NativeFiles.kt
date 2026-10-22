package com.stepbystep.salsa

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.WebResourceResponse
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import org.json.JSONArray
import org.json.JSONObject

/** The bridge only addresses granted document trees or explicitly registered imports. */
class NativeFiles(private val activity: Activity, private val script: (String) -> Unit) {
    private val prefs = activity.getSharedPreferences("document-trees", Activity.MODE_PRIVATE)
    private val imports = ConcurrentHashMap<String, Uri>()

    private data class Writer(
        val file: File,
        val output: FileOutputStream,
        val root: String,
        val path: String,
    )

    private val writers = ConcurrentHashMap<String, Writer>()

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
        require(
            activity.contentResolver.persistedUriPermissions.any {
                it.uri == parsed && it.isReadPermission && it.isWritePermission
            }
        ) {
            "Ordnerzugriff erneut erlauben"
        }
        return DocumentFile.fromTreeUri(activity, parsed) ?: error("Ordner nicht verfügbar")
    }

    private fun document(id: String, value: String): DocumentFile {
        var current = root(id)
        for (part in path(value)) current =
            current.findFile(part) ?: throw java.io.FileNotFoundException("Datei nicht gefunden")
        return current
    }

    fun register(uri: Uri): String {
        val id = UUID.randomUUID().toString()
        imports[id] = uri
        return "https://appassets.androidplatform.net/native/import/$id"
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

    @Synchronized
    @JavascriptInterface
    fun call(json: String): String {
        return try {
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
                        document(id, p)
                        val token = UUID.randomUUID().toString()
                        val file = File(activity.cacheDir, "saf-$token")
                        writers[token] = Writer(file, FileOutputStream(file), id, p)
                        token
                    }
                    "write" -> {
                        val w = writers[a.getString("token")] ?: error("Schreibvorgang fehlt")
                        val bytes = Base64.decode(a.getString("data"), Base64.DEFAULT)
                        require(bytes.size <= 262144)
                        w.output.write(bytes)
                        true
                    }
                    "close" -> {
                        val w =
                            writers.remove(a.getString("token")) ?: error("Schreibvorgang fehlt")
                        try {
                            w.output.fd.sync()
                            w.output.close()
                            val target = document(w.root, w.path)
                            activity.contentResolver.openOutputStream(target.uri, "wt")!!.use { out
                                ->
                                w.file.inputStream().use { it.copyTo(out, 65536) }
                            }
                            require(target.length() == w.file.length()) {
                                "Schreibprüfung fehlgeschlagen"
                            }
                            true
                        } finally {
                            w.file.delete()
                        }
                    }
                    "abort" -> {
                        writers.remove(a.getString("token"))?.let {
                            it.output.close()
                            it.file.delete()
                        }
                        true
                    }
                    else -> error("Unbekannter Dateivorgang")
                }
            JSONObject().put("value", value).toString()
        } catch (e: Exception) {
            JSONObject()
                .put("error", e.message ?: "Dateizugriff fehlgeschlagen")
                .put(
                    "name",
                    when (e) {
                        is java.io.FileNotFoundException -> "NotFoundError"
                        is SecurityException -> "NotAllowedError"
                        else -> "InvalidStateError"
                    },
                )
                .toString()
        }
    }

    // A complete SAF copy can take seconds. Return immediately to JavaScript so
    // WebView can keep rendering the saving indicator while the copy finishes.
    @JavascriptInterface
    fun closeAsync(token: String, request: String) {
        Thread {
                val result = call(JSONObject().put("op", "close").put("token", token).toString())
                script("window.AndroidFSWriteResult?.(${quote(request)}, $result);")
            }
            .start()
    }

    @Synchronized
    fun close() {
        writers.values.forEach {
            runCatching { it.output.close() }
            it.file.delete()
        }
        writers.clear()
        imports.clear()
    }
}
