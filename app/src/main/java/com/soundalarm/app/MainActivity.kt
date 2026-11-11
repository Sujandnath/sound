package com.soundalarm.app

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView

/** Simple level bar with a white line marking the trigger level. */
class MeterView(context: Context) : View(context) {
    var level = 0
    var threshold = 70
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#0B1220") }
    private val bar = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint().apply { color = Color.WHITE; strokeWidth = 6f }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val r = h / 2f
        canvas.drawRoundRect(RectF(0f, 0f, w, h), r, r, bg)
        bar.color = if (level >= threshold) Color.parseColor("#EF4444") else Color.parseColor("#22C55E")
        val bw = w * level / 100f
        if (bw > 0f) canvas.drawRoundRect(RectF(0f, 0f, bw, h), r, r, bar)
        val tx = w * threshold / 100f
        canvas.drawLine(tx, 0f, tx, h, line)
    }
}

class MainActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var scroll: ScrollView
    private lateinit var levelText: TextView
    private lateinit var meter: MeterView
    private lateinit var statusText: TextView
    private lateinit var startBtn: Button
    private lateinit var stopAlarmBtn: Button

    private val bgColor = Color.parseColor("#0F172A")
    private val cardColor = Color.parseColor("#1E293B")
    private val mutedColor = Color.parseColor("#94A3B8")
    private val textColor = Color.parseColor("#E2E8F0")

    private val refresh = object : Runnable {
        override fun run() {
            levelText.text = State.level.toString()
            meter.level = State.level
            meter.threshold = Settings.threshold
            meter.invalidate()
            startBtn.text = if (State.listening) "Stop listening" else "Start listening"
            stopAlarmBtn.visibility = if (State.alarming) View.VISIBLE else View.GONE
            statusText.text = when {
                State.alarming -> "🚨 LOUD SOUND DETECTED!"
                State.listening -> "Listening…"
                else -> State.message
            }
            if (State.alarming) {
                val flashOn = (System.currentTimeMillis() / 500) % 2L == 0L
                scroll.setBackgroundColor(if (flashOn) Color.parseColor("#7F1D1D") else bgColor)
            } else {
                scroll.setBackgroundColor(bgColor)
            }
            handler.postDelayed(this, 50)
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Settings.load(this)

        scroll = ScrollView(this)
        scroll.setBackgroundColor(bgColor)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(32), dp(16), dp(24))
        }
        scroll.addView(col)

        col.addView(TextView(this).apply {
            text = "🔊 Sound Alarm"
            textSize = 22f
            setTextColor(textColor)
            setPadding(dp(4), 0, 0, dp(12))
        })

        // Level card
        val levelCard = card()
        levelText = TextView(this).apply {
            text = "0"
            textSize = 48f
            setTextColor(textColor)
            gravity = Gravity.CENTER
        }
        meter = MeterView(this)
        statusText = TextView(this).apply {
            text = State.message
            setTextColor(mutedColor)
            gravity = Gravity.CENTER
            setPadding(0, dp(10), 0, 0)
        }
        levelCard.addView(levelText)
        levelCard.addView(meter, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(28)))
        levelCard.addView(statusText)
        col.addView(levelCard)

        // Detection card
        val detect = card()
        addSlider(detect, "Trigger level", 5, 100, Settings.threshold, { "$it / 100" }) { Settings.threshold = it }
        addSlider(detect, "Must stay loud for", 0, 50, Settings.durationTenths, { "%.1f s".format(it / 10f) }) { Settings.durationTenths = it }
        addSlider(detect, "Sensitivity (mic gain)", 5, 50, Settings.gainTenths, { "%.1f×".format(it / 10f) }) { Settings.gainTenths = it }
        col.addView(detect)

        // Alarm card
        val alarm = card()
        alarm.addView(TextView(this).apply {
            text = "Alarm sound"
            setTextColor(mutedColor)
            setPadding(0, dp(4), 0, dp(4))
        })
        val spinner = Spinner(this)
        val adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_item,
            listOf("Siren (two-tone)", "Beep", "Buzzer", "Chime")
        )
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinner.adapter = adapter
        spinner.setSelection(Settings.sound.coerceIn(0, 3))
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                Settings.sound = position
                Settings.save(this@MainActivity)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        alarm.addView(spinner)
        addSlider(alarm, "Alarm volume", 0, 100, Settings.volume, { "$it%" }) { Settings.volume = it }
        addSlider(alarm, "Alarm speed", 5, 30, Settings.speedTenths, { "%.1f×".format(it / 10f) }) { Settings.speedTenths = it }
        col.addView(alarm)

        // Buttons
        startBtn = button("Start listening", Color.parseColor("#38BDF8"), Color.parseColor("#04283A"))
        startBtn.setOnClickListener {
            if (State.listening) sendAction(MonitorService.ACTION_STOP) else requestAndStart()
        }
        stopAlarmBtn = button("Stop alarm", Color.parseColor("#EF4444"), Color.WHITE)
        stopAlarmBtn.setOnClickListener { sendAction(MonitorService.ACTION_RESET) }
        stopAlarmBtn.visibility = View.GONE
        val testBtn = button("Test alarm sound", Color.parseColor("#334155"), textColor)
        testBtn.setOnClickListener { if (!State.alarming) sendAction(MonitorService.ACTION_TEST) }
        col.addView(startBtn)
        col.addView(stopAlarmBtn)
        col.addView(testBtn)

        setContentView(scroll)
    }

    override fun onResume() {
        super.onResume()
        handler.post(refresh)
    }

    override fun onPause() {
        handler.removeCallbacks(refresh)
        super.onPause()
    }

    // ---------- helpers ----------

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(16), dp(16), dp(16))
        background = GradientDrawable().apply {
            setColor(cardColor)
            cornerRadius = dp(14).toFloat()
        }
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(12) }
    }

    private fun button(label: String, bg: Int, fg: Int): Button = Button(this).apply {
        text = label
        isAllCaps = false
        setTextColor(fg)
        setBackgroundColor(bg)
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(52)
        ).apply { topMargin = dp(8) }
    }

    private fun addSlider(
        parent: LinearLayout, title: String, min: Int, max: Int, init: Int,
        fmt: (Int) -> String, onChange: (Int) -> Unit
    ) {
        val label = TextView(this).apply {
            setTextColor(mutedColor)
            textSize = 14f
            setPadding(0, dp(10), 0, 0)
            text = "$title: ${fmt(init)}"
        }
        val sb = SeekBar(this)
        sb.max = max - min
        sb.progress = init - min
        sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                val v = progress + min
                label.text = "$title: ${fmt(v)}"
                if (fromUser) {
                    onChange(v)
                    Settings.save(this@MainActivity)
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {}
        })
        parent.addView(label)
        parent.addView(sb)
    }

    private fun sendAction(action: String) {
        startService(Intent(this, MonitorService::class.java).setAction(action))
    }

    private fun requestAndStart() {
        val need = mutableListOf<String>()
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.RECORD_AUDIO)
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            need.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (need.isEmpty()) startMonitoring() else requestPermissions(need.toTypedArray(), 1)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startMonitoring()
        } else {
            State.message = "Microphone permission is required"
        }
    }

    private fun startMonitoring() {
        startForegroundService(Intent(this, MonitorService::class.java).setAction(MonitorService.ACTION_START))
    }
}
