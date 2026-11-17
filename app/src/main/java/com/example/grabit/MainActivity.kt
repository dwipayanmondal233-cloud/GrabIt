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
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import com.yausername.youtubedl_android.YoutubeDL
import java.net.URL
import java.util.concurrent.Executors

class MainActivity : Activity() {

    private val worker = Executors.newSingleThreadExecutor()

    private lateinit var urlInput: EditText
    private lateinit var pasteBtn: Button
    private lateinit var infoBtn: Button
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
    private lateinit var downloadBtn: Button
    private lateinit var cancelBtn: Button
    private lateinit var dlBar: ProgressBar
    private lateinit var dlStatus: TextView

    private var currentUrl = ""
    private var currentTitle = ""

    private val heights = intArrayOf(0, 1080, 720, 480, 360)
    private val videoLabels = arrayOf("Best", "1080p", "720p", "480p", "360p")
    private val audioLabels = arrayOf("MP3 (best)", "M4A (best)")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        urlInput = findViewById(R.id.urlInput)
        pasteBtn = findViewById(R.id.pasteBtn)
        infoBtn = findViewById(R.id.infoBtn)
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
        downloadBtn = findViewById(R.id.downloadBtn)
        cancelBtn = findViewById(R.id.cancelBtn)
        dlBar = findViewById(R.id.dlBar)
        dlStatus = findViewById(R.id.dlStatus)

        fillSpinner(false)
        radioGroup.setOnCheckedChangeListener { _, id -> fillSpinner(id == R.id.rbAudio) }

        pasteBtn.setOnClickListener { pasteFromClipboard() }
        infoBtn.setOnClickListener { onGetInfo() }
        downloadBtn.setOnClickListener { onDownload() }
        cancelBtn.setOnClickListener { onCancel() }

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }

        progress.visibility = View.VISIBLE
        worker.execute {
            val err = Engine.ensure(application)
            runOnUiThread {
                progress.visibility = View.GONE
                status.text = if (err == null) "Engine ready" else "Engine failed to start: $err"
            }
        }
    }

    override fun onResume() {
        super.onResume()
        DownloadBus.listener = { s -> render(s) }
        render(DownloadBus.state)
    }

    override fun onPause() {
        super.onPause()
        DownloadBus.listener = null
    }

    private fun fillSpinner(audio: Boolean) {
        val labels = if (audio) audioLabels else videoLabels
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, labels)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        formatSpinner.adapter = adapter
    }

    private fun render(s: DownloadBus.State) {
        when (s.phase) {
            "running" -> {
                dlBar.visibility = View.VISIBLE
                dlBar.progress = s.percent
                dlStatus.text = s.text
                downloadBtn.isEnabled = false
                cancelBtn.visibility = View.VISIBLE
            }
            "done", "error", "cancelled" -> {
                dlBar.visibility = View.GONE
                dlStatus.text = s.text
                downloadBtn.isEnabled = true
                cancelBtn.visibility = View.GONE
            }
            else -> {
            }
        }
    }

    private fun pasteFromClipboard() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = cm.primaryClip?.getItemAt(0)?.text?.toString()?.trim()
        if (text.isNullOrEmpty()) {
            Toast.makeText(this, "Clipboard is empty", Toast.LENGTH_SHORT).show()
        } else {
            urlInput.setText(text)
        }
    }

    private fun onDownload() {
        if (currentUrl.isEmpty()) return
        if (DownloadBus.state.phase == "running") return

        if (Build.VERSION.SDK_INT < 29 &&
            checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), 2)
            Toast.makeText(this, "Allow storage, then tap Download again", Toast.LENGTH_LONG).show()
            return
        }

        val audio = rbAudio.isChecked
        val pos = formatSpinner.selectedItemPosition
        val i = Intent(this, DownloadService::class.java)
        i.action = DownloadService.ACTION_START
        i.putExtra(DownloadService.EXTRA_URL, currentUrl)
        i.putExtra(DownloadService.EXTRA_TITLE, currentTitle)
        i.putExtra(DownloadService.EXTRA_AUDIO, audio)
        if (audio) {
            i.putExtra(DownloadService.EXTRA_AUDIO_FMT, if (pos == 1) "m4a" else "mp3")
        } else {
            i.putExtra(DownloadService.EXTRA_HEIGHT, heights[pos])
        }

        DownloadBus.post(DownloadBus.State("running", 0, "Starting..."))
        startForegroundService(i)
    }

    private fun onCancel() {
        val i = Intent(this, DownloadService::class.java)
        i.action = DownloadService.ACTION_CANCEL
        startService(i)
        dlStatus.text = "Cancelling..."
    }

    private fun onGetInfo() {
        if (DownloadBus.state.phase == "running") {
            Toast.makeText(this, "Wait for the download to finish", Toast.LENGTH_SHORT).show()
            return
        }
        val url = urlInput.text.toString().trim()
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            Toast.makeText(this, "Paste a link that starts with http", Toast.LENGTH_SHORT).show()
            return
        }

        infoBtn.isEnabled = false
        progress.visibility = View.VISIBLE
        thumb.visibility = View.GONE
        optionsCard.visibility = View.GONE
        dlStatus.text = ""
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
                val info = YoutubeDL.getInstance().getInfo(url)
                val title = info.title ?: "(no title)"
                val uploader = info.uploader ?: "unknown"
                val seconds = info.duration.toString().toLongOrNull() ?: 0L

                var bmp: Bitmap? = null
                val thumbUrl = info.thumbnail
                if (!thumbUrl.isNullOrEmpty()) {
                    try {
                        URL(thumbUrl).openStream().use { bmp = BitmapFactory.decodeStream(it) }
                    } catch (e: Exception) {
                        bmp = null
                    }
                }
                val finalBmp = bmp

                runOnUiThread {
                    currentUrl = url
                    currentTitle = title
                    status.text = "Got it"
                    titleText.text = title
                    metaText.text = uploader + "  |  " + formatTime(seconds)
                    if (finalBmp != null) {
                        thumb.setImageBitmap(finalBmp)
                        thumb.visibility = View.VISIBLE
                    }
                    optionsCard.visibility = View.VISIBLE
                    progress.visibility = View.GONE
                    infoBtn.isEnabled = true
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
    }
}