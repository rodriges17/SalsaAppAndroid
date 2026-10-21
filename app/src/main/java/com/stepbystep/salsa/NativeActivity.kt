package com.stepbystep.salsa

import android.app.AlertDialog
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.widget.*
import java.io.ByteArrayOutputStream
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

/** Native library/editor/player. Advanced cloud and backup controllers share the PWA repository. */
class NativeActivity : WebAppActivity() {
    private val ui = Handler(Looper.getMainLooper())
    private val callbacks = mutableMapOf<Int, (JSONObject) -> Unit>()
    private var requestId = 0
    private lateinit var nativePanel: LinearLayout
    private lateinit var nativeBody: FrameLayout
    private lateinit var nativeNav: LinearLayout
    private var loaded = false
    private var screen = "loading"
    private var busy = false
    private var figures = listOf<JSONObject>()
    private var query = ""
    private var filter = ""
    private var tag = ""
    private var sort = 0
    private var light = false
    private val bg
        get() = Color.parseColor(if (light) "#f8f2ea" else "#17121f")

    private val ink
        get() = Color.parseColor(if (light) "#382921" else "#f3edf7")

    private val accent
        get() = Color.parseColor(if (light) "#91472f" else "#cfb4f6")

    private var draft: JSONObject? = null
    private var videos = mutableListOf<JSONObject>()
    private var dirty = false
    private var titleInput: EditText? = null
    private var descInput: EditText? = null
    private var tagsInput: EditText? = null
    private var statusInput: Spinner? = null
    private var replacing: String? = null
    private var picker = 741
    private var pendingImports = ArrayDeque<Uri>()
    private var preview: AlertDialog? = null
    private var previewView: VideoView? = null
    private var importedIntent: Intent? = null
    private var player: NativePlayer? = null
    private var playerContent: LinearLayout? = null
    private var playerStage: FrameLayout? = null
    private var playerTools: ScrollView? = null
    private var generation = 0
    private var activeMedia: String? = null
    private lateinit var workflows: NativeWorkflows
    private var fileKind = "zip"
    private val statuses = listOf("Neu", "Am Üben", "Sitzt")

    private fun JSONArray.objects() = (0 until length()).map { getJSONObject(it) }

    private fun JSONObject.array(key: String) = optJSONArray(key) ?: JSONArray()

    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()

    private fun text(value: String, size: Float = 16f, bold: Boolean = false) =
        TextView(this).apply {
            text = value
            textSize = size
            setTextColor(ink)
            setPadding(0, dp(6), 0, dp(6))
            if (bold) setTypeface(null, Typeface.BOLD)
        }

    private fun button(value: String, work: () -> Unit) =
        Button(this).apply {
            text = value
            isAllCaps = false
            setTextColor(accent)
            minHeight = dp(48)
            setOnClickListener { if (!busy) work() }
        }

