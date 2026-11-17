package com.example.grabit

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.Executors

class SettingsActivity : Activity() {

    private val worker = Executors.newSingleThreadExecutor()

    private lateinit var defGroup: RadioGroup
    private lateinit var rbDefVideo: RadioButton
    private lateinit var rbDefAudio: RadioButton
    private lateinit var spQuality: Spinner
    private lateinit var spAudio: Spinner
    private lateinit var spFolder: Spinner
    private lateinit var spTheme: Spinner
    private lateinit var cbWifi: CheckBox
    private lateinit var engineVersion: TextView
    private lateinit var updateBtn: Button
    private lateinit var upBar: ProgressBar
    private lateinit var updateStatus: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        Style.apply(findViewById<View>(android.R.id.content))

        defGroup = findViewById(R.id.defGroup)
        rbDefVideo = findViewById(R.id.rbDefVideo)
        rbDefAudio = findViewById(R.id.rbDefAudio)
        spQuality = findViewById(R.id.spQuality)
        spAudio = findViewById(R.id.spAudio)
        spFolder = findViewById(R.id.spFolder)
        spTheme = findViewById(R.id.spTheme)
        cbWifi = findViewById(R.id.cbWifi)
        engineVersion = findViewById(R.id.engineVersion)
        updateBtn = findViewById(R.id.updateBtn)
        upBar = findViewById(R.id.upBar)
        updateStatus = findViewById(R.id.updateStatus)

        findViewById<Button>(R.id.backBtn).setOnClickListener { finish() }
        findViewById<Button>(R.id.noticeBtn).setOnClickListener { Notice.showAgain(this) }

        if (Prefs.defaultAudio(this)) rbDefAudio.isChecked = true else rbDefVideo.isChecked = true
        defGroup.setOnCheckedChangeListener { _, id ->
            Prefs.setDefaultAudio(this, id == R.id.rbDefAudio)
        }

        cbWifi.isChecked = Prefs.wifiOnly(this)
        cbWifi.setOnCheckedChangeListener { _, checked -> Prefs.setWifiOnly(this, checked) }

        setup(spQuality, arrayOf("Best", "1080p", "720p", "480p", "360p"), Prefs.quality(this)) {
            Prefs.setQuality(this, it)
        }
        setup(spAudio, arrayOf("MP3", "M4A"), Prefs.audioFmt(this)) {
            Prefs.setAudioFmt(this, it)
        }
        setup(
            spFolder,
            arrayOf("Movies and Music (GrabIt folder)", "Downloads (GrabIt folder)"),
            Prefs.saveFolder(this)
        ) {
            Prefs.setSaveFolder(this, it)
        }
        setup(spTheme, arrayOf("Neon cyan", "Neon green", "Neon pink"), Prefs.theme(this)) {
            if (it != Prefs.theme(this)) {
                Prefs.setTheme(this, it)
                recreate()
            }
        }

        updateBtn.setOnClickListener { onUpdate() }

        engineVersion.text = "yt-dlp version: loading..."
        worker.execute {
            val err = Engine.ensure(application)
            val v = if (err == null) Engine.version(application) else "engine failed to start"
            runOnUiThread { engineVersion.text = "yt-dlp version: $v" }
        }
    }

    private fun setup(sp: Spinner, labels: Array<String>, selected: Int, onPick: (Int) -> Unit) {
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, labels)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        sp.adapter = adapter
        sp.setSelection(selected)
        sp.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                onPick(position)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {
            }
        }
    }

    private fun onUpdate() {
        if (Queue.items.any { it.phase == "running" || it.phase == "queued" }) {
            Toast.makeText(this, "Wait for downloads to finish first", Toast.LENGTH_LONG).show()
            return
        }
        if (!NetUtil.online(this)) {
            updateStatus.text = "No internet. Check your connection and try again."
            return
        }
        updateBtn.isEnabled = false
        upBar.visibility = View.VISIBLE
        updateStatus.text = "Updating... this can take a minute."

        worker.execute {
            val msg = Engine.update(application)
            val v = Engine.version(application)
            runOnUiThread {
                upBar.visibility = View.GONE
                updateBtn.isEnabled = true
                updateStatus.text = msg
                engineVersion.text = "yt-dlp version: $v"
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        worker.shutdown()
    }
}