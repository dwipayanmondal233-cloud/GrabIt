package com.example.grabit

import android.Manifest
import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import java.net.URL
import java.net.HttpURLConnection
import java.util.concurrent.Executors

class MainActivity : Activity() {

    private val worker = Executors.newSingleThreadExecutor()
    private val thumbnailWorker = Executors.newSingleThreadExecutor()

    private lateinit var urlInput: EditText
    private lateinit var pasteBtn: Button
    private lateinit var infoBtn: Button
    private lateinit var historyBtn: Button
    private lateinit var settingsBtn: Button
    private lateinit var progress: ProgressBar
    private lateinit var status: TextView
    private lateinit var thumb: ImageView
    private lateinit var titleText: TextView
    private lateinit var metaText: TextView
    private lateinit var optionsCard: View
    private lateinit var radioGroup: RadioGroup
    private lateinit var rbVideo: RadioButton
    private lateinit var rbAudio: RadioButton
    private lateinit var formatSpinner: Spinner
    private lateinit var addBtn: Button
    private lateinit var clipBar: View
    private lateinit var clipText: TextView
    private lateinit var clipUse: Button
    private lateinit var clipHide: Button
    private lateinit var queueList: LinearLayout
    private lateinit var queueEmpty: TextView
    private lateinit var clearQueueBtn: Button

    private var currentUrl = ""
    private var currentTitle = ""
    private var currentUploader = ""
    private var currentThumbUrl = ""
    private var clipLink = ""
    private var lastOffered = ""
    private var themeAtCreate = 0

    private val rows = HashMap<Long, View>()

    private val heights = intArrayOf(0, 1080, 720, 480, 360)
    private val videoLabels = arrayOf("Best", "1080p", "720p", "480p", "360p")
    private val audioLabels = arrayOf("MP3 (best)", "M4A (best)")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        themeAtCreate = Prefs.theme(this)

        urlInput = findViewById(R.id.urlInput)
        pasteBtn = findViewById(R.id.pasteBtn)
        infoBtn = findViewById(R.id.infoBtn)
        historyBtn = findViewById(R.id.historyBtn)
        settingsBtn = findViewById(R.id.settingsBtn)
        progress = findViewById(R.id.progress)
        status = findViewById(R.id.status)
        thumb = findViewById(R.id.thumb)
        titleText = findViewById(R.id.titleText)
        metaText = findViewById(R.id.metaText)
        optionsCard = findViewById(R.id.optionsCard)
        radioGroup = findViewById(R.id.radioGroup)
        rbVideo = findViewById(R.id.rbVideo)
        rbAudio = findViewById(R.id.rbAudio)
        formatSpinner = findViewById(R.id.formatSpinner)
        addBtn = findViewById(R.id.addBtn)
        clipBar = findViewById(R.id.clipBar)
        clipText = findViewById(R.id.clipText)
        clipUse = findViewById(R.id.clipUse)
        clipHide = findViewById(R.id.clipHide)
        queueList = findViewById(R.id.queueList)
        queueEmpty = findViewById(R.id.queueEmpty)
        clearQueueBtn = findViewById(R.id.clearQueueBtn)

        Style.apply(findViewById<View>(android.R.id.content))

        fillSpinner(false)
        radioGroup.setOnCheckedChangeListener { _, id -> fillSpinner(id == R.id.rbAudio) }

