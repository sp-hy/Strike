package com.strike.boot

import android.app.Activity
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import com.strike.MainActivity
import com.strike.core.Config
import com.strike.core.Logs
import com.strike.daemon.Shell

private const val TAG = "Startup"
private const val LAUNCH_DELAY_MS = 3_000L
private const val VERIFY_DELAY_MS = 2_000L
private const val MIN_INTERVAL_MS = 30_000L

object StartupSettings {
    const val OPEN_APP = "startup.openApp"

    fun openApp(): Boolean = Config.getBool(OPEN_APP, false)
}

/**
 * Opens Strike when the head unit comes up, if enabled in Settings.
 *
 * The process is re-created both at cold boot and right after the standby force-stop, so a
 * process start alone is not a power-on. Launch when the process starts with the display on,
 * and on SCREEN_ON while running. Skipped while any Strike screen is already started.
 */
object AppLauncher {
    private val main = Handler(Looper.getMainLooper())
    private var startedActivities = 0
    private var lastLaunchAt = 0L
    private var attached = false

    fun attach(app: Application, shell: Shell) {
        if (attached) return
        attached = true
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) { startedActivities++ }
            override fun onActivityStopped(activity: Activity) {
                startedActivities = (startedActivities - 1).coerceAtLeast(0)
            }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
        app.registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) = schedule(app, shell)
        }, IntentFilter(Intent.ACTION_SCREEN_ON))
        schedule(app, shell)
    }

    private fun schedule(app: Context, shell: Shell) {
        main.removeCallbacksAndMessages(null)
        main.postDelayed({ launchIfNeeded(app, shell) }, LAUNCH_DELAY_MS)
    }

    private fun launchIfNeeded(app: Context, shell: Shell) {
        if (startedActivities > 0) return
        val power = app.getSystemService(PowerManager::class.java)
        if (power != null && !power.isInteractive) return
        val now = SystemClock.elapsedRealtime()
        if (lastLaunchAt != 0L && now - lastLaunchAt < MIN_INTERVAL_MS) return
        // Settings are a shell-owned file; read it off the main thread.
        Thread({
            if (!StartupSettings.openApp()) return@Thread
            main.post { launch(app, shell, now) }
        }, "startup-check").start()
    }

    private fun launch(app: Context, shell: Shell, now: Long) {
        if (startedActivities > 0) return
        lastLaunchAt = now
        Logs.d(TAG, "opening Strike on head-unit start")
        try {
            app.startActivity(Intent(app, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED))
        } catch (e: RuntimeException) {
            Logs.w(TAG, "could not open Strike: ${e.message}")
        }
        // Background starts are dropped silently without the SYSTEM_ALERT_WINDOW grant.
        main.postDelayed({
            if (startedActivities > 0) return@postDelayed
            Thread({
                if (!shell.check("am start -n ${app.packageName}/${MainActivity::class.java.name}")) {
                    Logs.w(TAG, "Strike did not open on start")
                }
            }, "startup-launch").start()
        }, VERIFY_DELAY_MS)
    }
}
