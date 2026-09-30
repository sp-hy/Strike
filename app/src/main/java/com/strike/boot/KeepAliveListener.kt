package com.strike.boot

import android.content.ComponentName
import android.service.notification.NotificationListenerService
import com.strike.core.Logs

/**
 * Wake hook, not a notification reader. DiLink's CarPowerService force-stops every
 * third-party app on standby, which blocks broadcasts until the app is opened again.
 * An approved listener is still rebound by system_server, and each bind re-creates
 * the process so [com.strike.StrikeApp.onCreate] restores the shell, dashboard and recorder.
 */
class KeepAliveListener : NotificationListenerService() {
    override fun onListenerConnected() {
        Logs.d(TAG, "listener connected")
    }

    override fun onListenerDisconnected() {
        try {
            requestRebind(ComponentName(this, KeepAliveListener::class.java))
        } catch (e: RuntimeException) {
            Logs.w(TAG, "could not request a listener rebind")
        }
    }

    companion object {
        private const val TAG = "KeepAlive"

        fun component(pkg: String): String = "$pkg/${KeepAliveListener::class.java.name}"
    }
}