        pasteBtn.setOnClickListener { pasteFromClipboard() }
        infoBtn.setOnClickListener { onGetInfo() }
        addBtn.setOnClickListener { onAdd() }
        historyBtn.setOnClickListener { startActivity(Intent(this, HistoryActivity::class.java)) }
        settingsBtn.setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        clearQueueBtn.setOnClickListener { Queue.clearFinished() }
        clipUse.setOnClickListener {
            urlInput.setText(clipLink)
            clipBar.visibility = View.GONE
            onGetInfo()
        }
        clipHide.setOnClickListener { clipBar.visibility = View.GONE }

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }

        if (!Prefs.noticeAccepted(this)) {
            Notice.showFirstRun(this) { }
        }

        progress.visibility = View.VISIBLE
        worker.execute {
            val err = Engine.ensure(application)
            val ver = if (err == null) Engine.version(application) else ""
            runOnUiThread {
                progress.visibility = View.GONE
                status.text = if (err == null) {
                    "Engine ready | yt-dlp $ver"
                } else {
                    "Engine failed to start: $err"
                }
            }
        }

        if (savedInstanceState == null) handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        if (Prefs.theme(this) != themeAtCreate) {
            recreate()
            return
        }
        Queue.listener = { renderQueue() }
        renderQueue()
    }

    override fun onPause() {
        super.onPause()
        Queue.listener = null
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) checkClipboard()
    }

    private fun extractLink(text: String?): String? {
        if (text == null) return null
        return Regex("https?://[^\\s]+").find(text)?.value
    }

    private fun handleIntent(i: Intent?) {
        if (i == null) return
        if (i.action == Intent.ACTION_SEND && i.type == "text/plain") {
            val text = i.getStringExtra(Intent.EXTRA_TEXT)
            val link = extractLink(text)
            i.action = null
            if (link == null) {
                Toast.makeText(this, "No link found in the shared text", Toast.LENGTH_LONG).show()
                return
            }
            urlInput.setText(link)
            onGetInfo()
        }
    }

    private fun checkClipboard() {
        try {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val text = cm.primaryClip?.getItemAt(0)?.text?.toString()
            val link = extractLink(text) ?: return
            if (link == lastOffered) return
            if (link == urlInput.text.toString().trim()) return
            lastOffered = link
            clipLink = link
            clipText.text = "Link in clipboard:\n" + link
            clipBar.visibility = View.VISIBLE
        } catch (e: Exception) {
        }
    }

    private fun fillSpinner(audio: Boolean) {
        val labels = if (audio) audioLabels else videoLabels
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, labels)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        formatSpinner.adapter = adapter
    }

    private fun applyDefaults() {
        val audio = Prefs.defaultAudio(this)
        if (audio) rbAudio.isChecked = true else rbVideo.isChecked = true
        fillSpinner(audio)
        formatSpinner.setSelection(if (audio) Prefs.audioFmt(this) else Prefs.quality(this))
    }

    private fun renderQueue() {
        val items = Queue.items.toList()
        val ids = items.map { it.id }.toSet()

        val iter = rows.entries.iterator()
        while (iter.hasNext()) {
            val e = iter.next()
            if (!ids.contains(e.key)) {
                queueList.removeView(e.value)
                iter.remove()
            }
        }

        for (item in items) {
            var v = rows[item.id]
            if (v == null) {
                v = layoutInflater.inflate(R.layout.item_queue, queueList, false)
                Style.apply(v)
                rows[item.id] = v
                queueList.addView(v)
            }
            v?.let { view ->
                val qTitle = view.findViewById<TextView>(R.id.qTitle)
                val qBar = view.findViewById<ProgressBar>(R.id.qBar)
                val qText = view.findViewById<TextView>(R.id.qText)
                val qCancel = view.findViewById<Button>(R.id.qCancel)

                qTitle.text = item.title
                qText.text = item.text
                if (item.phase == "running") {
                    qBar.visibility = View.VISIBLE
                    qBar.progress = item.percent
                } else {
                    qBar.visibility = View.GONE
                }
                if (item.phase == "queued" || item.phase == "running") {
                    qCancel.visibility = View.VISIBLE
                    qCancel.setOnClickListener { cancelItem(item.id) }
                } else {
                    qCancel.visibility = View.GONE
                }
            }
        }
        queueEmpty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun cancelItem(id: Long) {
        val i = Intent(this, DownloadService::class.java)
        i.action = DownloadService.ACTION_CANCEL
        i.putExtra(DownloadService.EXTRA_ID, id)
        startService(i)
    }

    private fun pasteFromClipboard() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = cm.primaryClip?.getItemAt(0)?.text?.toString()?.trim()
        if (text.isNullOrEmpty()) {
            Toast.makeText(this, "Clipboard is empty", Toast.LENGTH_SHORT).show()
        } else {
            urlInput.setText(extractLink(text) ?: text)
        }
    }

    private fun onAdd() {
        if (currentUrl.isEmpty()) return

        if (Prefs.wifiOnly(this) && !NetUtil.onWifi(this)) {
            Toast.makeText(
                this,
                "Wi-Fi only is ON. Connect to Wi-Fi, or change it in Settings.",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        if (Build.VERSION.SDK_INT < 29 &&
            checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), 2)
            Toast.makeText(this, "Allow storage, then tap Add to queue again", Toast.LENGTH_LONG).show()
            return
        }

        val audio = rbAudio.isChecked
        val pos = formatSpinner.selectedItemPosition
        val fmt = if (audio) (if (pos == 1) "m4a" else "mp3") else ""
        val height = if (audio) 0 else heights[pos]

        val item = Queue.Item(
            Queue.newId(), currentUrl, currentTitle, currentUploader,
            currentThumbUrl, audio, height, fmt
        )
        Queue.add(item)

        val i = Intent(this, DownloadService::class.java)
        i.action = DownloadService.ACTION_START
        startForegroundService(i)

        currentUrl = ""
        optionsCard.visibility = View.GONE
        thumb.visibility = View.GONE
        titleText.text = ""
        metaText.text = ""
        urlInput.setText("")
        status.text = "Added to queue"
    }

    private fun onGetInfo() {
        val url = urlInput.text.toString().trim()
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            Toast.makeText(this, "Paste a link that starts with http", Toast.LENGTH_SHORT).show()
            return
        }
        if (!NetUtil.online(this)) {
            status.text = "No internet. Check your connection and try again."
            return
        }

        infoBtn.isEnabled = false
        progress.visibility = View.VISIBLE
        thumb.visibility = View.GONE
        optionsCard.visibility = View.GONE
        titleText.text = ""
        metaText.text = ""
        status.text = "Reading link..."
        currentUrl = ""

        worker.execute {
            val err = Engine.ensure(application)
            if (err != null) {
                runOnUiThread {
                    status.text = "Engine failed to start: $err"
                    progress.visibility = View.GONE
                    infoBtn.isEnabled = true
                }
                return@execute
            }
            try {
                // Skip format-availability probes during metadata lookup. They can add
                // several network round-trips and are not needed to show the video card.
                val request = YoutubeDLRequest(url)
                request.addOption("--no-playlist")
                request.addOption("--no-warnings")
                request.addOption("--no-check-formats")
                request.addOption("--socket-timeout", "15")
                request.addOption("--retries", "1")
                request.addOption("--extractor-retries", "1")
                val info = YoutubeDL.getInstance().getInfo(request)
                val title = info.title ?: "(no title)"
                val uploader = info.uploader ?: "unknown"
                val seconds = info.duration.toString().toLongOrNull() ?: 0L
                val thumbUrl = info.thumbnail ?: ""

                runOnUiThread {
                    currentUrl = url
                    currentTitle = title
                    currentUploader = uploader
                    currentThumbUrl = thumbUrl
                    status.text = "Ready to download"
                    titleText.text = title
                    metaText.text = uploader + "  |  " + formatTime(seconds)
                    applyDefaults()
                    optionsCard.visibility = View.VISIBLE
                    progress.visibility = View.GONE
                    infoBtn.isEnabled = true
                }

                // Thumbnail loading must never delay the Get Info result.
                if (thumbUrl.isNotEmpty()) {
                    thumbnailWorker.execute {
                        val bitmap = loadThumbnail(thumbUrl)
                        if (bitmap != null && !isFinishing) {
                            runOnUiThread {
                                if (currentUrl == url) {
                                    thumb.setImageBitmap(bitmap)
                                    thumb.visibility = View.VISIBLE
                                }
                            }
                        }
                    }
                }
            } catch (t: Throwable) {
                val msg = t.message ?: t.toString()
                runOnUiThread {
                    status.text = Engine.friendly(msg)
                    progress.visibility = View.GONE
                    infoBtn.isEnabled = true
                }
            }
        }
    }


    private fun loadThumbnail(url: String): Bitmap? {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 4000
                readTimeout = 4000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "Mozilla/5.0")
            }
            connection.connect()
            connection.inputStream.use { input ->
                if (connection.contentLengthLong > 8L * 1024L * 1024L) return null
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                val bytes = input.readBytes()
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                val sample = maxOf(1, maxOf(bounds.outWidth / 640, bounds.outHeight / 360))
                val options = BitmapFactory.Options().apply { inSampleSize = sample }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            }
        } catch (_: Exception) {
            null
        } finally {
            connection?.disconnect()
        }
    }

    private fun formatTime(sec: Long): String {
        val h = sec / 3600
        val m = (sec % 3600) / 60
        val s = sec % 60
        return if (h > 0) String.format("%d:%02d:%02d", h, m, s)
        else String.format("%d:%02d", m, s)
    }

    override fun onDestroy() {
        super.onDestroy()
        worker.shutdown()
        thumbnailWorker.shutdownNow()
    }
}