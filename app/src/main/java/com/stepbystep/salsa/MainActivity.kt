package com.stepbystep.salsa

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.Matrix
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import android.text.TextWatcher
import android.text.Editable
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

class MainActivity : Activity() {
    private val bg = Color.rgb(23, 18, 31)
    private val surface = Color.rgb(41, 33, 50)
    private val ink = Color.rgb(243, 237, 247)
    private val accent = Color.rgb(207, 180, 246)
    private val muted = Color.rgb(191, 178, 203)
    private val figures = mutableListOf<Figure>()
    private lateinit var root: LinearLayout
    private var query = ""
    private var statusFilter = "Alle"
    private var current: Figure? = null
    private var playing: VideoView? = null
    private var selectedUri: Uri? = null
    private var onVideoConfirmed: ((Uri) -> Unit)? = null
    private var abStart: Int? = null
    private var abEnd: Int? = null
    private var loop = false
    private var crop = false
    private val handler = Handler(Looper.getMainLooper())
    private var tick: Runnable? = null
    private val chooseVideo = 102

    data class Figure(val id: String, var title: String, var description: String, var status: String,
                      var tags: String, var video: String)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = bg
        window.navigationBarColor = bg
        load()
        showOverview()
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun column(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(18), dp(22), dp(18), dp(18))
        setBackgroundColor(bg)
    }
    private fun label(s: String, size: Float = 16f, color: Int = ink, bold: Boolean = false) = TextView(this).apply {
        text = s; textSize = size; setTextColor(color)
        if (bold) setTypeface(null, Typeface.BOLD)
        setPadding(0, dp(7), 0, dp(7))
    }
    private fun button(s: String, click: () -> Unit) = Button(this).apply {
        text = s; isAllCaps = false; setTextColor(ink)
        setBackgroundTintList(android.content.res.ColorStateList.valueOf(surface))
        setOnClickListener { click() }
    }
    private fun input(hintText: String, value: String = "") = EditText(this).apply {
        hint = hintText; setText(value); setTextColor(ink); setHintTextColor(muted)
        backgroundTintList = android.content.res.ColorStateList.valueOf(accent)
        setSingleLine(true)
    }
    private fun setScreen(view: LinearLayout) {
        root = view
        setContentView(ScrollView(this).apply { setBackgroundColor(bg); fillViewport = true; addView(root) })
    }
    private fun save() {
        val data = JSONArray()
        figures.forEach { f -> data.put(JSONObject().put("id", f.id).put("title", f.title)
            .put("description", f.description).put("status", f.status).put("tags", f.tags)
            .put("video", f.video)) }
        getPreferences(MODE_PRIVATE).edit().putString("figures", data.toString()).apply()
    }
    private fun load() {
        runCatching {
            val stored = JSONArray(getPreferences(MODE_PRIVATE).getString("figures", "[]") ?: "[]")
            for (i in 0 until stored.length()) {
                val o = stored.getJSONObject(i)
                figures.add(Figure(o.getString("id"), o.getString("title"), o.optString("description"),
                    o.optString("status", "Neu"), o.optString("tags"), o.optString("video")))
            }
        }
    }
    private fun showOverview() {
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        val page = column()
        val top = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        top.addView(label("♪  Step by Step", 23f, accent, true), LinearLayout.LayoutParams(0, -2, 1f))
        top.addView(button("⚙") { showSettings() })
        page.addView(top)
        page.addView(label("Meine Figuren", 24f, ink, true))
        page.addView(label("Deine Schritte. Dein Tempo.", 14f, muted))
        page.addView(button("＋ Figur hinzufügen") { editFigure(null) })
        val search = input("Figur suchen", query)
        page.addView(search)
        val filters = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf("Alle", "Neu", "Am Üben", "Sitzt").forEach { s ->
            filters.addView(button(if (s == statusFilter) "● $s" else s) {
                statusFilter = s; showOverview()
            }, LinearLayout.LayoutParams(0, dp(50), 1f))
        }
        page.addView(filters)
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        page.addView(list)
        fun renderList() {
            list.removeAllViews()
            val visible = figures.filter { f ->
                (statusFilter == "Alle" || statusFilter == f.status) &&
                (f.title.contains(query, true) || f.tags.contains(query, true))
            }
            list.addView(label("${visible.size} Figuren", 15f, accent, true))
            if (visible.isEmpty()) list.addView(label("Noch keine passenden Figuren. Füge ein Video hinzu.", 15f, muted))
            visible.reversed().forEach { f ->
                val row = LinearLayout(this).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(10), dp(7), dp(5), dp(7))
                    setBackgroundColor(surface)
                }
                if (f.video.isNotBlank()) {
                    runCatching {
                        val bitmap = contentResolver.loadThumbnail(Uri.parse(f.video), android.util.Size(dp(76), dp(76)), null)
                        row.addView(ImageView(this).apply { setImageBitmap(bitmap); scaleType = ImageView.ScaleType.CENTER_CROP },
                            LinearLayout.LayoutParams(dp(76), dp(76)))
                    }
                }
                val detail = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, dp(4), 0)
                    addView(label(f.title, 17f, ink, true))
                    addView(label(f.status + if (f.tags.isNotBlank()) " · " + f.tags else "", 13f, accent))
                }
                row.addView(detail, LinearLayout.LayoutParams(0, -2, 1f))
                row.addView(button("⋮") {
                    AlertDialog.Builder(this).setItems(arrayOf("Öffnen", "Bearbeiten", "Löschen")) { _, which ->
                        when (which) {
                            0 -> showPlayer(f)
                            1 -> editFigure(f)
                            2 -> AlertDialog.Builder(this).setMessage("„${f.title}“ löschen?")
                                .setNegativeButton("Abbrechen", null)
                                .setPositiveButton("Löschen") { _, _ -> figures.remove(f); save(); showOverview() }.show()
                        }
                    }.show()
                })
                row.setOnClickListener { showPlayer(f) }
                list.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(9) })
            }
        }
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                query = s.toString(); renderList()
            }
            override fun afterTextChanged(s: Editable?) {}
        })
        renderList()
        page.addView(label("Figuren sind nur auf diesem Android-Gerät gespeichert.", 12f, muted))
        setScreen(page)
    }
    private fun showSettings() {
        val page = column()
        page.addView(button("‹ Zurück") { showOverview() })
        page.addView(label("Einstellungen", 24f, accent, true))
        page.addView(label("Step by Step – Android · Version 0.1.0"))
        page.addView(label("Offline · Videos verbleiben am ursprünglichen Speicherort.", 14f, muted))
        page.addView(label("Cloud-Synchronisierung und ZIP-Import sind noch nicht implementiert.", 14f, muted))
        setScreen(page)
    }
    private fun editFigure(existing: Figure?) {
        val page = column()
        page.addView(button("‹ Zurück") { showOverview() })
        page.addView(label(if (existing == null) "Figur hinzufügen" else "Figur bearbeiten", 23f, accent, true))
        page.addView(label("Name der Figur"))
        val name = input("Name", existing?.title ?: ""); page.addView(name)
        page.addView(label("Beschreibung"))
        val description = input("Schritte, Hinweise …", existing?.description ?: ""); page.addView(description)
        page.addView(label("Tags"))
        val tags = input("z. B. Drehung, Anfänger", existing?.tags ?: ""); page.addView(tags)
        val statuses = arrayOf("Neu", "Am Üben", "Sitzt")
        val spinner = Spinner(this)
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, statuses)
        spinner.setSelection(statuses.indexOf(existing?.status ?: "Neu").coerceAtLeast(0))
        page.addView(label("Lernstand")); page.addView(spinner)
        var uri = existing?.video ?: ""
        val videoLabel = label(if (uri.isBlank()) "Kein Video ausgewählt" else "Video vorhanden", 14f, muted)
        page.addView(videoLabel)
        page.addView(button("Video auswählen und prüfen") {
            onVideoConfirmed = { confirmed ->
                uri = confirmed.toString()
                videoLabel.text = "Video bestätigt ✓"
                setScreen(page)
            }
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "video/*"
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            }
            startActivityForResult(intent, chooseVideo)
        })
        page.addView(label("Vor dem Übernehmen wird das Video abgespielt und muss bestätigt werden.", 13f, muted))
        page.addView(button("Figur speichern") {
            if (name.text.isBlank() || uri.isBlank()) {
                Toast.makeText(this, "Name und bestätigtes Video erforderlich", Toast.LENGTH_SHORT).show()
            } else {
                if (existing == null) figures.add(Figure(UUID.randomUUID().toString(), name.text.toString(),
                    description.text.toString(), statuses[spinner.selectedItemPosition], tags.text.toString(), uri))
                else {
                    existing.title = name.text.toString(); existing.description = description.text.toString()
                    existing.status = statuses[spinner.selectedItemPosition]; existing.tags = tags.text.toString()
                    existing.video = uri
                }
                save(); showOverview()
            }
        })
        setScreen(page)
    }
    @Deprecated("Uses Android document picker callback")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != chooseVideo || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        runCatching {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        selectedUri = uri
        val preview = VideoView(this)
        preview.setVideoURI(uri)
        preview.setMediaController(MediaController(this))
        preview.setOnPreparedListener { preview.start() }
        AlertDialog.Builder(this).setTitle("Video prüfen")
            .setMessage("Spiele das Video ab und bestätige die Übernahme.")
            .setView(preview)
            .setNegativeButton("Abbrechen") { _, _ -> preview.stopPlayback() }
            .setPositiveButton("Video übernehmen") { _, _ ->
                preview.stopPlayback()
                onVideoConfirmed?.invoke(uri)
            }.setOnDismissListener { preview.stopPlayback() }.show()
        preview.layoutParams = LinearLayout.LayoutParams(-1, dp(260))
    }
    private fun showPlayer(figure: Figure) {
        current = figure
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        loop = false; crop = false; abStart = null; abEnd = null
        val page = column()
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(button("‹") { exitPlayer() })
        header.addView(label(figure.title, 19f, ink, true), LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(button("↻") {
            requestedOrientation = if (requestedOrientation == ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE)
                ActivityInfo.SCREEN_ORIENTATION_PORTRAIT else ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        })
        page.addView(header)
        val video = object : VideoView(this) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                if (!crop) { super.onMeasure(widthMeasureSpec, heightMeasureSpec); return }
                setMeasuredDimension(View.MeasureSpec.getSize(widthMeasureSpec), View.MeasureSpec.getSize(heightMeasureSpec))
            }
        }
        playing = video
        val stage = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(video, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
        }
        page.addView(stage, LinearLayout.LayoutParams(-1, dp(330)))
        val info = label("0:00 / 0:00", 13f, muted)
        val seek = SeekBar(this).apply { max = 1000 }
        page.addView(seek); page.addView(info)
        video.setVideoURI(Uri.parse(figure.video))
        video.setOnPreparedListener { mp ->
            video.start()
            mp.isLooping = false
        }
        video.setOnErrorListener { _, _, _ ->
            Toast.makeText(this, "Video konnte nicht geöffnet werden", Toast.LENGTH_LONG).show(); true
        }
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onStartTrackingTouch(bar: SeekBar?) {}
            override fun onStopTrackingTouch(bar: SeekBar?) { video.seekTo(video.duration.coerceAtLeast(0) * seek.progress / 1000) }
            override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {}
        })
        val row = LinearLayout(this)
        row.addView(button("−5 s") { video.seekTo((video.currentPosition - 5000).coerceAtLeast(0)) }, LinearLayout.LayoutParams(0, dp(52), 1f))
        row.addView(button("▶ / ❚❚") { if (video.isPlaying) video.pause() else video.start() }, LinearLayout.LayoutParams(0, dp(52), 1f))
        row.addView(button("Fit / Crop") {
            crop = !crop
            val params = video.layoutParams as FrameLayout.LayoutParams
            val dimensions = runCatching {
                MediaMetadataRetriever().use { retriever ->
                    retriever.setDataSource(this, Uri.parse(figure.video))
                    Pair(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toInt() ?: 16,
                        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toInt() ?: 9)
                }
            }.getOrDefault(Pair(16, 9))
            val ratio = dimensions.first.toFloat() / dimensions.second.coerceAtLeast(1)
            val w = resources.displayMetrics.widthPixels - dp(36)
            val h = dp(330)
            if (crop) {
                if (w.toFloat() / h > ratio) {
                    params.width = w; params.height = (w / ratio).toInt()
                } else { params.height = h; params.width = (h * ratio).toInt() }
            } else { params.width = -1; params.height = -1 }
            params.gravity = Gravity.CENTER
            video.layoutParams = params
        }, LinearLayout.LayoutParams(0, dp(52), 1f))
        page.addView(row)
        val speeds = LinearLayout(this)
        listOf(0.25f, 0.5f, 0.75f, 1f).forEach { speed ->
            speeds.addView(button("${speed}×") {
                if (Build.VERSION.SDK_INT >= 23) runCatching {
                    video.setOnPreparedListener { mp -> mp.playbackParams = mp.playbackParams.setSpeed(speed) }
                    // Speed for a currently prepared VideoView is not exposed publicly; reopen at current position.
                    val position = video.currentPosition
                    video.stopPlayback(); video.setVideoURI(Uri.parse(figure.video))
                    video.setOnPreparedListener { mp -> mp.playbackParams = mp.playbackParams.setSpeed(speed); video.seekTo(position); video.start() }
                }
            }, LinearLayout.LayoutParams(0, dp(48), 1f))
        }
        page.addView(speeds)
        val loops = LinearLayout(this)
        loops.addView(button("Loop") { loop = !loop; Toast.makeText(this, if (loop) "Loop an" else "Loop aus", Toast.LENGTH_SHORT).show() })
        loops.addView(button("A") { abStart = video.currentPosition; abEnd = null })
        loops.addView(button("B") { if (abStart != null && video.currentPosition > abStart!!) abEnd = video.currentPosition })
        loops.addView(button("A–B löschen") { abStart = null; abEnd = null })
        page.addView(loops)
        page.addView(label(figure.description, 14f, muted))
        setScreen(page)
        tick?.let(handler::removeCallbacks)
        tick = object : Runnable {
            override fun run() {
                if (playing !== video) return
                val pos = video.currentPosition
                val end = video.duration.coerceAtLeast(0)
                if (end > 0) {
                    seek.progress = (pos.toLong() * 1000L / end).toInt()
                    info.text = "${pos / 60000}:${(pos / 1000 % 60).toString().padStart(2, '0')} / ${end / 60000}:${(end / 1000 % 60).toString().padStart(2, '0')}"
                    if (abEnd != null && abStart != null && pos >= abEnd!!) video.seekTo(abStart!!)
                    else if (loop && !video.isPlaying && pos >= end - 250) { video.seekTo(0); video.start() }
                }
                handler.postDelayed(this, 150)
            }
        }
        handler.post(tick!!)
    }
    private fun exitPlayer() {
        tick?.let(handler::removeCallbacks); tick = null
        playing?.stopPlayback(); playing = null
        current = null
        showOverview()
    }
    override fun onBackPressed() {
        if (playing != null) exitPlayer() else showOverview()
    }
    override fun onDestroy() {
        tick?.let(handler::removeCallbacks)
        playing?.stopPlayback()
        super.onDestroy()
    }
}
