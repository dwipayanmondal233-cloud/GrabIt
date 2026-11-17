package com.example.grabit

import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import java.net.URL
import java.util.concurrent.Executors

class MainActivity : Activity() {

    private val worker = Executors.newSingleThreadExecutor()

    @Volatile
    private var engineReady = false

    private lateinit var urlInput: EditText
    private lateinit var pasteBtn: Button
    private lateinit var infoBtn: Button
    private lateinit var progress: ProgressBar
    private lateinit var status: TextView
    private lateinit var thumb: ImageView
    private lateinit var titleText: TextView
    private lateinit var metaText: TextView

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

        pasteBtn.setOnClickListener { pasteFromClipboard() }
        infoBtn.setOnClickListener { onGetInfo() }

        progress.visibility = View.VISIBLE
        worker.execute {
            initEngine()
            ui { progress.visibility = View.GONE }
        }
    }

    private fun ui(block: () -> Unit) {
        runOnUiThread { block() }
    }

    private fun initEngine(): Boolean {
        if (engineReady) return true
        return try {
            YoutubeDL.getInstance().init(application)
            FFmpeg.getInstance().init(application)
            engineReady = true
            ui { status.text = "Engine ready" }
            true
        } catch (t: Throwable) {
            ui { status.text = "Engine failed to start: " + (t.message ?: t.toString()) }
            false
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

    private fun onGetInfo() {
        val url = urlInput.text.toString().trim()
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            Toast.makeText(this, "Paste a link that starts with http", Toast.LENGTH_SHORT).show()
            return
        }

        infoBtn.isEnabled = false
        progress.visibility = View.VISIBLE
        thumb.visibility = View.GONE
        titleText.text = ""
        metaText.text = ""
        status.text = "Reading link..."

        worker.execute {
            if (!initEngine()) {
                ui {
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

                ui {
                    status.text = "Got it"
                    titleText.text = title
                    metaText.text = uploader + "  |  " + formatTime(seconds)
                    if (finalBmp != null) {
                        thumb.setImageBitmap(finalBmp)
                        thumb.visibility = View.VISIBLE
                    }
                    progress.visibility = View.GONE
                    infoBtn.isEnabled = true
                }
            } catch (t: Throwable) {
                val msg = t.message ?: t.toString()
                ui {
                    status.text = friendly(msg)
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

    private fun friendly(msg: String): String {
        val m = msg.lowercase()
        return when {
            m.contains("private") || m.contains("login") || m.contains("sign in") ->
                "Private or login-only video. GrabIt saves public videos only."
            m.contains("unsupported url") ->
                "This link is not supported."
            m.contains("unable to resolve host") || m.contains("network is unreachable") ||
                m.contains("timed out") || m.contains("connection") ->
                "No internet. Check your connection."
            else ->
                "Could not read this link. The site may have changed.\n\n" + msg.take(300)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        worker.shutdown()
    }
}