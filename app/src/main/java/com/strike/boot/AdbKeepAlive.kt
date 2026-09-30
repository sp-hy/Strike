package com.strike.boot

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import com.strike.core.Logs

private const val TAG = "AdbKeepAlive"
private const val ADB_ENABLED = "adb_enabled"

/**
 * BYD's OTA app writes `adb_enabled=0` every time it starts on user builds. adbd keeps its
 * TCP port setting, so writing 1 back is enough for Strike's local shell to reconnect.
 */
object AdbKeepAlive {
    @Volatile private var observer: ContentObserver? = null

    fun start(context: Context) {
        val app = context.applicationContext
        if (!canWrite(app)) return
        ensureEnabled(app)
        if (observer != null) return
        val watch = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) = ensureEnabled(app)
        }
        app.contentResolver.registerContentObserver(Settings.Global.getUriFor(ADB_ENABLED), false, watch)
        observer = watch
    }

    private fun ensureEnabled(context: Context) {
        val resolver = context.contentResolver
        if (Settings.Global.getInt(resolver, ADB_ENABLED, 0) == 1) return
        try {
            Settings.Global.putInt(resolver, ADB_ENABLED, 1)
            Logs.d(TAG, "adb was turned off; turned it back on")
        } catch (e: SecurityException) {
            Logs.w(TAG, "cannot turn adb back on")
        }
    }

    private fun canWrite(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) ==
            PackageManager.PERMISSION_GRANTED
}
