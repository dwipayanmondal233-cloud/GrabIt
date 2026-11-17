package com.example.grabit

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.provider.MediaStore
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors

class DownloadService : Service() {

    companion object {
        const val ACTION_START = "com.example.grabit.START"
        const val ACTION_CANCEL = "com.example.grabit.CANCEL"
        const val EXTRA_URL = "url"
        const val EXTRA_TITLE = "title"
        const val EXTRA_AUDIO = "audio"
        const val EXTRA_HEIGHT = "height"
        const val EXTRA_AUDIO_FMT = "audio_fmt"
        const val CHANNEL_ID = "grabit_downloads"
        const val NOTIF_ID = 1
        const val NOTIF_DONE_ID = 2
        const val PROCESS_ID = "grabit_dl"
    }

    private val worker = Executors.newSingleThreadExecutor()

    @Volatile
    private var running = false

    @Volatile
    private var cancelled = false

    private var lastNotify = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Downloads", NotificationManager.IMPORTANCE_LOW)
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            if (running) {
                cancelled = true
                try {
                    YoutubeDL.getInstance().destroyProcessById(PROCESS_ID)
                } catch (t: Throwable) {
                }
            } else {
                stopSelf()
            }
            return START_NOT_STICKY
        }

        val url = intent?.getStringExtra(EXTRA_URL)
        val title = intent?.getStringExtra(EXTRA_TITLE) ?: "GrabIt"

        goForeground(buildProgress(title, "Starting...", 0, true))

        if (url == null) {
            if (!running) {
                stopForeground(Service.STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            return START_NOT_STICKY
        }
        if (running) return START_NOT_STICKY

        val audio = intent.getBooleanExtra(EXTRA_AUDIO, false)
        val height = intent.getIntExtra(EXTRA_HEIGHT, 0)
        val fmt = intent.getStringExtra(EXTRA_AUDIO_FMT) ?: "mp3"

        running = true
        cancelled = false
        DownloadBus.post(DownloadBus.State("running", 0, "Starting..."))
        worker.execute { runDownload(url, title, audio, height, fmt) }
        return START_NOT_STICKY
    }

    private fun goForeground(n: Notification) {
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun openAppIntent(): PendingIntent {
        val i = Intent(this, MainActivity::class.java)
        i.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(this, 1, i, PendingIntent.FLAG_IMMUTABLE)
    }

    private fun buildProgress(title: String, text: String, pct: Int, indeterminate: Boolean): Notification {
        val cancelIntent = Intent(this, DownloadService::class.java)
        cancelIntent.action = ACTION_CANCEL
        val cancelPi = PendingIntent.getService(
            this, 0, cancelIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(text)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setContentIntent(openAppIntent())
            .setProgress(100, pct, indeterminate)
            .addAction(0, "Cancel", cancelPi)
            .build()
    }

    private fun notifyFinal(title: String, text: String) {
        val n = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(openAppIntent())
            .build()
        getSystemService(NotificationManager::class.java).notify(NOTIF_DONE_ID, n)
    }

    private fun fmtTime(sec: Long): String {
        val m = sec / 60
        val s = sec % 60
        return String.format("%d:%02d", m, s)
    }

    private fun onProgress(title: String, p: Float, eta: Long, line: String) {
        if (p < 0f) {
            if (line.contains("[Merger]") || line.contains("[ExtractAudio]") ||
                line.contains("[VideoConvertor]") || line.contains("[VideoRemuxer]")
            ) {
                DownloadBus.post(DownloadBus.State("running", 100, "Converting..."))
            }
            return
        }
        val pct = p.toInt().coerceIn(0, 100)
        val speed = Regex("at\\s+(\\S+/s)").find(line)?.groupValues?.get(1) ?: ""
        val etaText = if (eta > 0) "ETA " + fmtTime(eta) else ""
        val text = listOf("$pct%", speed, etaText)
            .filter { it.isNotEmpty() }
            .joinToString("  |  ")
        DownloadBus.post(DownloadBus.State("running", pct, text))

        val now = System.currentTimeMillis()
        if (now - lastNotify > 700) {
            lastNotify = now
            getSystemService(NotificationManager::class.java)
                .notify(NOTIF_ID, buildProgress(title, text, pct, false))
        }
    }

    private fun buildRequest(url: String, dir: File, audio: Boolean, height: Int, fmt: String): YoutubeDLRequest {
        val r = YoutubeDLRequest(url)
        r.addOption("--no-playlist")
        r.addOption("-o", dir.absolutePath + "/%(title).80s.%(ext)s")
        if (audio) {
            if (fmt == "m4a") {
                r.addOption("-f", "bestaudio[ext=m4a]/bestaudio/best")
            } else {
                r.addOption("-f", "bestaudio/best")
            }
            r.addOption("-x")
            r.addOption("--audio-format", fmt)
            r.addOption("--audio-quality", "0")
        } else {
            val h = if (height > 0) "[height<=$height]" else ""
            r.addOption("-f", "bv*${h}[ext=mp4]+ba[ext=m4a]/b${h}[ext=mp4]/bv*${h}+ba/b${h}")
            r.addOption("--merge-output-format", "mp4")
        }
        return r
    }

    private fun runDownload(url: String, title: String, audio: Boolean, height: Int, fmt: String) {
        val dir = File(cacheDir, "dl_" + System.currentTimeMillis())
        try {
            val err = Engine.ensure(application)
            if (err != null) throw IOException("Engine failed to start: $err")
            dir.mkdirs()

            val request = buildRequest(url, dir, audio, height, fmt)
            YoutubeDL.getInstance().execute(request, PROCESS_ID) { p, eta, line ->
                onProgress(title, p, eta, line)
            }

            if (cancelled) throw IOException("cancelled")

            val file = dir.listFiles()
                ?.filter {
                    it.isFile && !it.name.endsWith(".part") &&
                        !it.name.endsWith(".ytdl") && !it.name.endsWith(".temp")
                }
                ?.maxByOrNull { it.length() }
                ?: throw IOException("No file was created")

            DownloadBus.post(DownloadBus.State("running", 100, "Saving..."))
            val where = saveToMediaStore(file, audio)

            DownloadBus.post(DownloadBus.State("done", 100, "Saved: $where"))
            notifyFinal("Download finished", where)
        } catch (t: Throwable) {
            if (cancelled) {
                DownloadBus.post(DownloadBus.State("cancelled", 0, "Cancelled"))
                notifyFinal("Download cancelled", title)
            } else {
                val msg = Engine.friendly(t.message ?: t.toString())
                DownloadBus.post(DownloadBus.State("error", 0, msg))
                notifyFinal("Download failed", msg.take(100))
            }
        } finally {
            try {
                dir.deleteRecursively()
            } catch (t: Throwable) {
            }
            running = false
            stopForeground(Service.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun mimeFor(ext: String, audio: Boolean): String {
        return when (ext) {
            "mp4" -> "video/mp4"
            "webm" -> if (audio) "audio/webm" else "video/webm"
            "mkv" -> "video/x-matroska"
            "mp3" -> "audio/mpeg"
            "m4a" -> "audio/mp4"
            "opus", "ogg" -> "audio/ogg"
            else -> if (audio) "audio/mpeg" else "video/mp4"
        }
    }

    // Returns a short text like "Movies/GrabIt/name.mp4"
    private fun saveToMediaStore(file: File, audio: Boolean): String {
        val name = file.name
        val mime = mimeFor(file.extension.lowercase(), audio)
        val folder = if (audio) Environment.DIRECTORY_MUSIC else Environment.DIRECTORY_MOVIES

        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues()
            values.put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            values.put(MediaStore.MediaColumns.MIME_TYPE, mime)
            values.put(MediaStore.MediaColumns.RELATIVE_PATH, "$folder/GrabIt")
            values.put(MediaStore.MediaColumns.IS_PENDING, 1)

            val collection = if (audio) {
                MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            } else {
                MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            }
            val uri = contentResolver.insert(collection, values)
                ?: throw IOException("Could not create the file in your storage")
            val out = contentResolver.openOutputStream(uri)
                ?: throw IOException("Could not open the file for writing")
            out.use { o -> file.inputStream().use { it.copyTo(o) } }

            val done = ContentValues()
            done.put(MediaStore.MediaColumns.IS_PENDING, 0)
            contentResolver.update(uri, done, null, null)
        } else {
            val base = Environment.getExternalStoragePublicDirectory(folder)
            val dir = File(base, "GrabIt")
            dir.mkdirs()
            val dest = File(dir, name)
            file.copyTo(dest, overwrite = true)
            MediaScannerConnection.scanFile(this, arrayOf(dest.absolutePath), arrayOf(mime), null)
        }
        return "$folder/GrabIt/$name"
    }
}