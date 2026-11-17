package com.example.grabit

import android.app.Application
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL

object Engine {

    @Volatile
    private var ready = false

    // Returns null if OK, or an error message.
    @Synchronized
    fun ensure(app: Application): String? {
        if (ready) return null
        return try {
            YoutubeDL.getInstance().init(app)
            FFmpeg.getInstance().init(app)
            ready = true
            null
        } catch (t: Throwable) {
            t.message ?: t.toString()
        }
    }

    fun version(app: Application): String {
        return try {
            val v = YoutubeDL.getInstance().version(app)
            v?.toString() ?: "unknown"
        } catch (t: Throwable) {
            "unknown"
        }
    }

    // Returns a short result text. Call from a background thread.
    fun update(app: Application): String {
        val err = ensure(app)
        if (err != null) return "Engine failed to start: $err"
        return try {
            val st = YoutubeDL.getInstance().updateYoutubeDL(app, YoutubeDL.UpdateChannel.STABLE)
            val s = st.toString()
            if (s.contains("ALREADY")) "Already up to date." else "Update finished."
        } catch (t: Throwable) {
            friendly(t.message ?: t.toString())
        }
    }

    fun friendly(msg: String): String {
        val m = msg.lowercase()
        return when {
            m.contains("no space left") ->
                "Your storage is full. Free some space and try again."
            m.contains("a bot") ->
                "The site wants proof you are not a bot. Wait a while, switch between Wi-Fi and mobile data, or tap Update engine in Settings."
            m.contains("age-restricted") || m.contains("age restricted") || m.contains("confirm your age") ->
                "This video is age-restricted. It needs a login, so GrabIt cannot save it."
            m.contains("drm") ->
                "This video is protected by DRM. GrabIt cannot save it."
            m.contains("private") || m.contains("login") || m.contains("logged") ||
                m.contains("sign in") || m.contains("cookies") || m.contains("members-only") ->
                "This video is private or needs a login. GrabIt saves public videos only."
            m.contains("unavailable") || m.contains("not available") || m.contains("removed") ||
                m.contains("deleted") || m.contains("http error 404") || m.contains("does not exist") ->
                "This video is not available. It may be removed, deleted or blocked in your country."
            m.contains("live event") || m.contains("is live") || m.contains("live stream") ->
                "Live streams are not supported. Try again after it ends."
            m.contains("unsupported url") ->
                "This link is not supported. Paste a direct link to a video."
            m.contains("unable to resolve host") || m.contains("network is unreachable") ||
                m.contains("timed out") || m.contains("connection") ||
                m.contains("name resolution") || m.contains("no address associated") ->
                "No internet. Check your connection and try again."
            m.contains("unable to extract") || m.contains("unable to download") ||
                m.contains("http error") || m.contains("extractor") || m.contains("nsig") ->
                "The site changed and the engine cannot read it yet. Open Settings, tap Update engine, then try again."
            m.contains("ffmpeg") || m.contains("postprocess") ->
                "Converting failed. Try another quality or format."
            else ->
                "Something went wrong. Try Update engine in Settings.\n\nDetails: " + msg.take(200)
        }
    }
}