package com.soundalarm.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import kotlin.math.log10
import kotlin.math.roundToInt
import kotlin.math.sqrt

private class Sound(val wave: Int, val freqs: DoubleArray, val baseMs: Int, val pulse: Boolean)

// Order matches the spinner: siren, beep, buzzer, chime.
// wave: 0 sine, 1 square, 2 sawtooth, 3 triangle
private val SOUNDS = listOf(
    Sound(1, doubleArrayOf(700.0, 1000.0), 350, false),
    Sound(0, doubleArrayOf(880.0), 250, true),
    Sound(2, doubleArrayOf(150.0), 400, true),
    Sound(3, doubleArrayOf(523.0, 659.0, 784.0, 1047.0), 300, false)
)

class MonitorService : Service() {

    companion object {
        const val ACTION_START = "com.soundalarm.app.START"
        const val ACTION_STOP = "com.soundalarm.app.STOP"
        const val ACTION_RESET = "com.soundalarm.app.RESET"
        const val ACTION_TEST = "com.soundalarm.app.TEST"
        private const val CH_MONITOR = "monitor"
        private const val CH_ALERT = "alert"
        private const val ID_MONITOR = 1
        private const val ID_ALERT = 2
    }

    @Volatile private var running = false
    @Volatile private var alarmOn = false
    @Volatile private var alarmGen = 0
    @Volatile private var loudSince = -1L
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Settings.load(this)
        createChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startListening()
            ACTION_STOP -> stopAll()
            ACTION_RESET -> {
                stopAlarm()
                if (!running) stopSelf()
            }
            ACTION_TEST -> startAlarm()
            else -> if (!running && !alarmOn) stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running = false
        alarmOn = false
        State.listening = false
        State.alarming = false
        State.level = 0
        try { vibrator().cancel() } catch (e: Exception) { }
        releaseWakeLock()
        super.onDestroy()
    }

    // ---------- listening ----------

    private fun startListening() {
        if (running) return
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(ID_MONITOR, monitorNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } else {
                startForeground(ID_MONITOR, monitorNotification())
            }
        } catch (e: Exception) {
            State.message = "Could not start: ${e.message}"
            stopSelf()
            return
        }
        running = true
        State.listening = true
        State.message = "Listening…"
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "soundalarm:listen").apply {
            setReferenceCounted(false)
            acquire()
        }
        Thread { recordLoop() }.start()
    }

    private fun stopAll(message: String = "Microphone is off") {
        running = false
        State.listening = false
        State.level = 0
        State.message = message
        stopAlarm()
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    private fun recordLoop() {
        val sr = 16000
        val minBuf = AudioRecord.getMinBufferSize(sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec: AudioRecord? = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC, sr,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuf, 4096)
            )
        } catch (e: Exception) {
            null
        }
        if (rec == null || rec.state != AudioRecord.STATE_INITIALIZED) {
            rec?.release()
            Handler(Looper.getMainLooper()).post { stopAll("Could not open the microphone") }
            return
        }

        val buf = ShortArray(800) // 50 ms
        try {
            rec.startRecording()
            while (running) {
                val n = rec.read(buf, 0, buf.size)
                if (n <= 0) {
                    Thread.sleep(20)
                    continue
                }
                val g = Settings.gainTenths / 10.0
                var sum = 0.0
                for (i in 0 until n) {
                    val v = buf[i] / 32768.0 * g
                    sum += v * v
                }
                val rms = sqrt(sum / n)
                val dbfs = 20.0 * log10(if (rms > 1e-6) rms else 1e-6)
                // -60 dBFS -> 0, 0 dBFS -> 100 (same scale as the web version)
                val lv = ((dbfs + 60.0) / 60.0 * 100.0).coerceIn(0.0, 100.0)
                State.level = lv.roundToInt()
                checkTrigger(lv)
            }
        } catch (e: Exception) {
            Handler(Looper.getMainLooper()).post { stopAll("Microphone error: ${e.message}") }
        } finally {
            try { rec.stop() } catch (e: Exception) { }
            rec.release()
            State.level = 0
        }
    }

    private fun checkTrigger(lv: Double) {
        if (alarmOn) {
            loudSince = -1L
            return
        }
        if (lv >= Settings.threshold) {
            val now = SystemClock.elapsedRealtime()
            if (loudSince < 0) loudSince = now
            if (now - loudSince >= Settings.durationTenths * 100L) startAlarm()
        } else {
            loudSince = -1L
        }
    }

    // ---------- alarm ----------

    @Synchronized
    private fun startAlarm() {
        if (alarmOn) return
        alarmOn = true
        alarmGen++
        State.alarming = true
        startVibration()
        showAlertNotification()
        Thread { playTones() }.start()
    }

    @Synchronized
    private fun stopAlarm() {
        alarmOn = false
        State.alarming = false
        loudSince = -1L
        try { vibrator().cancel() } catch (e: Exception) { }
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(ID_ALERT)
    }

    private fun playTones() {
        val gen = alarmGen
        val sr = 22050
        val minBuf = AudioTrack.getMinBufferSize(sr, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sr)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(maxOf(minBuf, 4096))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        val twoPi = 2.0 * Math.PI
        var phase = 0.0
        var step = 0
        val chunk = ShortArray(441) // 20 ms, so volume changes apply quickly
        try {
            track.play()
            while (alarmOn && gen == alarmGen) {
                val s = SOUNDS[Settings.sound.coerceIn(0, SOUNDS.size - 1)]
                val stepSamples = (sr * s.baseMs / 1000.0 / (Settings.speedTenths / 10.0)).toInt()
                val freq = s.freqs[step % s.freqs.size]
                val on = if (s.pulse) step % 2 == 0 else true
                var done = 0
                while (done < stepSamples && alarmOn && gen == alarmGen) {
                    val m = minOf(chunk.size, stepSamples - done)
                    val vol = Settings.volume / 100.0 * 0.5
                    for (i in 0 until m) {
                        phase += twoPi * freq / sr
                        if (phase > twoPi) phase -= twoPi
                        val saw = 2.0 * (phase / twoPi) - 1.0
                        val w = when (s.wave) {
                            0 -> Math.sin(phase)
                            1 -> if (phase < Math.PI) 1.0 else -1.0
                            2 -> saw
                            else -> 2.0 * Math.abs(saw) - 1.0
                        }
                        chunk[i] = if (on) (w * vol * 32767.0).toInt().toShort() else 0.toShort()
                    }
                    track.write(chunk, 0, m)
                    done += m
                }
                step++
            }
        } finally {
            try { track.stop() } catch (e: Exception) { }
            track.release()
        }
    }

    @Suppress("DEPRECATION")
    private fun vibrator(): Vibrator =
        if (Build.VERSION.SDK_INT >= 31) {
            (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }

    @Suppress("DEPRECATION")
    private fun startVibration() {
        try {
            val v = vibrator()
            if (!v.hasVibrator()) return
            val effect = VibrationEffect.createWaveform(longArrayOf(0, 500, 300), 0)
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            v.vibrate(effect, attrs)
        } catch (e: Exception) { }
    }

    // ---------- notifications ----------

    private fun createChannels() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CH_MONITOR, "Listening", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CH_ALERT, "Alarm alerts", NotificationManager.IMPORTANCE_HIGH))
    }

    private fun actionIntent(action: String, code: Int): PendingIntent =
        PendingIntent.getService(
            this, code,
            Intent(this, MonitorService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    private fun openAppIntent(): PendingIntent =
        PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    @Suppress("DEPRECATION")
    private fun monitorNotification(): Notification =
        Notification.Builder(this, CH_MONITOR)
            .setContentTitle("Sound Alarm is listening")
            .setContentText("Tap to open")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(openAppIntent())
            .setOngoing(true)
            .addAction(android.R.drawable.ic_media_pause, "Stop", actionIntent(ACTION_STOP, 1))
            .build()

    @Suppress("DEPRECATION")
    private fun showAlertNotification() {
        val n = Notification.Builder(this, CH_ALERT)
            .setContentTitle("Loud sound detected!")
            .setContentText("Tap to open, or silence the alarm")
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setCategory(Notification.CATEGORY_ALARM)
            .setContentIntent(openAppIntent())
            .addAction(android.R.drawable.ic_lock_silent_mode, "Silence", actionIntent(ACTION_RESET, 2))
            .build()
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(ID_ALERT, n)
    }
}
