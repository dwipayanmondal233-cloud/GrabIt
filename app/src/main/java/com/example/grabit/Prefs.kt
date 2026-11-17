package com.example.grabit

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

object Prefs {

    private fun sp(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences("grabit_prefs", Context.MODE_PRIVATE)

    fun noticeAccepted(ctx: Context): Boolean = sp(ctx).getBoolean("notice", false)
    fun setNoticeAccepted(ctx: Context) {
        sp(ctx).edit().putBoolean("notice", true).apply()
    }

    fun defaultAudio(ctx: Context): Boolean = sp(ctx).getBoolean("def_audio", false)
    fun setDefaultAudio(ctx: Context, v: Boolean) {
        sp(ctx).edit().putBoolean("def_audio", v).apply()
    }

    // 0 Best, 1 1080p, 2 720p, 3 480p, 4 360p
    fun quality(ctx: Context): Int = sp(ctx).getInt("quality", 0)
    fun setQuality(ctx: Context, v: Int) {
        sp(ctx).edit().putInt("quality", v).apply()
    }

    // 0 MP3, 1 M4A
    fun audioFmt(ctx: Context): Int = sp(ctx).getInt("audio_fmt", 0)
    fun setAudioFmt(ctx: Context, v: Int) {
        sp(ctx).edit().putInt("audio_fmt", v).apply()
    }

    fun wifiOnly(ctx: Context): Boolean = sp(ctx).getBoolean("wifi_only", false)
    fun setWifiOnly(ctx: Context, v: Boolean) {
        sp(ctx).edit().putBoolean("wifi_only", v).apply()
    }

    // 0 Movies and Music, 1 Downloads
    fun saveFolder(ctx: Context): Int = sp(ctx).getInt("save_folder", 0)
    fun setSaveFolder(ctx: Context, v: Int) {
        sp(ctx).edit().putInt("save_folder", v).apply()
    }

    // 0 cyan, 1 green, 2 pink
    fun theme(ctx: Context): Int = sp(ctx).getInt("theme", 0)
    fun setTheme(ctx: Context, v: Int) {
        sp(ctx).edit().putInt("theme", v).apply()
    }
}

object NetUtil {

    fun online(ctx: Context): Boolean {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val net = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    fun onWifi(ctx: Context): Boolean {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val net = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }
}

object Notice {

    private const val TEXT =
        "GrabIt is for personal use only.\n\n" +
            "Only save content you own or have permission to save.\n\n" +
            "Downloading from YouTube may break its Terms of Service. " +
            "You are responsible for what you download.\n\n" +
            "GrabIt saves public videos only. It does not bypass logins, DRM or paywalls.\n\n" +
            "No tracking, no analytics. The app only talks to the video sites you ask for, " +
            "and to GitHub when you tap Update engine."

    fun showFirstRun(a: Activity, onAccept: () -> Unit) {
        AlertDialog.Builder(a)
            .setTitle("Before you start")
            .setMessage(TEXT)
            .setCancelable(false)
            .setPositiveButton("I agree") { _, _ ->
                Prefs.setNoticeAccepted(a)
                onAccept()
            }
            .setNegativeButton("Exit") { _, _ -> a.finish() }
            .show()
    }

    fun showAgain(a: Activity) {
        AlertDialog.Builder(a)
            .setTitle("Notice")
            .setMessage(TEXT)
            .setPositiveButton("OK", null)
            .show()
    }
}