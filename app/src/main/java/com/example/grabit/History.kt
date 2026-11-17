package com.example.grabit

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

object History {

    class Entry(
        val id: Long,
        val title: String,
        val uploader: String,
        val uri: String,
        val mime: String,
        val audio: Boolean,
        val name: String,
        val thumbPath: String,
        val time: Long,
        val size: Long
    )

    private fun file(ctx: Context): File = File(ctx.filesDir, "history.json")

    @Synchronized
    fun load(ctx: Context): MutableList<Entry> {
        val list = mutableListOf<Entry>()
        try {
            val f = file(ctx)
            if (!f.exists()) return list
            val arr = JSONArray(f.readText())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                list.add(
                    Entry(
                        o.getLong("id"),
                        o.optString("title"),
                        o.optString("uploader"),
                        o.optString("uri"),
                        o.optString("mime"),
                        o.optBoolean("audio"),
                        o.optString("name"),
                        o.optString("thumbPath"),
                        o.optLong("time"),
                        o.optLong("size")
                    )
                )
            }
        } catch (e: Exception) {
        }
        return list
    }

    private fun save(ctx: Context, list: List<Entry>) {
        val arr = JSONArray()
        for (e in list) {
            val o = JSONObject()
            o.put("id", e.id)
            o.put("title", e.title)
            o.put("uploader", e.uploader)
            o.put("uri", e.uri)
            o.put("mime", e.mime)
            o.put("audio", e.audio)
            o.put("name", e.name)
            o.put("thumbPath", e.thumbPath)
            o.put("time", e.time)
            o.put("size", e.size)
            arr.put(o)
        }
        file(ctx).writeText(arr.toString())
    }

    @Synchronized
    fun add(ctx: Context, e: Entry) {
        val list = load(ctx)
        list.add(0, e)
        save(ctx, list)
    }

    @Synchronized
    fun remove(ctx: Context, id: Long) {
        val list = load(ctx)
        val hit = list.firstOrNull { it.id == id }
        if (hit != null) {
            if (hit.thumbPath.isNotEmpty()) File(hit.thumbPath).delete()
            list.remove(hit)
            save(ctx, list)
        }
    }

    @Synchronized
    fun clear(ctx: Context) {
        val list = load(ctx)
        for (e in list) {
            if (e.thumbPath.isNotEmpty()) File(e.thumbPath).delete()
        }
        save(ctx, emptyList())
    }
}