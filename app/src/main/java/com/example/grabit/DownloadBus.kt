package com.example.grabit

import android.os.Handler
import android.os.Looper

object DownloadBus {

    data class State(val phase: String, val percent: Int, val text: String)

    @Volatile
    var state: State = State("idle", 0, "")

    @Volatile
    var listener: ((State) -> Unit)? = null

    private val main = Handler(Looper.getMainLooper())

    fun post(s: State) {
        state = s
        main.post { listener?.invoke(s) }
    }
}