package com.example.grabit

import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

object Queue {

    class Item(
        val id: Long,
        val url: String,
        val title: String,
        val uploader: String,
        val thumbUrl: String,
        val audio: Boolean,
        val height: Int,
        val fmt: String
    ) {
        @Volatile
        var phase: String = "queued" // queued, running, done, error, cancelled

        @Volatile
        var percent: Int = 0

        @Volatile
        var text: String = "Waiting..."
    }

    val items = CopyOnWriteArrayList<Item>()

    private val counter = AtomicLong(System.currentTimeMillis())
    private val main = Handler(Looper.getMainLooper())

    @Volatile
    var listener: (() -> Unit)? = null

    fun newId(): Long = counter.incrementAndGet()

    fun changed() {
        main.post { listener?.invoke() }
    }

    fun add(item: Item) {
        items.add(item)
        changed()
    }

    fun nextQueued(): Item? = items.firstOrNull { it.phase == "queued" }

    fun find(id: Long): Item? = items.firstOrNull { it.id == id }

    fun queuedCount(): Int = items.count { it.phase == "queued" }

    fun clearFinished() {
        items.removeIf { it.phase == "done" || it.phase == "error" || it.phase == "cancelled" }
        changed()
    }
}