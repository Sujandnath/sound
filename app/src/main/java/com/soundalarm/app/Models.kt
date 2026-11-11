package com.soundalarm.app

import android.content.Context

/** User-adjustable settings, shared between the screen and the background service. */
object Settings {
    @Volatile var threshold = 70        // 5..100  trigger level
    @Volatile var durationTenths = 3    // 0..50   seconds x10 the sound must stay loud
    @Volatile var gainTenths = 10       // 5..50   mic sensitivity x10
    @Volatile var sound = 0             // 0 siren, 1 beep, 2 buzzer, 3 chime
    @Volatile var volume = 25           // 0..100  alarm volume
    @Volatile var speedTenths = 10      // 5..30   alarm speed x10

    private const val PREFS = "settings"

    fun load(c: Context) {
        val p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        threshold = p.getInt("threshold", 70)
        durationTenths = p.getInt("durationTenths", 3)
        gainTenths = p.getInt("gainTenths", 10)
        sound = p.getInt("sound", 0)
        volume = p.getInt("volume", 25)
        speedTenths = p.getInt("speedTenths", 10)
    }

    fun save(c: Context) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt("threshold", threshold)
            .putInt("durationTenths", durationTenths)
            .putInt("gainTenths", gainTenths)
            .putInt("sound", sound)
            .putInt("volume", volume)
            .putInt("speedTenths", speedTenths)
            .apply()
    }
}

/** Live state written by the service and read by the screen. */
object State {
    @Volatile var listening = false
    @Volatile var alarming = false
    @Volatile var level = 0
    @Volatile var message = "Microphone is off"
}