    private fun column() =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(12), dp(18), dp(16))
            setBackgroundColor(bg)
        }

    private fun row() = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }

    private fun field(
        page: LinearLayout,
        label: String,
        value: String,
        multiline: Boolean = false,
    ): EditText {
        page.addView(text(label, 14f, true))
        return EditText(this).apply {
            setText(value)
            setTextColor(ink)
            setHintTextColor(accent)
            setSingleLine(!multiline)
            if (multiline) minLines = 3
            page.addView(this)
        }
    }

    private fun selector(values: List<String>, selected: Int, work: (Int) -> Unit) =
        Spinner(this).apply {
            adapter =
                object :
                    ArrayAdapter<String>(
                        this@NativeActivity,
                        android.R.layout.simple_spinner_dropdown_item,
                        values,
                    ) {
                    override fun getView(
                        position: Int,
                        convertView: View?,
                        parent: ViewGroup,
                    ): View =
                        (super.getView(position, convertView, parent) as TextView).apply {
                            setTextColor(ink)
                        }
                }
            setSelection(selected.coerceAtLeast(0))
            onItemSelectedListener =
                object : AdapterView.OnItemSelectedListener {
                    override fun onNothingSelected(parent: AdapterView<*>?) {}

                    override fun onItemSelected(
                        parent: AdapterView<*>?,
                        view: View?,
                        position: Int,
                        id: Long,
                    ) {
                        work(position)
                    }
                }
        }

    private fun show(page: LinearLayout, scroll: Boolean = true) {
        nativePanel.visibility = View.VISIBLE
        web.visibility = View.INVISIBLE
        nativeBody.removeAllViews()
        if (::nativeNav.isInitialized)
            nativeNav.visibility =
                if (screen in listOf("library", "workflow")) View.VISIBLE else View.GONE
        nativeBody.addView(
            if (scroll)
                ScrollView(this).apply {
                    isFillViewport = true
                    addView(page)
                }
            else page,
            FrameLayout.LayoutParams(-1, -1),
        )
    }

    private fun notice(message: String) {
        AlertDialog.Builder(this).setMessage(message).setPositiveButton("OK", null).show()
    }

    private fun rpc(op: String, args: JSONObject = JSONObject(), done: (Any?) -> Unit) {
        val id = ++requestId
        callbacks[id] = { result ->
            busy = false
            if (result.has("error")) {
                if (::workflows.isInitialized) workflows.failed()
                preview?.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = true
                notice(result.getString("error"))
                if (screen == "loading") startLibrary()
            } else done(result.opt("value"))
        }
        web.evaluateJavascript("NativeModel.request($id,${JSONObject.quote(op)},$args)", null)
    }

    override fun configureNativeBridge() {
        web.addJavascriptInterface(
            object {
                @JavascriptInterface
                fun ready() {
                    runOnUiThread {
                        if (!loaded) {
                            loaded = true
                            bootLibrary()
                        }
                    }
                }

                @JavascriptInterface
                fun result(id: Int, result: String) {
                    runOnUiThread { callbacks.remove(id)?.invoke(JSONObject(result)) }
                }

                @JavascriptInterface
                fun progress(data: String) {
                    runOnUiThread {
                        if (::workflows.isInitialized) workflows.progress(JSONObject(data))
                    }
                }

                @JavascriptInterface
                fun home() {
                    runOnUiThread { if (!busy) startLibrary() }
                }
            },
            "NativeUI",
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        nativePanel =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(bg)
            }
        nativeBody = FrameLayout(this)
        nativePanel.addView(nativeBody, LinearLayout.LayoutParams(-1, 0, 1f))
        nativeNav =
            row().apply {
                addView(
                    button("Figuren") {
                        if (screen != "workflow" || !workflows.isBusy) startLibrary()
                    },
                    LinearLayout.LayoutParams(0, -2, 1f),
                )
                addView(
                    button("Cloud-Speicher") {
                        if (screen != "workflow" || !workflows.isBusy) showWorkflow("cloud")
                    },
                    LinearLayout.LayoutParams(0, -2, 1f),
                )
            }
        nativePanel.addView(nativeNav)
        frame.addView(nativePanel, FrameLayout.LayoutParams(-1, -1))
        val loading = column()
        loading.addView(text("Step by Step", 26f, true))
        loading.addView(ProgressBar(this))
        loading.addView(text("Bibliothek wird geöffnet …"))
        show(loading)
        workflows =
            NativeWorkflows(
                this,
                { op, args, done -> rpc(op, args, done) },
                { page -> show(page) },
                { startLibrary() },
                { data -> acceptImport(data) },
                { kind -> chooseWorkflowFile(kind) },
            )
        importedIntent = intent
        val fallback = button("Standardansicht öffnen") { showWeb("overview") }
        loading.addView(fallback)
    }

    private fun key(f: JSONObject) =
        JSONObject()
            .put("id", f.getString("id"))
            .put("storageFolder", f.opt("storageFolder") ?: JSONObject.NULL)
            .put("revision", f.opt("revision") ?: JSONObject.NULL)

    private fun bootLibrary() {
        val legacy =
            getSharedPreferences("MainActivity", MODE_PRIVATE).getString("figures", "[]") ?: "[]"
        val migration = getSharedPreferences("native-migration", MODE_PRIVATE)
        if (legacy == "[]" || migration.getBoolean("done", false)) {
            startLibrary()
            return
        }
        Thread {
                val result = runCatching {
                    val old = JSONArray(legacy)
                    val entries = JSONArray()
                    for (i in 0 until old.length()) {
                        val f = old.getJSONObject(i)
                        val uri = Uri.parse(f.getString("video"))
                        var duration = 0L
                        var width = 0
                        var height = 0
                        MediaMetadataRetriever().releasing { r ->
                            r.setDataSource(this, uri)
                            duration =
                                r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                                    ?.toLong() ?: 0
                            width =
                                r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                                    ?.toInt() ?: 0
                            height =
                                r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                                    ?.toInt() ?: 0
                        }
                        require(duration > 0) {
                            "Ein altes Video ist nicht mehr erreichbar. Bitte den ursprünglichen Speicherort prüfen."
                        }
                        entries.put(
                            JSONObject(f.toString())
                                .put("name", f.optString("title"))
                                .put("url", nativeFiles.register(uri))
                                .put("duration", duration / 1000.0)
                                .put("width", width)
                                .put("height", height)
                                .put("type", contentResolver.getType(uri) ?: "video/mp4")
                                .put(
                                    "tags",
                                    JSONArray(
                                        f.optString("tags")
                                            .split(',')
                                            .map(String::trim)
                                            .filter(String::isNotEmpty)
                                    ),
                                )
                        )
                    }
                    entries
                }
                runOnUiThread {
                    result.fold(
                        { entries ->
                            rpc("legacy", JSONObject().put("figures", entries)) {
                                migration.edit().putBoolean("done", true).apply()
                                startLibrary()
                            }
                        },
                        { error ->
                            notice(
                                "Die alte native Bibliothek konnte nicht vollständig übernommen werden: ${error.message}. Die ursprünglichen Daten bleiben erhalten."
                            )
                            startLibrary()
                        },
                    )
                }
            }
            .start()
    }

    private fun startLibrary() {
        generation++
        frame.findViewWithTag<View>("native-return")?.let(frame::removeView)
        player?.close()
        player = null
        activeMedia?.let(nativeMedia::abort)
        activeMedia = null
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        window.decorView.systemUiVisibility = 0
        screen = "library"
        draft = null
        dirty = false
        web.layoutParams = FrameLayout.LayoutParams(-1, -1)
        web.evaluateJavascript("JSON.stringify(window.stepAppearance.get())") { value ->
            light = value.contains("light")
            getSharedPreferences("appearance", 0).edit().putBoolean("light", light).apply()
            rpc("list") { data ->
                figures = (data as JSONArray).objects()
                showOverview()
                handleIncoming()
            }
        }
    }

    private fun showOverview() {
        val page = column()
        val header = row()
        header.addView(
            ImageView(this).apply {
                setImageResource(com.stepbystep.salsa.R.drawable.app_icon)
                contentDescription = "Step by Step"
            },
            LinearLayout.LayoutParams(dp(40), dp(40)),
        )
        header.addView(text("Step by Step", 25f, true), LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(button("⚙") { showWorkflow("settings") })
        page.addView(header)
        page.addView(text("Meine Figuren", 26f, true))
        page.addView(text("Deine Schritte. Dein Tempo.", 14f))
        val add = row()
        add.addView(
            button("＋ Neue Figur") { openEditor(null) },
            LinearLayout.LayoutParams(0, -2, 1f),
        )
        add.addView(
            button("ZIP importieren") { showWorkflow("import") },
            LinearLayout.LayoutParams(0, -2, 1f),
        )
        page.addView(add)
        val search =
            EditText(this).apply {
                hint = "Figur suchen"
                setText(query)
                setTextColor(ink)
                setHintTextColor(accent)
                setSingleLine(true)
            }
        page.addView(search)
        val chips = row()
        listOf("Alle" + if (filter.isEmpty()) " ✓" else "", *statuses.toTypedArray())
            .forEachIndexed { i, s ->
                chips.addView(
                    button(s + if (i > 0 && filter == s) " ✓" else "") {
                        filter = if (i == 0) "" else s
                        showOverview()
                    },
                    LinearLayout.LayoutParams(0, -2, 1f),
                )
            }
        page.addView(chips)
        val filters = row()
        filters.addView(
            button(if (tag.isEmpty()) "Tags" else "$tag ×") {
                val tags =
                    figures
                        .flatMap { f ->
                            f.array("tags").let { a ->
                                (0 until a.length()).map { a.getString(it) }
                            }
                        }
                        .distinct()
                        .sorted()
                val options = listOf("Alle Tags") + tags
                AlertDialog.Builder(this)
                    .setItems(options.toTypedArray()) { _, i ->
                        tag = if (i == 0) "" else options[i]
                        showOverview()
                    }
                    .show()
            },
            LinearLayout.LayoutParams(0, -2, 1f),
        )
        filters.addView(
            selector(listOf("Neueste", "Name", "Lernstand"), sort) {
                if (sort != it) {
                    sort = it
                    showOverview()
                }
            },
            LinearLayout.LayoutParams(0, dp(50), 1f),
        )
        page.addView(filters)
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        page.addView(list)
        fun render() {
            list.removeAllViews()
            val visible =
                figures
                    .filter { f ->
                        (filter.isEmpty() || f.optString("status") == filter) &&
                            (tag.isEmpty() ||
                                f.array("tags").toString().contains(JSONObject.quote(tag))) &&
                            listOf(
                                    f.optString("title"),
                                    f.optString("description"),
                                    f.array("tags").toString(),
                                )
                                .joinToString(" ")
                                .lowercase(Locale.GERMAN)
                                .contains(query.trim().lowercase(Locale.GERMAN))
                    }
                    .sortedWith(
                        when (sort) {
                            1 -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.optString("title") }
                            2 ->
                                compareBy<JSONObject> { statuses.indexOf(it.optString("status")) }
                                    .thenBy { it.optString("title") }
                            else -> compareByDescending { it.optLong("createdAt") }
                        }
                    )
            list.addView(text("${visible.size} Figuren", 14f, true))
            if (visible.isEmpty())
                list.addView(text("Noch keine passenden Figuren. Füge eigene Videos hinzu.", 16f))
            visible.forEach { f ->
                val card = row().apply { setPadding(0, dp(8), 0, dp(8)) }
                val poster = f.optString("poster")
                val image =
                    ImageView(this).apply {
                        scaleType = ImageView.ScaleType.CENTER_CROP
                        contentDescription = "Vorschaubild"
                        if (poster.isNotEmpty() && poster != "null")
                            runCatching {
                                val b = Base64.decode(poster, Base64.DEFAULT)
                                setImageBitmap(BitmapFactory.decodeByteArray(b, 0, b.size))
                            }
                    }
                card.addView(image, LinearLayout.LayoutParams(dp(80), dp(80)))
                val copy =
                    LinearLayout(this).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(dp(12), 0, 0, 0)
                        addView(text(f.optString("title"), 18f, true))
                        addView(
                            text(
                                f.optString("status") +
                                    " · " +
                                    f.array("tags").let { a ->
                                        (0 until a.length()).joinToString(" · ") { a.getString(it) }
                                    },
                                13f,
                            )
                        )
                        addView(
                            text(
                                "${formatTime((f.optDouble("duration",0.0)*1000).toInt())} · ${f.array("videoIds").length()} Videos" +
                                    if (f.has("storageFolder")) " · Geräteordner" else "",
                                12f,
                            )
                        )
                        if (f.optString("storageProblem").isNotEmpty())
                            addView(text(f.optString("storageProblem"), 12f))
                    }
                card.addView(copy, LinearLayout.LayoutParams(0, -2, 1f))
                card.addView(button("⋮") { figureMenu(f) })
                card.setOnClickListener { if (!busy) openPlayer(f) }
                card.setOnLongClickListener {
                    figureMenu(f)
                    true
                }
                list.addView(card)
            }
        }
        search.addTextChangedListener(
            object : TextWatcher {
                override fun beforeTextChanged(
                    s: CharSequence?,
                    start: Int,
                    count: Int,
                    after: Int,
                ) {}

                override fun afterTextChanged(s: Editable?) {}

                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    query = s.toString()
                    render()
                }
            }
        )
        render()
        page.addView(text("Offline bereit · lokal gespeichert", 12f))
        show(page)
    }

    private fun figureMenu(f: JSONObject) {
        val options =
            arrayOf(
                "Lernen",
                "Bearbeiten",
                "In Cloud veröffentlichen",
                "Figur teilen",
                "Speicherort übertragen",
                "Löschen",
            )
        AlertDialog.Builder(this)
            .setTitle(f.optString("title"))
            .setItems(options) { _, which ->
                when (which) {
                    0 -> openPlayer(f)
                    1 -> openEditor(f)
                    2 -> showWorkflow("cloud", f)
                    3 -> showWorkflow("share", f)
                    4 -> showWorkflow("transfer", f)
                    5 ->
                        AlertDialog.Builder(this)
                            .setMessage(
                                "Figur und verwaltete Videos löschen? Originaldateien bleiben erhalten."
                            )
                            .setNegativeButton("Abbrechen", null)
                            .setPositiveButton("Löschen") { _, _ ->
                                busy = true
                                rpc("delete", key(f)) { startLibrary() }
                            }
                            .show()
                }
            }
            .show()
    }

    private fun openEditor(f: JSONObject?) {
        busy = true
        rpc("edit", f?.let(::key) ?: JSONObject()) { data ->
            draft = data as JSONObject
            videos = data.array("videos").objects().toMutableList()
            dirty = false
            screen = "editor"
            renderEditor()
            if (pendingImports.isNotEmpty()) nextImport()
        }
    }

    private fun captureForm() {
        val f = draft?.getJSONObject("figure") ?: return
        titleInput?.let { f.put("title", it.text.toString()) }
        descInput?.let { f.put("description", it.text.toString()) }
        tagsInput?.let {
            f.put(
                "tags",
                JSONArray(
                    it.text
                        .toString()
                        .split(',')
                        .map(String::trim)
                        .filter(String::isNotEmpty)
                        .distinct()
                ),
            )
        }
        statusInput?.let { f.put("status", statuses[it.selectedItemPosition]) }
    }

    private fun renderEditor() {
        val data = draft ?: return
        val f = data.getJSONObject("figure")
        val page = column()
        val top = row()
        top.addView(button("‹ Zurück") { leaveEditor() })
        top.addView(text("Figur bearbeiten", 22f, true))
        page.addView(top)
        titleInput = field(page, "Name der Figur", f.optString("title"))
        titleInput!!.filters = arrayOf(android.text.InputFilter.LengthFilter(160))
        descInput = field(page, "Beschreibung", f.optString("description"), true)
        tagsInput =
            field(
                page,
                "Tags (mit Komma trennen)",
                f.array("tags").let { a ->
                    (0 until a.length()).joinToString(", ") { a.getString(it) }
                },
            )
        page.addView(text("Lernstand", 14f, true))
        statusInput =
            selector(statuses, statuses.indexOf(f.optString("status"))) {
                if (f.optString("status") != statuses[it]) {
                    dirty = true
                    f.put("status", statuses[it])
                }
            }
        page.addView(statusInput)
        listOf(titleInput, descInput, tagsInput).forEach {
            it?.addTextChangedListener(
                object : TextWatcher {
                    override fun beforeTextChanged(
                        s: CharSequence?,
                        start: Int,
                        count: Int,
                        after: Int,
                    ) {}

                    override fun afterTextChanged(s: Editable?) {
                        dirty = true
                    }

                    override fun onTextChanged(
                        s: CharSequence?,
                        start: Int,
                        before: Int,
                        count: Int,
                    ) {}
                }
            )
        }
        page.addView(text("Videos · erstes Video = Vorschaubild", 18f, true))
        videos.forEachIndexed { index, v ->
            val box = column()
            box.setPadding(0, dp(4), 0, dp(10))
            val name = field(box, "Video ${index+1}", v.optString("name"))
            name.addTextChangedListener(
                object : TextWatcher {
                    override fun beforeTextChanged(
                        s: CharSequence?,
                        start: Int,
                        count: Int,
                        after: Int,
                    ) {}

                    override fun onTextChanged(
                        s: CharSequence?,
                        start: Int,
                        before: Int,
                        count: Int,
                    ) {
                        v.put("name", s.toString())
                        dirty = true
                    }

                    override fun afterTextChanged(s: Editable?) {}
                }
            )
            if (v.optBoolean("missing")) box.addView(text(v.optString("problem")))
            box.addView(
                text(
                    if (v.optBoolean("confirmed")) "Bestätigt ✓"
                    else "Vorschau und Bestätigung erforderlich",
                    13f,
                )
            )
            val tools = row()
            tools.addView(
                button("▶") {
                    captureForm()
                    previewStored(v)
                },
                LinearLayout.LayoutParams(0, -2, 1f),
            )
            tools.addView(
                button("↑") {
                    captureForm()
                    if (index > 0) {
                        videos.removeAt(index)
                        videos.add(index - 1, v)
                        dirty = true
                        renderEditor()
                    }
                },
                LinearLayout.LayoutParams(0, -2, 1f),
            )
            tools.addView(
                button("↓") {
                    captureForm()
                    if (index < videos.lastIndex) {
                        videos.removeAt(index)
                        videos.add(index + 1, v)
                        dirty = true
                        renderEditor()
                    }
                },
                LinearLayout.LayoutParams(0, -2, 1f),
            )
            box.addView(tools)
            val edits = row()
            edits.addView(
                button("Ersetzen") {
                    captureForm()
                    pickVideos(v.getString("id"))
                },
                LinearLayout.LayoutParams(0, -2, 1f),
            )
            edits.addView(
                button("Entfernen") {
                    captureForm()
                    if (videos.size > 1) {
                        videos.remove(v)
                        dirty = true
                        renderEditor()
                    } else notice("Mindestens ein Video muss erhalten bleiben.")
                },
                LinearLayout.LayoutParams(0, -2, 1f),
            )
            box.addView(edits)
            page.addView(box)
        }
        if (videos.any { !it.optBoolean("confirmed") })
            page.addView(
                button("Alle neuen Videos prüfen") {
                    captureForm()
                    previewStored(videos.first { !it.optBoolean("confirmed") })
                }
            )
        page.addView(
            button("＋ Videos hinzufügen") {
                captureForm()
                pickVideos(null)
            }
        )
        page.addView(
            text(
                "Neue Videos werden vor dem Übernehmen abgespielt und bestätigt. Originaldateien bleiben unverändert.",
                13f,
            )
        )
        page.addView(
            button("Figur speichern") {
                captureForm()
                if (
                    f.optString("title").isBlank() ||
                        videos.isEmpty() ||
                        videos.any { !it.optBoolean("confirmed") || it.optBoolean("missing") }
                ) {
                    notice("Name und mindestens ein bestätigtes, abspielbares Video erforderlich.")
                    return@button
                }
                busy = true
                val args =
                    JSONObject(f.toString())
                        .put("token", data.getString("token"))
                        .put("videos", JSONArray(videos))
                rpc("save", args) {
                    dirty = false
                    startLibrary()
                }
            }
        )
        show(page)
    }

    private fun leaveEditor() {
        if (busy) return
        val exit = {
            draft?.optString("token")?.let { rpc("drop", JSONObject().put("token", it)) {} }
            pendingImports.clear()
            startLibrary()
        }
        if (dirty)
            AlertDialog.Builder(this)
                .setTitle("Änderungen verwerfen?")
                .setMessage("Dein Entwurf wurde noch nicht gespeichert.")
                .setNegativeButton("Weiter bearbeiten", null)
                .setPositiveButton("Verwerfen") { _, _ -> exit() }
                .show()
        else exit()
    }

    private fun pickVideos(replace: String?) {
        replacing = replace
        startActivityForResult(
            Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                type = "video/*"
                addCategory(Intent.CATEGORY_OPENABLE)
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, replace == null)
                addFlags(
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                )
            },
            picker,
        )
    }

    @Deprecated("Android document picker")
    override fun onActivityResult(code: Int, result: Int, data: Intent?) {
        super.onActivityResult(code, result, data)
        if (code == 742) {
            if (result != RESULT_OK || data == null) {
                if (fileKind == "zip") startLibrary()
                return
            }
            val clip = data.clipData
            val uris =
                if (clip != null) (0 until clip.itemCount).map { clip.getItemAt(it).uri }
                else listOfNotNull(data.data)
            val files = JSONArray()
            uris.forEach { uri ->
                var name = "import"
                contentResolver
                    .query(
                        uri,
                        arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
                        null,
                        null,
                        null,
                    )
                    ?.use { if (it.moveToFirst()) name = it.getString(0) }
                files.put(JSONObject().put("name", name).put("url", nativeFiles.register(uri)))
            }
            if (files.length() > 0) workflows.files(fileKind, files)
            return
        }
        if (code != picker || result != RESULT_OK || data == null) return
        val clips = data.clipData
        val uris =
            if (clips != null) (0 until clips.itemCount).map { clips.getItemAt(it).uri }
            else listOfNotNull(data.data)
        uris.forEach {
            runCatching {
                contentResolver.takePersistableUriPermission(
                    it,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
        }
        pendingImports.addAll(
            if (replacing != null) uris.take(1) else uris.take((100 - videos.size).coerceAtLeast(0))
        )
        nextImport()
    }

    private fun nextImport() {
        if (pendingImports.isNotEmpty()) previewImport(pendingImports.removeFirst())
        else {
            replacing = null
            renderEditor()
        }
    }

    private fun previewImport(uri: Uri) {
        if (draft == null) return
        val view = VideoView(this)
        previewView = view
        var prepared = false
        var duration = 0
        var width = 0
        var height = 0
        val layout =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(view, LinearLayout.LayoutParams(-1, dp(300)))
                addView(text("Bitte das Video prüfen und ausdrücklich übernehmen."))
            }
        val dialog =
            AlertDialog.Builder(this)
                .setTitle("Video prüfen")
                .setView(layout)
                .setNegativeButton("Überspringen", null)
                .setPositiveButton("Video übernehmen", null)
                .create()
        preview = dialog
        dialog.setOnDismissListener {
            view.stopPlayback()
            preview = null
            previewView = null
        }
        dialog.setOnCancelListener {
            pendingImports.clear()
            replacing = null
            renderEditor()
        }
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
            dialog.dismiss()
            nextImport()
        }
        view.setMediaController(MediaController(this))
        view.setOnPreparedListener { mp ->
            prepared = true
            duration = mp.duration
            width = mp.videoWidth
            height = mp.videoHeight
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = duration > 0
            view.start()
        }
        view.setOnErrorListener { _, _, _ ->
            prepared = false
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
            notice("Dieses Video ist nicht abspielbar. Bitte ein anderes Video wählen.")
            true
        }
        view.setVideoURI(uri)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            if (!prepared || busy) return@setOnClickListener
            busy = true
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
            val token = draft!!.getString("token")
            val replace = replacing
            Thread {
                    val metadata = runCatching {
                        var name = "Video"
                        contentResolver
                            .query(
                                uri,
                                arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
                                null,
                                null,
                                null,
                            )
                            ?.use { if (it.moveToFirst()) name = it.getString(0) }
                        var poster: String? = null
                        runCatching {
                            MediaMetadataRetriever().releasing { r ->
                                r.setDataSource(this, uri)
                                r.getFrameAtTime(0)?.let { bmp ->
                                    val factor = minOf(1f, 320f / maxOf(bmp.width, bmp.height))
                                    val scaled =
                                        Bitmap.createScaledBitmap(
                                            bmp,
                                            (bmp.width * factor).toInt().coerceAtLeast(1),
                                            (bmp.height * factor).toInt().coerceAtLeast(1),
                                            true,
                                        )
                                    val bytes = ByteArrayOutputStream()
                                    scaled.compress(Bitmap.CompressFormat.JPEG, 75, bytes)
                                    poster =
                                        Base64.encodeToString(bytes.toByteArray(), Base64.NO_WRAP)
                                    scaled.recycle()
                                    if (scaled !== bmp) bmp.recycle()
                                }
                            }
                        }
                        JSONObject()
                            .put("token", token)
                            .put("url", nativeFiles.register(uri))
                            .put("name", name)
                            .put("duration", duration / 1000.0)
                            .put("width", width)
                            .put("height", height)
                            .put("type", contentResolver.getType(uri) ?: "video/mp4")
                            .put("poster", poster ?: JSONObject.NULL)
                            .put("keep", JSONArray(videos))
                            .apply { if (replace != null) put("replace", replace) }
                    }
                    runOnUiThread {
                        metadata.fold(
                            { args ->
                                rpc("add", args) { value ->
                                    val incoming = (value as JSONArray).objects()
                                    val previous = videos.toList()
                                    videos =
                                        previous
                                            .mapNotNull { old ->
                                                incoming
                                                    .find {
                                                        it.optString("id") == old.optString("id")
                                                    }
                                                    ?.apply { put("name", old.optString("name")) }
                                                    ?: if (old.optString("id") == replace)
                                                        incoming.firstOrNull { n ->
                                                            previous.none {
                                                                it.optString("id") ==
                                                                    n.optString("id")
                                                            }
                                                        }
                                                    else null
                                            }
                                            .toMutableList()
                                    incoming
                                        .filter { n ->
                                            previous.none {
                                                it.optString("id") == n.optString("id")
                                            } &&
                                                videos.none {
                                                    it.optString("id") == n.optString("id")
                                                }
                                        }
                                        .forEach { videos.add(it) }
                                    dirty = true
                                    dialog.dismiss()
                                    nextImport()
                                }
                            },
                            { error ->
                                busy = false
                                notice(error.message ?: "Video konnte nicht gelesen werden")
                                dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = true
                            },
                        )
                    }
                }
                .start()
        }
    }

    private fun previewStored(v: JSONObject) {
        val session = draft?.getString("token") ?: return
        busy = true
        rpc("media", JSONObject().put("token", session).put("video", v.getString("id"))) { data ->
            if (screen != "editor") return@rpc
            val token = (data as JSONObject).getString("token")
            val file = nativeMedia.file(token)
            val view = VideoView(this)
            previewView = view
            var duration = 0
            var width = 0
            var height = 0
            val layout =
                LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(view, LinearLayout.LayoutParams(-1, dp(300)))
                    addView(text("Vor dem Speichern abspielen und bestätigen."))
                }
            val dialog =
                AlertDialog.Builder(this)
                    .setTitle(v.optString("name"))
                    .setView(layout)
                    .setNegativeButton("Schließen", null)
                    .setPositiveButton("Video übernehmen", null)
                    .create()
            preview = dialog
            dialog.setOnDismissListener {
                view.stopPlayback()
                nativeMedia.abort(token)
                preview = null
                previewView = null
            }
            dialog.show()
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
            view.setMediaController(MediaController(this))
            view.setOnPreparedListener { mp ->
                duration = mp.duration
                width = mp.videoWidth
                height = mp.videoHeight
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = duration > 0
                view.start()
            }
            view.setOnErrorListener { _, _, _ ->
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
                notice("Video nicht abspielbar. Bitte ersetzen.")
                true
            }
            view.setVideoPath(file.absolutePath)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (busy || duration <= 0) return@setOnClickListener
                busy = true
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
                Thread {
                        var poster: String? = null
                        runCatching {
                            MediaMetadataRetriever().releasing { r ->
                                r.setDataSource(file.absolutePath)
                                r.getFrameAtTime(0)?.let { bmp ->
                                    val scale = minOf(1f, 320f / maxOf(bmp.width, bmp.height))
                                    val scaled =
                                        Bitmap.createScaledBitmap(
                                            bmp,
                                            (bmp.width * scale).toInt().coerceAtLeast(1),
                                            (bmp.height * scale).toInt().coerceAtLeast(1),
                                            true,
                                        )
                                    val bytes = ByteArrayOutputStream()
                                    scaled.compress(Bitmap.CompressFormat.JPEG, 75, bytes)
                                    poster =
                                        Base64.encodeToString(bytes.toByteArray(), Base64.NO_WRAP)
                                    if (scaled !== bmp) scaled.recycle()
                                    bmp.recycle()
                                }
                            }
                        }
                        val args =
                            JSONObject()
                                .put("token", session)
                                .put("video", v.getString("id"))
                                .put("duration", duration / 1000.0)
                                .put("width", width)
                                .put("height", height)
                                .put("poster", poster ?: JSONObject.NULL)
                        runOnUiThread {
                            rpc("confirm", args) { value ->
                                val updated =
                                    (value as JSONArray).objects().find {
                                        it.getString("id") == v.getString("id")
                                    }
                                if (updated != null) {
                                    v.put("confirmed", true)
                                    v.put("duration", updated.optDouble("duration"))
                                    v.put("missing", false)
                                }
                                dirty = true
                                dialog.dismiss()
                                renderEditor()
                                videos
                                    .firstOrNull { !it.optBoolean("confirmed") }
                                    ?.let { next ->
                                        AlertDialog.Builder(this)
                                            .setMessage("Nächstes neues Video prüfen?")
                                            .setNegativeButton("Später", null)
                                            .setPositiveButton("Prüfen") { _, _ ->
                                                previewStored(next)
                                            }
                                            .show()
                                    }
                            }
                        }
                    }
                    .start()
            }
        }
    }

    private fun openPlayer(f: JSONObject) {
        busy = true
        rpc("videos", key(f)) { data ->
            val records = (data as JSONArray).objects()
            if (records.isEmpty()) {
                notice("Keine Videos vorhanden")
                return@rpc
            }
            screen = "player"
            val page = column()
            val top = row()
            top.addView(button("‹") { startLibrary() })
            top.addView(text(f.optString("title"), 19f, true), LinearLayout.LayoutParams(0, -2, 1f))
            top.addView(button("↻") { player?.rotate() })
            page.addView(top)
            val stage = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
            val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            playerContent = content
            playerStage = stage
            page.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))
            content.addView(stage, LinearLayout.LayoutParams(-1, 0, 1f))
            val tools = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            val toolScroll = ScrollView(this).apply { addView(tools) }
            playerTools = toolScroll
            content.addView(toolScroll, LinearLayout.LayoutParams(-1, dp(250)))
            val p = NativePlayer(this, stage)
            player = p
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            var selected = -1
            val choice =
                selector(records.map { it.optString("name").ifBlank { "Video" } }, 0) { i ->
                    if (i != selected) {
                        selected = i
                        loadMedia(f, records[i], p)
                    }
                }
            tools.addView(choice)
            val time = text("Video wird geladen …", 12f)
            tools.addView(time)
            val seek = SeekBar(this).apply { max = 1000 }
            tools.addView(seek)
            p.attachTimeline(seek, time)
            val controls = row()
            controls.addView(button("−5 s") { p.rewind() }, LinearLayout.LayoutParams(0, -2, 1f))
            controls.addView(button("▶ / Ⅱ") { p.toggle() }, LinearLayout.LayoutParams(0, -2, 1f))
            controls.addView(
                button("Fit / Crop") { p.toggleCrop() },
                LinearLayout.LayoutParams(0, -2, 1f),
            )
            tools.addView(controls)
            val loops = row()
            val loop =
                button("Loop aus") {
                    p.toggleLoop()
                    (loops.getChildAt(0) as Button).text = if (p.loop) "Loop an" else "Loop aus"
                }
            loops.addView(loop, LinearLayout.LayoutParams(0, -2, 1f))
            loops.addView(
                button("A") {
                    p.markA()
                    loop.text = "Loop aus"
                },
                LinearLayout.LayoutParams(0, -2, 1f),
            )
            loops.addView(
                button("B") {
                    p.markB()
                    loop.text = "Loop aus"
                },
                LinearLayout.LayoutParams(0, -2, 1f),
            )
            loops.addView(button("A–B ×") { p.clearAB() }, LinearLayout.LayoutParams(0, -2, 1f))
            tools.addView(loops)
            val bottom = row()
            bottom.addView(
                selector(listOf("0,25×", "0,5×", "0,75×", "1×"), 3) {
                    p.speed(listOf(.25f, .5f, .75f, 1f)[it])
                },
                LinearLayout.LayoutParams(0, dp(48), 1f),
            )
            bottom.addView(
                button("Vollbild") { p.fullscreen() },
                LinearLayout.LayoutParams(0, -2, 1f),
            )
            tools.addView(bottom)
            adjustPlayerLayout()
            show(page, false)
        }
    }

    private fun adjustPlayerLayout() {
        val content = playerContent ?: return
        val stage = playerStage ?: return
        val tools = playerTools ?: return
        val landscape =
            resources.configuration.orientation ==
                android.content.res.Configuration.ORIENTATION_LANDSCAPE
        content.orientation = if (landscape) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        stage.layoutParams =
            if (landscape) LinearLayout.LayoutParams(0, -1, 1f)
            else LinearLayout.LayoutParams(-1, 0, 1f)
        tools.layoutParams =
            if (landscape) LinearLayout.LayoutParams(dp(270), -1)
            else LinearLayout.LayoutParams(-1, dp(250))
    }

    override fun onConfigurationChanged(config: android.content.res.Configuration) {
        super.onConfigurationChanged(config)
        if (screen == "player") adjustPlayerLayout()
    }

    private fun loadMedia(f: JSONObject, v: JSONObject, p: NativePlayer) {
        val current = ++generation
        p.pause()
        p.clearAB()
        val args = key(f).put("video", v.getString("id"))
        rpc("media", args) { data ->
            val token = (data as JSONObject).getString("token")
            if (player !== p || generation != current) {
                nativeMedia.abort(token)
                return@rpc
            }
            activeMedia?.let(nativeMedia::abort)
            activeMedia = token
            p.load(nativeMedia.file(token))
        }
    }

    private fun acceptImport(data: JSONObject) {
        draft = data
        videos = data.array("videos").objects().toMutableList()
        dirty = true
        screen = "editor"
        renderEditor()
    }

    private fun chooseWorkflowFile(kind: String) {
        fileKind = kind
        startActivityForResult(
            Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                type = "*/*"
                addCategory(Intent.CATEGORY_OPENABLE)
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, kind == "backup")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            },
            742,
        )
    }

    private fun showWorkflow(action: String, f: JSONObject? = null) {
        screen = "workflow"
        generation++
        player?.close()
        player = null
        val loading = column()
        loading.addView(text("Bitte warten …"))
        show(loading)
        workflows.open(action, f)
    }

    private fun showWeb(action: String, f: JSONObject? = null) {
        generation++
        player?.close()
        player = null
        screen = "web"
        nativePanel.visibility = View.GONE
        web.visibility = View.VISIBLE
        val old = frame.findViewWithTag<View>("native-return")
        if (old != null) frame.removeView(old)
        val back =
            button("‹ Meine Figuren") {
                    web.evaluateJavascript("window.AndroidAppUI?.back()", null)
                }
                .apply {
                    tag = "native-return"
                    setBackgroundColor(bg)
                }
        frame.addView(back, FrameLayout.LayoutParams(-1, dp(48), Gravity.TOP))
        web.layoutParams = FrameLayout.LayoutParams(-1, -1).apply { topMargin = dp(48) }
        if (action != "overview")
            web.evaluateJavascript(
                "window.AndroidAppUI.show(${JSONObject.quote(action)},${f?.let(::key)?:"null"}).catch(e=>alert(e.message))",
                null,
            )
    }

    private fun handleIncoming() {
        val incoming = importedIntent ?: return
        importedIntent = null
        if (
            (incoming.action == Intent.ACTION_SEND && incoming.type == "text/plain") ||
                (incoming.action == Intent.ACTION_VIEW && incoming.data?.scheme == "https")
        ) {
            val raw = incoming.dataString ?: incoming.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
            val link = Regex("""https://[^\s<>"']+""").find(raw)?.value?.trimEnd('.', ',', ')')
            val library =
                link?.let { runCatching { Uri.parse(it).getQueryParameter("library") }.getOrNull() }
            if (library == null) showWorkflow("cloud")
            else {
                screen = "workflow"
                val loading = column()
                loading.addView(text("Bibliothek laden …"))
                show(loading)
                workflows.openLibrary(library)
            }
            return
        }
        val uris =
            when (incoming.action) {
                Intent.ACTION_SEND ->
                    listOfNotNull(incoming.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
                Intent.ACTION_SEND_MULTIPLE ->
                    incoming.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.toList()
                        ?: emptyList()
                else -> emptyList()
            }
        if (uris.isEmpty()) return
        if (incoming.type?.startsWith("video/") == true) {
            pendingImports.addAll(uris.take(100))
            openEditor(null)
        } else {
            screen = "workflow"
            val files = JSONArray()
            uris.forEach { uri ->
                var name = "import.zip"
                contentResolver
                    .query(
                        uri,
                        arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
                        null,
                        null,
                        null,
                    )
                    ?.use { if (it.moveToFirst()) name = it.getString(0) }
                files.put(JSONObject().put("url", nativeFiles.register(uri)).put("name", name))
            }
            if (
                files.length() > 0 &&
                    (0 until files.length()).all {
                        files.getJSONObject(it).getString("name").endsWith(".salsabackup", true)
                    }
            )
                workflows.receiveBackup(files)
            else workflows.files("zip", files)
        }
    }

    override fun onNewIntent(intent: Intent) {
        if ((screen == "editor" && dirty) || busy || (screen == "workflow" && workflows.isBusy)) {
            notice("Bitte den aktuellen Vorgang zuerst abschließen und danach erneut teilen.")
            return
        }
        setIntent(intent)
        if (
            (intent.action == Intent.ACTION_SEND && intent.type == "text/plain") ||
                (intent.action == Intent.ACTION_VIEW && intent.data?.scheme == "https")
        ) {
            val raw = intent.dataString ?: intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
            val link = Regex("""https://[^\s<>"']+""").find(raw)?.value?.trimEnd('.', ',', ')')
            if (link != null && Uri.parse(link).getQueryParameter("token_hash") != null) {
                rpc("login", JSONObject().put("link", link)) { showWorkflow("cloud") }
                return
            }
        }
        importedIntent = intent
        if (loaded) startLibrary()
    }

    @Deprecated("Android navigation")
    override fun onBackPressed() {
        if (busy) return
        when (screen) {
            "editor" -> leaveEditor()
            "player" -> startLibrary()
            "workflow" -> workflows.back()
            "web" -> web.evaluateJavascript("window.AndroidAppUI?.back()", null)
            else -> finish()
        }
    }

    override fun onPause() {
        player?.pause()
        previewView?.pause()
        if (::workflows.isInitialized) workflows.pause()
        super.onPause()
    }

    override fun onDestroy() {
        generation++
        preview?.dismiss()
        player?.close()
        callbacks.clear()
        super.onDestroy()
    }

    companion object {
        fun formatTime(ms: Int): String {
            val seconds = (ms / 1000).coerceAtLeast(0)
            return "${seconds/60}:${(seconds%60).toString().padStart(2,'0')}"
        }
    }
}
