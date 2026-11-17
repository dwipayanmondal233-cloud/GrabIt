package com.example.grabit

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.ImageView
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HistoryActivity : Activity() {

    private lateinit var listView: ListView
    private lateinit var emptyText: TextView
    private lateinit var clearBtn: Button
    private lateinit var backBtn: Button

    private var entries: MutableList<History.Entry> = mutableListOf()
    private val cache = HashMap<Long, Bitmap>()
    private val adapter = EntryAdapter()
    private val dateFmt = SimpleDateFormat("dd MMM, HH:mm", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_history)
        Style.apply(findViewById<View>(android.R.id.content))

        listView = findViewById(R.id.list)
        emptyText = findViewById(R.id.emptyText)
        clearBtn = findViewById(R.id.clearBtn)
        backBtn = findViewById(R.id.backBtn)

        listView.adapter = adapter
        backBtn.setOnClickListener { finish() }
        clearBtn.setOnClickListener { confirmClear() }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        entries = History.load(this)
        adapter.notifyDataSetChanged()
        emptyText.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE
        clearBtn.isEnabled = entries.isNotEmpty()
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }

    private fun formatSize(bytes: Long): String {
        return if (bytes < 1048576L) {
            String.format("%d KB", bytes / 1024L)
        } else {
            String.format("%.1f MB", bytes / 1048576.0)
        }
    }

    private fun thumbFor(e: History.Entry): Bitmap? {
        val c = cache[e.id]
        if (c != null) return c
        if (e.thumbPath.isEmpty()) return null
        val f = File(e.thumbPath)
        if (!f.exists()) return null
        return try {
            val o = BitmapFactory.Options()
            o.inSampleSize = 4
            val b = BitmapFactory.decodeFile(f.absolutePath, o)
            if (b != null) cache[e.id] = b
            b
        } catch (t: Throwable) {
            null
        }
    }

    private fun openEntry(e: History.Entry) {
        if (e.uri.isEmpty()) {
            toast("Cannot open from here. Find it in Movies/GrabIt or Music/GrabIt.")
            return
        }
        try {
            val i = Intent(Intent.ACTION_VIEW)
            i.setDataAndType(Uri.parse(e.uri), e.mime)
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(i)
        } catch (t: Exception) {
            toast("Could not open. The file may be deleted, or no app can play it.")
        }
    }

    private fun shareEntry(e: History.Entry) {
        if (e.uri.isEmpty()) {
            toast("Cannot share from here. Share it from your Gallery or Files app.")
            return
        }
        try {
            val i = Intent(Intent.ACTION_SEND)
            i.type = e.mime
            i.putExtra(Intent.EXTRA_STREAM, Uri.parse(e.uri))
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(Intent.createChooser(i, "Share"))
        } catch (t: Exception) {
            toast("Could not share. The file may be deleted.")
        }
    }

    private fun deleteFileOf(e: History.Entry) {
        if (e.uri.isEmpty()) return
        try {
            contentResolver.delete(Uri.parse(e.uri), null, null)
        } catch (t: Throwable) {
            toast("Could not delete the file. Delete it in your Files app.")
        }
    }

    private fun confirmDelete(e: History.Entry) {
        AlertDialog.Builder(this)
            .setTitle("Delete")
            .setMessage(e.title)
            .setPositiveButton("Delete file too") { _, _ ->
                deleteFileOf(e)
                History.remove(this, e.id)
                refresh()
            }
            .setNeutralButton("Remove from list") { _, _ ->
                History.remove(this, e.id)
                refresh()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmClear() {
        AlertDialog.Builder(this)
            .setTitle("Clear all history?")
            .setPositiveButton("List and files") { _, _ ->
                for (e in History.load(this)) deleteFileOf(e)
                History.clear(this)
                cache.clear()
                refresh()
            }
            .setNeutralButton("List only") { _, _ ->
                History.clear(this)
                cache.clear()
                refresh()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    inner class EntryAdapter : BaseAdapter() {

        override fun getCount(): Int = entries.size

        override fun getItem(position: Int): Any = entries[position]

        override fun getItemId(position: Int): Long = entries[position].id

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
           val v = convertView ?: layoutInflater.inflate(R.layout.item_history, parent, false).also { Style.apply(it) }
            val e = entries[position]

            val img = v.findViewById<ImageView>(R.id.hThumb)
            val title = v.findViewById<TextView>(R.id.hTitle)
            val meta = v.findViewById<TextView>(R.id.hMeta)
            val openBtn = v.findViewById<Button>(R.id.hOpen)
            val shareBtn = v.findViewById<Button>(R.id.hShare)
            val deleteBtn = v.findViewById<Button>(R.id.hDelete)

            val bmp = thumbFor(e)
            if (bmp != null) img.setImageBitmap(bmp) else img.setImageDrawable(null)

            title.text = e.title
            val kind = if (e.audio) "AUDIO" else "VIDEO"
            meta.text = kind + " | " + formatSize(e.size) + " | " + dateFmt.format(Date(e.time))

            openBtn.setOnClickListener { openEntry(e) }
            shareBtn.setOnClickListener { shareEntry(e) }
            deleteBtn.setOnClickListener { confirmDelete(e) }
            return v
        }
    }
}