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
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.provider.MediaStore
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import java.io.File
import java.io.IOException
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class DownloadService : Service() {

    companion object {
        const val ACTION_START = "com.example.grabit.START"
        const val ACTION_CANCEL = "com.example.grabit.CANCEL"
        const val EXTRA_ID = "id"
        const val CHANNEL_ID = "grabit_downloads"
        const val NOTIF_ID = 1
        const val PROCESS_ID = "grabit_dl"
    }

    private class Saved(val uri: String, val mime: String, val name: String, val where: String)

    private val worker = Executors.newSingleThreadExecutor()
    private val lock = Any()
    private var loopRunning = false
    private var lastStartId = 0

    @Volatile
    private var cancelRequested = false

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
        synchronized(lock) {
            lastStartId = startId
            if (intent?.action == ACTION_CANCEL) {
                handleCancel(intent.getLongExtra(EXTRA_ID, 0L))
                if (!loopRunning) stopSelf(startId)
                return START_NOT_STICKY
            }
            goForeground(buildProgress("GrabIt", "Starting...", 0, true))
            if (!loopRunning) {
                loopRunning = true
                worker.execute { loop() }
            }
        }
        return START_NOT_STICKY
    }

    private fun handleCancel(id: Long) {
        val item = if (id == 0L) {
            Queue.items.firstOrNull { it.phase == "running" }
        } else {
            Queue.find(id)
        }
        if (item == null) return
        if (item.phase == "queued") {
            item.phase = "cancelled"
            item.text = "Cancelled"
            Queue.changed()
        } else if (item.phase == "running") {
            cancelRequested = true
            try {
                YoutubeDL.getInstance().destroyProcessById(PROCESS_ID)
            } catch (t: Throwable) {
            }
        }
    }

    private fun loop() {
        while (true) {
            val next = synchronized(lock) {
                val n = Queue.nextQueued()
                if (n == null) {
                    loopRunning = false
                    stopForeground(Service.STOP_FOREGROUND_REMOVE)
                    stopSelf(lastStartId)
                }
                n
            }
            if (next == null) return

            if (Prefs.wifiOnly(this) && !NetUtil.onWifi(this)) {
                next.phase = "error"
                next.text = "Wi-Fi only is ON. Connect to Wi-Fi, or turn it off in Settings."
                Queue.changed()
                notifyFinal(next, "Download blocked", next.text, false)
            } else {
                runItem(next)
            }
        }
    }

    private fun goForeground(n: Notification) {
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun openIntent(cls: Class<*>, req: Int): PendingIntent {
        val i = Intent(this, cls)
        i.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(this, req, i, PendingIntent.FLAG_IMMUTABLE)
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
            .setContentIntent(openIntent(MainActivity::class.java, 1))
            .setProgress(100, pct, indeterminate)
            .addAction(0, "Cancel", cancelPi)
            .build()
    }

    private fun notifyProgress(item: Queue.Item, text: String, pct: Int, indeterminate: Boolean) {
        val more = Queue.queuedCount()
        val title = if (more > 0) item.title + " (+" + more + " waiting)" else item.title
        getSystemService(NotificationManager::class.java)
            .notify(NOTIF_ID, buildProgress(title, text, pct, indeterminate))
    }

    private fun notifyFinal(item: Queue.Item, title: String, text: String, ok: Boolean) {
        val target = if (ok) HistoryActivity::class.java else MainActivity::class.java
        val n = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(openIntent(target, 2))
            .build()
        val nid = (item.id % 1000000L).toInt() + 100
        getSystemService(NotificationManager::class.java).notify(nid, n)
    }

    private fun fmtTime(sec: Long): String {
        val m = sec / 60
        val s = sec % 60
        return String.format("%d:%02d", m, s)
    }

    private fun onProgress(item: Queue.Item, p: Float, eta: Long, line: String) {
        if (p < 0f) {
            if (line.contains("[Merger]") || line.contains("[ExtractAudio]") ||
                line.contains("[VideoConvertor]") || line.contains("[VideoRemuxer]")
            ) {
                item.percent = 100
                item.text = "Converting..."
                Queue.changed()
            }
            return
        }
        val pct = p.toInt().coerceIn(0, 100)
        val speed = Regex("at\\s+(\\S+/s)").find(line)?.groupValues?.get(1) ?: ""
        val etaText = if (eta > 0) "ETA " + fmtTime(eta) else ""
        val text = listOf("$pct%", speed, etaText)
            .filter { it.isNotEmpty() }
            .joinToString("  |  ")
        item.percent = pct
        item.text = text

        val now = System.currentTimeMillis()
        if (now - lastNotify > 500 || pct >= 100) {
            lastNotify = now
            Queue.changed()
            notifyProgress(item, text, pct, false)
        }
    }

    private fun buildRequest(item: Queue.Item, dir: File): YoutubeDLRequest {
        val r = YoutubeDLRequest(item.url)
        r.addOption("--no-playlist")
        r.addOption("--no-mtime")
        r.addOption("--socket-timeout", "20")
        r.addOption("--no-check-formats")
        r.addOption("--retries", "5")
        r.addOption("--fragment-retries", "5")
        // Parallel fragment fetching improves HLS/DASH downloads on supported sites.
        r.addOption("--concurrent-fragments", "4")
        r.addOption("-o", dir.absolutePath + "/%(title).80s.%(ext)s")
        if (item.audio) {
            if (item.fmt == "m4a") {
                r.addOption("-f", "bestaudio[ext=m4a]/bestaudio/best")
            } else {
                r.addOption("-f", "bestaudio/best")
            }
            r.addOption("-x")
            r.addOption("--audio-format", item.fmt)
            r.addOption("--audio-quality", "0")
        } else {
            val h = if (item.height > 0) "[height<=" + item.height + "]" else ""
            r.addOption("-f", "bv*${h}[ext=mp4]+ba[ext=m4a]/b${h}[ext=mp4]/bv*${h}+ba/b${h}")
            r.addOption("--merge-output-format", "mp4")
        }
        return r
    }

    private fun runItem(item: Queue.Item) {
        cancelRequested = false
        item.phase = "running"
        item.percent = 0
        item.text = "Starting..."
        Queue.changed()
        notifyProgress(item, "Starting...", 0, true)

        val dir = File(cacheDir, "dl_" + item.id)
        try {
            val err = Engine.ensure(application)
            if (err != null) throw IOException("Engine failed to start: $err")
            dir.mkdirs()

            val request = buildRequest(item, dir)
            YoutubeDL.getInstance().execute(request, PROCESS_ID) { p, eta, line ->
                onProgress(item, p, eta, line)
            }

            if (cancelRequested) throw IOException("cancelled")

            val file = dir.listFiles()
                ?.filter {
                    it.isFile && !it.name.endsWith(".part") &&
                        !it.name.endsWith(".ytdl") && !it.name.endsWith(".temp")
                }
                ?.maxByOrNull { it.length() }
                ?: throw IOException("No file was created")

            item.percent = 100
            item.text = "Saving..."
            Queue.changed()

            val size = file.length()
            val saved = saveToMediaStore(file, item.audio)
            val thumbPath = saveThumb(item)

            History.add(
                this,
                History.Entry(
                    item.id, item.title, item.uploader, saved.uri, saved.mime,
                    item.audio, saved.name, thumbPath, System.currentTimeMillis(), size
                )
            )

            item.phase = "done"
            item.percent = 100
            item.text = "Saved: " + saved.where
            Queue.changed()
            notifyFinal(item, "Download finished", item.title, true)
        } catch (t: Throwable) {
            if (cancelRequested) {
                item.phase = "cancelled"
                item.text = "Cancelled"
                Queue.changed()
                notifyFinal(item, "Download cancelled", item.title, false)
            } else {
                val msg = Engine.friendly(t.message ?: t.toString())
                item.phase = "error"
                item.text = msg
                Queue.changed()
                notifyFinal(item, "Download failed", msg.take(100), false)
            }
        } finally {
            try {
                dir.deleteRecursively()
            } catch (t: Throwable) {
            }
        }
    }

    private fun saveThumb(item: Queue.Item): String {
        if (item.thumbUrl.isEmpty()) return ""
        return try {
            val dir = File(filesDir, "thumbs")
            dir.mkdirs()
            val f = File(dir, item.id.toString() + ".jpg")
            val conn = URL(item.thumbUrl).openConnection()
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.getInputStream().use { input ->
                f.outputStream().use { out -> input.copyTo(out) }
            }
            f.absolutePath
        } catch (e: Exception) {
            ""
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

    private fun saveToMediaStore(file: File, audio: Boolean): Saved {
        val name = file.name
        val mime = mimeFor(file.extension.lowercase(), audio)
        val useDownloads = Prefs.saveFolder(this) == 1
        val folder = if (useDownloads) {
            Environment.DIRECTORY_DOWNLOADS
        } else if (audio) {
            Environment.DIRECTORY_MUSIC
        } else {
            Environment.DIRECTORY_MOVIES
        }

        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues()
            values.put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            values.put(MediaStore.MediaColumns.MIME_TYPE, mime)
            values.put(MediaStore.MediaColumns.RELATIVE_PATH, "$folder/GrabIt")
            values.put(MediaStore.MediaColumns.IS_PENDING, 1)

            val collection = if (useDownloads) {
                MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            } else if (audio) {
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
            return Saved(uri.toString(), mime, name, "$folder/GrabIt/$name")
        }

        val base = Environment.getExternalStoragePublicDirectory(folder)
        val dir = File(base, "GrabIt")
        dir.mkdirs()
        var dest = File(dir, name)
        if (dest.exists()) {
            dest = File(
                dir,
                file.nameWithoutExtension + "_" + System.currentTimeMillis() + "." + file.extension
            )
        }
        file.copyTo(dest, overwrite = true)

        val latch = CountDownLatch(1)
        var scanned: Uri? = null
        MediaScannerConnection.scanFile(
            this, arrayOf(dest.absolutePath), arrayOf(mime)
        ) { _, u ->
            scanned = u
            latch.countDown()
        }
        latch.await(4, TimeUnit.SECONDS)
        val uriStr = scanned?.toString() ?: ""
        return Saved(uriStr, mime, dest.name, "$folder/GrabIt/" + dest.name)
    }

    override fun onDestroy() {
        super.onDestroy()
        worker.shutdown()
    }
}