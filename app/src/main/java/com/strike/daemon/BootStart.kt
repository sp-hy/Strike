package com.strike.daemon

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Receiving boot creates the process; [com.strike.StrikeApp] then restores the recorder and
 * [com.strike.boot.AppLauncher] opens the app if the user asked for that.
 */
class BootStart : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) = Unit
}
