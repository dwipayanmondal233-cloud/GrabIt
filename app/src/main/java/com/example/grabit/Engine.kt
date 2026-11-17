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

    fun friendly(msg: String): String {
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
                "Failed. The site may have changed.\n\n" + msg.take(300)
        }
    }
}